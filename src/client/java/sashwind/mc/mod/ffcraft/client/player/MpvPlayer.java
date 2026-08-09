package sashwind.mc.mod.ffcraft.client.player;

import com.mojang.blaze3d.platform.NativeImage;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 libmpv 的视频播放器 — 替换 StreamPuller。
 *
 * 视频：mpv_render_context 软件渲染 → NativeImage → frameQueue → WorldDraw
 * 音频：mpv 直接输出到系统音频设备（无需 OpenAL）
 */
public class MpvPlayer {

    static {
        MpvNativeLoader.init();
    }

    private static MpvNative mpv() { return MpvNativeLoader.getApi(); }
    public static boolean isAvailable() { return MpvNativeLoader.isLoaded(); }

    private final BlockingQueue<NativeImage> frameQueue = new LinkedBlockingQueue<>(8);
    private final long handle;
    private long renderCtx;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean frameReady = new AtomicBoolean(false);
    private final AtomicBoolean hasVideo = new AtomicBoolean(false);

    /** 播放已结束（END_FILE 后置位，reload/新建时清除）。endedError=0 表示正常播完 */
    private volatile boolean ended;
    private volatile int endedError;

    /** 文件加载完成前发起的 seek 排队（loadfile 异步，立即 seek 会被 mpv 忽略 → 从头播） */
    private double pendingSeek = Double.NaN;
    /** 单曲循环检测：time-pos 从末尾跳回开头（loop-file 无缝循环无事件，只能轮询） */
    private double lastTimePos = -1;
    private volatile boolean loopDetected;

    // 渲染目标池（环形）：mpv 直接渲染进池目标后入队该目标（所有权转移给消费者，消费者负责 close），
    // 轮换时若已被消费者 close（isClosed()）则重建 → 省掉每帧一次全帧克隆拷贝
    private static final int TARGET_POOL_SIZE = 3;
    private final NativeImage[] targetPool = new NativeImage[TARGET_POOL_SIZE];
    private int targetIndex = 0;
    private int targetW, targetH;
    private long lastProbeLog;

    // 视频参数
    private volatile int videoW, videoH;
    private volatile double fps = 30;
    private volatile double duration;
    private volatile int audioSampleRate = 48000;
    private volatile int audioChannels = 2;

    // 音频捕获（空间音频用）
    private final BlockingQueue<short[]> audioQueue = new LinkedBlockingQueue<>(64);
    private long audioCtx;
    private boolean audioCtxOk;
    /** 旧版 libmpv（<0.33）无音频渲染 API → 回退 ao=auto 系统直出 */
    private final boolean audioRender;

    private String url;

    public MpvPlayer(String url) {
        if (mpv() == null) throw new IllegalStateException("libmpv not loaded");

        // Jellyfin/Emby fMP4 HLS 兼容
        if (url.contains("SegmentContainer=mp4"))
            url = url.replace("SegmentContainer=mp4", "SegmentContainer=ts");
        this.url = url;

        handle = mpv().mpv_create();

        // 配置
        // vo=libmpv 是 render API 专用的虚拟 VO（官方示例 main_sw.c 必须设置），
        // 否则视频帧流向默认 VO，render context 永远拿不到帧
        mpv().mpv_set_option_string(handle, "vo", "libmpv");
        mpv().mpv_set_option_string(handle, "hwdec", "no");          // SW 渲染是 CPU 路径，用软解
        mpv().mpv_set_option_string(handle, "cache", "yes");
        mpv().mpv_set_option_string(handle, "cache-secs", "3");
        mpv().mpv_set_option_string(handle, "demuxer-max-bytes", "50MiB");
        mpv().mpv_set_option_string(handle, "demuxer-max-back-bytes", "25MiB");
        mpv().mpv_set_option_string(handle, "video-output-level", "full");
        // 音频：新 libmpv（0.33-0.36）→ ao=null 由应用捕获 PCM 做空间音频；
        // 0.37+ 无音频渲染 API → 不设置 ao（默认自动选择驱动，mpv 直出），
        // 伪空间音频用 af=pan 滤镜（mpv 0.41 已删除 audio-pan-x / volume-l/r）
        // 注意：绝不能显式设 "ao=auto"！mpv 0.41 会把它当驱动名查找 → "Audio output auto not found"
        audioRender = MpvNativeLoader.hasAudioRenderApi();
        if (audioRender) {
            mpv().mpv_set_option_string(handle, "ao", "null");
        } else {
            // 伪空间音频：lavfi 包装的 pan 滤镜
            // ① 必须用 lavfi=[...] 包装：mpv 的 af 选项把 ":" 当滤镜分隔符
            // ② 第一段必须是布局名 "stereo"（数字 "2" 会被 ffmpeg 拒绝: Invalid out channel name）
            // ③ 系数用 "|" 分隔，且必须显式给出（af=pan=2 无系数 → 全 0 → 静音）
            mpv().mpv_set_option_string(handle, "af", "lavfi=[pan=stereo|c0=1*c0|c1=1*c1]");
        }

        // 单曲循环用 mpv 原生 loop-file=inf（无缝循环，不重新拉流 → 不卡主线程）。
        // 不要用 loadfile replace 重播：mpv 核心持锁销毁+重建播放链（网络 ~2s），
        // 主线程的 set_property/render 等 API 调用都会阻塞等锁 → 游戏卡顿
        mpv().mpv_set_option_string(handle, "loop-file", "no");

        // mpv 日志落盘（必须 initialize 前设置）
        mpv().mpv_set_option_string(handle, "log-file", "mpv.log");

        int r = mpv().mpv_initialize(handle);
        if (r < 0) throw new RuntimeException("mpv_initialize: " + mpv().mpv_error_string(r));

        // 转发 mpv 内部日志（调试）
        mpv().mpv_request_log_messages(handle, "v");

        // 观察属性变化
        mpv().mpv_observe_property(handle, 1, "video-params", MpvNative.MPV_FORMAT_NONE);
        mpv().mpv_observe_property(handle, 2, "duration", MpvNative.MPV_FORMAT_NONE);
        mpv().mpv_observe_property(handle, 3, "audio-params", MpvNative.MPV_FORMAT_NONE);
        mpv().mpv_observe_property(handle, 4, "container-fps", MpvNative.MPV_FORMAT_NONE);

        // 创建软件渲染上下文
        createRenderContext();

        // 创建音频渲染上下文（空间音频，仅新版 libmpv 支持）
        if (audioRender) createAudioContext();

        // 异步加载（不阻塞）
        mpv().mpv_command_async(handle, 0, new String[]{"loadfile", url, "replace"});

        // 版本日志（帮助诊断 DLL 新旧）
        Pointer ver = mpv().mpv_get_property_string(handle, "mpv-version");
        if (ver != null) {
            System.out.println("[MpvPlayer] " + ver.getString(0)
                    + (audioRender ? "（空间音频可用）" : "（旧版：无音频渲染API → 系统直出）"));
            mpv().mpv_free(ver);
        }
        System.out.println("[MpvPlayer] 初始化完成: " + url);
    }

    private void createAudioContext() {
        try {
            PointerByReference ctxRef = new PointerByReference();
            int r = mpv().mpv_audio_render_context_create(ctxRef, handle);
            if (r < 0) {
                System.err.println("[MpvPlayer] audio_render_context_create: " + mpv().mpv_error_string(r));
                return;
            }
            audioCtx = Pointer.nativeValue(ctxRef.getValue());
            audioCtxOk = true;
        } catch (Exception e) {
            System.err.println("[MpvPlayer] 音频上下文创建失败（无空间音频）: " + e.getMessage());
        }
    }

    private void createRenderContext() {
        PointerByReference ctxRef = new PointerByReference();
        // 注意：不设 ADVANCED_CONTROL！
        // advanced 模式要求必须调 mpv_render_context_update() 后按 MPV_RENDER_UPDATE_FRAME 再 render，
        // 否则 mpv 核心会卡在等 update() 上 → 视频管线无帧 → 黑屏（render.h "Threading" 段）。
        // SW 渲染走简单模式：update callback 只设标志，每 tick 无条件 render 即可。
        MpvNative.MpvRenderParam[] params = MpvNative.MpvRenderParam.alloc(2);
        MpvNative.MpvRenderParam.set(params, 0, MpvNative.MPV_RENDER_PARAM_API_TYPE,
                MpvNative.MpvRenderParam.cstr(MpvNative.MPV_RENDER_API_TYPE_SW));
        MpvNative.MpvRenderParam.set(params, 1, 0, null); // 终止符
        int r = mpv().mpv_render_context_create(ctxRef, handle, params);
        if (r < 0) throw new RuntimeException("render_context_create: " + mpv().mpv_error_string(r));
        renderCtx = Pointer.nativeValue(ctxRef.getValue());
        mpv().mpv_render_context_set_update_callback(renderCtx, this::onUpdate, 0);
    }

    /** mpv 渲染线程回调 — 只设标志位 */
    @SuppressWarnings("unused")
    private void onUpdate(long data) {
        updateCount++;
        frameReady.set(true);
    }

    private volatile long updateCount;

    private String getPropStr(String name) {
        try {
            Pointer p = mpv().mpv_get_property_string(handle, name);
            if (p == null) return "null";
            String s = p.getString(0);
            mpv().mpv_free(p);
            return s;
        } catch (Throwable t) { return "err"; }
    }

    private double getPropDouble(String name) {
        try (Memory m = new Memory(8)) {
            if (mpv().mpv_get_property(handle, name, MpvNative.MPV_FORMAT_DOUBLE, m) >= 0)
                return m.getDouble(0);
            return Double.NaN;
        }
    }

    // ============ 对外接口（与 StreamPuller 兼容） ============

    /** 每 tick 调用：渲染到 NativeImage 并推入队列；同时拉取音频 */
    public void pollAndRender() {
        pollAudio();
        if (renderCtx == 0 || !running.get()) return;

        // 每 tick 无条件渲染（不依赖 update callback，稳妥）
        frameReady.set(false);

        // 延迟到视频参数就绪（不依赖事件，主动读）
        int vw = videoW, vh = videoH;
        if (vw <= 0 || vh <= 0) {
            tryReadVideoParams();
            vw = videoW;
            vh = videoH;
            if (vw <= 0 || vh <= 0) return;
        }

        // SW 渲染目标 = 视频原始尺寸（stride = w*4，1280x720 天然 64 字节对齐）
        int fw = vw, fh = vh;
        NativeImage target = nextTarget(fw, fh);

        long ptr = target.getPointer();
        int stride = fw * 4;
        MpvNative.MpvRenderParam[] params = MpvNative.MpvRenderParam.alloc(5);
        MpvNative.MpvRenderParam.set(params, 0, MpvNative.MPV_RENDER_PARAM_SW_SIZE,
                MpvNative.MpvRenderParam.i32x2(fw, fh));
        // render.h: 仅支持 "rgb0"/"bgr0"/"0bgr"/"0rgb"（alpha 为垃圾值，拷贝时强制 255）
        MpvNative.MpvRenderParam.set(params, 1, MpvNative.MPV_RENDER_PARAM_SW_FORMAT,
                MpvNative.MpvRenderParam.cstr("rgb0"));
        MpvNative.MpvRenderParam.set(params, 2, MpvNative.MPV_RENDER_PARAM_SW_STRIDE,
                MpvNative.MpvRenderParam.i32(stride));
        MpvNative.MpvRenderParam.set(params, 3, MpvNative.MPV_RENDER_PARAM_SW_POINTER,
                MpvNative.MpvRenderParam.ptr(ptr));
        MpvNative.MpvRenderParam.set(params, 4, 0, null); // 终止符

        int r = mpv().mpv_render_context_render(renderCtx, params);
        if (r < 0) {
            System.err.println("[MpvPlayer] render 失败: " + mpv().mpv_error_string(r));
            return;
        }
        // 批量强制 alpha=255（mpv rgb0 输出 alpha 为垃圾值 → 不透明，否则画面不可见）
        IntBuffer pix = MemoryUtil.memByteBuffer(ptr, fw * fh * 4).asIntBuffer();
        for (int i = 0; i < fw * fh; i++) pix.put(i, pix.get(i) | 0xFF000000);

        hasVideo.set(true);
        // 直接入队渲染目标本身（不再克隆：池目标被消费者 close 后会自动重建）
        if (!frameQueue.offer(target)) target.close();

        // 调试：探测像素 + mpv 状态（每 2 秒）
        long now = System.currentTimeMillis();
        if (now - lastProbeLog > 2000) {
            lastProbeLog = now;
            int px = target.getPixel(fw / 2, fh / 2);
            System.out.println("[MpvPlayer] probe=0x" + String.format("%08X", px)
                    + " cb=" + updateCount
                    + " time=" + getPropDouble("time-pos")
                    + " vo=" + getPropStr("vo")
                    + " codec=" + getPropStr("video-params/codec"));
        }
    }

    /** 拉取音频样本（S16 交错）到 audioQueue，供 OpenAlAudioPlayer 做空间音频 */
    private void pollAudio() {
        if (!audioCtxOk || audioCtx == 0 || !running.get()) return;
        if (audioChannels <= 0 || audioSampleRate <= 0) return;   // 等 audio-params
        if (audioQueue.size() >= 60) return;                       // 队列快满，暂停拉取

        int maxSamples = 2048; // ≈42ms @48kHz，与 20tick/s 匹配
        try (Memory fmtMem = new Memory(8);
             Memory samplesMem = new Memory(4)) {
            fmtMem.setLong(0, MpvNative.MPV_SAMPLE_FMT_S16);
            samplesMem.setInt(0, maxSamples);
            PointerByReference pbr = new PointerByReference();

            MpvNative.MpvRenderParam[] params = MpvNative.MpvRenderParam.alloc(4);
            MpvNative.MpvRenderParam.set(params, 0, MpvNative.MPV_RENDER_PARAM_AUDIO_FORMAT,
                    MpvNative.MpvRenderParam.i64(MpvNative.MPV_SAMPLE_FMT_S16));
            MpvNative.MpvRenderParam.set(params, 1, MpvNative.MPV_RENDER_PARAM_AUDIO_SAMPLES,
                    samplesMem); // 引用内存：mpv 写入实际采样数
            MpvNative.MpvRenderParam.set(params, 2, MpvNative.MPV_RENDER_PARAM_AUDIO_DATA,
                    pbr.getPointer()); // void**：mpv 写入输出缓冲指针
            MpvNative.MpvRenderParam.set(params, 3, 0, null); // 终止符

            int r = mpv().mpv_audio_render_context_render(audioCtx, params);
            if (r < 0) return; // 无音频或暂停

            int samples = samplesMem.getInt(0);
            Pointer data = pbr.getValue();
            if (samples <= 0 || data == null) return;

            int total = samples * audioChannels;
            short[] pcm = new short[total];
            ByteBuffer bb = MemoryUtil.memByteBuffer(Pointer.nativeValue(data), total * 2);
            bb.asShortBuffer().get(pcm);
            audioQueue.offer(pcm);
        } catch (Exception e) {
            // 忽略偶发失败
        }
    }

    /** 获取一帧（非阻塞） */
    public NativeImage getFrame() { return frameQueue.poll(); }

    /** 是否有视频帧 */
    public boolean hasVideoFrame() { return hasVideo.get(); }

    /** 停止播放 */
    public void stopPulling() {
        running.set(false);
        try { mpv().mpv_command_string(handle, "quit 0"); } catch (Exception ignored) {}
        // 清理 frameQueue
        NativeImage f;
        while ((f = frameQueue.poll()) != null) f.close();
        // 释放音频渲染上下文
        if (audioCtx != 0) {
            try { mpv().mpv_audio_render_context_free(audioCtx); } catch (Exception ignored) {}
            audioCtx = 0;
            audioCtxOk = false;
        }
        // 释放渲染上下文
        if (renderCtx != 0) {
            try { mpv().mpv_render_context_free(renderCtx); } catch (Exception ignored) {}
            renderCtx = 0;
        }
        // 销毁 mpv
        try { mpv().mpv_terminate_destroy(handle); } catch (Exception ignored) {}
        // 清理渲染目标池
        for (int i = 0; i < TARGET_POOL_SIZE; i++) {
            if (targetPool[i] != null) { targetPool[i].close(); targetPool[i] = null; }
        }
        // 清理音频队列
        audioQueue.clear();
        System.out.println("[MpvPlayer] 已停止: " + url);
    }

    /** 跳转（秒） */
    public void seekTo(double seconds) {
        if (!running.get()) return;
        if (videoW <= 0) {
            // 文件未加载完成（loadfile 异步）→ 排队，FILE_LOADED 后执行；
            // 立即 seek 会被 mpv 忽略（idle/加载中）→ 从 0 播，丢失播放位置
            pendingSeek = seconds;
            return;
        }
        mpv().mpv_command_async(handle, 0, new String[]{"seek", String.valueOf(seconds), "absolute"});
    }

    /** 单曲循环检测：time-pos 从末尾跳回开头 → 置位循环标志（Manager 负责重置服务器进度） */
    private void checkLoop() {
        if (ended || duration <= 0) return; // 直播/未知时长不检测
        double t = getPropDouble("time-pos");
        if (t < 0) return; // NaN/不可用
        if (lastTimePos >= 0 && lastTimePos > duration - 3 && t < 3) {
            loopDetected = true;
        }
        lastTimePos = t;
    }

    /** 消费循环标志（单次有效，Manager 每 tick 调用） */
    public boolean consumeLoopDetected() {
        boolean d = loopDetected;
        loopDetected = false;
        return d;
    }

    /** 播放是否已结束（END_FILE 后置位，reload/新建时清除） */
    public boolean isEnded() { return ended; }

    /** 是否正常播完（error==0）。加载失败（error!=0）不自动重播，避免坏 url 死循环 */
    public boolean isEndedCleanly() { return ended && endedError == 0; }

    /** 自动重播：重新加载同一 url（SINGLE_LOOP 兜底，正常应走 loop-file 无缝循环）。
     *  注意：loadfile replace 会让 mpv 核心持锁重建播放链（网络 ~2s），主线程 API 调用会阻塞，
     *  仅作为 loop-file 不可用时的最后手段 */
    public void reload() {
        if (!running.get()) return;
        ended = false;
        System.out.println("[MpvPlayer] 自动重播: " + url);
        mpv().mpv_command_async(handle, 0, new String[]{"loadfile", url, "replace"});
    }

    /**
     * 设置单曲循环（mpv 原生无缝循环，播完自动 seek 0 重播，无 END_FILE/无重载/无卡顿）。
     * mode 变化时由 Manager 调用；运行时属性可随时切换。
     */
    public void setLoopFile(boolean loop) {
        if (!running.get()) return;
        mpv().mpv_set_property_string(handle, "loop-file", loop ? "inf" : "no");
    }

    /** 设置音量 0.0-1.0 */
    public void setVolume(float v) {
        if (!running.get()) return;
        try (Memory m = new Memory(8)) {
            m.setDouble(0, Math.max(0, Math.min(1, v)) * 100);
            mpv().mpv_set_property(handle, "volume", MpvNative.MPV_FORMAT_DOUBLE, m);
        }
    }

    /** 上次 af=pan 系数，用于变化检测 */
    private float lastPanL = Float.NaN, lastPanR = Float.NaN;
    private long lastPanUpdate;

    /**
     * 伪空间音频（无 audio render API 时）：mpv 直出声音。
     * ① volume 属性：全局音量 = 距离衰减 × 用户音量（两侧同降）
     * ② lavfi pan 滤镜系数：左右声道独立增益（mpv 0.41 已删除 audio-pan-x/volume-l/r，
     *    且 "af set <id> <param> <value>" 命令语法已废弃 → 用 set_property 整体替换 af 链）
     * lVol/rVol 为左右声道目标音量 [0,1]（已含距离衰减/用户音量/声道开关）。
     */
    public void setSpatialFallback(float lVol, float rVol) {
        if (!running.get()) return;

        // ① 全局音量（距离衰减 + 用户音量）
        float v = Math.max(lVol, rVol);
        try (Memory m = new Memory(8)) {
            m.setDouble(0, Math.max(0, Math.min(1, v)) * 100);
            mpv().mpv_set_property(handle, "volume", MpvNative.MPV_FORMAT_DOUBLE, m);
        }

        // ② 左右平衡（lavfi pan 系数）— 降频 + 变化阈值，避免频繁重建滤镜
        long now = System.currentTimeMillis();
        if (now - lastPanUpdate < 100) return;
        if (Math.abs(lVol - lastPanL) < 0.01 && Math.abs(rVol - lastPanR) < 0.01) return;
        lastPanL = lVol;
        lastPanR = rVol;
        lastPanUpdate = now;
        // 布局名必须用 "stereo"（数字 "2" → ffmpeg "Invalid out channel name"）
        String graph = String.format("lavfi=[pan=stereo|c0=%.3f*c0|c1=%.3f*c1]",
                (double) Math.max(0, Math.min(1, lVol)),
                (double) Math.max(0, Math.min(1, rVol)));
        int r = mpv().mpv_set_property_string(handle, "af", graph);
        if (r < 0) System.err.println("[MpvPlayer] af 链替换失败: " + mpv().mpv_error_string(r));
    }

    /** 暂停/恢复 */
    public void setPaused(boolean paused) {
        if (!running.get()) return;
        mpv().mpv_set_property_string(handle, "pause", paused ? "yes" : "no");
    }

    // ---- Getters ----

    public int getVideoWidth() { return videoW; }
    public int getVideoHeight() { return videoH; }
    public double getFrameRate() { return fps; }
    public double getDuration() { return duration; }
    public boolean isLive() { return duration <= 0; }
    public int getAudioSampleRate() { return audioSampleRate; }
    public int getAudioChannels() { return audioChannels; }
    public boolean isAudioReady() { return true; } // mpv 内部处理
    public boolean isRunning() { return running.get(); }

    /** 音频样本队列（供 OpenAlAudioPlayer 空间音频使用；旧版 libmpv 返回 null） */
    public BlockingQueue<short[]> getAudioQueue() { return audioRender ? audioQueue : null; }

    /** 是否支持空间音频（需 libmpv ≥0.33 音频渲染 API） */
    public boolean hasSpatialAudio() { return audioRender; }

    // ---- 事件处理（每 tick 调用） ----

    public void processEvents() {
        if (!running.get()) return;
        checkLoop(); // 单曲循环检测（每 tick，不依赖渲染）
        while (true) {
            MpvNative.mpv_event event = mpv().mpv_wait_event(handle, 0); // 非阻塞
            int id = event.event_id;
            if (id == MpvNative.MPV_EVENT_NONE) break;

            switch (id) {
                case MpvNative.MPV_EVENT_SHUTDOWN -> { running.set(false); }
                case MpvNative.MPV_EVENT_FILE_LOADED -> {
                    System.out.println("[MpvPlayer] 文件加载完成: " + url);
                    // 执行加载期间排队的 seek（保留播放位置）。
                    // clamp：SINGLE_LOOP 循环后服务器进度漂移（累计秒数可能远超时长）→ 取模到实际位置
                    if (!Double.isNaN(pendingSeek)) {
                        double s = pendingSeek;
                        pendingSeek = Double.NaN;
                        double dur = getPropDouble("duration");
                        if (dur > 0 && s > dur) s = s % dur;
                        mpv().mpv_command_async(handle, 0, new String[]{"seek", String.valueOf(s), "absolute"});
                    }
                }
                case MpvNative.MPV_EVENT_END_FILE -> {
                    System.out.println("[MpvPlayer] 播放结束/失败: error=" + event.error
                            + " (" + mpv().mpv_error_string(event.error) + ")");
                    // mpv 播完进入 idle：video-params 失效 → 清空尺寸，阻止继续渲染黑帧（fps 下降根源）
                    // 注意：videoW/H 必须立即清零，等 Manager 按模式决定重播/切集
                    videoW = 0;
                    videoH = 0;
                    ended = true;
                    endedError = event.error;
                }
                case MpvNative.MPV_EVENT_COMMAND_REPLY ->
                        System.out.println("[MpvPlayer] 命令回复 error=" + event.error
                                + " (" + mpv().mpv_error_string(event.error) + ")");
                case MpvNative.MPV_EVENT_VIDEO_RECONFIG -> {
                    System.out.println("[MpvPlayer] 视频重配置");
                    tryReadVideoParams();
                }
                case MpvNative.MPV_EVENT_LOG_MESSAGE -> {
                    MpvNative.mpv_event_log_message msg =
                            new MpvNative.mpv_event_log_message(event.data);
                    if (msg.text != null)
                        System.out.println("[mpv/" + msg.level + "/" + msg.prefix + "] " + msg.text.trim());
                }
                case MpvNative.MPV_EVENT_PROPERTY_CHANGE -> {
                    if (event.data == null) break;
                    MpvNative.mpv_event_property prop = new MpvNative.mpv_event_property(event.data);
                    long uid = event.reply_userdata;
                    switch ((int) uid) {
                        case 1 -> handleVideoParams(prop);
                        case 2 -> handleDuration(prop);
                        case 3 -> handleAudioParams(prop);
                        case 4 -> handleFps(prop);
                    }
                }
            }
        }
    }

    private void handleVideoParams(MpvNative.mpv_event_property prop) {
        tryReadVideoParams();
    }

    /** 主动读取视频分辨率（不依赖事件，稳妥） */
    private void tryReadVideoParams() {
        try (Memory wMem = new Memory(8); Memory hMem = new Memory(8)) {
            int rw = mpv().mpv_get_property(handle, "video-params/w", MpvNative.MPV_FORMAT_INT64, wMem);
            int rh = mpv().mpv_get_property(handle, "video-params/h", MpvNative.MPV_FORMAT_INT64, hMem);
            if (rw < 0 || rh < 0) {
                // 解码器未就绪/已结束（idle）→ 清空尺寸，阻止用旧尺寸渲染黑帧
                // （END_FILE 也会清零，这里兜底覆盖重载间隙等场景）
                if (videoW != 0 || videoH != 0) {
                    videoW = 0; videoH = 0;
                    System.out.println("[MpvPlayer] 视频参数失效，停止渲染");
                }
                return;
            }
            int nw = (int) wMem.getLong(0);
            int nh = (int) hMem.getLong(0);
            if (nw > 0 && nh > 0 && (nw != videoW || nh != videoH)) {
                videoW = nw; videoH = nh;
                // 目标池会在 nextTarget 尺寸检测时自动重建
                System.out.println("[MpvPlayer] 视频分辨率: " + nw + "x" + nh);
            }
        }
    }

    private void handleDuration(MpvNative.mpv_event_property prop) {
        try (Memory m = new Memory(8)) {
            if (mpv().mpv_get_property(handle, "duration", MpvNative.MPV_FORMAT_DOUBLE, m) >= 0)
                duration = m.getDouble(0);
        }
    }

    private void handleAudioParams(MpvNative.mpv_event_property prop) {
        try (Memory sr = new Memory(8); Memory ch = new Memory(8)) {
            if (mpv().mpv_get_property(handle, "audio-params/samplerate", MpvNative.MPV_FORMAT_INT64, sr) >= 0)
                audioSampleRate = (int) sr.getLong(0);
            if (mpv().mpv_get_property(handle, "audio-params/channel-count", MpvNative.MPV_FORMAT_INT64, ch) >= 0)
                audioChannels = (int) ch.getLong(0);
        }
    }

    private void handleFps(MpvNative.mpv_event_property prop) {
        try (Memory m = new Memory(8)) {
            if (mpv().mpv_get_property(handle, "container-fps", MpvNative.MPV_FORMAT_DOUBLE, m) >= 0) {
                double f = m.getDouble(0);
                if (f > 0) fps = f;
            }
        }
    }

    // ---- 内部 ----

    /**
     * 取下一个渲染目标（环形池）。
     * 池目标入队后所有权归消费者（消费者负责 close）；轮换时若已被 close 则重建。
     * 必须用 stb 模式（true）：pointer 是 CPU 可写内存；
     * false 时创建的是 GL 纹理，getPointer() 是纹理 ID，mpv 无法写入。
     */
    private NativeImage nextTarget(int w, int h) {
        // 尺寸变化 → 重建整个池（先清空队列，避免消费者取到已被 close 的旧池帧）
        if (w != targetW || h != targetH) {
            NativeImage f;
            while ((f = frameQueue.poll()) != null) f.close();
            for (int i = 0; i < TARGET_POOL_SIZE; i++) {
                if (targetPool[i] != null) targetPool[i].close();
                targetPool[i] = new NativeImage(NativeImage.Format.RGBA, w, h, true);
            }
            targetW = w; targetH = h;
        }
        NativeImage t = targetPool[targetIndex];
        if (t.isClosed()) { // 消费者已 close → 重建
            t = new NativeImage(NativeImage.Format.RGBA, w, h, true);
            targetPool[targetIndex] = t;
        }
        targetIndex = (targetIndex + 1) % TARGET_POOL_SIZE;
        return t;
    }
}
