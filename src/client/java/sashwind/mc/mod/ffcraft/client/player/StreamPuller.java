package sashwind.mc.mod.ffcraft.client.player;

import com.mojang.blaze3d.platform.NativeImage;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.lwjgl.system.MemoryUtil;

public class StreamPuller extends Thread {
    private final BlockingQueue<NativeImage> frameQueue;
    private final BlockingQueue<short[]> audioQueue = new LinkedBlockingQueue<>(30);
    private final BlockingQueue<VideoTask> videoQueue = new LinkedBlockingQueue<>(4); // 视频任务队列
    private volatile int audioSampleRate = 0; // 0=未就绪
    private volatile int audioChannels = 0;
    private volatile boolean audioReady = false;
    private long videoStartPts = -1;
    private volatile boolean hasVideoFrame = false;

    private String streamUrl;
    private volatile boolean running = true;
    private FFmpegFrameGrabber grabber;
    private FFmpegSettings settings;
    private VideoProcessor videoProcessor;

    public StreamPuller(String streamUrl, FFmpegSettings settings) {
        // Jellyfin/Emby fMP4 HLS → 强制 TS（fMP4 会导致 grabber.start() 卡死）
        if (streamUrl.contains("SegmentContainer=mp4")) {
            streamUrl = streamUrl.replace("SegmentContainer=mp4", "SegmentContainer=ts");
        }
        this.streamUrl = streamUrl;
        this.frameQueue = new LinkedBlockingQueue<>(8);
        this.settings = settings;
    }

    @Override
    public void run() {
        grabber = new FFmpegFrameGrabber(streamUrl);
        grabber.setOption("rtsp_transport", settings.rtsp_transport);
        grabber.setOption("stimeout", settings.stimeout);
        for (String flag : settings.fflags) {
            grabber.setOption("fflags", flag);
        }
        grabber.setImageWidth(settings.setImageWidth);
        grabber.setImageHeight(settings.setImageHeight);
        grabber.setFrameRate(settings.setFrameRate);
        grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);

        // 启动视频处理线程
        videoProcessor = new VideoProcessor();
        videoProcessor.start();

        try {
            grabber.start();
            // 应用暂存的 seek（grabber 初始化前设置的）
            if (pendingSeek >= 0) {
                try { grabber.setTimestamp(Math.round(pendingSeek * 1_000_000)); }
                catch (Exception e) { System.err.println("[StreamPuller] pending seek failed: " + e.getMessage()); }
                pendingSeek = -1;
            }
            // 从流元数据获取实际采样率
            int actualSr = grabber.getSampleRate();
            if (actualSr > 0) audioSampleRate = actualSr;
            else audioSampleRate = 48000;
            int actualCh = grabber.getAudioChannels();
            if (actualCh > 0) audioChannels = actualCh;
            else audioChannels = 2;
            audioReady = true;
            System.out.println("拉流线程启动: " + streamUrl + " sr=" + audioSampleRate + " ch=" + audioChannels);

            while (running && !Thread.currentThread().isInterrupted()) {
                Frame frame = grabber.grabFrame();
                if (frame == null) {
                    System.out.println("帧为空，可能流已断开");
                    break;
                }
                handleFrame(frame);
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("拉流出错: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            running = false;
            if (videoProcessor != null) videoProcessor.shutdown();
            closeResources();
        }
    }

    public boolean isRunning() { return running; }

    private void handleFrame(Frame frame) {
        // 音频：主线程快速处理（不阻塞）
        if (frame.type == Frame.Type.AUDIO && frame.samples != null && frame.samples.length > 0) {
            var samples = frame.samples;
            if (audioSampleRate <= 0) {
                audioSampleRate = frame.sampleRate > 0 ? frame.sampleRate : 48000;
                System.out.println("[StreamPuller] Audio sr=" + audioSampleRate + " ch=" + frame.audioChannels + " (from first frame)");
            }
            audioChannels = frame.audioChannels > 0 ? frame.audioChannels : 2;
            short[] pcm = null;
            if (samples[0] instanceof java.nio.ShortBuffer sb) {
                pcm = new short[sb.remaining()];
                sb.get(pcm); sb.rewind();
            } else if (samples[0] instanceof java.nio.FloatBuffer fb) {
                pcm = new short[fb.remaining()];
                for (int i = 0; i < pcm.length; i++)
                    pcm[i] = (short) Math.max(-32768, Math.min(32767, (int) (fb.get(i) * 32767)));
            } else if (samples[0] instanceof java.nio.ByteBuffer bb) {
                pcm = new short[bb.remaining() / 2];
                bb.asShortBuffer().get(pcm);
            }
            if (pcm != null) {
                try { audioQueue.put(pcm); } catch (InterruptedException e) { running = false; }
            }
            return;
        }

        // 视频：拷贝原始字节 → 丢给 VideoProcessor 线程（主线程不阻塞）
        if (frame.type == Frame.Type.VIDEO && frame.image != null && frame.image.length > 0) {
            ByteBuffer buffer = (ByteBuffer) frame.image[0];
            if (buffer == null) return;
            buffer.position(0);
            int w = frame.imageWidth, h = frame.imageHeight;
            int size = w * h * 4;
            if (buffer.remaining() < size) return;

            byte[] raw = new byte[size];
            buffer.get(raw);

            if (videoStartPts < 0) {
                videoStartPts = grabber.getTimestamp();
                System.out.println("[StreamPuller] 首帧PTS=" + videoStartPts + "μs (" + (videoStartPts/1000000.0) + "s)");
            }

            videoQueue.offer(new VideoTask(w, h, raw)); // 非阻塞，让音频帧能通过
        }
    }

    /** 视频帧处理线程：将原始字节转为 NativeImage（分离后主线程不再被 GPU 内存分配阻塞） */
    private class VideoProcessor extends Thread {
        private volatile boolean active = true;

        VideoProcessor() { super("VideoProcessor"); setDaemon(true); }

        @Override
        public void run() {
            while (active) {
                try {
                    VideoTask task = videoQueue.take();
                    if (!active) break;
                    NativeImage img = createNativeImage(task.width, task.height, task.raw);
                    if (img != null) {
                        hasVideoFrame = true;
                        frameQueue.put(img);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            // 清理残留任务
            VideoTask task;
            while ((task = videoQueue.poll()) != null) {
                NativeImage img = createNativeImage(task.width, task.height, task.raw);
                if (img != null && !frameQueue.offer(img)) img.close();
            }
        }

        void shutdown() { active = false; this.interrupt(); }
    }

    private static NativeImage createNativeImage(int width, int height, byte[] raw) {
        try {
            int border = 2;
            int fw = width + border * 2, fh = height + border * 2;
            int total = fw * fh * 4;

            NativeImage img = new NativeImage(NativeImage.Format.RGBA, fw, fh, false);
            ByteBuffer dst = MemoryUtil.memByteBuffer(img.getPointer(), total);
            MemoryUtil.memSet(dst, 0);

            int line = fw * 4;
            int row = width * 4;
            for (int y = 0; y < height; y++) {
                dst.position((border + y) * line + border * 4);
                dst.put(raw, y * row, row);
            }
            return img;
        } catch (Exception e) {
            System.err.println("createNativeImage 错误: " + e.getMessage());
            return null;
        }
    }

    /** 视频处理任务（数据已从 Frame 拷贝，线程安全） */
    private record VideoTask(int width, int height, byte[] raw) {}

    private void closeResources() {
        if (grabber != null) {
            try { grabber.stop(); grabber.release(); }
            catch (Exception e) { e.printStackTrace(); }
        }
        System.out.println("拉流资源已释放");
    }

    public void stopPulling() {
        running = false;
        this.interrupt(); // 让阻塞在 grabFrame() 的线程醒来退出
    }

    public void waitForStop() {
        try { this.join(5000); } catch (InterruptedException ignored) {}
    }

    private volatile double pendingSeek = -1;

    public void seekTo(double seconds) {
        if (grabber != null) {
            try { grabber.setTimestamp(Math.round(seconds * 1_000_000)); }
            catch (Exception e) { System.err.println("[StreamPuller] seek failed: " + e.getMessage()); }
        } else {
            pendingSeek = seconds; // grabber 还没初始化，暂存
        }
    }

    // getters
    public NativeImage getFrame() { return frameQueue.poll(); }
    public BlockingQueue<short[]> getAudioQueue() { return audioQueue; }
    public int getAudioSampleRate() { return audioSampleRate; }
    public int getAudioChannels() { return audioChannels; }
    public boolean isAudioReady() { return audioReady; }
    public double getDuration() {
        if (!running || grabber == null) return 0;
        try { return grabber.getLengthInTime() / 1_000_000.0; } catch (Exception e) { return 0; }
    }
    public boolean isLive() { return getDuration() <= 0; }
    public int getVideoWidth() {
        if (!running || grabber == null) return 0;
        try { return grabber.getImageWidth(); } catch (Exception e) { return 0; }
    }
    public int getVideoHeight() {
        if (!running || grabber == null) return 0;
        try { return grabber.getImageHeight(); } catch (Exception e) { return 0; }
    }
    public double getFrameRate() {
        if (grabber != null && running) {
            try { return grabber.getFrameRate(); } catch (Exception e) { return 30; }
        }
        return 30;
    }
    public boolean hasVideoFrame() { return hasVideoFrame; }
    public void resetVideoFrameFlag() { hasVideoFrame = false; }
}
