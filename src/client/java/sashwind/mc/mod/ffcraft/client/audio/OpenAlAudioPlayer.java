package sashwind.mc.mod.ffcraft.client.audio;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import java.util.concurrent.BlockingQueue;

public class OpenAlAudioPlayer {
    private final BlockingQueue<short[]> queue;
    private int source;
    private volatile boolean running;
    private boolean started;
    private final int[] buffers = new int[4];
    private int sampleRate, channels;
    private boolean leftOn = true, rightOn = true;
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

    /** 设置声道开关 */
    public void setChannels(boolean left, boolean right) {
        this.leftOn = left;
        this.rightOn = right;
    }

    /** 对立体声PCM数据应用声道屏蔽（清零禁用声道） */
    private short[] applyChannelMask(short[] pcm) {
        if (leftOn && rightOn) return pcm;
        if (channels < 2) return pcm; // 单声道不需要处理
        if (!leftOn && !rightOn) {
            // 两个声道都禁用：全部清零
            for (int i = 0; i < pcm.length; i++) pcm[i] = 0;
            return pcm;
        }
        if (!leftOn) {
            // 只禁用左声道：清零偶数位(L)
            for (int i = 0; i < pcm.length; i += 2) pcm[i] = 0;
        } else {
            // 只禁用右声道：清零奇数位(R)
            for (int i = 1; i < pcm.length; i += 2) pcm[i] = 0;
        }
        return pcm;
    }

    private double lastDist = -1;

    /** 设置音源位置并根据与监听者的距离手动计算增益 */
    public void updatePositionAndGain(double srcX, double srcY, double srcZ,
                                       double listenerX, double listenerY, double listenerZ) {
        if (source == 0) return;
        AL10.alSource3f(source, AL10.AL_POSITION, (float) srcX, (float) srcY, (float) srcZ);

        double dx = srcX - listenerX, dy = srcY - listenerY, dz = srcZ - listenerZ;
        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        lastDist = dist;

        // 手动距离衰减: 4格内满音量 → 128格外完全静音
        float spatialGain;
        if (dist <= 4) {
            spatialGain = 1f;
        } else if (dist >= 128) {
            spatialGain = 0f;
        } else {
            // 线性衰减: 4格=1.0 → 128格=0.0
            spatialGain = (float) (1.0 - (dist - 4) / (128 - 4));
            // 逆距离衰减(更自然): spatialGain = (float) (4.0 / dist);
        }
        spatialVolume = spatialGain;
    }

    private float spatialVolume = 1f;
    private long totalSamplesConsumed = 0;

    /** 累计已消耗的音频采样数（用于 audio-pacing 视频同步） */
    public long getTotalSamplesConsumed() { return totalSamplesConsumed; }

    /** 获取上次计算的距离（调试用） */
    public double getLastDistance() { return lastDist; }

    /** Called every tick - drains PCM queue into OpenAL buffers */
    public void update() {
        debugTick++;
        if (!running || source == 0) return;

        int fmt = channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;

        if (!started && queue.isEmpty()) return; // wait for data

        // unqueue processed buffers
        int processed = AL10.alGetSourcei(source, AL11.AL_BUFFERS_PROCESSED);

        // refill processed buffers only if PCM data available
        for (int i = 0; i < processed; i++) {
            short[] pcm = queue.poll();
            if (pcm != null) {
                int buf = AL10.alSourceUnqueueBuffers(source);
                totalSamplesConsumed += pcm.length / Math.max(1, channels);
                pcm = applyChannelMask(pcm);
                AL10.alBufferData(buf, fmt, pcm, sampleRate);
                AL10.alSourceQueueBuffers(source, buf);
            }
            // if no PCM, leave buffer queued (will replay) - better than silence pop
        }

        // if never started and we have data, fill all buffers and play
        AL10.alSourcef(source, AL10.AL_GAIN, volume * spatialVolume);

        if (!started && !queue.isEmpty()) {
            for (int buf : buffers) {
                short[] pcm = queue.poll();
                if (pcm != null) {
                    totalSamplesConsumed += pcm.length / Math.max(1, channels);
                    pcm = applyChannelMask(pcm);
                    AL10.alBufferData(buf, fmt, pcm, sampleRate);
                    AL10.alSourceQueueBuffers(source, buf);
                }
            }
            AL10.alSourcePlay(source);
            started = true;
            System.out.println("[OpenAL] First play: source=" + source + " fmt=" + (fmt == AL10.AL_FORMAT_STEREO16 ? "STEREO" : "MONO")
                    + " sr=" + sampleRate + " ch=" + channels);
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
    }

    public boolean isRunning() { return running; }
}
