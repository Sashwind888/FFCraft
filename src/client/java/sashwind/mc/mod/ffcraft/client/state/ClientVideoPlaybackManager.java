package sashwind.mc.mod.ffcraft.client.state;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import sashwind.mc.mod.ffcraft.client.audio.OpenAlAudioPlayer;
import sashwind.mc.mod.ffcraft.client.player.MpvPlayer;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientVideoPlaybackManager {
    private static final Map<UUID, MpvPlayer> players = new ConcurrentHashMap<>();
    /** 空间音频（每播放器一个，从 MpvPlayer 的音频队列拉 PCM） */
    private static final Map<UUID, OpenAlAudioPlayer> audioPlayers = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> uvRecalculated = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> videoFrameSeq = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> uvManuallyEdited = new ConcurrentHashMap<>();
    private static NativeImage placeholderImage = null;
    private static UUID lastPlayerId;

    private static int pixelsPerBlock = 64;
    public static final int[] QUALITY_OPTIONS = {0, 16, 32, 64, 128, 256};
    public static final String[] QUALITY_LABELS = {"原画", "16x", "32x", "64x", "128x", "256x"};

    // ===== 超出距离的省电策略（纯本地行为，不改变服务器状态/网络协议） =====
    /** >256 格 → 直接停止播放（销毁播放器，省解码+渲染 CPU） */
    private static final double STOP_DISTANCE = 256.0;
    /** >128 格 → 暂停播放（保留播放器，走近自动恢复） */
    private static final double PAUSE_DISTANCE = 128.0;
    /** 滞回：越过阈值 32 格才恢复，防止边界抖动导致反复暂停/恢复/销毁重建 */
    private static final double HYSTERESIS = 32.0;
    private static final Map<UUID, Boolean> stopByDistance = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> pauseByDistance = new ConcurrentHashMap<>();

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
        for (int i = 0; i < QUALITY_OPTIONS.length; i++)
            if (QUALITY_OPTIONS[i] == pixelsPerBlock) return i;
        return 3;
    }
    public static void setQualityByIndex(int idx) {
        if (idx >= 0 && idx < QUALITY_OPTIONS.length) pixelsPerBlock = QUALITY_OPTIONS[idx];
    }

    // ===== 对外接口 =====

    public static double getDuration(UUID playerId) {
        var p = players.get(playerId);
        return p != null ? p.getDuration() : 0;
    }
    public static int getVideoFps(UUID playerId) {
        var p = players.get(playerId);
        return p != null ? (int) p.getFrameRate() : 30;
    }
    public static void seekStream(UUID playerId, double seconds) {
        var p = players.get(playerId);
        if (p != null) p.seekTo(seconds);
        playbackStartMs.put(playerId, System.currentTimeMillis());
        playbackStartSecs.put(playerId, (int) seconds);
    }
    public static boolean isLive(UUID playerId) {
        var p = players.get(playerId);
        return p != null && p.isLive();
    }
    public static void setGlobalVolume(float v) {
        currentVolume = v;
        // 空间音频：音量由 OpenAL 应用侧控制（空间计算已含 currentVolume）
        for (var ap : audioPlayers.values()) ap.setVolume(v);
        // 回退模式（旧版 libmpv 无音频渲染 API）：mpv 直接输出，音量走 mpv volume
        for (var mp : players.values()) {
            if (!mp.hasSpatialAudio()) mp.setVolume(v);
        }
    }

    // ===== 进度计算 =====

    private static float currentVolume = 1f;
    private static final Map<UUID, Long> playbackStartMs = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> playbackStartSecs = new ConcurrentHashMap<>();

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
        if (status != PlaybackStatus.PLAYING) return serverProg;
        if (Minecraft.getInstance().isPaused()) return serverProg;
        Long startMs = playbackStartMs.get(playerId);
        Integer startSecs = playbackStartSecs.get(playerId);
        if (startMs != null && startSecs != null && startMs > 0) {
            long elapsed = (System.currentTimeMillis() - startMs) / 1000;
            return startSecs + (int) elapsed;
        }
        return serverProg;
    }

    // ===== 占位图 =====

    private static NativeImage getOrCreatePlaceholder() {
        if (placeholderImage != null) return placeholderImage;
        try {
            var manager = Minecraft.getInstance().getResourceManager();
            var res = manager.getResource(net.minecraft.resources.Identifier.fromNamespaceAndPath("ffcraft", "music.png"));
            if (res.isPresent()) {
                placeholderImage = NativeImage.read(res.get().open());
                return placeholderImage;
            }
        } catch (Exception ignored) {}
        int w = 320, h = 180;
        placeholderImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        return placeholderImage;
    }

    // ===== 缩放 + 推送 =====

    private static int calcMaxScreenPixels(VideoPlayerData player) {
        if (pixelsPerBlock <= 0) return Integer.MAX_VALUE;
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
            double screenW = Math.max(maxX - minX, maxZ - minZ);
            double screenH = (maxY - minY < 0.01) ? Math.min(maxX - minX, maxZ - minZ) : maxY - minY;
            maxBlockDim = Math.max(maxBlockDim, Math.max(screenW, screenH));
        }
        return (int) (maxBlockDim * pixelsPerBlock);
    }

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

    // ===== 生命周期 =====

    private static int lastIndex = -1;
    private static String lastUrl;
    private static sashwind.mc.mod.ffcraft.common.model.PlaybackMode lastMode;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientVideoPlaybackManager::onTick);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(c -> stopAll());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> stopAll());
    }

    private static int tickCounter = 0;

    private static void onTick(Minecraft client) {
        if (!MpvPlayer.isAvailable()) return;
        if (client.player == null || client.level == null) return;
        if (client.isPaused() && client.getCurrentServer() == null) return;
        tickCounter++;
        var snap = ClientVideoPlayerCache.getSnapshot();
        if (snap == null) return;

        for (VideoPlayerData player : snap.players()) {
            var pb = player.playbackState();

            if (pb.status() != PlaybackStatus.PLAYING) {
                UUID pid = player.id();
                stopLocalPlayer(pid);
                stopByDistance.remove(pid);
                pauseByDistance.remove(pid);
                continue;
            }

            int idx = pb.currentIndex();
            if (idx < 0 || idx >= player.playlist().size()) continue;

            String url = player.playlist().get(idx).url();
            UUID pid = player.id();

            // 超出距离的省电策略（滞回防抖动：跨过阈值 32 格才恢复）
            double dist = distanceToPlayer(player);
            if (dist > STOP_DISTANCE) {
                if (!stopByDistance.getOrDefault(pid, false)) {
                    stopByDistance.put(pid, true);
                    stopLocalPlayer(pid);
                }
                continue; // 已停止：下 tick 在范围内时才重建播放器
            } else if (dist < STOP_DISTANCE - HYSTERESIS) {
                stopByDistance.remove(pid);
            }
            boolean farPause;
            if (dist > PAUSE_DISTANCE) {
                farPause = true;
                pauseByDistance.put(pid, true);
            } else if (dist < PAUSE_DISTANCE - HYSTERESIS) {
                pauseByDistance.remove(pid);
                farPause = false;
            } else {
                farPause = pauseByDistance.getOrDefault(pid, false); // 滞回区间内保持原状态
            }

            // 创建新 MpvPlayer（URL/player/mode 变更时）
            if (!pid.equals(lastPlayerId) || idx != lastIndex || !url.equals(lastUrl)
                    || !pb.mode().equals(lastMode) || !players.containsKey(pid)) {
                if (lastPlayerId != null) {
                    var old = players.remove(lastPlayerId);
                    if (old != null) old.stopPulling();
                }
                try {
                    MpvPlayer mp = new MpvPlayer(url);
                    players.put(pid, mp);
                    // 单曲循环：mpv 原生 loop-file 无缝循环（不重载不卡顿）；其他模式关闭
                    mp.setLoopFile(pb.mode() == sashwind.mc.mod.ffcraft.common.model.PlaybackMode.SINGLE_LOOP);
                    // 空间音频：OpenAlAudioPlayer 从 mpv 的音频队列拉 PCM
                    // （旧版 libmpv 无音频渲染 API → mpv 系统直出，跳过）
                    audioPlayers.remove(pid);
                    if (mp.hasSpatialAudio()) {
                        OpenAlAudioPlayer ap = new OpenAlAudioPlayer(mp.getAudioQueue());
                        ap.start(mp.getAudioSampleRate(), mp.getAudioChannels());
                        audioPlayers.put(pid, ap);
                    }
                    if (pb.progressSeconds() > 0) mp.seekTo(pb.progressSeconds());

                    lastPlayerId = pid;
                    lastIndex = idx;
                    lastUrl = url;
                    lastMode = pb.mode();
                    playbackStartMs.put(pid, System.currentTimeMillis());
                    playbackStartSecs.put(pid, pb.progressSeconds());
                    uvRecalculated.remove(pid);
                    videoFrameSeq.remove(pid);

                    // 预计算 UV
                    var vs = player.playlist().get(idx);
                    if (vs.originalWidth() > 0 && vs.originalHeight() > 0) {
                        recalcUvForVideo(pid, player, (double) vs.originalWidth() / vs.originalHeight());
                        uvRecalculated.put(pid, true);
                    }
                } catch (Exception e) {
                    System.err.println("[VideoPlayer] MpvPlayer 创建失败: " + e.getMessage());
                    continue;
                }
            }

            var mp = players.get(pid);
            if (mp == null) continue;

            // 处理 mpv 事件（属性变化等）
            mp.processEvents();
            // 单曲循环（loop-file 无缝循环无事件）：检测到循环点 → 重置服务器进度，进度条归零
            if (mp.consumeLoopDetected()) {
                sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.seekPlayback(pid, 0);
                playbackStartMs.put(pid, System.currentTimeMillis());
                playbackStartSecs.put(pid, 0);
            }
            // 渲染待处理帧到 frameQueue（远距离暂停时跳过，省解码+渲染 CPU）
            if (!farPause) mp.pollAndRender();

            // === 空间音频 ===
            OpenAlAudioPlayer ap = audioPlayers.get(pid);
            if (ap != null) {
                if (!ap.isRunning()) ap.start(mp.getAudioSampleRate(), mp.getAudioChannels());
                if (!farPause) {
                    updateSpatialAudio(player, mp, ap);
                    ap.update(); // 排空 PCM 队列到 OpenAL
                }
            } else if (!farPause) {
                // 伪空间音频（mpv 直出）：audio-pan-x/volume 模拟
                updateSpatialAudio(player, mp, null);
            }

            // 取帧
            long pushed = videoFrameSeq.getOrDefault(pid, 0L);
            NativeImage latest = null;
            for (int taken = 0; taken < 3; taken++) {
                NativeImage f = mp.getFrame();
                if (f == null) break;
                if (latest != null) latest.close();
                latest = f;
                pushed++;
            }
            // 排空积压帧
            while (true) {
                NativeImage f = mp.getFrame();
                if (f == null) break;
                f.close();
                pushed++;
            }

            if (latest != null) {
                pushDownscaledToScreens(player, latest);
                videoFrameSeq.put(pid, pushed);

                // 首帧 UV fallback
                if (uvRecalculated.get(pid) == null || !uvRecalculated.get(pid)) {
                    var vs = player.playlist().get(idx);
                    int vw = vs.originalWidth();
                    int vh = vs.originalHeight();
                    if (vw <= 0 || vh <= 0) { vw = latest.getWidth(); vh = latest.getHeight(); }
                    if (vw > 0 && vh > 0) {
                        recalcUvForVideo(pid, player, (double) vw / vh);
                        uvRecalculated.put(pid, true);
                    }
                }
                latest.close();
            }

            // 纯音频流 → 推送占位图
            Long pms = playbackStartMs.get(pid);
            long elapsed = pms != null ? System.currentTimeMillis() - pms : 0;
            if (elapsed > 1000 && !mp.hasVideoFrame()) {
                pushDownscaledToScreens(player, getOrCreatePlaceholder());
            }

            // 暂停状态：服务器暂停 或 远距离暂停（本地省电）
            boolean pausedByServer = (pb.status() == PlaybackStatus.PAUSED);
            mp.setPaused(pausedByServer || farPause);
        }

        // 清理不再播放的 player
        for (var e : players.entrySet()) {
            UUID pid = e.getKey();
            boolean keep = false;
            for (var p : snap.players()) {
                if (p.id().equals(pid) && p.playbackState().status() == PlaybackStatus.PLAYING)
                { keep = true; break; }
            }
            if (keep && e.getValue().isEndedCleanly()) {
                // 播放正常结束 → 按模式处理：单曲重播（本地 reload，保留播放器）或切集（通知服务器，等广播后重建）
                boolean singleLoopRestart = false;
                for (var p : snap.players()) {
                    if (p.id().equals(pid)) {
                        var pb = p.playbackState();
                        var pm = pb.mode();
                        int size = p.playlist().size();
                        switch (pm) {
                            case SINGLE_LOOP -> {
                                e.getValue().reload();
                                // 同步服务器进度归零（协议不变：复用现有 seek 指令）
                                sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.seekPlayback(pid, 0);
                                singleLoopRestart = true;
                            }
                            case LOOP_LIST -> {
                                int ni = pb.currentIndex() + 1;
                                if (ni >= size) ni = 0;
                                sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.updatePlayback(
                                        pid, PlaybackStatus.PLAYING, pm, ni, pb.volume());
                                sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.seekPlayback(pid, 0);
                            }
                            case SEQUENTIAL -> {
                                int ni = pb.currentIndex() + 1;
                                if (ni >= size) {
                                    // 列表播完 → 停止
                                    sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.updatePlayback(
                                            pid, PlaybackStatus.STOPPED, pm, pb.currentIndex(), pb.volume());
                                } else {
                                    sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.updatePlayback(
                                            pid, PlaybackStatus.PLAYING, pm, ni, pb.volume());
                                    sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.seekPlayback(pid, 0);
                                }
                            }
                            case RANDOM -> {
                                int ni = pb.currentIndex();
                                if (size > 1) {
                                    do { ni = (int) (Math.random() * size); } while (ni == pb.currentIndex());
                                }
                                sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.updatePlayback(
                                        pid, PlaybackStatus.PLAYING, pm, ni, pb.volume());
                                sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.seekPlayback(pid, 0);
                            }
                        }
                        playbackStartMs.put(pid, System.currentTimeMillis());
                        playbackStartSecs.put(pid, 0);
                        break;
                    }
                }
                if (singleLoopRestart) {
                    continue; // 单曲循环：播放器保留（reload 中），跳过下面的移除逻辑
                }
                keep = false; // 切集/停止：移除已结束的播放器，等服务器广播新快照后重建
            }
            boolean paused = false;
            for (var p : snap.players()) {
                if (p.id().equals(pid) && p.playbackState().status() == PlaybackStatus.PAUSED)
                { paused = true; break; }
            }
            if (!keep && !paused) {
                e.getValue().stopPulling();
                var oldAp = audioPlayers.remove(e.getKey());
                if (oldAp != null) oldAp.stop();
                players.remove(e.getKey());
                playbackStartMs.remove(e.getKey());
                playbackStartSecs.remove(e.getKey());
                if (e.getKey().equals(lastPlayerId)) { lastPlayerId = null; lastMode = null; }
            }
        }
    }

    // ===== 距离检测 + 本地停止 =====

    /** 播放器到最近屏幕中心的距离（格） */
    private static double distanceToPlayer(VideoPlayerData player) {
        var cam = Minecraft.getInstance().player;
        if (cam == null) return 0;
        double minSq = Double.MAX_VALUE;
        for (var sc : player.screens()) {
            if (sc.vertices().isEmpty()) continue;
            double cx = 0, cy = 0, cz = 0;
            for (var v : sc.vertices()) { cx += v.x(); cy += v.y(); cz += v.z(); }
            int n = sc.vertices().size();
            cx /= n; cy /= n; cz /= n;
            double dx = cx - cam.getX(), dy = cy - cam.getEyeY(), dz = cz - cam.getZ();
            minSq = Math.min(minSq, dx * dx + dy * dy + dz * dz);
        }
        return minSq == Double.MAX_VALUE ? 0 : Math.sqrt(minSq);
    }

    /** 本地停止一个播放器（不通知服务器，网络协议不变） */
    private static void stopLocalPlayer(UUID pid) {
        var old = players.remove(pid);
        if (old != null) old.stopPulling();
        var oldAp = audioPlayers.remove(pid);
        if (oldAp != null) oldAp.stop();
        uvRecalculated.remove(pid);
        videoFrameSeq.remove(pid);
        playbackStartMs.remove(pid);
        playbackStartSecs.remove(pid);
        if (pid.equals(lastPlayerId)) { lastPlayerId = null; lastMode = null; }
    }

    // ===== 空间音频 =====

    /**
     * 计算空间音量：
     * 屏幕中心距离衰减 × 视角方向左右平衡（等功率法则）× 声道开关 × 用户音量
     * ap != null → OpenAL PCM 空间音频（0.33-0.36 旧 API）
     * ap == null → 伪空间音频：mpv audio-pan-x/volume 模拟（0.37+ 无音频渲染 API）
     */
    private static void updateSpatialAudio(VideoPlayerData player, MpvPlayer mp, OpenAlAudioPlayer ap) {
        var cam = Minecraft.getInstance().player;
        if (cam == null || player.screens().isEmpty()) return;
        var sc = player.screens().get(0);
        if (sc.vertices().isEmpty()) return;

        // ① 屏幕中心点
        double cx = 0, cy = 0, cz = 0;
        for (var v : sc.vertices()) { cx += v.x(); cy += v.y(); cz += v.z(); }
        int n = sc.vertices().size();
        cx /= n; cy /= n; cz /= n;

        // ② 距离衰减: 16 格内满音量，每 16 格减半
        double dx = cx - cam.getX();
        double dy = cy - cam.getEyeY();
        double dz = cz - cam.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float distVol = (float) Math.clamp(1.0 / Math.max(1, dist / 16), 0, 1);

        // ③ 左右声道平衡（等功率法则）
        var look = cam.getLookAngle();
        var right = new net.minecraft.world.phys.Vec3(-look.z, 0, look.x).normalize();
        var toScr = new net.minecraft.world.phys.Vec3(dx, 0, dz).normalize();
        float pan = (float) Math.clamp(right.dot(toScr), -1, 1);

        // ④ 声道开关
        var ch = sc.channelState();
        float leftFactor = ch.leftEnabled() ? 1f : 0f;
        float rightFactor = ch.rightEnabled() ? 1f : 0f;

        // ⑤ 综合
        float u = currentVolume;
        float lVol = distVol * u * leftFactor  * (float) Math.sqrt((1 - pan) / 2);
        float rVol = distVol * u * rightFactor * (float) Math.sqrt((1 + pan) / 2);

        if (ap != null) ap.setSpatialVolumes(lVol, rVol);
        else mp.setSpatialFallback(lVol, rVol);
    }

    public static void stopAll() {
        for (var mp : players.values()) mp.stopPulling();
        players.clear();
        for (var ap : audioPlayers.values()) ap.stop();
        audioPlayers.clear();
        uvRecalculated.clear();
        videoFrameSeq.clear();
        uvManuallyEdited.clear();
        playbackStartMs.clear();
        playbackStartSecs.clear();
        if (placeholderImage != null) { placeholderImage.close(); placeholderImage = null; }
        ClientScreenRenderManager.clearAll();
        stopByDistance.clear();
        pauseByDistance.clear();
        lastPlayerId = null;
        lastMode = null;
    }

    public static void stopLocal(UUID playerId) {
        stopLocalPlayer(playerId);
        stopByDistance.remove(playerId);
        pauseByDistance.remove(playerId);
        ClientVideoPlayerCache.forceStop(playerId);
    }

    // ===== UV =====

    public static void markUvManuallyEdited(UUID screenId) { uvManuallyEdited.put(screenId, true); }
    public static boolean isUvManuallyEdited(UUID screenId) { return uvManuallyEdited.getOrDefault(screenId, false); }

    private static void recalcUvForVideo(UUID pid, VideoPlayerData player, double videoAspect) {
        for (var sd : player.screens()) {
            if (sd.uvManuallyEdited() || uvManuallyEdited.getOrDefault(sd.id(), false)) continue;
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
            double su, sv;
            if (screenAspect > videoAspect) { su = screenAspect / videoAspect; sv = 1.0; }
            else { su = 1.0; sv = videoAspect / screenAspect; }
            var cur = sd.uvTransform();
            if (Math.abs(cur.scaleU() - su) < 0.005 && Math.abs(cur.scaleV() - sv) < 0.005) continue;
            var newUv = new UvTransform(0, 0, su, sv, 0, cur.flipU(), cur.flipV());
            sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking.updateScreenUv(pid, sd.id(), newUv);
        }
    }
}
