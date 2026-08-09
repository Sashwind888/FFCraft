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
    private int progress;

    private int boxW = 360, boxH = 240;
    private int boxX, boxY;

    // 镜像选择器点击区域
    private int mirrorLeftX, mirrorRightX, mirrorLabelX, mirrorLabelW, mirrorRowY;

    public MpvInstallScreen() {
        super(Component.translatable("key.screens.mpvinstall.title"));
    }

    @Override
    protected void init() {
        boxX = (this.width - boxW) / 2;
        boxY = (this.height - boxH) / 2;

        int bw = 90, btnRowY = boxY + 190;
        int bx = (this.width - bw * 3 - 16) / 2;

        // 镜像选择行：标签 + ◀ + 名称框 + ▶ 整体居中
        mirrorRowY = boxY + 148;
        String label = txt("key.screens.mpvinstall.mirror") + ":";
        int labelW = this.font.width(label);
        int mw = 240; // 选择器总宽（含 ◀▶）
        int rowW = labelW + 10 + mw;
        int rowX = (this.width - rowW) / 2;
        mirrorLabelX = rowX;
        mirrorLeftX  = rowX + labelW + 10;
        mirrorRightX = mirrorLeftX + mw - 20;
        mirrorLabelW = mw - 40;

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
            progress = MpvNativeLoader.getDownloadProgress();
            if (MpvNativeLoader.isLoaded()) closeScreen();
            if (MpvNativeLoader.getState() == MpvNativeLoader.State.FAILED) downloading = false;
        }
    }

    private void startDownload() {
        if (downloading) return;
        downloading = true;
        MpvNativeLoader.downloadAsync(p -> {}).thenAccept(ok -> {
            if (ok) Minecraft.getInstance().execute(this::closeScreen);
            else downloading = false;
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

        // ---- 镜像选择行 ----
        String mirrorLabel = txt("key.screens.mpvinstall.mirror") + ":";
        graphics.text(font, mirrorLabel, mirrorLabelX, mirrorRowY + 2, 0xFFAAAAAA);

        // ◀ 按钮
        int mlx = mirrorLeftX, mrx = mirrorRightX;
        boolean hovL = inRect(mouseX, mouseY, mlx, mirrorRowY, 20, 16);
        boolean hovR = inRect(mouseX, mouseY, mrx, mirrorRowY, 20, 16);
        graphics.fill(mlx, mirrorRowY, mlx + 20, mirrorRowY + 16, hovL ? 0xFF555555 : 0xFF3A3A3A);
        graphics.text(font, "◀", mlx + 5, mirrorRowY + 2, hovL ? 0xFFFFAA44 : 0xFFCCCCCC);

        // 当前镜像名
        String curMirror = MpvNativeLoader.getMirrorName(MpvNativeLoader.getSelectedMirror());
        int labelX = mirrorLabelX + 20;
        graphics.fill(labelX, mirrorRowY, labelX + mirrorLabelW, mirrorRowY + 16, 0xFF222233);
        drawBorder(graphics, labelX, mirrorRowY, mirrorLabelW, 16, 0xFF555577);
        int textW = font.width(curMirror);
        graphics.text(font, curMirror, labelX + (mirrorLabelW - textW) / 2, mirrorRowY + 2, 0xFFEEEEEE);

        // ▶ 按钮
        graphics.fill(mrx, mirrorRowY, mrx + 20, mirrorRowY + 16, hovR ? 0xFF555555 : 0xFF3A3A3A);
        graphics.text(font, "▶", mrx + 5, mirrorRowY + 2, hovR ? 0xFFFFAA44 : 0xFFCCCCCC);

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
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();

        // 镜像左箭头
        if (inRect(mx, my, mirrorLeftX, mirrorRowY, 20, 16)) {
            int cur = MpvNativeLoader.getSelectedMirror();
            int n = (cur - 1 + MpvNativeLoader.getMirrorCount()) % MpvNativeLoader.getMirrorCount();
            MpvNativeLoader.setSelectedMirror(n);
            return true;
        }
        // 镜像右箭头
        if (inRect(mx, my, mirrorRightX, mirrorRowY, 20, 16)) {
            MpvNativeLoader.cycleMirror();
            return true;
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
