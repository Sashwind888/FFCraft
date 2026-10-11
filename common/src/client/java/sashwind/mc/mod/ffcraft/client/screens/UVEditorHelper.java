package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;
import sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking;
import sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager;
import sashwind.mc.mod.ffcraft.common.model.*;

import java.util.*;

/**
 * UV 编辑器面板 —— 交互式 UV 预览、屏幕覆盖层绘制、手柄拖拽编辑。
 */
public class UVEditorHelper {

    float uvOffsetX, uvOffsetY, uvScaleU = 1f, uvScaleV = 1f, rotationY;
    public boolean uvFlipU, uvFlipV, uvIs3D;
    java.util.UUID lastEditedScreenId;
    float previewZoom = 1f, previewOffsetX = 0f, previewOffsetY = 0f;
    float videoAspect = 16f / 9f; // 视频宽高比，默认 16:9
    String editingMode;
    float editStartScale, editStartScaleV, editStartOffsetX, editStartOffsetY, editStartDist;
    int editCorner;

    int uvX, uvY, uvW, uvH;
    float frameX, frameY, frameW, frameH;

    public void loadFromScreen(VideoScreenData screen) {
        java.util.UUID id = screen.id();
        if (!id.equals(lastEditedScreenId)) {
            lastEditedScreenId = id;
            UvTransform ut = screen.uvTransform();
            uvOffsetX = (float) (ut.offsetU() * 500.0);
            uvOffsetY = (float) (ut.offsetV() * 500.0);
            uvScaleU   = (float) ut.scaleU();
            uvScaleV   = (float) ut.scaleU(); // 屏幕采用物理正交度量，缩放强制保持 1:1 等比，彻底消除历史遗留的拉伸畸变！
            rotationY  = (float) ut.rotationDegrees();
            uvFlipU    = ut.flipU();
            uvFlipV    = ut.flipV();
            uvIs3D     = ut.is3D();
            previewZoom = 1f;
            previewOffsetX = 0f;
            previewOffsetY = 0f;
            System.out.printf("[UV Load] screen=%s scaleU=%.3f scaleV=%.3f offsetU=%.3f offsetV=%.3f rot=%.1f flipU=%b flipV=%b is3D=%b%n",
                    screen.name(), ut.scaleU(), ut.scaleV(), ut.offsetU(), ut.offsetV(),
                    ut.rotationDegrees(), ut.flipU(), ut.flipV(), ut.is3D());
        }
    }

    public UvTransform buildUV() {
        return new UvTransform(uvOffsetX / 500.0, uvOffsetY / 500.0, uvScaleU, uvScaleV, rotationY, uvFlipU, uvFlipV, uvIs3D);
    }

    // ==================== 渲染 ====================

    public void render(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY,
                       VideoPlayerData player, VideoScreenData screen,
                       int px, int py, int pw, int ph) {
        uvX = px; uvY = py; uvW = pw; uvH = ph;
        graphics.fill(px, py, px + pw, py + ph, 0xFF1A1A1A);

        float pad = 10f;
        float areaW = pw - pad * 2;
        float areaH = ph - pad * 2;
        float cx = px + pw / 2f + previewOffsetX;
        float cy = py + ph / 2f + previewOffsetY;
        // 编辑画布严格以当前实际视频画幅 (如 2.11:1 或 16:9 或 4:3) 作为基准参考视口，确保所见即所得
        float va = videoAspect > 0.01f ? videoAspect : (16f / 9f);
        if (areaW / areaH > va) {
            frameH = areaH * previewZoom;
            frameW = frameH * va;
        } else {
            frameW = areaW * previewZoom;
            frameH = frameW / va;
        }
        frameX = cx - frameW / 2f;
        frameY = cy - frameH / 2f;

        // 关键防护：开启严格的局部 Scissor 裁剪，防止多边形或手柄拖出 UV 框外导致渲染穿透遮挡下方控件
        graphics.enableScissor(px, py, px + pw, py + ph);

        graphics.fill((int) frameX, (int) frameY, (int) (frameX + frameW), (int) (frameY + frameH), 0xFF000000);

        // 网格：在视频视口内绘制自适应等距网格背景
        if (previewZoom <= 5f) {
            float cellSize = frameH / 9f; // 保证横纵网格皆为绝对正方形
            for (float gx = frameX; gx <= frameX + frameW + 0.1f; gx += cellSize) {
                vLine(graphics, gx, Math.max(py, frameY), Math.min(py + ph, frameY + frameH), 0x25FFFFFF);
            }
            for (float gy = frameY; gy <= frameY + frameH + 0.1f; gy += cellSize) {
                hLine(graphics, gy, Math.max(px, frameX), Math.min(px + pw, frameX + frameW), 0x25FFFFFF);
            }
        }

        // 视频分辨率/画幅信息标识
        String aspectStr = String.format("Video (%.2f:1)", va);
        graphics.text(font, aspectStr, (int) frameX + 4, (int) frameY + 4, 0xFF00BFFF);

        // 绘制以视频中心为坐标原点的绝对正交物理坐标系（原点标记、+U 红色轴、+V 绿色轴、基准中心线）
        float vcx = frameX + frameW / 2f;
        float vcy = frameY + frameH / 2f;
        drawCoordinateSystem(graphics, font, vcx, vcy, frameH, frameW);

        // 覆盖层多边形屏幕绘制
        java.util.UUID curId = screen.id();
        for (VideoScreenData s : player.screens()) {
            if (s.id().equals(curId)) continue;
            if (s.vertices().size() >= 3)
                drawScreenOverlay(graphics, s, null, 0x20444444, 0xAA666666, 0xFF888888);
        }
        drawScreenOverlay(graphics, screen, buildUV(), 0x20FF4488, 0xFFFF4488, 0xFF66FFFF);

        // frame 视频外边框
        rectBorder(graphics, (int) frameX, (int) frameY, (int) frameW, (int) frameH, 0xFFAAAAAA);

        graphics.text(font, "(0,0)", (int) frameX + 2, (int) frameY + 2, 0xFFFFFFFF);
        graphics.text(font, "(1,0)", (int) (frameX + frameW) - 28, (int) frameY + 2, 0xFFFFFFFF);
        graphics.text(font, "(0,1)", (int) frameX + 2, (int) (frameY + frameH) - 12, 0xFFFFFFFF);
        graphics.text(font, screen.name(), (int) frameX + 4, (int) (frameY + frameH) - 14, 0xFFFF66AA);

        // 关闭局部 Scissor 裁剪，确保不破坏外部 Area 滚动裁剪栈
        graphics.disableScissor();

        // 绘制 UV 预览区域最外层常驻边框
        rectBorder(graphics, px, py, pw, ph, 0xFF444444);
    }

    /**
     * 绘制以视频中心为原点的物理正交坐标系（红轴 +U，绿轴 +V）
     */
    private void drawCoordinateSystem(GuiGraphicsExtractor g, Font font, float vcx, float vcy, float vidH, float vidW) {
        // 1. 水平与垂直半透明中心轴基准线
        hLine(g, vcy, Math.max(uvX, frameX), Math.min(uvX + uvW, frameX + frameW), 0x30FF5555);
        vLine(g, vcx, Math.max(uvY, frameY), Math.min(uvY + uvH, frameY + frameH), 0x3055FF55);

        // 2. 坐标系主轴线（长度约占视口 20%~25%）
        float axisLenU = Math.min(50f, vidW * 0.22f);
        float axisLenV = Math.min(50f, vidH * 0.22f);

        // +U 轴 (水平向右，鲜红色)
        line(g, (int) vcx, (int) vcy, (int) (vcx + axisLenU), (int) vcy, 0xFFFF4444, 2);
        // +U 箭头
        line(g, (int) (vcx + axisLenU), (int) vcy, (int) (vcx + axisLenU - 5), (int) (vcy - 3), 0xFFFF4444, 2);
        line(g, (int) (vcx + axisLenU), (int) vcy, (int) (vcx + axisLenU - 5), (int) (vcy + 3), 0xFFFF4444, 2);
        g.text(font, "+U", (int) (vcx + axisLenU + 4), (int) (vcy - 4), 0xFFFF5555);

        // +V 轴 (垂直向下，鲜绿色)
        line(g, (int) vcx, (int) vcy, (int) vcx, (int) (vcy + axisLenV), 0xFF44FF44, 2);
        // +V 箭头
        line(g, (int) vcx, (int) (vcy + axisLenV), (int) (vcx - 3), (int) (vcy + axisLenV - 5), 0xFF44FF44, 2);
        line(g, (int) vcx, (int) (vcy + axisLenV), (int) (vcx + 3), (int) (vcy + axisLenV - 5), 0xFF44FF44, 2);
        g.text(font, "+V", (int) (vcx - 4), (int) (vcy + axisLenV + 4), 0xFF55FF55);

        // 3. 原点中心标记
        g.fill((int) vcx - 2, (int) vcy - 2, (int) vcx + 3, (int) vcy + 3, 0xFFFFFFFF);
        g.fill((int) vcx - 1, (int) vcy - 1, (int) vcx + 2, (int) vcy + 2, 0xFFFFCC00);
        g.text(font, "O(0,0)", (int) vcx - 28, (int) vcy - 11, 0xDDFFFF00);
    }

    // ==================== 覆盖层绘制 ====================

    private void drawScreenOverlay(GuiGraphicsExtractor g, VideoScreenData s, UvTransform localUv,
                                   int fillColor, int edgeColor, int pointColor) {
        List<Vector3d> vs = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        for (var v : s.vertices()) { vs.add(v.toVector()); dists.add(v.pitch()); dists.add(v.yaw()); }

        UvTransform ut = localUv != null ? localUv : s.uvTransform();
        double scaleRatio = ClientVideoPlaybackManager.getAspectScaleRatio(s.id());
        float su = (float) (ut.scaleU() * scaleRatio), sv = su; // 保持绝对等比并应用动态比例自适应系数
        float ou = (float) ut.offsetU(), ov = (float) ut.offsetV();
        float rot = (float) ut.rotationDegrees();
        boolean fpu = ut.flipU(), fpv = ut.flipV();
        float ca = (float) Math.cos(Math.toRadians(rot));
        float sa = (float) Math.sin(Math.toRadians(rot));

        List<double[]> stitched = sashwind.mc.mod.ffcraft.client.player.Three2Flat.getStitchedUVs(vs, dists);
        for (double[] uvs : stitched) {
            int n = uvs.length / 2;
            if (n < 3) continue;
            for (int i = 1; i < n - 1; i++) {
                int[] t = tri(uvs, 0, i, i + 1, su, sv, ou, ov, ca, sa, fpu, fpv);
                fillTri(g, t[0], t[1], t[2], t[3], t[4], t[5], fillColor);
            }
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                int[] seg = seg(uvs, i, j, su, sv, ou, ov, ca, sa, fpu, fpv);
                line(g, seg[0], seg[1], seg[2], seg[3], edgeColor, 2);
            }
            for (int i = 0; i < n; i++) {
                int[] pt = pt(uvs, i, su, sv, ou, ov, ca, sa, fpu, fpv);
                g.fill(pt[0] - 3, pt[1] - 3, pt[0] + 4, pt[1] + 4, pointColor);
            }
        }
    }

    // ---- 坐标转换 ----

    private float[] xform(float u, float v, float su, float sv, float ou, float ov, float ca, float sa, boolean fpu, boolean fpv) {
        float rotDeg = (float) Math.toDegrees(Math.atan2(sa, ca));
        org.joml.Vector2d rigid = sashwind.mc.mod.ffcraft.client.player.MetricCoordinateSystem.transformRigid(
                u, v, ou, ov, su, sv, rotDeg, fpu, fpv);
        return sashwind.mc.mod.ffcraft.client.player.MetricCoordinateSystem.toUI_Pixels(
                rigid.x, rigid.y, frameX, frameY, frameW, frameH);
    }
    private int[] pt(double[] uvs, int i, float su, float sv, float ou, float ov, float ca, float sa, boolean fpu, boolean fpv) {
        float[] t = xform((float) uvs[i * 2], (float) uvs[i * 2 + 1], su, sv, ou, ov, ca, sa, fpu, fpv);
        return new int[]{(int) t[0], (int) t[1]};
    }
    private int[] seg(double[] uvs, int i, int j, float su, float sv, float ou, float ov, float ca, float sa, boolean fpu, boolean fpv) {
        float[] a = xform((float) uvs[i * 2], (float) uvs[i * 2 + 1], su, sv, ou, ov, ca, sa, fpu, fpv);
        float[] b = xform((float) uvs[j * 2], (float) uvs[j * 2 + 1], su, sv, ou, ov, ca, sa, fpu, fpv);
        return new int[]{(int) a[0], (int) a[1], (int) b[0], (int) b[1]};
    }
    private int[] tri(double[] uvs, int i0, int i1, int i2, float su, float sv, float ou, float ov, float ca, float sa, boolean fpu, boolean fpv) {
        float[] a = xform((float) uvs[i0 * 2], (float) uvs[i0 * 2 + 1], su, sv, ou, ov, ca, sa, fpu, fpv);
        float[] b = xform((float) uvs[i1 * 2], (float) uvs[i1 * 2 + 1], su, sv, ou, ov, ca, sa, fpu, fpv);
        float[] c = xform((float) uvs[i2 * 2], (float) uvs[i2 * 2 + 1], su, sv, ou, ov, ca, sa, fpu, fpv);
        return new int[]{(int) a[0], (int) a[1], (int) b[0], (int) b[1], (int) c[0], (int) c[1]};
    }

    // ==================== 交互 ====================

    public boolean isInUVArea(int mx, int my) { return mx >= uvX && mx <= uvX + uvW && my >= uvY && my <= uvY + uvH; }
    public boolean isEditing() { return editingMode != null; }

    float getMU(int mx) { return (mx - frameX) / frameW; }
    float getMV(int my) { return (my - frameY) / frameH; }
    float getCU() {
        float va = videoAspect > 0.01f ? videoAspect : (16f / 9f);
        return (uvOffsetX / 500f) / va + 0.5f;
    }
    float getCV() { return uvOffsetY / 500f + 0.5f; }

    float editStartMouseU, editStartMouseV;

    public boolean mouseClicked(int mx, int my, VideoScreenData screen) {
        if (!isInUVArea(mx, my)) return false;
        float mu = getMU(mx), mv = getMV(my);
        float hr = 10f / frameW;

        // 优先检测四角缩放手柄
        float[] b = computeBounds(screen);
        float[][] corners = {{b[0], b[2]}, {b[1], b[2]}, {b[0], b[3]}, {b[1], b[3]}};
        for (int i = 0; i < 4; i++) {
            if (Math.sqrt(Math.pow(mu - corners[i][0], 2) + Math.pow(mv - corners[i][1], 2)) < hr * 1.5f) {
                editingMode = "scale"; editStartScale = uvScaleU; editStartScaleV = uvScaleV;
                editStartOffsetX = uvOffsetX; editStartOffsetY = uvOffsetY; editCorner = i;
                float cu = getCU(), cv = getCV();
                editStartDist = (float) Math.sqrt(Math.pow(mu - cu, 2) + Math.pow(mv - cv, 2));
                return true;
            }
        }

        // 其余区域：左键拖动调整 UV 偏移（记录按下时的初始鼠标画布坐标，绝不乱跳）
        editingMode = "move";
        editStartOffsetX = uvOffsetX;
        editStartOffsetY = uvOffsetY;
        editStartMouseU = mu;
        editStartMouseV = mv;
        return true;
    }

    public void mouseDragged(int mx, int my) {
        if (editingMode == null) return;
        float mu = getMU(mx), mv = getMV(my);
        if ("move".equals(editingMode)) {
            // 平滑增量拖动：无论鼠标点在多边形的什么位置，只按实际位移量平移，彻底杜绝乱跳！
            float va = videoAspect > 0.01f ? videoAspect : (16f / 9f);
            float deltaU = (mu - editStartMouseU) * va;
            float deltaV = mv - editStartMouseV;
            uvOffsetX = editStartOffsetX + deltaU * 500f;
            uvOffsetY = editStartOffsetY + deltaV * 500f;
        } else if ("scale".equals(editingMode)) {
            float cu = getCU(), cv = getCV();
            float cd = (float) Math.sqrt(Math.pow(mu - cu, 2) + Math.pow(mv - cv, 2));
            if (editStartDist > 0.001f) {
                float ratio = Math.max(0.1f, Math.min(3f, cd / editStartDist));
                uvScaleU = editStartScale * ratio;
                uvScaleV = uvScaleU;
            }
            uvOffsetX = editStartOffsetX; uvOffsetY = editStartOffsetY;
        }
    }

    public void finishDrag() { if (editingMode != null) { editingMode = null; markEdited(); } }

    public boolean zoomWheel(int mx, int my, double scrollY) {
        if (!isInUVArea(mx, my)) return false;
        float oldZ = previewZoom;
        previewZoom = Math.max(0.1f, Math.min(10f, previewZoom + (float) scrollY * 0.1f));
        float zf = previewZoom / oldZ;
        // 以鼠标位置为中心缩放：
        // newOffset = mouseRel * (1 - zf) + zf * oldOffset
        float dx = mx - uvX - uvW / 2f;
        float dy = my - uvY - uvH / 2f;
        previewOffsetX = dx * (1f - zf) + zf * previewOffsetX;
        previewOffsetY = dy * (1f - zf) + zf * previewOffsetY;
        return true;
    }

    public void panDrag(int mx, int my, double dx, double dy) { previewOffsetX += (float) dx; previewOffsetY += (float) dy; }

    public float[] computeBounds(VideoScreenData screen) {
        double scaleRatio = ClientVideoPlaybackManager.getAspectScaleRatio(screen.id());
        float su = (float) (uvScaleU * scaleRatio), sv = su;
        float ou = uvOffsetX / 500f, ov = uvOffsetY / 500f;
        float rot = rotationY;
        boolean fpu = uvFlipU, fpv = uvFlipV;
        float ca = (float) Math.cos(Math.toRadians(rot));
        float sa = (float) Math.sin(Math.toRadians(rot));
        float[] bounds = {1f, -1f, 1f, -1f};
        List<Vector3d> vs = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        for (var v : screen.vertices()) { vs.add(v.toVector()); dists.add(v.pitch()); dists.add(v.yaw()); }
        List<double[]> stitched = sashwind.mc.mod.ffcraft.client.player.Three2Flat.getStitchedUVs(vs, dists);
        float va = videoAspect > 0.01f ? videoAspect : (16f / 9f);
        for (double[] uvs : stitched) {
            for (int i = 0; i < uvs.length / 2; i++) {
                float u = (float) uvs[i * 2], v = (float) uvs[i * 2 + 1];
                org.joml.Vector2d rigid = sashwind.mc.mod.ffcraft.client.player.MetricCoordinateSystem.transformRigid(
                        u, v, ou, ov, su, sv, rot, fpu, fpv);
                float ru = (float) (rigid.x / va + 0.5);
                float rv = (float) (rigid.y + 0.5);
                if (ru < bounds[0]) bounds[0] = ru; if (ru > bounds[1]) bounds[1] = ru;
                if (rv < bounds[2]) bounds[2] = rv; if (rv > bounds[3]) bounds[3] = rv;
            }
        }
        return bounds;
    }

    /** i18n helper */
    private static String txt(String key) { return Component.translatable(key).getString(); }

    public void markEdited() { if (lastEditedScreenId != null) ClientVideoPlaybackManager.markUvManuallyEdited(lastEditedScreenId); }

    // ---- 翻转/声道 ----

    public boolean flipClick(int mx, int my, Font font, int baseX, int baseY) {
        String prefix = txt("key.screens.mainscreen.label.flip") + ": ";
        String horz = (uvFlipU ? "[✓] " : "[ ] ") + txt("key.screens.mainscreen.label.horizontal");
        String vert = (uvFlipV ? "[✓] " : "[ ] ") + txt("key.screens.mainscreen.label.vertical");
        int xU = baseX + font.width(prefix);
        int xV = xU + font.width(horz + "  ");
        if (mx >= xU && mx <= xU + font.width(horz) && my >= baseY && my <= baseY + 12) { uvFlipU = !uvFlipU; markEdited(); return true; }
        if (mx >= xV && mx <= xV + font.width(vert) && my >= baseY && my <= baseY + 12) { uvFlipV = !uvFlipV; markEdited(); return true; }
        return false;
    }

    public boolean channelClick(int mx, int my, Font font, int baseX, int baseY,
                                 VideoPlayerData player, VideoScreenData screen) {
        ScreenChannelState ch = screen.channelState();
        String prefix = txt("key.screens.mainscreen.label.channel") + ": ";
        String left  = (ch.leftEnabled()  ? "[✓] " : "[ ] ") + txt("key.screens.mainscreen.label.left");
        String right = (ch.rightEnabled() ? "[✓] " : "[ ] ") + txt("key.screens.mainscreen.label.right");
        int xL = baseX + font.width(prefix);
        int xR = xL + font.width(left + "  ");
        if (mx >= xL && mx <= xL + font.width(left) && my >= baseY && my <= baseY + 12) {
            VideoPlayerClientNetworking.updateScreenChannel(player.id(), screen.id(), new ScreenChannelState(!ch.leftEnabled(), ch.rightEnabled()));
            return true;
        }
        if (mx >= xR && mx <= xR + font.width(right) && my >= baseY && my <= baseY + 12) {
            VideoPlayerClientNetworking.updateScreenChannel(player.id(), screen.id(), new ScreenChannelState(ch.leftEnabled(), !ch.rightEnabled()));
            return true;
        }
        return false;
    }

    // ---- 绘制辅助 ----

    private void fillTri(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int x2, int y2, int color) {
        int minY = Math.min(y0, Math.min(y1, y2)), maxY = Math.max(y0, Math.max(y1, y2));
        if (minY == maxY) return;
        for (int y = minY; y <= maxY; y++) {
            List<Integer> xs = new ArrayList<>();
            addX(xs, x0, y0, x1, y1, y); addX(xs, x1, y1, x2, y2, y); addX(xs, x2, y2, x0, y0, y);
            if (xs.size() >= 2) { xs.sort(Integer::compareTo); g.fill(xs.get(0), y, xs.get(xs.size() - 1), y + 1, color); }
        }
    }
    private void addX(List<Integer> l, int x0, int y0, int x1, int y1, int y) {
        if ((y0 <= y && y1 > y) || (y1 <= y && y0 > y)) l.add(x0 + (y - y0) * (x1 - x0) / (y1 - y0));
    }
    private void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int c, int t) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), s = Math.max(dx, dy);
        if (s == 0) { g.fill(x0, y0, x0 + t, y0 + t, c); return; }
        for (int i = 0; i <= s; i++) g.fill(x0+(x1-x0)*i/s, y0+(y1-y0)*i/s, x0+(x1-x0)*i/s+t, y0+(y1-y0)*i/s+t, c);
    }
    private void rectBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c); g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c); g.fill(x + w - 1, y, x + w, y + h, c);
    }
    private void vLine(GuiGraphicsExtractor g, float x, float top, float bottom, int c) { g.fill((int) x, (int) top, (int) x + 1, (int) bottom, c); }
    private void hLine(GuiGraphicsExtractor g, float y, float left, float right, int c) { g.fill((int) left, (int) y, (int) right, (int) y + 1, c); }
}
