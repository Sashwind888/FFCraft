package sashwind.mc.mod.ffcraft.client.player;

import org.joml.Vector2d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 保持真实物理尺寸 (单位：方块/米) 的 2D 投影与刚体 UV 变换器。
 * 解决多边形旋转变形、长宽比失真问题，并将世界坐标精准映射到 16:9 标准画布空间。
 */
public class MetricPlaneProjection {

    public static final double STD_ASPECT = 16.0 / 9.0;

    /**
     * 将 3D 多边形顶点刚体正交投影至二维物理平面（单位：米）。
     * 保留顶点的绝对长宽比，绝不拉伸。
     */
    public static List<Vector2d> projectToMetric2D(List<Vector3d> points) {
        int n = points != null ? points.size() : 0;
        if (n < 3) return List.of();

        Vector3d normal;
        try {
            normal = Three2Flat.calculateNormal(points.get(0), points.get(1), points.get(2));
        } catch (RuntimeException e) {
            return List.of();
        }

        Vector3d origin = points.get(0);
        Vector3d uAxis = new Vector3d(points.get(1)).sub(origin).normalize();
        Vector3d vAxis = new Vector3d(normal).cross(uAxis).normalize();
        uAxis = new Vector3d(vAxis).cross(normal).normalize();

        List<Vector2d> metric2D = new ArrayList<>(n);
        for (Vector3d p : points) {
            Vector3d rel = new Vector3d(p).sub(origin);
            double xMeter = rel.dot(uAxis);
            double yMeter = rel.dot(vAxis);
            metric2D.add(new Vector2d(xMeter, yMeter));
        }

        return metric2D;
    }

    /**
     * 将真实米数二维多边形等比居中归一化到 16:9 标准画幅基准 [0, 1] x [0, 1]
     */
    public static List<Vector2d> normalizeToStandard16x9(List<Vector2d> metricPoints) {
        if (metricPoints.isEmpty()) return List.of();

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (Vector2d p : metricPoints) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }

        double widthM = Math.max(0.001, maxX - minX);
        double heightM = Math.max(0.001, maxY - minY);
        double midX = (minX + maxX) / 2.0;
        double midY = (minY + maxY) / 2.0;

        // 比较多边形物理纵横比与 16:9 画布纵横比
        double polyAspect = widthM / heightM;
        double scaleFactor;
        if (polyAspect > STD_ASPECT) {
            // 受限于宽度
            scaleFactor = 1.0 / widthM;
        } else {
            // 受限于高度
            scaleFactor = (1.0 / STD_ASPECT) / heightM;
        }

        List<Vector2d> result = new ArrayList<>(metricPoints.size());
        for (Vector2d p : metricPoints) {
            // 相对物理中心（米）
            double rx = p.x - midX;
            double ry = p.y - midY;

            // 等比缩放到 16:9 画布单位空间
            double u = rx * scaleFactor + 0.5;
            double v = ry * scaleFactor * STD_ASPECT + 0.5;
            result.add(new Vector2d(u, v));
        }

        return result;
    }

    /**
     * 刚体正交变换（平移、旋转、缩放、翻转）：
     * 在正交等比空间中旋转，旋转绝不改变多边形长宽比！
     */
    public static Vector2d transformRigid(double baseU, double baseV,
                                         double offU, double offV,
                                         double scaleU, double scaleV,
                                         double rotDeg,
                                         boolean flipU, boolean flipV) {
        double u = flipU ? (1.0 - baseU) : baseU;
        double v = flipV ? (1.0 - baseV) : baseV;

        // 转换至等比正交度量空间（以 16:9 画布中心为原点）
        double ox = (u - 0.5) * 16.0;
        double oy = (v - 0.5) * 9.0;

        // 正交刚体纯旋转
        double rad = Math.toRadians(rotDeg);
        double cosA = Math.cos(rad);
        double sinA = Math.sin(rad);

        double rx = cosA * ox - sinA * oy;
        double ry = sinA * ox + cosA * oy;

        // 缩放
        rx *= scaleU;
        ry *= scaleV;

        // 还原回 16:9 画布归一化空间并加上平移偏移
        double finalCanvasU = (rx / 16.0) + offU + 0.5;
        double finalCanvasV = (ry / 9.0) + offV + 0.5;

        return new Vector2d(finalCanvasU, finalCanvasV);
    }

    /**
     * 将 16:9 画布上的 UV 映射到实际视频（任意非 16:9 分辨率）的 Letterbox/Pillarbox UV。
     * 视频居中等比缩放到 16:9 画布内部：
     * - 超宽视频 (videoAspect > 16:9)：视频占满宽度，高度居中缩小，上下为黑边
     * - 窄屏视频 (videoAspect < 16:9)：视频占满高度，宽度居中缩小，左右为黑边
     */
    public static Vector2d applyVideoAspect(double canvasU, double canvasV, double videoAspect) {
        if (videoAspect <= 0.01) videoAspect = STD_ASPECT;

        double finalU = canvasU;
        double finalV = canvasV;

        if (videoAspect > STD_ASPECT) {
            // 超宽视频 (如 21:9)：视频高度缩至 (STD_ASPECT / videoAspect)
            double factor = videoAspect / STD_ASPECT;
            finalV = (canvasV - 0.5) * factor + 0.5;
        } else if (videoAspect < STD_ASPECT) {
            // 窄视频 (如 4:3)：视频宽度缩至 (videoAspect / STD_ASPECT)
            double factor = STD_ASPECT / videoAspect;
            finalU = (canvasU - 0.5) * factor + 0.5;
        }

        return new Vector2d(finalU, finalV);
    }
}
