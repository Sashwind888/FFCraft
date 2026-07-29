package sashwind.mc.mod.ffcraft.client.screens;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    boolean uvFlipU, uvFlipV;
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
            uvScaleV   = (float) ut.scaleV();
            rotationY  = (float) ut.rotationDegrees();
            uvFlipU    = ut.flipU();
            uvFlipV    = ut.flipV();
            previewZoom = 1f;
            previewOffsetX = 0f;
            previewOffsetY = 0f;
            System.out.printf("[UV Load] screen=%s scaleU=%.3f scaleV=%.3f offsetU=%.3f offsetV=%.3f rot=%.1f flipU=%b flipV=%b%n",
                    screen.name(), ut.scaleU(), ut.scaleV(), ut.offsetU(), ut.offsetV(),
                    ut.rotationDegrees(), ut.flipU(), ut.flipV());
        }
    }

    public UvTransform buildUV() {
        return new UvTransform(uvOffsetX / 500.0, uvOffsetY / 500.0, uvScaleU, uvScaleV, rotationY, uvFlipU, uvFlipV);
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
        // 按视频宽高比约束帧尺寸，避免拉伸
        if (areaW / areaH > videoAspect) {
            frameH = areaH * previewZoom;
            frameW = frameH * videoAspect;
        } else {
            frameW = areaW * previewZoom;
            frameH = frameW / videoAspect;
        }
        frameX = cx - frameW / 2f;
        frameY = cy - frameH / 2f;

        graphics.fill((int) frameX, (int) frameY, (int) (frameX + frameW), (int) (frameY + frameH), 0xFF000000);
        graphics.enableScissor(px, py, px + pw, py + ph);

        java.util.UUID curId = screen.id();
        // 未选中屏幕
        for (VideoScreenData s : player.screens()) {
            if (s.id().equals(curId)) continue;
            if (s.vertices().size() >= 3)
                drawScreenOverlay(graphics, s, null, 0x20444444, 0xAA666666, 0xFF888888);
        }
        // 选中屏幕（使用本地编辑 UV）
        drawScreenOverlay(graphics, screen, buildUV(), 0x20FF4488, 0xFFFF4488, 0xFF66FFFF);

        // 网格
        if (previewZoom <= 5f) {
            for (int g = 0; g <= 10; g++) {
                float gx = frameX + frameW * g / 10f;
                float gy = frameY + frameH * g / 10f;
                vLine(graphics, gx, Math.max(py, frameY), Math.min(py + ph, frameY + frameH), 0x30FFFFFF);
                hLine(graphics, gy, Math.max(px, frameX), Math.min(px + pw, frameX + frameW), 0x30FFFFFF);
            }
        }
        // frame 边框
        rectBorder(graphics, (int) frameX, (int) frameY, (int) frameW, (int) frameH, 0xFFAAAAAA);

        graphics.text(font, "(0,0)", (int) frameX + 2, (int) frameY + 2, 0xFFFFFFFF);
        graphics.text(font, "(1,0)", (int) (frameX + frameW) - 28, (int) frameY + 2, 0xFFFFFFFF);
        graphics.text(font, "(0,1)", (int) frameX + 2, (int) (frameY + frameH) - 12, 0xFFFFFFFF);
        graphics.text(font, screen.name(), (int) frameX + 4, (int) (frameY + frameH) - 14, 0xFFFF66AA);

        graphics.disableScissor();
    }

    // ==================== 覆盖层绘制 ====================

    private void drawScreenOverlay(GuiGraphicsExtractor g, VideoScreenData s, UvTransform localUv,
                                   int fillColor, int edgeColor, int pointColor) {
        List<Vector3d> vs = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        for (var v : s.vertices()) { vs.add(v.toVector()); dists.add(v.pitch()); dists.add(v.yaw()); }

        UvTransform ut = localUv != null ? localUv : s.uvTransform();
        float su = (float) ut.scaleU(), sv = (float) ut.scaleV();
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
        if (fpu) u = 1 - u; if (fpv) v = 1 - v;
        float ru = (ca * (u - 0.5f) - sa * (v - 0.5f)) * su + ou + 0.5f;
        float rv = (sa * (u - 0.5f) + ca * (v - 0.5f)) * sv + ov + 0.5f;
        return new float[]{frameX + ru * frameW, frameY + rv * frameH};
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
    float getCU() { return uvOffsetX / 500f + 0.5f; }
    float getCV() { return uvOffsetY / 500f + 0.5f; }

    public boolean mouseClicked(int mx, int my, VideoScreenData screen) {
        if (!isInUVArea(mx, my)) return false;
        float mu = getMU(mx), mv = getMV(my);
        float cu = getCU(), cv = getCV();
        float hr = 10f / frameW;
        float dist = (float) Math.sqrt(Math.pow(mu - cu, 2) + Math.pow(mv - cv, 2));
        if (dist < hr) {
            editingMode = "move"; editStartOffsetX = uvOffsetX; editStartOffsetY = uvOffsetY;
            return true;
        }
        float[] b = computeBounds(screen);
        float[][] corners = {{b[0], b[2]}, {b[1], b[2]}, {b[0], b[3]}, {b[1], b[3]}};
        for (int i = 0; i < 4; i++) {
            if (Math.sqrt(Math.pow(mu - corners[i][0], 2) + Math.pow(mv - corners[i][1], 2)) < hr * 1.5f) {
                editingMode = "scale"; editStartScale = uvScaleU; editStartScaleV = uvScaleV;
                editStartOffsetX = uvOffsetX; editStartOffsetY = uvOffsetY; editCorner = i;
                editStartDist = (float) Math.sqrt(Math.pow(mu - cu, 2) + Math.pow(mv - cv, 2));
                return true;
            }
        }
        return false;
    }

    public void mouseDragged(int mx, int my) {
        if (editingMode == null) return;
        float mu = getMU(mx), mv = getMV(my);
        float cu = getCU(), cv = getCV();
        if ("move".equals(editingMode)) {
            uvOffsetX = editStartOffsetX + (mu - cu) * 500f;
            uvOffsetY = editStartOffsetY + (mv - cv) * 500f;
        } else if ("scale".equals(editingMode)) {
            float cd = (float) Math.sqrt(Math.pow(mu - cu, 2) + Math.pow(mv - cv, 2));
            if (editStartDist > 0.001f) {
                float ratio = Math.max(0.1f, Math.min(3f, cd / editStartDist));
                uvScaleU = editStartScale * ratio; uvScaleV = editStartScaleV * ratio;
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
        float su = uvScaleU, sv = uvScaleV;
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
        for (double[] uvs : stitched) {
            for (int i = 0; i < uvs.length / 2; i++) {
                float u = (float) uvs[i * 2], v = (float) uvs[i * 2 + 1];
                if (fpu) u = 1 - u; if (fpv) v = 1 - v;
                float ru = (ca * (u - 0.5f) - sa * (v - 0.5f)) * su + ou + 0.5f;
                float rv = (sa * (u - 0.5f) + ca * (v - 0.5f)) * sv + ov + 0.5f;
                if (ru < bounds[0]) bounds[0] = ru; if (ru > bounds[1]) bounds[1] = ru;
                if (rv < bounds[2]) bounds[2] = rv; if (rv > bounds[3]) bounds[3] = rv;
            }
        }
        return bounds;
    }

    public void markEdited() { if (lastEditedScreenId != null) ClientVideoPlaybackManager.markUvManuallyEdited(lastEditedScreenId); }

    // ---- 翻转/声道 ----

    public boolean flipClick(int mx, int my, Font font, int baseX, int baseY) {
        String prefix = "翻转: ";
        String horz = uvFlipU ? "[✓] 水平" : "[ ] 水平";
        String vert = uvFlipV ? "[✓] 垂直" : "[ ] 垂直";
        int xU = baseX + font.width(prefix);
        int xV = xU + font.width(horz + "  ");
        if (mx >= xU && mx <= xU + font.width(horz) && my >= baseY && my <= baseY + 12) { uvFlipU = !uvFlipU; markEdited(); return true; }
        if (mx >= xV && mx <= xV + font.width(vert) && my >= baseY && my <= baseY + 12) { uvFlipV = !uvFlipV; markEdited(); return true; }
        return false;
    }

    public boolean channelClick(int mx, int my, Font font, int baseX, int baseY,
                                 VideoPlayerData player, VideoScreenData screen) {
        ScreenChannelState ch = screen.channelState();
        String prefix = "声道: ";
        String left  = ch.leftEnabled()  ? "[✓] 左" : "[ ] 左";
        String right = ch.rightEnabled() ? "[✓] 右" : "[ ] 右";
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
