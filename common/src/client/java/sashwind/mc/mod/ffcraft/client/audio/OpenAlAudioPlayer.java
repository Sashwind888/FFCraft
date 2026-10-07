package sashwind.mc.mod.ffcraft.client.audio;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import java.util.concurrent.BlockingQueue;

public class OpenAlAudioPlayer {
    private final BlockingQueue<short[]> queue;
    private int source;
    private volatile boolean running;
    private boolean started;
    private final int[] buffers = new int[8]; // 8个buffer保证96000Hz下足够缓冲(≈85ms)
    private int sampleRate, channels;
    /** Per-channel spatial volume (distance × pan × channel state), applied in PCM domain */
    private float spatialVolumeL = 1f, spatialVolumeR = 1f;
    private int debugTick = 0;

    public OpenAlAudioPlayer(BlockingQueue<short[]> queue) {
        this.queue = queue;
    }

    public void start(int sampleRate, int channels) {
        if (running) return;
        this.sampleRate = sampleRate;
        this.channels = channels;
        running = true;
        source = AL10.alGenSources();
        AL10.alGenBuffers(buffers);
        // 关闭OpenAL内置衰减（Minecraft设了AL_NONE，手动计算增益）
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
    }

    private float volume = 1f;
    public void setVolume(float v) { this.volume = v; }

    /**
     * 设置左右声道的空间音量（已综合距离衰减 + 立体声平衡 + 声道状态）
     * 在 PCM 域独立控制左右声道增益，实现真正的立体声空间音频。
     */
    public void setSpatialVolumes(float l, float r) {
        this.spatialVolumeL = l;
        this.spatialVolumeR = r;
    }

    /**
     * 对立体声 PCM 数据应用独立的左右声道增益。
     * 立体声交错格式 [L][R][L][R]...，每个采样 16bit。
     * 左声道（偶数索引）× spatialVolumeL，右声道（奇数索引）× spatialVolumeR。
     */
    private short[] applySpatialGain(short[] pcm) {
        if (channels < 2) {
            // 单声道：应用左右声道平均增益
            float monoGain = (spatialVolumeL + spatialVolumeR) / 2f;
            if (monoGain >= 1f) return pcm; // 无需处理
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) Math.clamp(pcm[i] * monoGain, -32768, 32767);
            }
            return pcm;
        }
        // 立体声：左声道 × spatialVolumeL，右声道 × spatialVolumeR
        if (spatialVolumeL >= 1f && spatialVolumeR >= 1f) return pcm; // 无需处理
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            pcm[i]     = (short) Math.clamp(pcm[i]     * spatialVolumeL, -32768, 32767);
            pcm[i + 1] = (short) Math.clamp(pcm[i + 1] * spatialVolumeR, -32768, 32767);
        }
        return pcm;
    }

    private long totalSamplesConsumed = 0;
    private long prebufSamples = -1; // 首次播放前的预缓冲采样数

    /** 实际已播放的采样数（扣除预缓冲，用于音视频同步） */
    public long getTotalSamplesConsumed() {
        if (prebufSamples < 0) return 0; // 还没开始播
        return totalSamplesConsumed - prebufSamples;
    }

    /** Called every tick - drains PCM queue into OpenAL buffers */
    public void update() {
        debugTick++;
        if (!running || source == 0) return;

        int fmt = channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;

        if (!started && queue.isEmpty()) return; // wait for data

        // unqueue processed buffers
        int processed = AL10.alGetSourcei(source, AL11.AL_BUFFERS_PROCESSED);

        for (int i = 0; i < processed; i++) {
            int buf = AL10.alSourceUnqueueBuffers(source);
            short[] pcm = queue.poll();
            if (pcm != null) {
                totalSamplesConsumed += pcm.length / Math.max(1, channels);
                pcm = applySpatialGain(pcm);
                AL10.alBufferData(buf, fmt, pcm, sampleRate);
            } else {
                // 队列空：填充静音避免旧数据重放导致卡顿
                int bufSize = AL10.alGetBufferi(buf, AL10.AL_SIZE);
                short[] silence = new short[bufSize / 2];
                AL10.alBufferData(buf, fmt, silence, sampleRate);
            }
            AL10.alSourceQueueBuffers(source, buf);
        }

        // if never started and we have data, fill all buffers and play
        AL10.alSourcef(source, AL10.AL_GAIN, volume);

        if (!started && !queue.isEmpty()) {
            for (int buf : buffers) {
                short[] pcm = queue.poll();
                if (pcm != null) {
                    totalSamplesConsumed += pcm.length / Math.max(1, channels);
                    pcm = applySpatialGain(pcm);
                    AL10.alBufferData(buf, fmt, pcm, sampleRate);
                    AL10.alSourceQueueBuffers(source, buf);
                }
            }
            AL10.alSourcePlay(source);
            started = true;
            prebufSamples = totalSamplesConsumed; // 扣除预缓冲偏移
            System.out.println("[OpenAL] First play: source=" + source + " fmt=" + (fmt == AL10.AL_FORMAT_STEREO16 ? "STEREO" : "MONO")
                    + " sr=" + sampleRate + " ch=" + channels + " prebuf=" + prebufSamples);
        }

        // restart if stalled
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        int queued = AL10.alGetSourcei(source, AL11.AL_BUFFERS_QUEUED);
        if (state != AL10.AL_PLAYING && queued > 0) {
            AL10.alSourcePlay(source);
            if (debugTick % 60 == 0) {
                System.out.println("[OpenAL] Restart stalled source=" + source + " state=" + state + " queued=" + queued);
            }
        }

    }

    public void stop() {
        running = false;
        if (source != 0) {
            AL10.alSourceStop(source);
            int q = AL10.alGetSourcei(source, AL11.AL_BUFFERS_QUEUED);
            for (int i = 0; i < q; i++) AL10.alSourceUnqueueBuffers(source);
            AL10.alDeleteSources(source);
            AL10.alDeleteBuffers(buffers);
            source = 0;
        }
        // 清理残留的 PCM 数据，解除对队列的强引用
        if (queue != null) queue.clear();
    }

    public boolean isRunning() { return running; }
}
