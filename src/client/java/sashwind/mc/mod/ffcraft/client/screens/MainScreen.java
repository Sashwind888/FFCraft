package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking;
import sashwind.mc.mod.ffcraft.client.player.MpvNativeLoader;
import sashwind.mc.mod.ffcraft.client.player.MpvNativeLoader.State;
import sashwind.mc.mod.ffcraft.client.player.MpvPlayer;
import sashwind.mc.mod.ffcraft.client.player.Player;
import sashwind.mc.mod.ffcraft.client.state.ClientScreenCreationManager;
import sashwind.mc.mod.ffcraft.client.state.ClientVideoPlayerCache;
import sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager;
import sashwind.mc.mod.ffcraft.common.model.*;
import java.awt.Desktop;
import java.net.URI;

import java.util.*;

public class MainScreen extends Screen {

    // ==================== 布局常量 ====================
    static final int HEADER_H     = 24;
    static final int TAB_Y        = HEADER_H + 6;
    static final int TAB_BUTTON_W = 72;
    static final int TAB_BUTTON_H = 20;
    static final int ENTRY_H      = 14;
    static final int SLIDER_H     = 18;
    static final int SLIDER_LABEL_W = 36;
    static final int SLIDER_VAL_W   = 44;
    static final int TRANSPORT_BTN_SIZE = 24;

    static final int BTN_BG = 0xFF3A3A3A, BTN_BORDER = 0xFF666666;
    static final int BTN_BG_HOV = 0xFF555555, BTN_TEXT = 0xFFE0E0E0, BTN_TEXT_HOV = 0xFFFFFF00;

    // ==================== 右侧面板 ====================
    private int rightPaneX, rightPaneY, rightPaneW;

    // ==================== 数据状态 ====================
    private static int localPlayerCounter = 1;
    private static int selectedVideoIndex = -1;
    static List<VideoPlayerData> cachedPlayers = new ArrayList<>();
    private static long lastSnapshotVersion = Long.MIN_VALUE;
    private int activeTab = 0;

    // ==================== 子组件 ====================
    private final LeftPanelHelper leftPanel = new LeftPanelHelper();
    private final SeekBarHelper seekBar = new SeekBarHelper();
    private final UVEditorHelper uvEditor = new UVEditorHelper();

    // ==================== Widget ====================
    private EditBox urlInput, renameInput, valueEditBox;
    /** Area 承载右侧面板，处理 scissor / 滚动偏移 / 滚动条 */
    private Area rightPaneArea;

    // ==================== 播放控制页签 —— 存储坐标 ====================
    private int transportBtnY, transportPrevX, transportPlayX, transportStopX, transportNextX;
    private int volSliderX, volSliderY, volSliderW;
    private int qualityBtnX, qualityBtnY, qualityBtnW;
    private int modeBtnX, modeBtnY, modeBtnW;

    // ==================== 播放列表页签 ====================
    private int playlistScroll, playlistX, playlistY, playlistW, playlistH;

    // ==================== 屏幕设置页签 ====================
    private int scrSliderBaseY, scrSliderX, scrSliderW;
    private int scrFlipBtnY, scrChanBtnY, scrSaveBtnY;

    // ==================== 拖拽/编辑状态 ====================
    private String sliderDragging;
    private String sliderEditMode;
    private boolean panningUV;
    private int dragLockedScroll; // UV 拖动时锁定的滚动偏移，防止抽搐
    private boolean renameInputInitialized; // 防止 syncRenameInput 每帧覆盖用户输入

    // ==================== 内部 Widget：放在 Area 里渲染 ====================
    private class TabContentWidget extends net.minecraft.client.gui.components.AbstractWidget {
        final int tabIdx;
        TabContentWidget(int tabIdx, int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
            this.tabIdx = tabIdx;
        }
        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
            if (!hasSelectedPlayer()) return;
            VideoPlayerData p = getSelectedPlayer();
            int px = this.getX(), py = this.getY(), pw = this.width;
            switch (tabIdx) {
                case 0 -> renderPlaybackTab(g, mx, my, p, px, py, pw);
                case 1 -> renderPlaylistTab(g, mx, my, p, px, py, pw);
                case 2 -> renderScreenTab(g, mx, my, p, px, py, pw);
            }
        }
        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput o) {}
        // 鼠标事件由 MainScreen 统一处理，不在此消费
        @Override public boolean mouseClicked(MouseButtonEvent e, boolean d) { return false; }
        @Override public boolean mouseReleased(MouseButtonEvent e) { return false; }
        @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) { return false; }
        @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return false; }
    }

    // ==================== 访问器 ====================
    static boolean hasSelectedPlayer() {
        int i = LeftPanelHelper.selectedPlayerIndex;
        return i >= 0 && i < cachedPlayers.size();
    }
    static VideoPlayerData getSelectedPlayer() { return cachedPlayers.get(LeftPanelHelper.selectedPlayerIndex); }
    static boolean hasSelectedScreen() {
        if (!hasSelectedPlayer()) return false;
        int i = LeftPanelHelper.selectedScreenIndex;
        return i >= 0 && i < getSelectedPlayer().screens().size();
    }
    static VideoScreenData getSelectedScreen() {
        return getSelectedPlayer().screens().get(LeftPanelHelper.selectedScreenIndex);
    }

    public MainScreen() { super(Component.translatable("key.screens.mainscreen.title")); }

    /** 将屏幕 Y 坐标转为内容空间 Y（加 Area 滚动偏移） */
    private int scrollAdjY(int screenY) {
        return rightPaneArea != null ? screenY + rightPaneArea.getScrollOffset() : screenY;
    }

    /** i18n 辅助：根据翻译键获取当前语言文本 */
    private static String txt(String key) { return Component.translatable(key).getString(); }
    private String tx(String key) { return txt(key); }

    // ==================== 生命周期 ====================
    @Override
    protected void init() {
        syncCache();
        recalcLayout();

        int rp = rightPaneX, cw = rightPaneW;

        // Tab 按钮
        String TK = "key.screens.mainscreen.tab.";
        addRenderableWidget(btn(tx(TK + "playback"), b -> { activeTab = 0; rebuildTabContent(); }, rp, TAB_Y, TAB_BUTTON_W, TAB_BUTTON_H));
        addRenderableWidget(btn(tx(TK + "playlist"), b -> { activeTab = 1; rebuildTabContent(); }, rp + TAB_BUTTON_W + 2, TAB_Y, TAB_BUTTON_W, TAB_BUTTON_H));
        addRenderableWidget(btn(tx(TK + "screen"), b -> { activeTab = 2; rebuildTabContent(); }, rp + TAB_BUTTON_W * 2 + 4, TAB_Y, TAB_BUTTON_W, TAB_BUTTON_H));

        // URL 输入（放在添加按钮左边）—— 上移半个编辑框（10px），给下方播放列表让出空间
        int urlBY = this.height - 38;
        urlInput = new EditBox(this.font, rp, urlBY, cw - 160, 20, Component.translatable("key.screens.mainscreen.placeholder.url"));
        urlInput.setMaxLength(2048);
        addRenderableWidget(urlInput);

        renameInput = new EditBox(this.font, 0, 0, 100, 16, Component.translatable("key.screens.mainscreen.placeholder.rename"));
        renameInput.setMaxLength(64); renameInput.visible = false;
        addRenderableWidget(renameInput);

        valueEditBox = new EditBox(this.font, 0, 0, 80, 16, Component.translatable("key.screens.mainscreen.placeholder.value"));
        valueEditBox.setMaxLength(10); valueEditBox.visible = false;
        addRenderableWidget(valueEditBox);

        // 左侧面板按钮
        int lbY = this.height - 55;
        addRenderableWidget(btn(tx("key.screens.mainscreen.button.create_player"), b -> {
            VideoPlayerClientNetworking.createPlayer(new CreatePlayerRequest("Player" + localPlayerCounter++, false));
        }, LeftPanelHelper.PANE_X, lbY, LeftPanelHelper.PANE_W, 20));
        addRenderableWidget(btn(tx("key.screens.mainscreen.button.delete_player"), b -> {
            if (hasSelectedPlayer()) {
                VideoPlayerClientNetworking.deletePlayer(getSelectedPlayer().id());
                LeftPanelHelper.selectedPlayerIndex = -1; LeftPanelHelper.selectedScreenIndex = -1;
            }
        }, LeftPanelHelper.PANE_X, lbY + 22, LeftPanelHelper.PANE_W, 20));

        // 播放列表操作按钮
        addRenderableWidget(btn(tx("key.screens.mainscreen.button.add_video"), b -> addVideo(), rp + cw - 152, urlBY, 72, 20));
        addRenderableWidget(btn(tx("key.screens.mainscreen.button.remove_video"), b -> deleteSelectedVideo(), rp + cw - 76, urlBY, 72, 20));

        // 屏幕操作按钮 → 改为在 renderScreenTab 中手动绘制，随内容滚动

        // === 右侧滚动区域（Area 管理 scissor / 滚动 / 滚动条） ===
        int availH = this.height - rightPaneY - 10;
        rightPaneArea = new Area(rightPaneX, rightPaneY, rightPaneW, availH);
        rebuildTabContent();

        // === mpv 下载提示浮层按钮（始终创建，按需显示/隐藏） ===
        int bw = 90, bx = (this.width - bw * 3 - 16) / 2, by = (this.height - 180) / 2 + 130;
        mpvDownloadBtn = btn(mpvOverlayLabel("download"), b -> mpvOverlayAction("download"), bx, by, bw, 20);
        mpvManualBtn   = btn(mpvOverlayLabel("manual"),   b -> mpvOverlayAction("manual"),   bx + bw + 8, by, bw, 20);
        mpvDismissBtn  = btn(mpvOverlayLabel("dismiss"),  b -> mpvOverlayAction("dismiss"),  bx + bw * 2 + 16, by, bw, 20);
        addRenderableWidget(mpvDownloadBtn);
        addRenderableWidget(mpvManualBtn);
        addRenderableWidget(mpvDismissBtn);
        updateMpvOverlayButtons();
    }

    // ==================== mpv 下载浮层 ====================

    /** 浮层布局 — 由 updateMpvOverlayButtons() 每 tick 更新 */
    private int mpvBoxX, mpvBoxY, mpvBoxW = 340, mpvBoxH = 180;

    private void updateMpvOverlayButtons() {
        boolean vis = showMpvPrompt;
        mpvDownloadBtn.visible = vis;
        mpvManualBtn.visible = vis;
        mpvDismissBtn.visible = vis;
        if (!vis) return;

        mpvBoxX = (this.width  - mpvBoxW) / 2;
        mpvBoxY = (this.height - mpvBoxH) / 2;
        int bw = 90, bx = (this.width - bw * 3 - 16) / 2, by = mpvBoxY + 130;
        mpvDownloadBtn.setX(bx);           mpvDownloadBtn.setY(by);
        mpvManualBtn.setX(bx + bw + 8);    mpvManualBtn.setY(by);
        mpvDismissBtn.setX(bx + bw * 2 + 16); mpvDismissBtn.setY(by);

        mpvDownloadBtn.setMessage(Component.literal(mpvOverlayLabel("download")));
        mpvManualBtn.setMessage(Component.literal(mpvOverlayLabel("manual")));
        mpvDismissBtn.setMessage(Component.literal(mpvOverlayLabel("dismiss")));
    }

    private String mpvOverlayLabel(String key) {
        boolean downloading = mpvDownloading;
        boolean failed = MpvNativeLoader.getState() == MpvNativeLoader.State.FAILED;
        return switch (key) {
            case "download" -> downloading ? mpvProgress + "%"
                    : failed ? tx("key.screens.mainscreen.mpv.retry")
                    : tx("key.screens.mainscreen.mpv.download");
            case "manual"   -> tx("key.screens.mainscreen.mpv.manual");
            case "dismiss"  -> tx("key.screens.mainscreen.mpv.dismiss");
            default -> "";
        };
    }

    private void mpvOverlayAction(String key) {
        switch (key) {
            case "download" -> {
                if (!mpvDownloading) {
                    mpvDownloading = true;
                    MpvNativeLoader.downloadAsync(p -> mpvProgress = Math.max(mpvProgress, p)).thenAccept(ok -> {
                        if (ok) showMpvPrompt = false;
                        mpvDownloading = false;
                    });
                }
            }
            case "manual" -> {
                try { Desktop.getDesktop().browse(URI.create(MpvNativeLoader.getDownloadPageUrl())); }
                catch (Exception e) { System.err.println("[MpvOverlay] 无法打开浏览器: " + e); }
            }
            case "dismiss" -> showMpvPrompt = false;
        }
    }

    // ==================== 字段 ====================

    private boolean mpvPromptShown = false; // 实例字段：每次打开控制面板都重新检查
    private boolean showMpvPrompt = false;
    private Button mpvDownloadBtn, mpvManualBtn, mpvDismissBtn;
    private boolean mpvDownloading;
    private volatile int mpvProgress; // 下载线程回调直接写入
    // 浮层镜像下拉
    private boolean mirrorMenuOpen;
    private int mirrorBoxX, mirrorBoxW = 260, mirrorRowY;

    @Override
    public void tick() {
        super.tick();
        // 每次打开控制面板检查 libmpv：未加载且未安装/加载失败 → 先看文件是否已存在
        // （存在则直接加载，覆盖手动放置 DLL / 上次残留），否则显示内嵌提示
        if (!mpvPromptShown && !MpvPlayer.isAvailable()) {
            State st = MpvNativeLoader.getState();
            if (st == State.NOT_INSTALLED || st == State.FAILED) {
                mpvPromptShown = true;
                if (!MpvNativeLoader.tryLoadExisting()) showMpvPrompt = true;
            }
        }
        if (mpvDownloading) {
            mpvProgress = MpvNativeLoader.getDownloadProgress();
            if (MpvNativeLoader.isLoaded()) { showMpvPrompt = false; mpvDownloading = false; }
            if (MpvNativeLoader.getState() == MpvNativeLoader.State.FAILED) mpvDownloading = false;
        }
        updateMpvOverlayButtons();
    }
    @Override public void onClose() { cachedPlayers.clear(); lastSnapshotVersion = Long.MIN_VALUE; super.onClose(); }
    @Override public void removed() { super.removed(); }

    // ==================== 布局 ====================
    private void recalcLayout() {
        rightPaneX = LeftPanelHelper.PANE_X + LeftPanelHelper.PANE_W + 8;
        rightPaneY = TAB_Y + TAB_BUTTON_H + 8;
        rightPaneW = Math.max(200, this.width - rightPaneX - 12);
    }

    /** 当前标签页的内容总高度 */
    private int calcTabContentH() {
        int availH = this.height - rightPaneY - 10;
        int innerW = rightPaneW - 12; // 留滚动条宽度
        if (activeTab == 0) return Math.max(availH, calcPlaybackTabH(innerW));
        if (activeTab == 1) return availH; // 列表用内部滚动，不让 Area 滚
        if (activeTab == 2 && hasSelectedPlayer()) return Math.max(availH, calcScreenTabH(getSelectedPlayer(), innerW));
        return availH;
    }

    private int calcPlaybackTabH(int pw) {
        return pw * 9 / 16 + 8 + SeekBarHelper.BAR_H + 6 + ENTRY_H + 6 + TRANSPORT_BTN_SIZE + 8 + SLIDER_H + 6 + 24 + 16;
    }

    private int calcScreenTabH(VideoPlayerData p, int pw) {
        int uvH = Math.max(100, Math.min(pw * 9 / 16, 200));
        int h = 4 + ENTRY_H * 2 + 4 + uvH + 8 + (SLIDER_H + 4) * 4 + 8 + 20 + 20 + 8 + 22;
        if (p.screens().size() > 1) h += ENTRY_H + 6;
        return h;
    }

    private void rebuildTabContent() {
        if (rightPaneArea == null) return;
        rightPaneArea.clearChildren();
        int innerW = rightPaneW - 12;
        int ch = calcTabContentH();
        rightPaneArea.addChild(new TabContentWidget(activeTab, rightPaneX, rightPaneY, innerW, ch));
    }

    // ==================== 主渲染 ====================
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        syncCache();
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);

        // 标题栏
        graphics.fill(0, 0, this.width, HEADER_H, 0xDD1A1A2E);
        graphics.fill(0, HEADER_H - 1, this.width, HEADER_H, 0xFF444466);
        String hdr = tx("key.screens.mainscreen.title");
        graphics.text(this.font, hdr, (this.width - this.font.width(hdr)) / 2, 5, 0xFFFFFFFF);
        int closeX = this.width - 20, closeY = 4;
        boolean hov = mouseX >= closeX && mouseX <= closeX + 16 && mouseY >= closeY && mouseY <= closeY + 16;
        graphics.fill(closeX, closeY, closeX + 16, closeY + 16, hov ? 0xFFFF4444 : 0x66FFFFFF);
        graphics.text(this.font, "×", closeX + 5, closeY + 2, 0xFFFFFFFF);

        // 左侧面板
        leftPanel.render(graphics, this.font, mouseX, mouseY, cachedPlayers, this.height);
        syncRenameInput();

        // === 右侧面板：Area 负责 scissor + 滚动 + 滚动条，TabContentWidget 负责内容 ===
        if (hasSelectedPlayer() && rightPaneArea != null) {
            rightPaneArea.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
        }

        // 空状态提示
        if (cachedPlayers.isEmpty()) {
            graphics.text(this.font, tx("key.screens.mainscreen.empty.no_player"), rightPaneX + 4, rightPaneY + 4, 0xFF888888);
        }

        updateWidgetVisibility();
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // === mpv 下载浮层（渲染在最上层） ===
        if (showMpvPrompt) renderMpvOverlay(graphics, mouseX, mouseY);
    }

    private void renderMpvOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // 半透明遮罩
        graphics.fill(0, 0, this.width, this.height, 0xAA000000);

        int cx = mpvBoxX, cy = mpvBoxY, cw = mpvBoxW, ch = mpvBoxH;

        // 面板背景
        graphics.fill(cx, cy, cx + cw, cy + ch, 0xFF2A2A3E);
        drawRect(graphics, cx, cy, cw, ch, 0xFF6666AA);

        // 标题
        boolean failed = MpvNativeLoader.getState() == MpvNativeLoader.State.FAILED;
        String title = tx(failed ? "key.screens.mainscreen.mpv.title_failed"
                : mpvDownloading ? "key.screens.mainscreen.mpv.title_downloading"
                : "key.screens.mainscreen.mpv.title");
        graphics.text(this.font, title, cx + (cw - this.font.width(title)) / 2, cy + 10, 0xFFFFAA44);

        // 描述 / 状态
        String desc;
        if (failed) {
            desc = MpvNativeLoader.getStatusMsg();
        } else if (mpvDownloading) {
            desc = tx("key.screens.mainscreen.mpv.downloading") + "  " + mpvProgress + "%";
        } else {
            desc = tx("key.screens.mainscreen.mpv.desc");
        }
        graphics.text(this.font, desc, cx + (cw - this.font.width(desc)) / 2, cy + 38, 0xFFCCCCCC);

        String hint = tx("key.screens.mainscreen.mpv.hint");
        graphics.text(this.font, hint, cx + (cw - this.font.width(hint)) / 2, cy + 58, 0xFF888888);

        String path = MpvNativeLoader.getLibPath().toString();
        String shortPath = "▸ " + (path.length() > 50 ? "…" + path.substring(path.length() - 48) : path);
        graphics.text(this.font, shortPath, cx + (cw - this.font.width(shortPath)) / 2, cy + 74, 0xFF777777);

        // 进度条（下载中）
        if (mpvDownloading) {
            int pbX = cx + 30, pbW = cw - 60, pbY = cy + 104, pbH = 8;
            graphics.fill(pbX, pbY, pbX + pbW, pbY + pbH, 0xFF222222);
            graphics.fill(pbX, pbY, pbX + (int)(pbW * mpvProgress / 100f), pbY + pbH, 0xFF44AA44);
            drawRect(graphics, pbX, pbY, pbW, pbH, 0xFF555555);
        }

        // 操作按钮（手绘在面板之上——widget 按钮在 super 里先渲染，会被本层遮罩/面板盖住）
        int bw = 90, bx = (this.width - bw * 3 - 16) / 2, by = mpvBoxY + 130;
        drawUniBtn(graphics, bx, by, bw, 20, mpvOverlayLabel("download"), mouseX, mouseY);
        drawUniBtn(graphics, bx + bw + 8, by, bw, 20, mpvOverlayLabel("manual"), mouseX, mouseY);
        drawUniBtn(graphics, bx + bw * 2 + 16, by, bw, 20, mpvOverlayLabel("dismiss"), mouseX, mouseY);

        // 镜像下拉（最后渲染置顶，展开的选项列表盖住下方按钮，与 MpvInstallScreen 一致）
        mirrorRowY = cy + 84;
        mirrorBoxX = (this.width - mirrorBoxW) / 2;
        String curMirror = MpvNativeLoader.getMirrorName(MpvNativeLoader.getSelectedMirror());
        String boxText = tx("key.screens.mpvinstall.mirror") + ": " + curMirror;
        boolean hovBox = inRect(mouseX, mouseY, mirrorBoxX, mirrorRowY, mirrorBoxW, 16);
        graphics.fill(mirrorBoxX, mirrorRowY, mirrorBoxX + mirrorBoxW, mirrorRowY + 16,
                hovBox || mirrorMenuOpen ? 0xFF444455 : 0xFF222233);
        drawRect(graphics, mirrorBoxX, mirrorRowY, mirrorBoxW, 16, 0xFF555577);
        graphics.text(this.font, boxText, mirrorBoxX + (mirrorBoxW - this.font.width(boxText)) / 2,
                mirrorRowY + 2, 0xFFEEEEEE);
        graphics.text(this.font, mirrorMenuOpen ? "▲" : "▼",
                mirrorBoxX + mirrorBoxW - 12, mirrorRowY + 2, 0xFFAAAAAA);

        if (mirrorMenuOpen) {
            int sel = MpvNativeLoader.getSelectedMirror();
            int count = MpvNativeLoader.getMirrorCount();
            for (int i = 0; i < count; i++) {
                int oy = mirrorRowY + 16 + i * 16;
                boolean oh = inRect(mouseX, mouseY, mirrorBoxX, oy, mirrorBoxW, 16);
                graphics.fill(mirrorBoxX, oy, mirrorBoxX + mirrorBoxW, oy + 16,
                        oh ? 0xFF444466 : 0xFF2A2A3E);
                if (i == sel) drawRect(graphics, mirrorBoxX, oy, mirrorBoxW, 16, 0xFF55AA55);
                graphics.text(this.font, MpvNativeLoader.getMirrorName(i),
                        mirrorBoxX + 8, oy + 2, i == sel ? 0xFF55FF55 : 0xFFCCCCCC);
            }
        }
    }

    // ==================== 播放控制页签 ====================
    private void renderPlaybackTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                    VideoPlayerData player, int px, int py, int pw) {
        PlaybackState pb = player.playbackState();
        int curY = py;

        // 视频预览占位
        int prevH = Math.max(80, pw * 9 / 16);
        graphics.fill(px, curY, px + pw, curY + prevH, 0xFF0A0A0A);
        drawGrid(graphics, px, curY, pw, prevH, 24);
        String pl = tx("key.screens.mainscreen.label.wait_video");
        graphics.text(this.font, pl, (int)(px + (pw - this.font.width(pl)) / 2f),
                (int)(curY + prevH / 2f - 6), 0xFF666666);
        drawRect(graphics, px, curY, pw, prevH, 0xFF555555);
        curY += prevH + 8;

        // 进度条
        seekBar.setIsLive(ClientVideoPlaybackManager.isLive(player.id()));
        seekBar.syncToDuration(ClientVideoPlaybackManager.getDuration(player.id()),
                ClientVideoPlaybackManager.getLocalProgressSeconds(player.id()));
        curY += seekBar.render(graphics, this.font, mouseX, mouseY, player, px, curY, pw) + 6;

        // 状态文本
        String status = switch (pb.status()) {
            case PLAYING -> tx("key.screens.mainscreen.status.playing");
            case PAUSED -> tx("key.screens.mainscreen.status.paused");
            case STOPPED -> tx("key.screens.mainscreen.status.stopped");
        };
        graphics.text(this.font, status, px + 2, curY, 0xFFAAAAAA);
        curY += ENTRY_H + 6;

        // 播放控制按钮
        transportBtnY = curY;
        int bc = px + pw / 2;
        transportPrevX = bc - 62; transportPlayX = bc - 29;
        transportStopX = bc + 4;  transportNextX = bc + 37;

        drawUniBtn(graphics, transportPrevX, curY, TRANSPORT_BTN_SIZE, TRANSPORT_BTN_SIZE, "⏮", mouseX, mouseY);
        String pLabel = pb.status() == PlaybackStatus.PLAYING ? "⏸" : "▶";
        drawUniBtn(graphics, transportPlayX, curY, TRANSPORT_BTN_SIZE, TRANSPORT_BTN_SIZE, pLabel, mouseX, mouseY);
        drawUniBtn(graphics, transportStopX, curY, TRANSPORT_BTN_SIZE, TRANSPORT_BTN_SIZE, "■", mouseX, mouseY);
        drawUniBtn(graphics, transportNextX, curY, TRANSPORT_BTN_SIZE, TRANSPORT_BTN_SIZE, "⏭", mouseX, mouseY);
        curY += TRANSPORT_BTN_SIZE + 8;

        // 音量滑块
        volSliderY = curY;
        volSliderX = px + 4;
        volSliderW = pw - SLIDER_VAL_W - 8;
        renderSlider(graphics, px + 4, curY, volSliderW, SLIDER_H, pb.volume() / 300f,
                String.format("%d%%", pb.volume()), inRect(mouseX, mouseY, px + 4, curY, volSliderW, SLIDER_H));
        curY += SLIDER_H + 6;

        // === 画质 + 模式（右对齐，与进度条同宽） ===
        int[] res = getVideoRes();
        String[] qOpts = ClientVideoPlaybackManager.getQualityOptions(Math.max(res[0], res[1]));
        int curQ = ClientVideoPlaybackManager.getQualityIndex();
        int curQi = 0;
        for (int i = 0; i < qOpts.length; i++)
            if (qOpts[i].equals(ClientVideoPlaybackManager.QUALITY_LABELS[curQ])) { curQi = i; break; }

        modeBtnW = 80; qualityBtnW = 64;
        int rowW = modeBtnW + 8 + qualityBtnW + (qOpts.length > 1 ? 36 : 0);
        int rowX = px + pw - rowW - 4;

        qualityBtnY = curY; qualityBtnX = rowX + modeBtnW + 8 + (qOpts.length > 1 ? 18 : 0);
        modeBtnY = curY;    modeBtnX = rowX;

        String modeLabel = switch (pb.mode()) {
            case SEQUENTIAL -> tx("key.screens.mainscreen.mode.sequential");
            case LOOP_LIST -> tx("key.screens.mainscreen.mode.loop_list");
            case SINGLE_LOOP -> tx("key.screens.mainscreen.mode.single_loop");
            case RANDOM -> tx("key.screens.mainscreen.mode.random");
        };
        drawUniBtn(graphics, modeBtnX, modeBtnY, modeBtnW, 20, modeLabel, mouseX, mouseY);

        String qLabel = qOpts.length > curQi ? qOpts[curQi] : tx("key.screens.mainscreen.quality.original");
        drawUniBtn(graphics, qualityBtnX, qualityBtnY, qualityBtnW, 20, qLabel, mouseX, mouseY);
        if (qOpts.length > 1) {
            drawUniBtn(graphics, qualityBtnX - 18, qualityBtnY, 18, 20, "◀", mouseX, mouseY);
            drawUniBtn(graphics, qualityBtnX + qualityBtnW, qualityBtnY, 18, 20, "▶", mouseX, mouseY);
        }
    }

    // ==================== 播放列表页签 ====================
    private void renderPlaylistTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                    VideoPlayerData player, int px, int py, int pw) {
        PlaybackState pb = player.playbackState();
        List<VideoSource> playlist = player.playlist();
        int ci = pb.currentIndex();
        int curY = py + 4; // 顶部留白

        String curInfo = playlist.isEmpty() ? tx("key.screens.mainscreen.empty.no_video")
                : String.format(tx("key.screens.mainscreen.label.playlist_current"),
                ci + 1, playlist.size(), ci >= 0 && ci < playlist.size() ? shorten(playlist.get(ci).url(), 40) : "-");
        graphics.text(this.font, curInfo, px + 2, curY, 0xFFAAAAAA);
        curY += ENTRY_H + 4;

        int availH = this.height - rightPaneY - 10;
        // 列表向下扩展两个编辑框（40px）：原 56 预留给底部按钮区，但输入行已上移半个编辑框，
        // 直接 +40 会与输入框重叠 12px，故按实际可用空间扩到与输入行保持 2px 空隙
        int listH = availH - (ENTRY_H + 4) - 4 - 30;
        playlistX = px; playlistY = curY; playlistW = pw; playlistH = listH;
        graphics.fill(px, curY, px + pw, curY + listH, 0xAA111111);
        graphics.fill(px, curY, px + pw, curY + 1, 0xFF555555);
        if (playlist.isEmpty()) {
            graphics.text(this.font, tx("key.screens.mainscreen.empty.playlist"), px + 4, curY + 4, 0xFF888888);
        } else {
            int vis = listH / (ENTRY_H + 2);
            int maxScroll = Math.max(0, playlist.size() - vis);
            if (playlistScroll > maxScroll) playlistScroll = maxScroll;
            for (int i = playlistScroll; i < Math.min(playlist.size(), playlistScroll + vis); i++) {
                String url = playlist.get(i).url();
                int ey = curY + 2 + (i - playlistScroll) * (ENTRY_H + 2);
                boolean sel = (i == selectedVideoIndex), cur = (i == ci);
                boolean hov = mouseX >= px && mouseX <= px + pw && mouseY >= ey && mouseY <= ey + ENTRY_H;
                if (sel) graphics.fill(px, ey, px + pw, ey + ENTRY_H, 0xFF444444);
                else if (hov) graphics.fill(px, ey, px + pw, ey + ENTRY_H, 0x55333333);
                graphics.text(this.font, (cur ? "▶ " : "   ") + shorten(url, pw / 7 - 3),
                        px + 3, ey + 1, cur ? 0xFF55FF55 : sel ? 0xFFFFAA00 : 0xFFE0E0E0);
            }
        }
    }

    // ==================== 屏幕设置页签 ====================
    private void renderScreenTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                  VideoPlayerData player, int px, int py, int pw) {
        List<VideoScreenData> screens = player.screens();
        if (screens.isEmpty()) {
            graphics.text(this.font, tx("key.screens.mainscreen.empty.no_screen"), px + 2, py, 0xFF888888);
            // 空列表也显示“新建屏幕”按钮（否则没有屏幕时创建按钮丢失，无法创建第一个屏幕）
            scrSaveBtnY = py + 26;
            drawUniBtn(graphics, px + 76, scrSaveBtnY, 68, 20,
                    tx("key.screens.mainscreen.button.new_screen"), mouseX, mouseY);
            return;
        }
        if (LeftPanelHelper.selectedScreenIndex < 0 || LeftPanelHelper.selectedScreenIndex >= screens.size())
            LeftPanelHelper.selectedScreenIndex = 0;
        VideoScreenData screen = getSelectedScreen();
        uvEditor.loadFromScreen(screen);

        int curY = py + 4; // 顶部留白
        graphics.text(this.font, "◎ " + screen.name(), px + 2, curY, 0xFFCCCCFF);

        // 坐标中心（顶点平均值）
        String coordStr = "";
        if (!screen.vertices().isEmpty()) {
            double cx = 0, cy = 0, cz = 0;
            for (var v : screen.vertices()) { cx += v.x(); cy += v.y(); cz += v.z(); }
            int n = screen.vertices().size();
            coordStr = String.format("  ·  (%.0f, %.0f, %.0f)", cx / n, cy / n, cz / n);
        }
        graphics.text(this.font, screen.dimension().identifier() + "  ·  " + screen.vertices().size()
                        + "  " + tx("key.screens.mainscreen.label.vertices") + coordStr,
                px + 2, curY + ENTRY_H, 0xFF888888);
        curY += ENTRY_H * 2 + 4;

        // 屏幕选择器（多屏幕时）
        if (screens.size() > 1) {
            for (int i = 0; i < screens.size(); i++) {
                int sx = px + i * 80;
                boolean s = (i == LeftPanelHelper.selectedScreenIndex);
                if (s) graphics.fill(sx, curY, sx + 76, curY + ENTRY_H, 0xFF444444);
                graphics.text(this.font, (s ? "● " : "○ ") + screens.get(i).name(), sx + 2, curY + 1,
                        s ? 0xFF55FF55 : 0xFFE0E0E0);
            }
            curY += ENTRY_H + 6;
        }

        // UV 预览（按视频宽高比）
        int uvH = Math.max(100, Math.min(pw * 9 / 16, 200));
        int ci = player.playbackState().currentIndex();
        float va = 16f / 9f;
        if (ci >= 0 && ci < player.playlist().size()) {
            var src = player.playlist().get(ci);
            if (src.originalWidth() > 0 && src.originalHeight() > 0)
                va = (float) src.originalWidth() / src.originalHeight();
        }
        uvEditor.videoAspect = va;
        uvEditor.render(graphics, this.font, mouseX, mouseY, player, screen, px, curY, pw, uvH);
        curY += uvH + 8;

        // === 5 个滑块 ===
        scrSliderBaseY = curY;
        scrSliderX = px + SLIDER_LABEL_W + 4;
        scrSliderW = Math.min(pw - SLIDER_LABEL_W - SLIDER_VAL_W - 12, 150);

        curY = renderSliderRow(graphics, px, curY, tx("key.screens.mainscreen.slider.rotation"),
                (uvEditor.rotationY + 180) / 360f, String.format("%.0f°", uvEditor.rotationY), 0, mouseX, mouseY);
        curY = renderSliderRow(graphics, px, curY, tx("key.screens.mainscreen.slider.scale"),
                (uvEditor.uvScaleU - 0.5f) / 1.5f,
                String.format("%.0f%%", uvEditor.uvScaleU * 100), 1, mouseX, mouseY);
        curY = renderSliderRow(graphics, px, curY, tx("key.screens.mainscreen.slider.offset_u"),
                (uvEditor.uvOffsetX + 500) / 1000f, String.format("%.0f", uvEditor.uvOffsetX), 2, mouseX, mouseY);
        curY = renderSliderRow(graphics, px, curY, tx("key.screens.mainscreen.slider.offset_v"),
                (uvEditor.uvOffsetY + 500) / 1000f, String.format("%.0f", uvEditor.uvOffsetY), 3, mouseX, mouseY);
        curY += 8;

        // 翻转按钮
        scrFlipBtnY = curY;
        drawUniBtn(graphics, px + 4, curY, 70, 16, (uvEditor.uvFlipU ? "[✓] " : "") + tx("key.screens.mainscreen.flip.horizontal"), mouseX, mouseY);
        drawUniBtn(graphics, px + 78, curY, 70, 16, (uvEditor.uvFlipV ? "[✓] " : "") + tx("key.screens.mainscreen.flip.vertical"), mouseX, mouseY);
        curY += 20;

        // 声道按钮
        scrChanBtnY = curY;
        ScreenChannelState ch = screen.channelState();
        drawUniBtn(graphics, px + 4, curY, 54, 16, (ch.leftEnabled() ? "[✓] " : "") + tx("key.screens.mainscreen.channel.left"), mouseX, mouseY);
        drawUniBtn(graphics, px + 62, curY, 54, 16, (ch.rightEnabled() ? "[✓] " : "") + tx("key.screens.mainscreen.channel.right"), mouseX, mouseY);
        curY += 28;

        // 操作按钮（跟随内容滚动）
        scrSaveBtnY = curY;
        drawUniBtn(graphics, px + 4, curY, 68, 20, tx("key.screens.mainscreen.button.save_uv"), mouseX, mouseY);
        drawUniBtn(graphics, px + 76, curY, 68, 20, tx("key.screens.mainscreen.button.new_screen"), mouseX, mouseY);
        drawUniBtn(graphics, px + 148, curY, 68, 20, tx("key.screens.mainscreen.button.delete_screen"), mouseX, mouseY);
    }

    private int renderSliderRow(GuiGraphicsExtractor g, int px, int y, String label,
                                 float frac, String val, int idx, int mx, int my) {
        g.text(this.font, label, px + 2, y + 2, 0xFFE0E0E0);
        g.text(this.font, val, scrSliderX + scrSliderW + 4, y + 2, 0xFFAAAAAA);
        int sy = scrSliderBaseY + idx * (SLIDER_H + 4);
        boolean hov = sliderEditMode == null && sliderDragging == null
                && mx >= scrSliderX && mx <= scrSliderX + scrSliderW && my >= sy && my <= sy + SLIDER_H;
        renderSlider(g, scrSliderX, y, scrSliderW, SLIDER_H, frac, null, hov);
        return y + SLIDER_H + 4;
    }

    // ==================== 鼠标事件 ====================
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();

        // mpv 浮层可见时，拦截所有点击，仅透传给浮层按钮/镜像下拉
        if (showMpvPrompt) {
            // 镜像下拉：点击框体展开/收起
            if (inRect(mx, my, mirrorBoxX, mirrorRowY, mirrorBoxW, 16)) {
                mirrorMenuOpen = !mirrorMenuOpen;
                return true;
            }
            if (mirrorMenuOpen) {
                int count = MpvNativeLoader.getMirrorCount();
                for (int i = 0; i < count; i++) {
                    int oy = mirrorRowY + 16 + i * 16;
                    if (inRect(mx, my, mirrorBoxX, oy, mirrorBoxW, 16)) {
                        MpvNativeLoader.setSelectedMirror(i);
                        mirrorMenuOpen = false;
                        return true;
                    }
                }
                mirrorMenuOpen = false; // 点选项外部 → 收起（不拦截，继续走按钮命中）
            }
            if (mpvDownloadBtn.visible && mpvDownloadBtn.isMouseOver(mx, my)) return mpvDownloadBtn.mouseClicked(event, doubleClick);
            if (mpvManualBtn.visible   && mpvManualBtn.isMouseOver(mx, my))   return mpvManualBtn.mouseClicked(event, doubleClick);
            if (mpvDismissBtn.visible  && mpvDismissBtn.isMouseOver(mx, my))  return mpvDismissBtn.mouseClicked(event, doubleClick);
            return true; // 吃掉浮层外的所有点击
        }

        // 关闭按钮
        if (mx >= this.width - 20 && mx <= this.width - 4 && my >= 4 && my <= 20) { this.onClose(); return true; }

        // 重命名输入框可见时，点击在其范围内 → 交由 EditBox 处理，不触发左侧面板
        boolean renameActive = leftPanel.isRenameInputVisible() && renameInput.isVisible();
        int renameX = renameActive ? renameInput.getX() : -1;
        int renameY = renameActive ? renameInput.getY() : -1;
        int renameW = renameActive ? renameInput.getWidth() : 0;
        int renameH = renameActive ? renameInput.getHeight() : 0;
        boolean clickOnRename = renameActive && mx >= renameX && mx <= renameX + renameW
                && my >= renameY && my <= renameY + renameH;

        // 左侧面板（重命名编辑框激活时跳过，避免点击编辑框触发列表取消重命名）
        if (!clickOnRename) {
            if (doubleClick && leftPanel.mouseDoubleClicked(mx, my, cachedPlayers)) return true;
            if (leftPanel.mouseClicked(mx, my, cachedPlayers)) return true;
        }

        // Area 滚动条处理
        if (rightPaneArea != null && rightPaneArea.mouseClicked(event, doubleClick)) return true;

        if (!hasSelectedPlayer()) return super.mouseClicked(event, doubleClick);

        // 右侧面板内容：仅当鼠标在可见区域内才做滚动转换
        int availRH = this.height - rightPaneY - 10;
        if (my < rightPaneY || my > rightPaneY + availRH)
            return super.mouseClicked(event, doubleClick);

        int pmy = scrollAdjY(my);
        int rp = rightPaneX;

        // --- 播放控制页签 ---
        if (activeTab == 0) {
            if (seekBar.isInBar(mx, pmy)) { seekBar.startDrag(mx); return true; }
            if (clickBtn(mx, pmy, transportPrevX, transportBtnY, TRANSPORT_BTN_SIZE)) { playPrev(); return true; }
            if (clickBtn(mx, pmy, transportPlayX, transportBtnY, TRANSPORT_BTN_SIZE)) { togglePlayPause(); return true; }
            if (clickBtn(mx, pmy, transportStopX, transportBtnY, TRANSPORT_BTN_SIZE)) { stopPlayback(); return true; }
            if (clickBtn(mx, pmy, transportNextX, transportBtnY, TRANSPORT_BTN_SIZE)) { playNext(); return true; }
            if (inRect(mx, pmy, volSliderX, volSliderY, volSliderW, SLIDER_H))
            { sliderDragging = "volume"; updateVolumeFromMouse(mx); return true; }
            if (inRect(mx, pmy, qualityBtnX, qualityBtnY, qualityBtnW, 20)) { nextQuality(); return true; }
            if (inRect(mx, pmy, qualityBtnX - 18, qualityBtnY, 18, 20)) { prevQuality(); return true; }
            if (inRect(mx, pmy, qualityBtnX + qualityBtnW, qualityBtnY, 18, 20)) { nextQuality(); return true; }
            if (inRect(mx, pmy, modeBtnX, modeBtnY, modeBtnW, 20)) { cyclePlaybackMode(); return true; }
        }

        // --- 播放列表页签 ---
        if (activeTab == 1 && inRect(mx, pmy, playlistX, playlistY, playlistW, playlistH)) {
            var pl = getSelectedPlayer().playlist();
            int idx = (pmy - playlistY - 2) / (ENTRY_H + 2) + playlistScroll;
            if (idx >= 0 && idx < pl.size()) {
                selectedVideoIndex = idx;
                if (doubleClick) {
                    PlaybackState pb = getSelectedPlayer().playbackState();
                    VideoPlayerClientNetworking.updatePlayback(getSelectedPlayer().id(),
                            PlaybackStatus.PLAYING, pb.mode(), idx, pb.volume());
                }
            }
            return true;
        }

        // --- 屏幕设置页签 ---
        if (activeTab == 2) {
            // 空列表：只支持“新建屏幕”（按钮丢失修复——没有屏幕时无法创建第一个屏幕）
            if (!hasSelectedScreen()) {
                if (inRect(mx, pmy, rp + 76, scrSaveBtnY, 68, 20) && hasSelectedPlayer())
                    createScreenFor(getSelectedPlayer());
                return super.mouseClicked(event, doubleClick);
            }
            if (event.button() == 2 && uvEditor.isInUVArea(mx, pmy))
            { panningUV = true; dragLockedScroll = rightPaneArea != null ? rightPaneArea.getScrollOffset() : 0; return true; }
            if (doubleClick) { String s = hitSlidAt(mx, pmy); if (s != null) { startSliderEdit(s); return true; } }
            String s = hitSlidAt(mx, pmy);
            if (s != null) { sliderDragging = s; return true; }
            if (uvEditor.mouseClicked(mx, pmy, getSelectedScreen()))
            { dragLockedScroll = rightPaneArea != null ? rightPaneArea.getScrollOffset() : 0; return true; }

            if (inRect(mx, pmy, rp + 4, scrFlipBtnY, 70, 16))
            { uvEditor.uvFlipU = !uvEditor.uvFlipU; uvEditor.markEdited(); saveUV(); return true; }
            if (inRect(mx, pmy, rp + 78, scrFlipBtnY, 70, 16))
            { uvEditor.uvFlipV = !uvEditor.uvFlipV; uvEditor.markEdited(); saveUV(); return true; }

            VideoScreenData screen = getSelectedScreen();
            ScreenChannelState ch = screen.channelState();
            if (inRect(mx, pmy, rp + 4, scrChanBtnY, 54, 16)) {
                VideoPlayerClientNetworking.updateScreenChannel(getSelectedPlayer().id(), screen.id(),
                        new ScreenChannelState(!ch.leftEnabled(), ch.rightEnabled())); return true;
            }
            if (inRect(mx, pmy, rp + 62, scrChanBtnY, 54, 16)) {
                VideoPlayerClientNetworking.updateScreenChannel(getSelectedPlayer().id(), screen.id(),
                        new ScreenChannelState(ch.leftEnabled(), !ch.rightEnabled())); return true;
            }

            // 屏幕选择器
            List<VideoScreenData> screens = getSelectedPlayer().screens();
            if (screens.size() > 1) {
                int selY = rightPaneY + ENTRY_H * 2 + 4 + 4;
                for (int i = 0; i < screens.size(); i++) {
                    int sx = rp + i * 80;
                    if (mx >= sx && mx <= sx + 76 && pmy >= selY && pmy <= selY + ENTRY_H)
                    { LeftPanelHelper.selectedScreenIndex = i; return true; }
                }
            }

            // 操作按钮
            if (inRect(mx, pmy, rp + 4, scrSaveBtnY, 68, 20)) { saveUV(); return true; }
            if (inRect(mx, pmy, rp + 76, scrSaveBtnY, 68, 20)) {
                if (hasSelectedPlayer()) createScreenFor(getSelectedPlayer()); return true;
            }
            if (inRect(mx, pmy, rp + 148, scrSaveBtnY, 68, 20)) {
                if (hasSelectedPlayer() && hasSelectedScreen()) {
                    VideoPlayerClientNetworking.deleteScreen(getSelectedPlayer().id(), getSelectedScreen().id());
                    LeftPanelHelper.selectedScreenIndex = -1;
                }
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // Area 滚动条释放
        if (rightPaneArea != null && rightPaneArea.mouseReleased(event)) return true;

        if (seekBar.dragging && hasSelectedPlayer()) { seekBar.finishDrag(getSelectedPlayer().id()); sliderDragging = null; return true; }
        if ("volume".equals(sliderDragging)) { sliderDragging = null; return true; }
        if (sliderDragging != null) { sliderDragging = null; uvEditor.markEdited(); saveUV(); return true; }
        if (uvEditor.isEditing()) { uvEditor.finishDrag(); dragLockedScroll = 0; saveUV(); return true; }
        if (panningUV) { panningUV = false; dragLockedScroll = 0; return true; }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        // Area 滚动条拖拽
        if (rightPaneArea != null && rightPaneArea.mouseDragged(event, dx, dy)) return true;

        int mx = (int) event.x();
        if (seekBar.dragging) { seekBar.updateDrag(mx); return true; }
        if ("volume".equals(sliderDragging)) { updateVolumeFromMouse(mx); return true; }
        if (sliderDragging != null) { updateScreenSliderFromMouse(mx); return true; }
        if (uvEditor.isEditing()) { uvEditor.mouseDragged(mx, (int) event.y() + dragLockedScroll); return true; }
        if (panningUV) { uvEditor.panDrag(mx, (int) event.y() + dragLockedScroll, dx, dy); return true; }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double sx, double sy) {
        int mx = (int) mouseX, my = (int) mouseY;

        // 左侧面板滚动
        if (leftPanel.mouseScrolled(mx, my, sy, cachedPlayers)) return true;

        // 播放列表内部滚动
        if (activeTab == 1 && inRect(mx, my, playlistX, playlistY, playlistW, playlistH) && hasSelectedPlayer()) {
            int vis = playlistH / (ENTRY_H + 2);
            playlistScroll = Math.max(0, Math.min(playlistScroll - (int) sy * 2,
                    Math.max(0, getSelectedPlayer().playlist().size() - vis)));
            return true;
        }

        // UV 缩放
        if (activeTab == 2 && uvEditor.zoomWheel(mx, scrollAdjY(my), sy)) return true;

        // Area 外层滚动（仅内容超出时才消费事件）
        if (rightPaneArea != null && rightPaneArea.mouseScrolled(mouseX, mouseY, sx, sy)) return true;

        return super.mouseScrolled(mouseX, mouseY, sx, sy);
    }

    // ==================== 键盘事件 ====================
    @Override
    public boolean keyPressed(KeyEvent e) {
        if (rightPaneArea != null && rightPaneArea.keyPressed(e)) return true;

        int k = e.key();
        if (k == GLFW.GLFW_KEY_ENTER && valueEditBox.isVisible()) { confirmSliderEdit(); return true; }
        if (k == GLFW.GLFW_KEY_ESCAPE && valueEditBox.isVisible()) { cancelSliderEdit(); return true; }
        if (k == GLFW.GLFW_KEY_ENTER && leftPanel.isRenameInputVisible()) { confirmRename(); return true; }
        if (k == GLFW.GLFW_KEY_ESCAPE && leftPanel.isRenameInputVisible())
        { leftPanel.cancelRename(); renameInput.visible = false; renameInputInitialized = false; return true; }
        if (k == GLFW.GLFW_KEY_SPACE && activeTab == 0 && hasSelectedPlayer()
                && !valueEditBox.isVisible() && !renameInput.isVisible()) { togglePlayPause(); return true; }
        if (k == GLFW.GLFW_KEY_S && ctrlDown() && activeTab == 2 && hasSelectedPlayer() && hasSelectedScreen())
        { saveUV(); return true; }
        return super.keyPressed(e);
    }

    @Override
    public boolean keyReleased(KeyEvent e) {
        if (rightPaneArea != null && rightPaneArea.keyReleased(e)) return true;
        return super.keyReleased(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (rightPaneArea != null && rightPaneArea.charTyped(e)) return true;
        if (valueEditBox.isVisible()) { valueEditBox.charTyped(e); return true; }
        return super.charTyped(e);
    }

    // ==================== Widget 可见性 ====================
    private void updateWidgetVisibility() {
        boolean hp = hasSelectedPlayer();
        String addLabel = tx("key.screens.mainscreen.button.add_video");
        String removeLabel = tx("key.screens.mainscreen.button.remove_video");
        for (var c : this.children()) {
            if (!(c instanceof net.minecraft.client.gui.components.AbstractWidget w)) continue;
            if (c == renameInput || c == valueEditBox) continue;
            String l = w.getMessage().getString();
            if (isAlwaysVisible(l)) continue;
            if (c == urlInput) { w.visible = activeTab == 1 && hp; continue; }
            if (addLabel.equals(l) || removeLabel.equals(l)) { w.visible = activeTab == 1 && hp; continue; }
        }
    }

    private boolean isAlwaysVisible(String l) {
        return l.equals(tx("key.screens.mainscreen.tab.playback"))
                || l.equals(tx("key.screens.mainscreen.tab.playlist"))
                || l.equals(tx("key.screens.mainscreen.tab.screen"))
                || l.equals(tx("key.screens.mainscreen.button.create_player"))
                || l.equals(tx("key.screens.mainscreen.button.delete_player"));
    }

    // ==================== 重命名 ====================
    private void syncRenameInput() {
        if (leftPanel.isRenameInputVisible()) {
            renameInput.visible = true;
            renameInput.setX(leftPanel.getRenameInputX());
            renameInput.setY(leftPanel.getRenameInputY(cachedPlayers));
            renameInput.setWidth(leftPanel.getRenameInputW());
            renameInput.setHeight(leftPanel.getRenameInputH());
            // 仅首次设置初始值，之后不再覆盖用户输入
            if (!renameInputInitialized) {
                renameInput.setValue(leftPanel.getRenameTarget());
                renameInputInitialized = true;
            }
            if (!renameInput.isFocused()) { setFocused(renameInput); renameInput.setFocused(true); }
        } else {
            renameInput.visible = false;
            renameInputInitialized = false;
        }
    }

    private void confirmRename() {
        String r = leftPanel.confirmRename(cachedPlayers, renameInput.getValue());
        if (r != null) {
            if (r.startsWith("player:")) VideoPlayerClientNetworking.renamePlayer(
                    UUID.fromString(r.substring(7)), renameInput.getValue().trim());
            else if (r.startsWith("screen:") && hasSelectedPlayer()) VideoPlayerClientNetworking.renameScreen(
                    getSelectedPlayer().id(), UUID.fromString(r.substring(7)), renameInput.getValue().trim());
        }
        renameInput.visible = false;
        renameInputInitialized = false;
    }

    // ==================== 滑块交互（屏幕设置） ====================
    private static final String[] SLIDER_KEYS = {"rotation", "scale", "offsetU", "offsetV"};

    private String hitSlidAt(int mx, int my) {
        for (int i = 0; i < SLIDER_KEYS.length; i++) {
            int sy = scrSliderBaseY + i * (SLIDER_H + 4);
            if (mx >= scrSliderX && mx <= scrSliderX + scrSliderW && my >= sy && my <= sy + SLIDER_H)
                return SLIDER_KEYS[i];
        }
        return null;
    }

    private float getSliderVal(String k) {
        return switch (k) {
            case "rotation" -> uvEditor.rotationY;
            case "scale" -> uvEditor.uvScaleU;
            case "offsetU" -> uvEditor.uvOffsetX;
            case "offsetV" -> uvEditor.uvOffsetY;
            default -> 0f;
        };
    }

    private void setSliderVal(String k, float v) {
        switch (k) {
            case "rotation" -> uvEditor.rotationY = v;
            case "scale" -> {
                float ratio = uvEditor.uvScaleV > 0.001f ? uvEditor.uvScaleV / uvEditor.uvScaleU : 1f;
                uvEditor.uvScaleU = Math.max(0.01f, Math.min(10f, v));
                uvEditor.uvScaleV = Math.max(0.01f, uvEditor.uvScaleU * ratio);
            }
            case "offsetU" -> uvEditor.uvOffsetX = v;
            case "offsetV" -> uvEditor.uvOffsetY = v;
        }
    }

    private void startSliderEdit(String k) {
        sliderEditMode = k;
        int idx = Arrays.asList(SLIDER_KEYS).indexOf(k);
        int sy = scrSliderBaseY + idx * (SLIDER_H + 4);
        valueEditBox.visible = true;
        valueEditBox.setX(scrSliderX); valueEditBox.setY(sy); valueEditBox.setWidth(scrSliderW);
        valueEditBox.setValue(String.format("%.2f", getSliderVal(k)));
        setFocused(valueEditBox); valueEditBox.setFocused(true);
    }

    private void confirmSliderEdit() {
        if (sliderEditMode == null) return;
        try {
            float v = Float.parseFloat(valueEditBox.getValue().trim());
            if ("scale".equals(sliderEditMode) && v <= 0) v = 0.01f;
            setSliderVal(sliderEditMode, v); uvEditor.markEdited(); saveUV();
        } catch (NumberFormatException ignored) {}
        valueEditBox.visible = false; sliderEditMode = null;
    }

    private void cancelSliderEdit() { valueEditBox.visible = false; sliderEditMode = null; }

    private void updateScreenSliderFromMouse(int mx) {
        if (sliderDragging == null) return;
        float f = Math.max(0f, Math.min(1f, (float) (mx - scrSliderX) / scrSliderW));
        switch (sliderDragging) {
            case "rotation" -> uvEditor.rotationY = -180 + f * 360;
            case "scale" -> {
                float oldU = uvEditor.uvScaleU;
                float oldV = uvEditor.uvScaleV;
                float ratio = oldV > 0.001f ? oldV / oldU : 1f;
                // 匹配旧版 ImGui 范围: 50% ~ 200% (0.5 ~ 2.0)
                float newU = Math.max(0.01f, Math.min(10f, 0.5f + f * 1.5f));
                uvEditor.uvScaleU = newU;
                uvEditor.uvScaleV = Math.max(0.01f, newU * ratio);
            }
            case "offsetU" -> uvEditor.uvOffsetX = -500 + f * 1000;
            case "offsetV" -> uvEditor.uvOffsetY = -500 + f * 1000;
        }
    }

    // ==================== 音量 / 画质 / 模式 ====================
    private void updateVolumeFromMouse(int mx) {
        if (!hasSelectedPlayer()) return;
        float f = Math.max(0f, Math.min(1f, (float) (mx - volSliderX) / volSliderW));
        int v = Math.round(f * 300);
        PlaybackState pb = getSelectedPlayer().playbackState();
        VideoPlayerClientNetworking.updatePlayback(getSelectedPlayer().id(), pb.status(), pb.mode(), pb.currentIndex(), v);
        ClientVideoPlaybackManager.setGlobalVolume(v / 100f);
    }

    private int[] getVideoRes() {
        int ci = getSelectedPlayer().playbackState().currentIndex();
        if (ci >= 0 && ci < getSelectedPlayer().playlist().size()) {
            var src = getSelectedPlayer().playlist().get(ci);
            if (src.originalWidth() > 0) return new int[]{src.originalWidth(), src.originalHeight()};
        }
        return new int[]{1280, 720};
    }

    private void prevQuality() {
        int[] r = getVideoRes(); String[] o = ClientVideoPlaybackManager.getQualityOptions(Math.max(r[0], r[1]));
        if (o.length == 0) return;
        int cur = ClientVideoPlaybackManager.getQualityIndex(), ci = 0;
        for (int i = 0; i < o.length; i++) if (o[i].equals(ClientVideoPlaybackManager.QUALITY_LABELS[cur])) { ci = i; break; }
        setQualityByLabel(o[(ci - 1 + o.length) % o.length]);
    }

    private void nextQuality() {
        int[] r = getVideoRes(); String[] o = ClientVideoPlaybackManager.getQualityOptions(Math.max(r[0], r[1]));
        if (o.length == 0) return;
        int cur = ClientVideoPlaybackManager.getQualityIndex(), ci = 0;
        for (int i = 0; i < o.length; i++) if (o[i].equals(ClientVideoPlaybackManager.QUALITY_LABELS[cur])) { ci = i; break; }
        setQualityByLabel(o[(ci + 1) % o.length]);
    }

    private void setQualityByLabel(String label) {
        for (int i = 0; i < ClientVideoPlaybackManager.QUALITY_LABELS.length; i++)
            if (ClientVideoPlaybackManager.QUALITY_LABELS[i].equals(label))
            { ClientVideoPlaybackManager.setQualityByIndex(i); return; }
    }

    private void cyclePlaybackMode() {
        if (!hasSelectedPlayer()) return;
        PlaybackState pb = getSelectedPlayer().playbackState();
        PlaybackMode[] m = PlaybackMode.values();
        VideoPlayerClientNetworking.updatePlayback(getSelectedPlayer().id(), pb.status(),
                m[(pb.mode().ordinal() + 1) % m.length], pb.currentIndex(), pb.volume());
    }

    // ==================== 播放控制 ====================
    private void playPrev() { changeTrack(-1); }
    private void playNext() { changeTrack(1); }
    private void changeTrack(int d) {
        if (!hasSelectedPlayer()) return;
        PlaybackState pb = getSelectedPlayer().playbackState();
        int tot = getSelectedPlayer().playlist().size(); if (tot == 0) return;
        int idx = (pb.currentIndex() + d + tot) % tot;
        VideoPlayerClientNetworking.updatePlayback(getSelectedPlayer().id(), PlaybackStatus.PLAYING, pb.mode(), idx, pb.volume());
    }
    private void togglePlayPause() {
        if (!hasSelectedPlayer()) return;
        PlaybackState pb = getSelectedPlayer().playbackState();
        PlaybackStatus ns = pb.status() == PlaybackStatus.PLAYING ? PlaybackStatus.PAUSED : PlaybackStatus.PLAYING;
        VideoPlayerClientNetworking.updatePlayback(getSelectedPlayer().id(), ns, pb.mode(), pb.currentIndex(), pb.volume());
        if (ns == PlaybackStatus.PAUSED) ClientVideoPlaybackManager.stopLocal(getSelectedPlayer().id());
        else VideoPlayerClientNetworking.seekPlayback(getSelectedPlayer().id(), seekBar.currentSecs);
    }
    private void stopPlayback() {
        if (!hasSelectedPlayer()) return;
        PlaybackState pb = getSelectedPlayer().playbackState();
        VideoPlayerClientNetworking.updatePlayback(getSelectedPlayer().id(), PlaybackStatus.STOPPED, pb.mode(), pb.currentIndex(), pb.volume());
        ClientVideoPlaybackManager.stopLocal(getSelectedPlayer().id()); seekBar.reset();
    }
    private void addVideo() {
        String url = urlInput.getValue().trim();
        if (!url.isEmpty() && hasSelectedPlayer()) {
            // 使用原始分辨率（0, 0, 0 → 服务端自动检测）
            VideoPlayerClientNetworking.addVideoToPlaylist(getSelectedPlayer().id(), url, 0, 0, 0);
            urlInput.setValue("");
        }
    }
    private void deleteSelectedVideo() {
        if (hasSelectedPlayer() && selectedVideoIndex >= 0 && selectedVideoIndex < getSelectedPlayer().playlist().size()) {
            VideoPlayerClientNetworking.removeVideoFromPlaylist(getSelectedPlayer().id(), selectedVideoIndex);
            selectedVideoIndex = -1;
        }
    }
    private void saveUV() {
        if (!hasSelectedPlayer() || !hasSelectedScreen()) return;
        UvTransform uv = uvEditor.buildUV();
        System.out.printf("[UV Save] pid=%s sid=%s scaleU=%.3f scaleV=%.3f offsetU=%.3f offsetV=%.3f rot=%.1f flipU=%b flipV=%b%n",
                getSelectedPlayer().id(), getSelectedScreen().id(),
                uv.scaleU(), uv.scaleV(), uv.offsetU(), uv.offsetV(),
                uv.rotationDegrees(), uv.flipU(), uv.flipV());
        VideoPlayerClientNetworking.updateScreenUv(getSelectedPlayer().id(), getSelectedScreen().id(), uv, true);
        uvEditor.markEdited();
    }
    private void createScreenFor(VideoPlayerData p) {
        if (ClientScreenCreationManager.start(p.id(), p.name() + "-screen")) {
            sashwind.mc.mod.drawlib.client.lib.setScreenCompat(Minecraft.getInstance(), null);
            Player.startVertexPlacement(() -> Minecraft.getInstance().execute(() ->
                    sashwind.mc.mod.drawlib.client.lib.setScreenCompat(Minecraft.getInstance(), new MainScreen())));
        }
    }

    // ==================== 数据同步 ====================
    private void syncCache() {
        long v = ClientVideoPlayerCache.getVersion();
        if (v != lastSnapshotVersion) {
            lastSnapshotVersion = v;
            cachedPlayers = new ArrayList<>(VideoPlayerClientNetworking.snapshot().players());
            if (LeftPanelHelper.selectedPlayerIndex >= cachedPlayers.size())
                LeftPanelHelper.selectedPlayerIndex = cachedPlayers.isEmpty() ? -1 : 0;
            if (LeftPanelHelper.selectedScreenIndex >= (hasSelectedPlayer() ? getSelectedPlayer().screens().size() : 0))
                LeftPanelHelper.selectedScreenIndex = 0;
        }
    }

    // ==================== 渲染工具 ====================
    private void renderSlider(GuiGraphicsExtractor g, int x, int y, int w, int h,
                               float frac, String rLabel, boolean hover) {
        int barH = 4, barY = y + h / 2 - barH / 2;
        g.fill(x, barY, x + w, barY + barH, 0xFF222222);
        g.fill(x, barY, x + w, barY + 1, 0xFF555555);
        g.fill(x, barY, x + (int)(w * frac), barY + barH, 0xFF44AA44);
        int tx = x + (int)(w * frac) - 4;
        g.fill(tx, y + 1, tx + 8, y + h - 1, hover ? 0xFFFFFFFF : 0xFFCCCCCC);
        g.fill(tx, y + 1, tx + 1, y + h - 1, 0xFF888888);
        g.fill(tx + 7, y + 1, tx + 8, y + h - 1, 0xFF888888);
        if (rLabel != null) g.text(this.font, rLabel, x + w + 6, y + 2, 0xFFAAAAAA);
    }

    private void drawUniBtn(GuiGraphicsExtractor g, int x, int y, int w, int h, String t, int mx, int my) {
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + h;
        g.fill(x, y, x + w, y + h, hov ? BTN_BG_HOV : BTN_BG);
        g.fill(x, y, x + w, y + 1, BTN_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BTN_BORDER);
        g.fill(x, y, x + 1, y + h, BTN_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BTN_BORDER);
        String s = shorten(t, (w - 8) / 7 + 1);
        g.text(this.font, s, x + (w - this.font.width(s)) / 2, y + (h - 9) / 2 + 1, hov ? BTN_TEXT_HOV : BTN_TEXT);
    }

    private void drawGrid(GuiGraphicsExtractor g, int x, int y, int w, int h, int cell) {
        for (int gx = x; gx < x + w; gx += cell) g.fill(gx, y, gx + 1, y + h, 0x18FFFFFF);
        for (int gy = y; gy < y + h; gy += cell) g.fill(x, gy, x + w, gy + 1, 0x18FFFFFF);
    }

    private void drawRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c); g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c); g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private static boolean inRect(int mx, int my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
    }

    private static boolean clickBtn(int mx, int my, int x, int y, int s) {
        return mx >= x && mx <= x + s && my >= y && my <= y + s;
    }

    private static String shorten(String s, int max) {
        if (s.length() <= max) return s; return s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static boolean ctrlDown() {
        long w = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(w, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(w, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private static Button btn(String t, Button.OnPress a, int x, int y, int w, int h) {
        return Button.builder(Component.literal(t), a).pos(x, y).size(w, h).build();
    }
}
