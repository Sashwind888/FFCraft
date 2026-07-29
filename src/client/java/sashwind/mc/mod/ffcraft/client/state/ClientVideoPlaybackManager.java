package sashwind.mc.mod.ffcraft.client.state;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import sashwind.mc.mod.ffcraft.client.audio.OpenAlAudioPlayer;
import sashwind.mc.mod.ffcraft.client.player.FFmpegSettings;
import sashwind.mc.mod.ffcraft.client.player.StreamPuller;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientVideoPlaybackManager {
    private static final Map<UUID, StreamPuller> pullers = new ConcurrentHashMap<>();
    private static final Map<UUID, OpenAlAudioPlayer> audioPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> audioStarted = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> audioOnlyPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> uvRecalculated = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> audioSampleCount = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> videoFrameSeq = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> uvManuallyEdited = new ConcurrentHashMap<>();
    private static NativeImage placeholderImage = null;
    private static UUID lastPlayerId;

    private static int pixelsPerBlock = 64;  // 0=原画, 16/32/64/128/256=每方块像素
    public static final int[] QUALITY_OPTIONS = {0, 16, 32, 64, 128, 256}; // 0=原画
    public static final String[] QUALITY_LABELS = {"原画", "16x", "32x", "64x", "128x", "256x"};

    public static int getPixelsPerBlock() { return pixelsPerBlock; }
    public static void setPixelsPerBlock(int ppb) { pixelsPerBlock = ppb; }
    public static String[] getQualityOptions(int videoMaxDim) {
        var list = new java.util.ArrayList<String>();
        for (int i = 0; i < QUALITY_OPTIONS.length; i++) {
            int q = QUALITY_OPTIONS[i];
            if (q == 0 || q <= videoMaxDim) list.add(QUALITY_LABELS[i]);
        }
        return list.toArray(new String[0]);
    }
    public static int getQualityIndex() {
        for (int i = 0; i < QUALITY_OPTIONS.length; i++) {
            if (QUALITY_OPTIONS[i] == pixelsPerBlock) return i;
        }
        return 3; // default 64x
    }
    public static void setQualityByIndex(int idx) {
        if (idx >= 0 && idx < QUALITY_OPTIONS.length) pixelsPerBlock = QUALITY_OPTIONS[idx];
    }

    // cached preview texture for GUI (OpenGL texture ID)
    private static float currentVolume = 1f;

    public static double getDuration(UUID playerId) {
        StreamPuller sp = pullers.get(playerId);
        return sp != null ? sp.getDuration() : 0;
    }
    public static int getVideoFps(UUID playerId) {
        StreamPuller sp = pullers.get(playerId);
        return sp != null ? (int) sp.getFrameRate() : 30;
    }

    public static void seekStream(UUID playerId, double seconds) {
        StreamPuller sp = pullers.get(playerId);
        if (sp != null) sp.seekTo(seconds);
        playbackStartMs.put(playerId, System.currentTimeMillis());
        playbackStartSecs.put(playerId, (int) seconds);
    }

    public static boolean isLive(UUID playerId) {
        StreamPuller sp = pullers.get(playerId);
        return sp != null && sp.isLive();
    }

    /** Local progress: server time + elapsed. Paused时返回服务器固定值。 */
    public static int getLocalProgressSeconds(UUID playerId) {
        var snap = ClientVideoPlayerCache.getSnapshot();
        PlaybackStatus status = PlaybackStatus.STOPPED;
        int serverProg = 0;
        if (snap != null) {
            for (var p : snap.players()) {
                if (p.id().equals(playerId)) {
                    serverProg = p.playbackState().progressSeconds();
                    status = p.playbackState().status();
                    break;
                }
            }
        }
        // 暂停/停止：返回服务器值（不变）
        if (status != PlaybackStatus.PLAYING) return serverProg;
        // 游戏暂停（ESC 菜单等）：不累计本地时间
        if (net.minecraft.client.Minecraft.getInstance().isPaused()) return serverProg;
        // 播放中：用本地计时
        Long startMs = playbackStartMs.get(playerId);
        Integer startSecs = playbackStartSecs.get(playerId);
        if (startMs != null && startSecs != null && startMs > 0) {
            long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            return startSecs + (int) elapsed;
        }
        return serverProg;
    }

    /** 加载资源文件夹中的 music.png 作为音频占位图 */
    private static NativeImage getOrCreatePlaceholder() {
        if (placeholderImage != null) return placeholderImage;
        try {
            var manager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            var res = manager.getResource(net.minecraft.resources.Identifier.fromNamespaceAndPath("ffcraft", "music.png"));
            if (res.isPresent()) {
                placeholderImage = NativeImage.read(res.get().open());
                System.out.println("[VideoPlayer] Loaded music.png placeholder: "
                    + placeholderImage.getWidth() + "x" + placeholderImage.getHeight());
                return placeholderImage;
            }
        } catch (Exception e) {
            System.err.println("[VideoPlayer] Failed to load music.png: " + e.getMessage());
        }
        // 回退：简单深色占位图
        int w = 320, h = 180;
        placeholderImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        System.out.println("[VideoPlayer] Using fallback procedural placeholder");
        return placeholderImage;
    }

    /** 根据屏幕尺寸计算所需最大像素数 */
    private static int calcMaxScreenPixels(VideoPlayerData player) {
        if (pixelsPerBlock <= 0) return Integer.MAX_VALUE; // 原画，不缩放
        double maxBlockDim = 0;
        for (var sd : player.screens()) {
            if (sd.vertices().size() < 3) continue;
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (var v : sd.vertices()) {
                minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
                minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
            }
            double sizeX = maxX - minX, sizeY = maxY - minY, sizeZ = maxZ - minZ;
            double screenW = Math.max(sizeX, sizeZ);
            double screenH = (sizeY < 0.01) ? Math.min(sizeX, sizeZ) : sizeY;
            maxBlockDim = Math.max(maxBlockDim, Math.max(screenW, screenH));
        }
        return (int) (maxBlockDim * pixelsPerBlock);
    }

    /** 降采样 NativeImage（简单最近邻） */
    private static NativeImage downscale(NativeImage src, int maxPixels) {
        int sw = src.getWidth(), sh = src.getHeight();
        int maxDim = Math.max(sw, sh);
        if (maxDim <= maxPixels) return null;
        double scale = (double) maxPixels / maxDim;
        int dw = Math.max(2, (int) (sw * scale));
        int dh = Math.max(2, (int) (sh * scale));
        NativeImage dst = new NativeImage(NativeImage.Format.RGBA, dw, dh, false);
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx = x * sw / dw, sy = y * sh / dh;
                dst.setPixel(x, y, src.getPixel(sx, sy));
            }
        }
        return dst;
    }

    /** 降采样后推送到共享 WorldDraw（一个播放器一个 WorldDraw → 一张纹理 → 一次 upload） */
    private static void pushDownscaledToScreens(VideoPlayerData player, NativeImage frame) {
        NativeImage toPush = frame;
        NativeImage downscaled = null;
        int maxPixels = calcMaxScreenPixels(player);
        if (maxPixels > 0 && Math.max(frame.getWidth(), frame.getHeight()) > maxPixels) {
            downscaled = downscale(frame, maxPixels);
            if (downscaled != null) toPush = downscaled;
        }

        var wd = ClientScreenRenderManager.getPlayerWorldDraw(player.id());
        if (wd != null) {
            wd.setTexture(toPush.getWidth(), toPush.getHeight(), toPush);
        }

        if (downscaled != null) downscaled.close();
    }

    public static void setGlobalVolume(float v) {
        currentVolume = v;
        for (OpenAlAudioPlayer ap : audioPlayers.values()) ap.setVolume(v);
    }

    private static final Map<UUID, Long> playbackStartMs = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> playbackStartSecs = new ConcurrentHashMap<>();
    private static int previewTexId = 0;
    private static int previewTexW = 0, previewTexH = 0;
    private static boolean previewTexDirty = false;
    private static final Object previewTexLock = new Object();
    private static byte[] previewPixels = null;

    public static int getPreviewTextureId() { return previewTexId; }
    public static boolean isPreviewReady() { return previewTexId != 0; }
    private static int lastIndex = -1;
    private static String lastUrl;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientVideoPlaybackManager::onTick);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopAll());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopAll());
    }

    private static int tickCounter = 0;
    private static void onTick(net.minecraft.client.Minecraft client) {
        if (client.player == null || client.level == null) return;
        if (client.isPaused() && client.getCurrentServer() == null) return;
        tickCounter++;
        var snap = ClientVideoPlayerCache.getSnapshot();
        if (snap == null) return;

        for (VideoPlayerData player : snap.players()) {
            var pb = player.playbackState();
            // 暂停/停止时停止本地 puller 和音频
            if (pb.status() != PlaybackStatus.PLAYING) {
                UUID pid = player.id();
                StreamPuller sp = pullers.remove(pid);
                if (sp != null) sp.stopPulling();
                OpenAlAudioPlayer ap = audioPlayers.remove(pid);
                if (ap != null) ap.stop();
                audioStarted.remove(pid);
                audioOnlyPlayers.remove(pid);
                uvRecalculated.remove(pid);
                audioSampleCount.remove(pid);
                videoFrameSeq.remove(pid);
                playbackStartMs.remove(pid);
                playbackStartSecs.remove(pid);
                if (pid.equals(lastPlayerId)) lastPlayerId = null;
                continue;
            }

            int idx = pb.currentIndex();
            if (idx < 0 || idx >= player.playlist().size()) continue;

            String url = player.playlist().get(idx).url();
            UUID pid = player.id();

            // only create new puller if URL/player changed (NOT on resolution change)
            if (!pid.equals(lastPlayerId) || idx != lastIndex || !url.equals(lastUrl) || !pullers.containsKey(pid)) {
                // stop old puller and audio
                if (lastPlayerId != null) {
                    StreamPuller old = pullers.remove(lastPlayerId);
                    if (old != null) old.stopPulling();
                    OpenAlAudioPlayer oldAp = audioPlayers.remove(lastPlayerId);
                    if (oldAp != null) oldAp.stop();
                    audioStarted.remove(lastPlayerId);
                }

                // start new one with current resolution
                FFmpegSettings s = new FFmpegSettings();
                StreamPuller sp = new StreamPuller(url, s);
                sp.start();
                pullers.put(pid, sp);
                // seek到服务器进度（重连/切换时同步播放位置）
                if (pb.progressSeconds() > 0) sp.seekTo(pb.progressSeconds());
                audioOnlyPlayers.remove(pid);  // 新流可能有视频，重置音频检测
                audioSampleCount.remove(pid);   // 重置音频计数
                videoFrameSeq.remove(pid);      // 重置帧序列
                lastPlayerId = pid;
                lastIndex = idx;
                lastUrl = url;
                playbackStartMs.put(pid, System.currentTimeMillis());
                playbackStartSecs.put(pid, pb.progressSeconds());

                // 根据实际视频分辨率重新计算UV (解决非16:9视频需要黑边填充的问题)
                uvRecalculated.remove(pid); // 重置标志，允许首帧 fallback 重新计算
                var videoSrc = player.playlist().get(idx);
                if (videoSrc.originalWidth() > 0 && videoSrc.originalHeight() > 0) {
                    double videoAspect = (double) videoSrc.originalWidth() / videoSrc.originalHeight();
                    recalcUvForVideo(pid, player, videoAspect);
                    uvRecalculated.put(pid, true);
                }
            }

            // video frames — audio-pacing: 音频为主时钟，每次限取N帧防止跳帧
            StreamPuller sp = pullers.get(pid);
            if (sp != null) {
                long samples = audioSampleCount.getOrDefault(pid, 0L);
                double audioSecs = (double) samples / Math.max(1, sp.getAudioSampleRate());
                long pushed = videoFrameSeq.getOrDefault(pid, 0L);
                double fps = sp.getFrameRate();
                double videoSecs = (double) pushed / Math.max(1, fps);
                double threshold = 2.0 / Math.max(1, fps);

                // 取帧直到追上音频或队列空（每帧都计入时钟，丢弃的也不遗漏）
                NativeImage latest = null;
                while (videoSecs <= audioSecs + threshold) {
                    NativeImage f = sp.getFrame();
                    if (f == null) break;
                    if (latest != null) latest.close();
                    latest = f;
                    pushed++;
                    videoSecs = (double) pushed / Math.max(1, fps);
                }
                if (latest != null) {
                    audioOnlyPlayers.remove(pid);
                    pushDownscaledToScreens(player, latest);
                    videoFrameSeq.put(pid, pushed);

                        if (uvRecalculated.get(pid) == null || !uvRecalculated.get(pid)) {
                            var videoSrc = player.playlist().get(idx);
                            int vw = videoSrc.originalWidth();
                            int vh = videoSrc.originalHeight();
                            if (vw <= 0 || vh <= 0) {
                                vw = latest.getWidth();
                                vh = latest.getHeight();
                            }
                            if (vw > 0 && vh > 0) {
                                recalcUvForVideo(pid, player, (double) vw / vh);
                                uvRecalculated.put(pid, true);
                            }
                        }
                        latest.close();
                    }

                {
                        // 检测纯音频流：运行超过1秒仍无视频帧 → 推送占位图
                        Long pms = playbackStartMs.get(pid);
                        long elapsed = pms != null ? System.currentTimeMillis() - pms : 0;
                        if (elapsed > 1000 && !sp.hasVideoFrame()) {
                            audioOnlyPlayers.put(pid, true);
                        }
                        if (Boolean.TRUE.equals(audioOnlyPlayers.get(pid))) {
                            NativeImage placeholder = getOrCreatePlaceholder();
                            pushDownscaledToScreens(player, placeholder);
                        }
                    }

                // audio: start immediately (video will catch up naturally)
                {
                    Boolean started = audioStarted.get(pid);
                    if (started == null) started = false;

                    OpenAlAudioPlayer ap = audioPlayers.computeIfAbsent(pid, k -> {
                        var a = new OpenAlAudioPlayer(sp.getAudioQueue());
                        return a;
                    });

                    if (!started && sp.isAudioReady()) {
                        ap.start(sp.getAudioSampleRate(), sp.getAudioChannels());
                        audioStarted.put(pid, true);
                        System.out.println("[Audio] Started audio sr=" + sp.getAudioSampleRate() + " ch=" + sp.getAudioChannels() + " player=" + pid);
                    } else if (!started && tickCounter % 40 == 0) {
                        System.out.println("[Audio] Waiting for audio metadata...");
                    }

                    ap.update();
                    // 同步音频采样计数（用于 video audio-pacing）
                    audioSampleCount.put(pid, ap.getTotalSamplesConsumed());
                    // 空间音频：距离衰减 + 立体声左右平衡
                    if (!player.screens().isEmpty()) {
                        var sc = player.screens().get(0);
                        var cam = Minecraft.getInstance().player;
                        if (!sc.vertices().isEmpty() && cam != null) {
                            // ① 计算屏幕中心点
                            double cx = 0, cy = 0, cz = 0;
                            for (var v : sc.vertices()) { cx += v.x(); cy += v.y(); cz += v.z(); }
                            cx /= sc.vertices().size(); cy /= sc.vertices().size(); cz /= sc.vertices().size();
                            // ② 距离衰减: 1.0 / max(1, dist/16) — 16格内满音量，每16格减半
                            double dx = cx - cam.getX();
                            double dy = cy - cam.getEyeY();
                            double dz = cz - cam.getZ();
                            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                            float distVol = (float) Math.clamp(1.0 / Math.max(1, dist / 16), 0, 1);
                            // ③ 左右声道平衡（Pan）：基于玩家视角方向与屏幕方向的点积
                            //    使用等功率法则 sqrt((1±pan)/2)，pan=0 时左右各≈0.707（比线性法则响亮）
                            var look = cam.getLookAngle();
                            var right = new net.minecraft.world.phys.Vec3(-look.z, 0, look.x).normalize();
                            var toScr = new net.minecraft.world.phys.Vec3(dx, 0, dz).normalize();
                            float pan = (float) Math.clamp(right.dot(toScr), -1, 1);
                            // ④ 声道状态（屏幕可独立开关左右声道）
                            var ch = sc.channelState();
                            float leftFactor = ch.leftEnabled() ? 1f : 0f;
                            float rightFactor = ch.rightEnabled() ? 1f : 0f;
                            // ⑤ 综合音量 = 用户设置音量 × 距离衰减 × 等功率立体声平衡 × 声道开关
                            float u = currentVolume;
                            float lVol = distVol * u * leftFactor  * (float) Math.sqrt((1 - pan) / 2);
                            float rVol = distVol * u * rightFactor * (float) Math.sqrt((1 + pan) / 2);
                            ap.setSpatialVolumes(lVol, rVol);
                        }
                    }
                }
            }
        }

        // stop pullers for non-playing players or end-of-stream
        for (var e : pullers.entrySet()) {
            try {
            UUID pid = e.getKey();
            boolean keep = false;
            for (VideoPlayerData p : snap.players()) {
                if (p.id().equals(pid) && p.playbackState().status() == PlaybackStatus.PLAYING) {
                    keep = true; break;
                }
            }
            // also stop if decoder has finished (end of stream)
            if (keep && !e.getValue().isRunning()) {
                keep = false;
                // stream ended: auto advance
                for (var p : snap.players()) {
                    if (p.id().equals(pid)) {
                        var pb = p.playbackState();
                        int nextIdx = pb.currentIndex() + 1;
                        if (pb.mode() == sashwind.mc.mod.ffcraft.common.model.PlaybackMode.SINGLE_LOOP || nextIdx >= p.playlist().size()) {
                            if (pb.mode() == sashwind.mc.mod.ffcraft.common.model.PlaybackMode.SINGLE_LOOP) nextIdx = pb.currentIndex();
                            else if (pb.mode() == sashwind.mc.mod.ffcraft.common.model.PlaybackMode.LOOP_LIST) nextIdx = 0;
                            else { nextIdx = 0; /* STOP */ }
                        }
                        sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.updatePlayback(
                            pid, PlaybackStatus.PLAYING, pb.mode(), nextIdx, pb.volume());
                        // 自动切换/循环后强制归零进度
                        sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.seekPlayback(pid, 0);
                        playbackStartMs.put(pid, System.currentTimeMillis());
                        playbackStartSecs.put(pid, 0);
                        break;
                    }
                }
            }

            // Don't remove paused players (keep puller for resume)
            boolean paused = false;
            for (VideoPlayerData p : snap.players()) {
                if (p.id().equals(pid) && p.playbackState().status() == PlaybackStatus.PAUSED) {
                    paused = true; break;
                }
            }

            if (!keep && !paused) {
                e.getValue().stopPulling();
                pullers.remove(e.getKey());
                playbackStartMs.remove(e.getKey());
                playbackStartSecs.remove(e.getKey());
                OpenAlAudioPlayer ap = audioPlayers.remove(e.getKey());
                if (ap != null) ap.stop();
                audioStarted.remove(e.getKey());
                audioOnlyPlayers.remove(e.getKey());
                if (e.getKey().equals(lastPlayerId)) lastPlayerId = null;
            }
            } catch (Exception ex) {
                System.err.println("[VideoPlayer] cleanup error: " + ex.getMessage());
            }
        }
    }

    private static void cachePreviewFrame(NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        synchronized (previewTexLock) {
            byte[] rawBytes = new byte[w * h * 4];
            long ptr = img.getPointer();
            org.lwjgl.system.MemoryUtil.memByteBuffer(ptr, w * h * 4).get(rawBytes);
            previewPixels = rawBytes;
            previewTexW = w; previewTexH = h;
            previewTexDirty = true;
            System.out.println("[Preview] Cached frame " + w + "x" + h + ", bytes=" + rawBytes.length);
        }
    }

    private static boolean loggedFirstUpload = false;
    /** Call on render thread to upload preview texture to GL */
    public static void uploadPreviewTexture() {
        synchronized (previewTexLock) {
            if (!previewTexDirty || previewPixels == null) return;
            previewTexDirty = false;

            if (!loggedFirstUpload) {
                System.out.println("[Preview] Uploading texture " + previewTexW + "x" + previewTexH + " GLid=" + previewTexId);
                loggedFirstUpload = true;
            }

            try {
                if (previewTexId == 0) {
                    previewTexId = org.lwjgl.opengl.GL11C.glGenTextures();
                }
                org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, previewTexId);
                org.lwjgl.opengl.GL11C.glTexParameteri(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D,
                        org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11C.GL_LINEAR);
                org.lwjgl.opengl.GL11C.glTexParameteri(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D,
                        org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11C.GL_LINEAR);

                // 视频帧可能很大（4K），MemoryStack 栈空间不够，用堆外内存
                java.nio.ByteBuffer directBuf = org.lwjgl.system.MemoryUtil.memAlloc(previewPixels.length);
                try {
                    directBuf.put(previewPixels);
                    directBuf.flip();
                    org.lwjgl.opengl.GL11C.glTexImage2D(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0,
                            org.lwjgl.opengl.GL11C.GL_RGBA, previewTexW, previewTexH, 0,
                            org.lwjgl.opengl.GL11C.GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE,
                            directBuf);
                } finally {
                    org.lwjgl.system.MemoryUtil.memFree(directBuf);
                }
                System.out.println("[Preview] Upload successful, GLid=" + previewTexId);
            } catch (Exception e) {
                System.err.println("[Preview] Upload failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /** 从选中播放器的视频流抓取一帧到预览纹理 */
    public static void capturePreviewFrame(UUID playerId) {
        StreamPuller sp = pullers.get(playerId);
        if (sp == null) return;
        NativeImage frame = sp.getFrame();
        if (frame != null) {
            try { cachePreviewFrame(frame); } finally { frame.close(); }
        }
    }

    public static void stopAll() {
        for (StreamPuller sp : pullers.values()) sp.stopPulling();
        pullers.clear();
        for (OpenAlAudioPlayer ap : audioPlayers.values()) ap.stop();
        audioPlayers.clear();
        audioStarted.clear();
        audioOnlyPlayers.clear();
        uvRecalculated.clear();
        audioSampleCount.clear();
        videoFrameSeq.clear();
        uvManuallyEdited.clear();
        playbackStartMs.clear();
        playbackStartSecs.clear();
        // 释放 OpenGL 预览纹理（glDeleteTextures 必须在渲染线程，stopAll 可能在 Netty 线程调用）
        if (previewTexId != 0) {
            final int texId = previewTexId;
            previewTexId = 0;
            previewTexW = 0;
            previewTexH = 0;
            previewTexDirty = false;
            previewPixels = null;
            loggedFirstUpload = false;
            net.minecraft.client.Minecraft.getInstance().execute(() ->
                org.lwjgl.opengl.GL11C.glDeleteTextures(texId));
        }
        // 释放 NativeImage 占位图
        if (placeholderImage != null) {
            placeholderImage.close();
            placeholderImage = null;
        }
        ClientScreenRenderManager.clearAll();
        lastPlayerId = null;
    }

    /** UV 是否已被手动修改（阻止自动重算） */
    public static void markUvManuallyEdited(UUID screenId) { uvManuallyEdited.put(screenId, true); }
    public static boolean isUvManuallyEdited(UUID screenId) { return uvManuallyEdited.getOrDefault(screenId, false); }

    /** 立即停止本地播放（不等服务器同步） */
    public static void stopLocal(UUID playerId) {
        StreamPuller sp = pullers.remove(playerId);
        if (sp != null) sp.stopPulling();
        OpenAlAudioPlayer ap = audioPlayers.remove(playerId);
        if (ap != null) ap.stop();
        audioStarted.remove(playerId);
        playbackStartMs.remove(playerId);
        playbackStartSecs.remove(playerId);
        if (playerId.equals(lastPlayerId)) lastPlayerId = null;
        // 立即更新本地缓存（progress=0, status=STOPPED）
        ClientVideoPlayerCache.forceStop(playerId);
    }

    public static NativeImage getLatestFrame(UUID playerId) {
        StreamPuller sp = pullers.get(playerId);
        if (sp == null) return null;
        NativeImage latest = null;
        while (true) {
            NativeImage f = sp.getFrame();
            if (f == null) break;
            if (latest != null) latest.close();
            latest = f;
        }
        return latest;
    }

    /** 根据实际视频宽高比重新计算所有屏幕的 UV 变换 */
    private static void recalcUvForVideo(UUID pid, VideoPlayerData player, double videoAspect) {
        System.out.printf("[UV] Recalculating for aspect=%.3f%n", videoAspect);
        for (var sd : player.screens()) {
            // 玩家手动改过 UV → 跳过自动计算（双保险：持久化标志 + 内存标志）
            if (sd.uvManuallyEdited() || uvManuallyEdited.getOrDefault(sd.id(), false)) {
                System.out.printf("[UV] Screen '%s': skipped (manually edited)%n", sd.name());
                continue;
            }
            if (sd.vertices().size() < 3) continue;

            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (var v : sd.vertices()) {
                minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
                minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
                minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
            }
            double sizeX = maxX - minX, sizeY = maxY - minY, sizeZ = maxZ - minZ;
            double screenW, screenH;
            if (sizeY < 0.01) { screenW = Math.max(sizeX, sizeZ); screenH = Math.min(sizeX, sizeZ); }
            else { screenW = Math.max(sizeX, sizeZ); screenH = sizeY; }
            if (screenW < 0.01 || screenH < 0.01) continue;

            double screenAspect = screenW / screenH;
            double newScaleU, newScaleV;
            if (screenAspect > videoAspect) {
                newScaleU = screenAspect / videoAspect;
                newScaleV = 1.0;
            } else {
                newScaleU = 1.0;
                newScaleV = videoAspect / screenAspect;
            }

            var curUv = sd.uvTransform();
            UvTransform newUv = new UvTransform(0, 0, newScaleU, newScaleV, 0, curUv.flipU(), curUv.flipV());

            if (Math.abs(curUv.scaleU() - newScaleU) > 0.005 || Math.abs(curUv.scaleV() - newScaleV) > 0.005) {
                System.out.printf("[UV] Screen '%s': scaleU %.3f→%.3f scaleV %.3f→%.3f%n",
                        sd.name(), curUv.scaleU(), newScaleU, curUv.scaleV(), newScaleV);
                // 自动计算，uvManuallyEdited = false（不覆盖手动编辑标志）
                sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.updateScreenUv(
                        pid, sd.id(), newUv);
            }
        }
    }
}
