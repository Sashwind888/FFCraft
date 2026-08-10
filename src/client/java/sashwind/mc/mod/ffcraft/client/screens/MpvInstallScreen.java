package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import sashwind.mc.mod.drawlib.client.lib;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import sashwind.mc.mod.ffcraft.client.player.MpvNativeLoader;

import java.net.URI;

/**
 * libmpv 安装界面 — 在标题画面之前弹出，引导用户安装原生库。
 */
public class MpvInstallScreen extends Screen {

    private static boolean alreadyShown;

    public static boolean hasShown() { return alreadyShown; }
    public static void markShown() { alreadyShown = true; }

    private boolean downloading;
    private volatile int progress; // 下载线程回调直接写入
    private int doneTicks; // 下载完成后停留的 tick 数（让用户看到 100%）

    private int boxW = 360, boxH = 280;
    private int boxX, boxY;

    // 镜像下拉菜单
    private boolean mirrorMenuOpen;
    private int mirrorBoxX, mirrorBoxW, mirrorRowY;

    public MpvInstallScreen() {
        super(Component.translatable("key.screens.mpvinstall.title"));
    }

    @Override
    protected void init() {
        // 每次打开界面检查文件是否存在：存在则直接加载并关闭，不显示下载界面
        // （覆盖手动放置 DLL / 上次下载完成后加载失败但文件有效等场景）
        if (!MpvNativeLoader.isLoaded() && MpvNativeLoader.tryLoadExisting()) {
            Minecraft.getInstance().execute(this::closeScreen);
            return;
        }
        boxX = (this.width - boxW) / 2;
        boxY = (this.height - boxH) / 2;

        int bw = 90, btnRowY = boxY + 190;
        int bx = (this.width - bw * 3 - 16) / 2;

        // 镜像下拉选择框（整体居中）
        mirrorRowY = boxY + 148;
        mirrorBoxW = 260;
        mirrorBoxX = (this.width - mirrorBoxW) / 2;

        addRenderableWidget(Button.builder(
                Component.translatable("key.screens.mpvinstall.download"),
                b -> startDownload()
        ).pos(bx, btnRowY).size(bw, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("key.screens.mpvinstall.manual"),
                b -> openManual()
        ).pos(bx + bw + 8, btnRowY).size(bw, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("key.screens.mpvinstall.dismiss"),
                b -> closeScreen()
        ).pos(bx + bw * 2 + 16, btnRowY).size(bw, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (downloading) {
            // 下载线程通过回调直连 progress，这里只做兜底（不会倒退回退）
            progress = Math.max(progress, MpvNativeLoader.getDownloadProgress());
            // 下载完成后停留 10 tick（500ms）显示 100%，再关闭界面
            if (MpvNativeLoader.isLoaded()) {
                if (++doneTicks >= 10) closeScreen();
            } else if (MpvNativeLoader.getState() == MpvNativeLoader.State.FAILED) {
                downloading = false;
            }
        }
    }

    private void startDownload() {
        if (downloading) return;
        downloading = true;
        doneTicks = 0;
        MpvNativeLoader.downloadAsync(p -> progress = Math.max(progress, p)).thenAccept(ok -> {
            // 成功时不立即关闭：由 tick 里的 doneTicks 停留 500ms 显示 100%
            if (!ok) downloading = false;
        });
    }

    private void openManual() {
        try {
            // 用 MC 自带的方式打开浏览器（java.awt.Desktop 在 MC 环境是 Headless）
            Util.getPlatform().openUri(URI.create(MpvNativeLoader.getDownloadPageUrl()));
        } catch (Exception e) {
            System.err.println("[MpvInstall] 无法打开浏览器: " + e);
        }
    }

    private void closeScreen() {
        alreadyShown = true;
        lib.setScreenCompat(Minecraft.getInstance(), null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, this.width, this.height, 0xC0101010);

        Font font = this.font;
        int cx = boxX, cy = boxY, cw = boxW, ch = boxH;

        // 面板
        graphics.fill(cx, cy, cx + cw, cy + ch, 0xFF2A2A3E);
        drawBorder(graphics, cx, cy, cw, ch, 0xFF6666AA);

        // 标题
        boolean failed = MpvNativeLoader.getState() == MpvNativeLoader.State.FAILED;
        String title = txt(failed ? "key.screens.mpvinstall.title_failed"
                : downloading ? "key.screens.mpvinstall.title_downloading"
                : "key.screens.mpvinstall.title");
        graphics.text(font, title, cx + (cw - font.width(title)) / 2, cy + 12, 0xFFFFAA44);

        // 描述
        String desc;
        if (failed) {
            desc = MpvNativeLoader.getStatusMsg();
        } else if (downloading) {
            desc = txt("key.screens.mpvinstall.downloading");
        } else {
            desc = txt("key.screens.mpvinstall.desc");
        }
        graphics.text(font, desc, cx + (cw - font.width(desc)) / 2, cy + 40, 0xFFCCCCCC);

        String hint = txt("key.screens.mpvinstall.hint");
        graphics.text(font, hint, cx + (cw - font.width(hint)) / 2, cy + 58, 0xFF888888);

        String path = MpvNativeLoader.getLibPath().toString();
        String shortPath = path.length() > 50 ? "…" + path.substring(path.length() - 48) : path;
        graphics.text(font, "▸ " + shortPath, cx + (cw - font.width("▸ " + shortPath)) / 2, cy + 74, 0xFF777777);

        // 进度条
        if (downloading) {
            int pbX = cx + 30, pbW = cw - 60, pbY = boxY + 170, pbH = 8;
            graphics.fill(pbX, pbY, pbX + pbW, pbY + pbH, 0xFF222222);
            int fill = (int) (pbW * progress / 100f);
            if (fill > 0) graphics.fill(pbX, pbY, pbX + fill, pbY + pbH, 0xFF44AA44);
            drawBorder(graphics, pbX, pbY, pbW, pbH, 0xFF555555);
            String pct = progress + "%";
            graphics.text(font, pct, cx + (cw - font.width(pct)) / 2, pbY + pbH + 2, 0xFFAAAAAA);
        }

        if (failed) {
            String err = MpvNativeLoader.getStatusMsg();
            graphics.text(font, err, cx + (cw - font.width(err)) / 2, cy + 110, 0xFFFF6666);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // ---- 镜像下拉选择（最后渲染置顶，展开的选项盖住下方按钮）----
        String curMirror = MpvNativeLoader.getMirrorName(MpvNativeLoader.getSelectedMirror());
        String boxText = txt("key.screens.mpvinstall.mirror") + ": " + curMirror;
        boolean hovBox = inRect(mouseX, mouseY, mirrorBoxX, mirrorRowY, mirrorBoxW, 16);
        graphics.fill(mirrorBoxX, mirrorRowY, mirrorBoxX + mirrorBoxW, mirrorRowY + 16,
                hovBox || mirrorMenuOpen ? 0xFF444455 : 0xFF222233);
        drawBorder(graphics, mirrorBoxX, mirrorRowY, mirrorBoxW, 16, 0xFF555577);
        graphics.text(font, boxText, mirrorBoxX + (mirrorBoxW - font.width(boxText)) / 2,
                mirrorRowY + 2, 0xFFEEEEEE);
        graphics.text(font, mirrorMenuOpen ? "▲" : "▼",
                mirrorBoxX + mirrorBoxW - 12, mirrorRowY + 2, 0xFFAAAAAA);

        // 展开的镜像选项列表
        if (mirrorMenuOpen) {
            int sel = MpvNativeLoader.getSelectedMirror();
            int count = MpvNativeLoader.getMirrorCount();
            for (int i = 0; i < count; i++) {
                int oy = mirrorRowY + 16 + i * 16;
                boolean oh = inRect(mouseX, mouseY, mirrorBoxX, oy, mirrorBoxW, 16);
                graphics.fill(mirrorBoxX, oy, mirrorBoxX + mirrorBoxW, oy + 16,
                        oh ? 0xFF444466 : 0xFF2A2A3E);
                if (i == sel) drawBorder(graphics, mirrorBoxX, oy, mirrorBoxW, 16, 0xFF55AA55);
                graphics.text(font, MpvNativeLoader.getMirrorName(i),
                        mirrorBoxX + 8, oy + 2, i == sel ? 0xFF55FF55 : 0xFFCCCCCC);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();

        // 镜像下拉框：切换展开/收起
        if (inRect(mx, my, mirrorBoxX, mirrorRowY, mirrorBoxW, 16)) {
            mirrorMenuOpen = !mirrorMenuOpen;
            return true;
        }
        // 展开时：点击选项 → 选中并收起；点击其他区域 → 收起（不拦截后续点击）
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
            mirrorMenuOpen = false;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { closeScreen(); return true; }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() { closeScreen(); }

    private static String txt(String key) { return Component.translatable(key).getString(); }

    private static boolean inRect(int mx, int my, int rx, int ry, int rw, int rh) {
        return mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh;
    }

    private static void drawBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c);
        g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c);
        g.fill(x + w - 1, y, x + w, y + h, c);
    }
}
