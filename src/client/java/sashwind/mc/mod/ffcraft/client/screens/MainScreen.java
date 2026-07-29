package sashwind.mc.mod.ffcraft.client.screens;

import cn.enaium.fabric.imgui.ImGuiRenderable;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;
import sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking;
import sashwind.mc.mod.ffcraft.client.player.Player;
import sashwind.mc.mod.ffcraft.client.state.ClientScreenCreationManager;
import sashwind.mc.mod.ffcraft.common.model.CreatePlayerRequest;
import sashwind.mc.mod.ffcraft.common.model.PlaybackMode;
import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
import sashwind.mc.mod.ffcraft.common.model.ScreenChannelState;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerData;
import sashwind.mc.mod.ffcraft.common.model.VideoScreenData;
import java.util.*;


public class MainScreen extends Screen implements ImGuiRenderable {
    private static final float FONT_SIZE = 30f;
    private static final float ITEM_HEIGHT = 32f;
    private static final float BUTTON_W = 140f;
    private static final float BUTTON_H = 34f;
    private static final float LEFT_PANE_W = 320f;
    private static int localPlayerCounter = 1;
    private static int selectedPlayerIndex = -1;
    private static int selectedScreenIndex = -1;
    private static int selectedVideoIndex = -1;
    private static List<VideoPlayerData> cachedPlayers = new ArrayList<>();
    private static long lastSnapshotVersion = Long.MIN_VALUE;
    private static boolean fontConfigured = false;

    private int renamingPlayerIdx = -1;
    private java.util.UUID renamingScreenId;
    private final ImString renameBuf = new ImString(128);
    private final ImString urlInputBuf = new ImString(2048);
    private float uvOffsetX, uvOffsetY, uvScaleU = 1f, uvScaleV = 1f, rotationY;
    private boolean uvFlipU, uvFlipV;
    private java.util.UUID lastEditedScreenId;
    private final int[] seekValue = {0};
    private boolean seekDragging = false;
    private int lastFps = 30;
    private float previewZoom = 1f, previewOffsetX = 0f, previewOffsetY = 0f;
    private String editingMode;
    private float editStartU, editStartV, editStartScale, editStartScaleV, editStartOffsetX, editStartOffsetY;
    private float editStartDist;
    private int editCorner;
    private boolean editingRotation = false;
    private final ImString rotationEditBuf = new ImString(16);

    public MainScreen() { super(Component.translatable("key.screens.mainscreen.title")); }

    @Override
    public void removed() {
        super.removed();
        // 清空静态缓存，允许 GC 回收 VideoPlayerData 引用
        cachedPlayers.clear();
        lastSnapshotVersion = Long.MIN_VALUE;
    }

    @Override public void render(ImGuiIO io) {
        syncCache();
        sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.uploadPreviewTexture();
        ImVec2 ds = io.getDisplaySize();
        float w = Math.min(ds.x * 0.8f, 1100f), h = Math.min(ds.y * 0.85f, 750f);
        ImGui.setNextWindowSize(w, h, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos((ds.x - w) * 0.5f, (ds.y - h) * 0.5f, ImGuiCond.FirstUseEver);
        if (ImGui.begin("FFCraft · 播放器管理", ImGuiWindowFlags.NoSavedSettings)) renderLayout();
        ImGui.end();
    }

    public static boolean isFontConfigured() { return fontConfigured; }
    public static void configureFontOnce(ImGuiIO io) {
        if (fontConfigured) return;
        fontConfigured = true;
        ImFontAtlas fonts = io.getFonts();
        fonts.clearFonts();
        ImFontConfig cfg = new ImFontConfig(); cfg.setSizePixels(FONT_SIZE);
        try {
            if (!tryLoadBuiltinFont(fonts, cfg)) { fonts.addFontDefault(); io.setFontGlobalScale(2.0f); }
            fonts.build();
        } finally {
            cfg.destroy();
        }
    }
    private static boolean tryLoadBuiltinFont(ImFontAtlas fonts, ImFontConfig cfg) {
        try {
            var in = MainScreen.class.getClassLoader().getResourceAsStream("assets/ffcraft/font/cjk.ttf");
            if (in == null) return false;
            byte[] d = in.readAllBytes(); in.close();
            fonts.addFontFromMemoryTTF(d, FONT_SIZE, cfg, fonts.getGlyphRangesChineseFull());
            return true;
        } catch (Exception e) { return false; }
    }

    private void renderLayout() {
        float ah = ImGui.getContentRegionAvailY(), lw = LEFT_PANE_W, rw = ImGui.getContentRegionAvailX() - lw - 8f;
        if (ImGui.beginChild("##left", lw, ah, true, ImGuiWindowFlags.NoScrollWithMouse)) { renderLeftPane(); } ImGui.endChild();
        ImGui.sameLine(0, 8f);
        if (ImGui.beginChild("##right", rw, ah, true, ImGuiWindowFlags.NoScrollWithMouse)) {
            if (cachedPlayers.isEmpty()) ImGui.textColored(0.5f, 0.5f, 0.5f, 1f, "暂无播放器");
            else if (selectedPlayerIndex < 0 || selectedPlayerIndex >= cachedPlayers.size()) {
                selectedPlayerIndex = 0; ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "请在左侧选择播放器");
            } else renderRightPane(cachedPlayers.get(selectedPlayerIndex));
        } ImGui.endChild();
    }

    private void renderLeftPane() {
        boolean hs = selectedPlayerIndex >= 0 && selectedPlayerIndex < cachedPlayers.size();
        ImGui.separator(); ImGui.spacing(); ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "播放器列表"); ImGui.spacing();
        float lh = ImGui.getContentRegionAvailY() - 220f;
        if (ImGui.beginChild("##ps", 0, lh)) {
            for (int i = 0; i < cachedPlayers.size(); i++) {
                VideoPlayerData p = cachedPlayers.get(i);
                if (renamingPlayerIdx == i) {
                    ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 8f);
                    ImGui.inputText("##rp" + p.id(), renameBuf); ImGui.setItemDefaultFocus();
                    if (ImGui.isItemDeactivatedAfterEdit() || ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter)) {
                        String nm = renameBuf.toString().trim();
                        if (!nm.isEmpty()) VideoPlayerClientNetworking.renamePlayer(p.id(), nm);
                        renamingPlayerIdx = -1;
                    }
                    if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) renamingPlayerIdx = -1;
                } else {
                    if (ImGui.selectable(p.name() + "##p" + p.id(), i == selectedPlayerIndex, 0, new ImVec2(0, ITEM_HEIGHT))) {
                        selectedPlayerIndex = i; selectedScreenIndex = -1;
                    }
                    if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                        selectedPlayerIndex = i; renameBuf.set(p.name()); renamingPlayerIdx = i; renamingScreenId = null;
                    }
                }
            }
        } ImGui.endChild();
        ImGui.spacing(); ImGui.separator(); ImGui.spacing();
        ImGui.textColored(0.7f, 0.7f, 0.7f, 1f, "屏幕列表"); ImGui.spacing();
        if (hs) {
            VideoPlayerData p = cachedPlayers.get(selectedPlayerIndex);
            if (ImGui.beginChild("##ss", 0, 120f)) {
                for (int i = 0; i < p.screens().size(); i++) {
                    VideoScreenData sc = p.screens().get(i);
                    if (renamingScreenId != null && renamingScreenId.equals(sc.id())) {
                        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - 8f);
                        ImGui.inputText("##rs" + sc.id(), renameBuf); ImGui.setItemDefaultFocus();
                        if (ImGui.isItemDeactivatedAfterEdit() || ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter)) {
                            String nm = renameBuf.toString().trim();
                            if (!nm.isEmpty()) VideoPlayerClientNetworking.renameScreen(p.id(), sc.id(), nm);
                            renamingScreenId = null;
                        }
                        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) renamingScreenId = null;
                    } else {
                        if (ImGui.selectable(sc.name() + "##sc" + i, i == selectedScreenIndex, 0, new ImVec2(0, ITEM_HEIGHT)))
                            selectedScreenIndex = i;
                        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                            selectedScreenIndex = i; renameBuf.set(sc.name()); renamingScreenId = sc.id(); renamingPlayerIdx = -1;
                        }
                    }
                }
            } ImGui.endChild();
        }
        ImGui.spacing(); ImGui.separator(); ImGui.spacing();
        if (ImGui.button("+ 新建播放器", BUTTON_W, BUTTON_H)) {
            VideoPlayerClientNetworking.createPlayer(new CreatePlayerRequest("Player" + localPlayerCounter++, false));
        }
        if (hs) { ImGui.sameLine();
            if (ImGui.button("× 删除播放器", BUTTON_W, BUTTON_H)) {
                VideoPlayerClientNetworking.deletePlayer(cachedPlayers.get(selectedPlayerIndex).id());
                selectedPlayerIndex = -1; selectedScreenIndex = -1;
            }
        }
    }

    private void renderRightPane(VideoPlayerData player) {
        if (ImGui.beginTabBar("##tabs")) {
            if (ImGui.beginTabItem("播放控制")) { renderPlaybackTab(player); ImGui.endTabItem(); }
            if (ImGui.beginTabItem("播放列表")) { renderPlaylistTab(player); ImGui.endTabItem(); }
            if (ImGui.beginTabItem("屏幕设置")) { renderScreenTab(player); ImGui.endTabItem(); }
            ImGui.endTabBar();
        }
    }

    private void renderPlaybackTab(VideoPlayerData player) {
        PlaybackState pb = player.playbackState();
        int tot = player.playlist().size(), ci = pb.currentIndex();
        float availW = ImGui.getContentRegionAvailX();

        // ==== VIDEO PREVIEW (16:9) ====
        sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.uploadPreviewTexture();
        float prevH = availW * 9f / 16f;
        if (ImGui.beginChild("##videoPrev", availW, prevH, true, ImGuiWindowFlags.NoScrollWithMouse)) {
            float pw = ImGui.getContentRegionAvailX(), ph = ImGui.getContentRegionAvailY();
            ImVec2 c = ImGui.getCursorScreenPos();

            int texId = sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.getPreviewTextureId();
            if (texId != 0) {
                // System.out.println("[Preview GUI] Drawing image with texId=" + texId + ", size=" + pw + "x" + ph);
                ImGui.image(texId, pw, ph, 0, 1, 1, 0);
            } else {

                ImGui.getWindowDrawList().addRectFilled(c.x, c.y, c.x + pw, c.y + ph, 0xFF0A0A0A);
                float cell = 24f;
                for (float gx = c.x; gx < c.x + pw; gx += cell)
                    ImGui.getWindowDrawList().addLine(gx, c.y, gx, c.y + ph, 0x18FFFFFF, 0.5f);
                for (float gy = c.y; gy < c.y + ph; gy += cell)
                    ImGui.getWindowDrawList().addLine(c.x, gy, c.x + pw, gy, 0x18FFFFFF, 0.5f);
            }
        }
        ImGui.endChild();
        ImGui.spacing();

        // ==== PROGRESS BAR ====
        int secs = sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.getLocalProgressSeconds(player.id());
        boolean isLive = sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.isLive(player.id());
        double dur = sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.getDuration(player.id());
        int maxSecs = Math.max((int) dur, secs + 1);

        // sync from server; reset on stop
        if (pb.status() == PlaybackStatus.STOPPED) { seekValue[0] = 0; seekDragging = false; }
        else if (!seekDragging) seekValue[0] = secs;

        String ts = String.format("%d:%02d", seekValue[0] / 60, seekValue[0] % 60);
        ImGui.setNextItemWidth(availW);
        if (isLive) ImGui.beginDisabled();
        boolean changed = ImGui.sliderInt("##seek", seekValue, 0, maxSecs, ts + " | " + (pb.currentIndex() + 1) + "/" + Math.max(tot, 1));
        boolean active = ImGui.isItemActive();
        if (changed && !seekDragging) seekDragging = true;
        if (!active && seekDragging) {
            // user released: send seek
            VideoPlayerClientNetworking.seekPlayback(player.id(), seekValue[0]);
            sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.seekStream(player.id(), seekValue[0]);
            seekDragging = false;
        }
        if (isLive) ImGui.endDisabled();
        ImGui.spacing();

        // ==== PLAYBACK CONTROLS (centered) ====
        float bw = 52f, totalCtrlW = bw * 4 + 12f;
        ImGui.setCursorPosX((availW - totalCtrlW) / 2);
        if (ImGui.button("⏮", bw, 0)) {
            int prevIdx = ci > 0 ? ci - 1 : Math.max(0, tot - 1);
            VideoPlayerClientNetworking.updatePlayback(player.id(), PlaybackStatus.PLAYING, pb.mode(), prevIdx, pb.volume());
        }
        ImGui.sameLine(0, 4f);
        if (ImGui.button(pb.status() == PlaybackStatus.PLAYING ? "⏸" : "▶", bw, 0)) {
            var ns = pb.status() == PlaybackStatus.PLAYING ? PlaybackStatus.PAUSED : PlaybackStatus.PLAYING;
            VideoPlayerClientNetworking.updatePlayback(player.id(), ns, pb.mode(), ci, pb.volume());
            if (ns == PlaybackStatus.PLAYING) {
                // 恢复：seek 到暂停前的进度
                VideoPlayerClientNetworking.seekPlayback(player.id(), seekValue[0]);
            } else {
                // 暂停：seek 到当前位置保存进度
                VideoPlayerClientNetworking.seekPlayback(player.id(), seekValue[0]);
                sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.stopLocal(player.id());
            }
        }
        ImGui.sameLine(0, 4f);
        if (ImGui.button("■", bw, 0)) {
            VideoPlayerClientNetworking.updatePlayback(player.id(), PlaybackStatus.STOPPED, pb.mode(), ci, pb.volume());
            sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.stopLocal(player.id());
            seekValue[0] = 0; seekDragging = false;
        }
        ImGui.sameLine(0, 4f);
        if (ImGui.button("⏭", bw, 0)) {
            int nextIdx = ci < tot - 1 ? ci + 1 : 0;
            VideoPlayerClientNetworking.updatePlayback(player.id(), PlaybackStatus.PLAYING, pb.mode(), nextIdx, pb.volume());
        }
        ImGui.spacing();

        // ==== SETTINGS ROW ====
        int[] vol = {pb.volume()}; ImGui.text("音量"); ImGui.sameLine(); ImGui.setNextItemWidth(70f);
        if (ImGui.sliderInt("##vol", vol, 0, 300, "%d%%", 0)) {
            VideoPlayerClientNetworking.updatePlayback(player.id(), pb.status(), pb.mode(), ci, vol[0]);
            sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.setGlobalVolume(vol[0] / 100f);
        }
        ImGui.sameLine(); ImGui.text(" 画质"); ImGui.sameLine(); ImGui.setNextItemWidth(80f);
        // 根据当前视频分辨率过滤可选项（超过视频分辨率的选项隐藏）
        int videoW = 1280, videoH = 720;
        if (ci >= 0 && ci < player.playlist().size()) {
            var src = player.playlist().get(ci);
            if (src.originalWidth() > 0) { videoW = src.originalWidth(); videoH = src.originalHeight(); }
        }
        int videoMaxDim = Math.max(videoW, videoH);
        String[] qOpts = sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.getQualityOptions(videoMaxDim);
        int curQ = sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.getQualityIndex();
        // 找到当前值在过滤后列表中的索引
        int curQIdx = 0;
        for (int i = 0; i < qOpts.length; i++) {
            if (qOpts[i].equals(sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.QUALITY_LABELS[curQ])) {
                curQIdx = i; break;
            }
        }
        ImInt qi = new ImInt(curQIdx);
        if (ImGui.combo("##quality", qi, qOpts)) {
            // 从过滤后的标签映射回原始索引
            String selLabel = qOpts[qi.get()];
            for (int i = 0; i < sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.QUALITY_LABELS.length; i++) {
                if (sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.QUALITY_LABELS[i].equals(selLabel)) {
                    sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.setQualityByIndex(i);
                    break;
                }
            }
        }
        // 模式
        ImGui.sameLine(); ImGui.text(" 模式"); ImGui.sameLine(); ImGui.setNextItemWidth(80f);
        ImInt mi = new ImInt(pb.mode().ordinal());
        if (ImGui.combo("##mode", mi, new String[]{"顺序","循环","单曲"})) {
            VideoPlayerClientNetworking.updatePlayback(player.id(), pb.status(), PlaybackMode.values()[mi.get()], ci, pb.volume());
        }
    }

    private void renderPlaylistTab(VideoPlayerData player) {
        PlaybackState pb = player.playbackState();
        int tot = player.playlist().size(), ci = pb.currentIndex();
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, "播放列表 (" + tot + " 首)"); ImGui.spacing();
        float lh = ImGui.getContentRegionAvailY() - 100f;
        if (tot == 0) ImGui.textColored(0.4f, 0.4f, 0.4f, 1f, "（播放列表为空）");
        else if (ImGui.beginChild("##pl", 0, lh, true)) {
            for (int i = 0; i < tot; i++) {
                String url = player.playlist().get(i).url();
                String label = (i == ci ? "▶ " : "   ") + (url.length() > 40 ? url.substring(0, 40) + "..." : url) + "##v" + i;
                if (ImGui.selectable(label, i == selectedVideoIndex, 0, new ImVec2(0, ITEM_HEIGHT))) selectedVideoIndex = i;
                if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0))
                    VideoPlayerClientNetworking.updatePlayback(player.id(), PlaybackStatus.PLAYING, pb.mode(), i, pb.volume());
            }
            ImGui.endChild();
        }
        ImGui.spacing();
        boolean hs = selectedVideoIndex >= 0 && selectedVideoIndex < tot;
        if (!hs) ImGui.beginDisabled();
        if (ImGui.button("× 删除", BUTTON_W, BUTTON_H)) { VideoPlayerClientNetworking.removeVideoFromPlaylist(player.id(), selectedVideoIndex); selectedVideoIndex = -1; }
        if (!hs) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("+ 添加URL", BUTTON_W, BUTTON_H)) {
            String url = urlInputBuf.toString().trim();
            if (!url.isEmpty()) { VideoPlayerClientNetworking.addVideoToPlaylist(player.id(), url, 1920, 1080, 30); urlInputBuf.set(""); }
        }
        ImGui.sameLine(); ImGui.setNextItemWidth(Math.max(300f, ImGui.getContentRegionAvailX() - 10f));
        if (ImGui.inputText("##url", urlInputBuf, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue) || ImGui.isItemDeactivatedAfterEdit()) {
            String url = urlInputBuf.toString().trim();
            if (!url.isEmpty()) { VideoPlayerClientNetworking.addVideoToPlaylist(player.id(), url, 1920, 1080, 30); urlInputBuf.set(""); }
        }
    }

    private void renderScreenTab(VideoPlayerData player) {
        if (player.screens().isEmpty()) {
            ImGui.textColored(0.4f, 0.4f, 0.4f, 1f, "（暂无屏幕）"); ImGui.spacing();
            if (ImGui.button("+ 新建屏幕", BUTTON_W, BUTTON_H)) createScreenFor(player);
            return;
        }
        if (selectedScreenIndex < 0 || selectedScreenIndex >= player.screens().size()) selectedScreenIndex = 0;
        VideoScreenData screen = player.screens().get(selectedScreenIndex);
        // only load UV from server when switching screens, not every frame
        java.util.UUID curScreenId = screen.id();
        if (!curScreenId.equals(lastEditedScreenId)) {
            lastEditedScreenId = curScreenId;
            UvTransform ut = screen.uvTransform();
            uvOffsetX = (float) (ut.offsetU() * 500.0); uvOffsetY = (float) (ut.offsetV() * 500.0);
            uvScaleU = (float) ut.scaleU(); uvScaleV = (float) ut.scaleV(); rotationY = (float) ut.rotationDegrees();
            uvFlipU = ut.flipU(); uvFlipV = ut.flipV();
        }
        ImGui.textColored(0.8f, 0.8f, 1f, 1f, screen.name());
        ImGui.textColored(0.6f, 0.6f, 0.6f, 1f, screen.dimension().identifier() + ", " + screen.vertices().size() + " 顶点");
        ImGui.spacing();

        if (player.screens().size() > 1) {
            ImGui.text("选择:"); ImGui.sameLine(); ImGui.setNextItemWidth(140f);
            String[] sn = player.screens().stream().map(VideoScreenData::name).toArray(String[]::new);
            ImInt si = new ImInt(selectedScreenIndex);
            if (ImGui.combo("##ssel", si, sn)) selectedScreenIndex = si.get();
            ImGui.spacing();
        }

        // UV preview showing all screens with actual UV mapping
        float ps = ImGui.getContentRegionAvailX() - 20f;
        // 子窗口比frame大一圈，让UV扩展到0-1之外时（信箱效果）可见
        float pad = 30f;
        float frameAreaW = ps - pad * 2;
        float frameAreaH = frameAreaW * 9f / 16f;
        float childW = ps;
        float childH = frameAreaH + pad * 2;
        if (ImGui.beginChild("##uv", childW, childH, true, ImGuiWindowFlags.NoScrollWithMouse)) {
            ImVec2 cur = ImGui.getCursorScreenPos();
            ImVec2 av = new ImVec2(childW, childH);

            // frame居中于子窗口内，周围有padding
            float zoom = previewZoom;
            float frameCX = cur.x + childW / 2f + previewOffsetX;
            float frameCY = cur.y + childH / 2f + previewOffsetY;
            float frameW = frameAreaW * zoom;
            float frameH = frameAreaH * zoom;
            float frameX = frameCX - frameW / 2f;
            float frameY = frameCY - frameH / 2f;

            // 外层区域深灰 + frame区域黑色，zoom变化时frame大小可见
            ImGui.getWindowDrawList().addRectFilled(cur.x, cur.y, cur.x + av.x, cur.y + av.y, 0xFF1A1A1A);
            ImGui.getWindowDrawList().addRectFilled(frameX, frameY, frameX + frameW, frameY + frameH, 0xFF000000);
            ImGui.pushClipRect(cur.x, cur.y, cur.x + av.x, cur.y + av.y, true);

            ImGui.invisibleButton("##uvcap", av.x, av.y, 0);
            boolean capH = ImGui.isItemHovered(), capA = ImGui.isItemActive();

            // Preview zoom: Ctrl+wheel
            if (capH && ImGui.getIO().getKeyCtrl()) {
                float wh = ImGui.getIO().getMouseWheel();
                if (wh != 0) {
                    float oldZoom = zoom;
                    zoom = Math.max(0.1f, Math.min(10f, zoom + wh * 0.1f));
                    float zoomFactor = zoom / oldZoom;
                    ImVec2 mp = ImGui.getIO().getMousePos();
                    previewOffsetX = (float)(frameCX - cur.x - (mp.x - cur.x - (mp.x - frameCX) / zoomFactor));
                    previewOffsetY = (float)(frameCY - cur.y - (mp.y - cur.y - (mp.y - frameCY) / zoomFactor));
                    previewZoom = zoom;
                }
            }

            // 如果Ctrl+wheel改变了zoom，重新计算frame坐标
            frameCX = cur.x + childW / 2f + previewOffsetX;
            frameCY = cur.y + childH / 2f + previewOffsetY;
            frameW = frameAreaW * zoom;
            frameH = frameAreaH * zoom;
            frameX = frameCX - frameW / 2f;
            frameY = frameCY - frameH / 2f;

            // 先绘制所有屏幕的UV叠加层（底层→顶层）
            // draw unselected screens first (behind)
            for (int screenIdx = 0; screenIdx < player.screens().size(); screenIdx++) {
                VideoScreenData s = player.screens().get(screenIdx);
                if (s.id().equals(curScreenId)) continue;
                
                if (s.vertices().size() >= 3) {
                    // 未选中屏幕：透明填充 + 灰色边框 + 灰色顶点
                    drawScreenUV(s, null, frameX, frameY, frameW, frameH, 0x00444444, 0xAA666666, 0xFF888888);
                }
            }

            // draw selected screen on top (colorful) and handle editing
            boolean screenEditing = false;
            ImVec2 mousePos = ImGui.getIO().getMousePos();
            float mouseU = (mousePos.x - frameX) / frameW;
            float mouseV = (mousePos.y - frameY) / frameH;

            for (int screenIdx = 0; screenIdx < player.screens().size(); screenIdx++) {
                VideoScreenData s = player.screens().get(screenIdx);
                if (!s.id().equals(curScreenId)) continue;
                
                if (s.vertices().size() >= 3) {
                    // 使用本地编辑变量构建UV变换，这样拖动滑块时叠加层会实时更新
                    UvTransform localUv = new UvTransform(
                        uvOffsetX / 500.0, uvOffsetY / 500.0, uvScaleU, uvScaleV, rotationY, uvFlipU, uvFlipV);
                    // 选中屏幕：透明填充 + 亮粉边框 + 青色顶点（线框叠加层）
                    drawScreenUV(s, localUv, frameX, frameY, frameW, frameH, 0x00FF4488, 0xFFFF4488, 0xFF66FFFF);

                    // 使用本地编辑变量（而不是服务器数据）来计算控制手柄位置
                    float su = uvScaleU, sv = uvScaleV;
                    float ou = uvOffsetX / 500.0f, ov = uvOffsetY / 500.0f;
                    float rot = rotationY;
                    boolean flpu = uvFlipU, flpv = uvFlipV;

                    float ca = (float) Math.cos(Math.toRadians(rot));
                    float sa = (float) Math.sin(Math.toRadians(rot));

                    // calculate screen center in UV space
                    float screenCenterU = ou + 0.5f;
                    float screenCenterV = ov + 0.5f;

                    // calculate screen bounds after transform
                    float[] bounds = {1f, -1f, 1f, -1f}; // minU, maxU, minV, maxV
                    List<Vector3d> vs = new ArrayList<>();
                    List<Double> dists = new ArrayList<>();
                    for (var v : s.vertices()) { vs.add(v.toVector()); dists.add(v.pitch()); dists.add(v.yaw()); }
                    List<double[]> stitched = sashwind.mc.mod.ffcraft.client.player.Three2Flat.getStitchedUVs(vs, dists);
                    for (double[] uvs : stitched) {
                        for (int i = 0; i < uvs.length / 2; i++) {
                            float u = (float) uvs[i * 2], v = (float) uvs[i * 2 + 1];
                            if (flpu) u = 1 - u;
                            if (flpv) v = 1 - v;
                            float ru = (ca * (u - 0.5f) - sa * (v - 0.5f)) * su + ou + 0.5f;
                            float rv = (sa * (u - 0.5f) + ca * (v - 0.5f)) * sv + ov + 0.5f;
                            if (ru < bounds[0]) bounds[0] = ru;
                            if (ru > bounds[1]) bounds[1] = ru;
                            if (rv < bounds[2]) bounds[2] = rv;
                            if (rv > bounds[3]) bounds[3] = rv;
                        }
                    }

                    // 手柄尺寸（固定像素，不随zoom/frame变化）
                    float handleRadiusPx = 10f;
                    float edgeHandlePx = 7f;
                    // 碰撞检测用的UV空间阈值
                    float handleRadiusUV = handleRadiusPx / frameW;
                    float edgeHandleUV = edgeHandlePx / frameW;

                    // draw center handle
                    float centerX = frameX + screenCenterU * frameW;
                    float centerY = frameY + screenCenterV * frameH;
                    ImGui.getWindowDrawList().addCircleFilled(centerX, centerY, handleRadiusPx, 0xFFFF66AA, 12);
                    ImGui.getWindowDrawList().addCircle(centerX, centerY, handleRadiusPx, 0xFFFFFFFF, 12, 2f);

                    // draw edge handles (4 corners)
                    float[][] corners = {
                        {bounds[0], bounds[2]}, {bounds[1], bounds[2]},
                        {bounds[0], bounds[3]}, {bounds[1], bounds[3]}
                    };
                    for (float[] corner : corners) {
                        float cx = frameX + corner[0] * frameW;
                        float cy = frameY + corner[1] * frameH;
                        ImGui.getWindowDrawList().addRectFilled(cx - edgeHandlePx, cy - edgeHandlePx,
                                cx + edgeHandlePx, cy + edgeHandlePx, 0xFFFF66AA);
                        ImGui.getWindowDrawList().addRect(cx - edgeHandlePx, cy - edgeHandlePx,
                                cx + edgeHandlePx, cy + edgeHandlePx, 0xFFFFFFFF, 1f);
                    }

                    // check if mouse is over screen
                    boolean overCenter = false, overEdge = false;
                    int overCorner = -1;

                    if (capH) {
                        float distToCenter = (float) Math.sqrt(Math.pow(mouseU - screenCenterU, 2) + Math.pow(mouseV - screenCenterV, 2));
                        if (distToCenter < handleRadiusUV) {
                            overCenter = true;
                        } else {
                            for (int i = 0; i < corners.length; i++) {
                                float dist = (float) Math.sqrt(Math.pow(mouseU - corners[i][0], 2) + Math.pow(mouseV - corners[i][1], 2));
                                if (dist < edgeHandleUV) {
                                    overEdge = true;
                                    overCorner = i;
                                    break;
                                }
                            }
                        }
                    }

                    // handle screen editing
                    if (!ImGui.getIO().getKeyCtrl()) {
                        if (overCenter && ImGui.isMouseClicked(0)) {
                            editingMode = "move";
                            editStartU = mouseU;
                            editStartV = mouseV;
                            editStartOffsetX = uvOffsetX;
                            editStartOffsetY = uvOffsetY;
                            screenEditing = true;
                        } else if (overEdge && ImGui.isMouseClicked(0)) {
                            editingMode = "scale";
                            editStartU = mouseU;
                            editStartV = mouseV;
                            editStartScale = uvScaleU;
                            editStartScaleV = uvScaleV;
                            editStartOffsetX = uvOffsetX;
                            editStartOffsetY = uvOffsetY;
                            editCorner = overCorner;
                            // 记录点击位置到屏幕中心的初始距离（UV空间）
                            editStartDist = (float) Math.sqrt(
                                Math.pow(mouseU - screenCenterU, 2) + Math.pow(mouseV - screenCenterV, 2));
                            screenEditing = true;
                        }

                        if (!ImGui.isMouseDown(0)) {
                            editingMode = null;
                        }

                        if ("move".equals(editingMode) && ImGui.isMouseDragging(0, 0f)) {
                            ImVec2 d = ImGui.getMouseDragDelta(0, 0);
                            float du = d.x / frameW;
                            float dv = d.y / frameH;
                            // 不用resetMouseDragDelta — editStartOffset + 累计delta = 正确位置
                            uvOffsetX = editStartOffsetX + du * 500f;
                            uvOffsetY = editStartOffsetY + dv * 500f;
                            screenEditing = true;
                        } else if ("scale".equals(editingMode) && ImGui.isMouseDragging(0, 0f)) {
                            // 以中心手柄为锚点的缩放：计算鼠标到中心的当前距离，与初始距离比较
                            float curDist = (float) Math.sqrt(
                                Math.pow(mouseU - screenCenterU, 2) + Math.pow(mouseV - screenCenterV, 2));
                            if (editStartDist > 0.001f) {
                                float ratio = Math.max(0.1f, Math.min(3f, curDist / editStartDist));
                                uvScaleU = editStartScale * ratio;
                                uvScaleV = editStartScaleV * ratio;
                            }
                            // 中心不动，不改变offset
                            uvOffsetX = editStartOffsetX;
                            uvOffsetY = editStartOffsetY;

                            screenEditing = true;
                        }
                    }

                    ImGui.getWindowDrawList().addText(frameX + 4f, frameY + frameH - 14f, 0xFFFF66AA, s.name());
                }
            }

            // Preview pan: Ctrl+drag (only when NOT editing screen)
            if (!screenEditing && capA && ImGui.isMouseDragging(0, 0f) && ImGui.getIO().getKeyCtrl()) {
                ImVec2 d = ImGui.getMouseDragDelta(0, 0);
                previewOffsetX += d.x; previewOffsetY += d.y; ImGui.resetMouseDragDelta(0);
            }
            // 网格线和UV边框始终绘制在最顶层，确保缩放效果可见
            if (zoom <= 5f) {
                for (int g = 0; g <= 10; g++) {
                    float gx = frameX + frameW * g / 10f;
                    float gy = frameY + frameH * g / 10f;
                    if (gx >= cur.x && gx <= cur.x + av.x) {
                        ImGui.getWindowDrawList().addLine(gx, Math.max(cur.y, frameY), gx, Math.min(cur.y + av.y, frameY + frameH), 0x50FFFFFF, 1.5f);
                    }
                    if (gy >= cur.y && gy <= cur.y + av.y) {
                        ImGui.getWindowDrawList().addLine(Math.max(cur.x, frameX), gy, Math.min(cur.x + av.x, frameX + frameW), gy, 0x50FFFFFF, 1.5f);
                    }
                }
            }

            // draw UV frame boundaries (0,0) to (1,1)
            ImGui.getWindowDrawList().addRect(frameX, frameY, frameX + frameW, frameY + frameH, 0xFFAAAAAA, 3f);
            ImGui.getWindowDrawList().addText(frameX + 4f, frameY + 4f, 0xFFFFFFFF, "(0,0)");
            ImGui.getWindowDrawList().addText(frameX + frameW - 30f, frameY + 4f, 0xFFFFFFFF, "(1,0)");
            ImGui.getWindowDrawList().addText(frameX + 4f, frameY + frameH - 14f, 0xFFFFFFFF, "(0,1)");
            ImGui.getWindowDrawList().addText(frameX + frameW - 30f, frameY + frameH - 14f, 0xFFFFFFFF, "(1,1)");

            ImGui.popClipRect();
        } ImGui.endChild();

        // Preview zoom slider
        ImGui.spacing();
        ImGui.text("预览缩放"); ImGui.sameLine(); ImGui.setNextItemWidth(140f);
        int[] zv = {(int) (previewZoom * 100)};
        if (ImGui.sliderInt("##previewZoom", zv, 10, 500)) {
            previewZoom = zv[0] / 100f;
            previewOffsetX = 0f;
            previewOffsetY = 0f;
            System.out.println("[Zoom] 缩放滑块改变: previewZoom=" + previewZoom + " (" + zv[0] + "%)");
        }
        ImGui.sameLine(); ImGui.text(String.format("%.0f%%", previewZoom * 100));

        ImGui.spacing(); ImGui.separatorText("UV 参数"); ImGui.spacing();
        
        // Rotation with double-click edit
        ImGui.text("旋转 (°) "); ImGui.sameLine(); ImGui.setNextItemWidth(120f);
        int[] rv = {(int) rotationY};
        if (ImGui.sliderInt("##rot", rv, 0, 360)) { rotationY = rv[0]; uvEdited(); }
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
            editingRotation = true;
            rotationEditBuf.set(String.valueOf((int) rotationY));
        }
        ImGui.sameLine();
        if (editingRotation) {
            ImGui.setNextItemWidth(60f);
            ImGui.inputText("##rotEdit", rotationEditBuf);
            ImGui.setItemDefaultFocus();
            if (ImGui.isItemDeactivatedAfterEdit() || ImGui.isKeyPressed(imgui.flag.ImGuiKey.Enter)) {
                try {
                    int val = Integer.parseInt(rotationEditBuf.toString());
                    rotationY = Math.max(0, Math.min(360, val));
                } catch (NumberFormatException e) {}
                editingRotation = false;
            }
            if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) editingRotation = false;
        } else {
            ImGui.text(String.format("%d°", (int) rotationY));
        }
        ImGui.spacing();
        int[] sv = {(int) (uvScaleU * 100)}; ImGui.text("缩放 (%) "); ImGui.sameLine(); ImGui.setNextItemWidth(120f);
        if (ImGui.sliderInt("##sc", sv, 50, 200)) {
            float newScale = sv[0] / 100f;
            float ratio = newScale / uvScaleU;
            uvScaleU = newScale;
            uvScaleV *= ratio;
            uvEdited();
        } ImGui.spacing();
        int[] oxv = {(int) uvOffsetX}; ImGui.text("偏移 X  "); ImGui.sameLine(); ImGui.setNextItemWidth(120f);
        if (ImGui.sliderInt("##ox", oxv, -500, 500)) { uvOffsetX = oxv[0]; uvEdited(); } ImGui.spacing();
        int[] oyv = {(int) uvOffsetY}; ImGui.text("偏移 Y  "); ImGui.sameLine(); ImGui.setNextItemWidth(120f);
        if (ImGui.sliderInt("##oy", oyv, -500, 500)) { uvOffsetY = oyv[0]; uvEdited(); }
        ImGui.spacing();

        // Flip controls
        ImGui.text("翻转: "); ImGui.sameLine();
        if (ImGui.checkbox("水平##flipU", uvFlipU)) {
            uvFlipU = !uvFlipU; uvEdited();
        }
        ImGui.sameLine();
        if (ImGui.checkbox("垂直##flipV", uvFlipV)) {
            uvFlipV = !uvFlipV; uvEdited();
        }
        ImGui.spacing(); ImGui.separator();

        // Channel toggle
        var ch = screen.channelState();
        boolean leftOn = ch.leftEnabled(), rightOn = ch.rightEnabled();
        ImGui.text("声道: "); ImGui.sameLine();
        if (ImGui.checkbox("左##chL", leftOn)) {
            leftOn = !leftOn;
            VideoPlayerClientNetworking.updateScreenChannel(player.id(), screen.id(),
                    new ScreenChannelState(leftOn, rightOn));
        }
        ImGui.sameLine();
        if (ImGui.checkbox("右##chR", rightOn)) {
            rightOn = !rightOn;
            VideoPlayerClientNetworking.updateScreenChannel(player.id(), screen.id(),
                    new ScreenChannelState(leftOn, rightOn));
        }
        ImGui.spacing();

        if (ImGui.button("保存 UV", BUTTON_W, BUTTON_H)) {
            UvTransform uv = new UvTransform(uvOffsetX / 500.0, uvOffsetY / 500.0, uvScaleU, uvScaleV, rotationY, uvFlipU, uvFlipV);
            System.out.println("[UV Send] Sending UV: offsetU=" + uv.offsetU() + ", offsetV=" + uv.offsetV() +
                    ", scaleU=" + uv.scaleU() + ", scaleV=" + uv.scaleV() + ", rotation=" + uv.rotationDegrees());
            VideoPlayerClientNetworking.updateScreenUv(player.id(), screen.id(), uv);
            sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.markUvManuallyEdited(screen.id());
        }
        ImGui.sameLine();
        if (ImGui.button("+ 新建屏幕", BUTTON_W, BUTTON_H)) createScreenFor(player);
        ImGui.sameLine();
        if (ImGui.button("× 删除屏幕", BUTTON_W, BUTTON_H)) { VideoPlayerClientNetworking.deleteScreen(player.id(), screen.id()); selectedScreenIndex = -1; }
    }

    private void uvEdited() {
        if (lastEditedScreenId != null) {
            sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.markUvManuallyEdited(lastEditedScreenId);
        }
    }

    private void createScreenFor(VideoPlayerData player) {
        if (ClientScreenCreationManager.start(player.id(), player.name() + "-screen")) {
            sashwind.mc.mod.drawlib.client.lib.setScreenCompat(Minecraft.getInstance(), null);
            Player.startVertexPlacement(() -> Minecraft.getInstance().execute(() -> sashwind.mc.mod.drawlib.client.lib.setScreenCompat(Minecraft.getInstance(), new MainScreen())));
        }
    }

    private static String statusText(PlaybackState pb) {
        return switch (pb.status()) { case PLAYING -> "播放中"; case PAUSED -> "已暂停"; case STOPPED -> "已停止"; };
    }
    private void syncCache() {
        long v = sashwind.mc.mod.ffcraft.client.state.ClientVideoPlayerCache.getVersion();
        if (v != lastSnapshotVersion) {
            lastSnapshotVersion = v; cachedPlayers = new ArrayList<>(VideoPlayerClientNetworking.snapshot().players());
            if (selectedPlayerIndex >= cachedPlayers.size()) selectedPlayerIndex = cachedPlayers.isEmpty() ? -1 : 0;
        }
    }

    private int getVideoPreviewTexture() {
        return sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.getPreviewTextureId();
    }

    private void drawScreenUV(VideoScreenData s, UvTransform localUv, float frameX, float frameY, float frameW, float frameH,
                              int fillColor, int edgeColor, int pointColor) {
        List<Vector3d> vs = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        for (var v : s.vertices()) { vs.add(v.toVector()); dists.add(v.pitch()); dists.add(v.yaw()); }

        var ut = localUv != null ? localUv : s.uvTransform();
        float su = (float) ut.scaleU(), sv = (float) ut.scaleV();
        float ou = (float) ut.offsetU(), ov = (float) ut.offsetV();
        float rot = (float) ut.rotationDegrees();
        boolean flpu = ut.flipU(), flpv = ut.flipV();

        float ca = (float) Math.cos(Math.toRadians(rot));
        float sa = (float) Math.sin(Math.toRadians(rot));

        List<double[]> stitched = sashwind.mc.mod.ffcraft.client.player.Three2Flat.getStitchedUVs(vs, dists);

        for (int pi = 0; pi < stitched.size(); pi++) {
            double[] uvs = stitched.get(pi);
            int n = uvs.length / 2;
            if (n < 3) continue;

            for (int i = 1; i < n - 1; i++) {
                float u0 = (float) uvs[0], v0 = (float) uvs[1];
                float ui = (float) uvs[i * 2], vi = (float) uvs[i * 2 + 1];
                float uj = (float) uvs[(i + 1) * 2], vj = (float) uvs[(i + 1) * 2 + 1];

                if (flpu) { u0 = 1 - u0; ui = 1 - ui; uj = 1 - uj; }
                if (flpv) { v0 = 1 - v0; vi = 1 - vi; vj = 1 - vj; }

                float ru0 = (ca * (u0 - 0.5f) - sa * (v0 - 0.5f)) * su + ou + 0.5f;
                float rv0 = (sa * (u0 - 0.5f) + ca * (v0 - 0.5f)) * sv + ov + 0.5f;
                float rui = (ca * (ui - 0.5f) - sa * (vi - 0.5f)) * su + ou + 0.5f;
                float rvi = (sa * (ui - 0.5f) + ca * (vi - 0.5f)) * sv + ov + 0.5f;
                float ruj = (ca * (uj - 0.5f) - sa * (vj - 0.5f)) * su + ou + 0.5f;
                float rvj = (sa * (uj - 0.5f) + ca * (vj - 0.5f)) * sv + ov + 0.5f;

                float x0 = frameX + ru0 * frameW, y0 = frameY + rv0 * frameH;
                float xi = frameX + rui * frameW, yi = frameY + rvi * frameH;
                float xj = frameX + ruj * frameW, yj = frameY + rvj * frameH;

                ImGui.getWindowDrawList().addTriangleFilled(x0, y0, xi, yi, xj, yj, fillColor);
            }

            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float ui = (float) uvs[i * 2], vi = (float) uvs[i * 2 + 1];
                float uj = (float) uvs[j * 2], vj = (float) uvs[j * 2 + 1];

                if (flpu) { ui = 1 - ui; uj = 1 - uj; }
                if (flpv) { vi = 1 - vi; vj = 1 - vj; }

                float rui = (ca * (ui - 0.5f) - sa * (vi - 0.5f)) * su + ou + 0.5f;
                float rvi = (sa * (ui - 0.5f) + ca * (vi - 0.5f)) * sv + ov + 0.5f;
                float ruj = (ca * (uj - 0.5f) - sa * (vj - 0.5f)) * su + ou + 0.5f;
                float rvj = (sa * (uj - 0.5f) + ca * (vj - 0.5f)) * sv + ov + 0.5f;

                float xi = frameX + rui * frameW, yi = frameY + rvi * frameH;
                float xj = frameX + ruj * frameW, yj = frameY + rvj * frameH;

                ImGui.getWindowDrawList().addLine(xi, yi, xj, yj, edgeColor, 2f);
            }

            for (int i = 0; i < n; i++) {
                float ui = (float) uvs[i * 2], vi = (float) uvs[i * 2 + 1];

                if (flpu) ui = 1 - ui;
                if (flpv) vi = 1 - vi;

                float rui = (ca * (ui - 0.5f) - sa * (vi - 0.5f)) * su + ou + 0.5f;
                float rvi = (sa * (ui - 0.5f) + ca * (vi - 0.5f)) * sv + ov + 0.5f;

                float vx = frameX + rui * frameW, vy = frameY + rvi * frameH;
                ImGui.getWindowDrawList().addCircleFilled(vx, vy, 4.5f, pointColor, 12);
            }
        }
    }
}
