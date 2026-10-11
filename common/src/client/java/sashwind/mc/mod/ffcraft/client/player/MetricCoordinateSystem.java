package sashwind.mc.mod.ffcraft.client.player;

import org.joml.Vector2d;

/**
 * 真正 1:1 绝对正交的物理度量总坐标系 (Metric Coordinate System)
 * 视频中心为坐标系原点 (0, 0)。
 * 坐标系空间内 1 米横向 = 1 米纵向。
 * 屏幕多边形在其几何中心处做刚体旋转、缩放与翻转，
 * 并以视频中心为参考原点进行位置平移与贴图映射，
 * 彻底消除旋转非 90 度变菱形、角度畸变与长宽比例失真！
 */
public class MetricCoordinateSystem {

    /**
     * 在 1:1 绝对正交物理空间中对多边形执行刚体变换
     * @param rawU 多边形展开点相对几何中心的 U (已居中于 0.5)
     * @param rawV 多边形展开点相对几何中心的 V (已居中于 0.5)
     * @param offU 屏幕几何中心相对于视频中心的横向偏移
     * @param offV 屏幕几何中心相对于视频中心的纵向偏移
     * @param scaleU 横向缩放
     * @param scaleV 纵向缩放
     * @param rotDeg 刚体欧拉旋转角度（度）
     * @param flipU 水平翻转
     * @param flipV 垂直翻转
     * @return 多边形顶点在以视频中心为原点的正交度量坐标系中的坐标 (rx, ry)
     */
    public static Vector2d transformRigid(double rawU, double rawV,
                                         double offU, double offV,
                                         double scaleU, double scaleV,
                                         double rotDeg,
                                         boolean flipU, boolean flipV) {
        // 多边形相对自身几何中心的物理向量 (ox, oy)，高度跨度 1.0，宽度跨度为真实物理长宽比
        double ox = rawU;
        double oy = rawV;

        if (flipU) ox = -ox;
        if (flipV) oy = -oy;

        ox *= scaleU;
        oy *= scaleV;

        double rad = Math.toRadians(rotDeg);
        double cosA = Math.cos(rad);
        double sinA = Math.sin(rad);

        // 纯正等距刚体旋转（保持内积与正交角度恒定）
        double rx = cosA * ox - sinA * oy;
        double ry = sinA * ox + cosA * oy;

        // 平移：多边形几何中心位于视频中心的 (offU, offV) 处
        rx += offU;
        ry += offV;

        return new Vector2d(rx, ry);
    }

    /**
     * 将以视频中心为原点的正交度量坐标 (rx, ry)
     * 映射到真实视频贴图采样的 GPU 纹理 UV 坐标 (0.0 ~ 1.0)
     */
    public static Vector2d toGPU_UV(double rx, double ry, double videoAspect) {
        if (videoAspect <= 0.01) videoAspect = 16.0 / 9.0;

        // 视频在正交空间内的有效范围: 宽为 videoAspect, 高为 1.0
        // 将 [-videoAspect/2, videoAspect/2] 映射到 U in [0, 1]
        // 将 [-0.5, 0.5] 映射到 V in [0, 1]
        double finalU = (rx / videoAspect) + 0.5;
        double finalV = ry + 0.5;

        return new Vector2d(finalU, finalV);
    }

    /**
     * 将正交坐标 (rx, ry) 映射为 UI 屏幕上的实际绘制像素坐标 (px, py)
     * @param rx 正交相对 X（以视频中心为原点）
     * @param ry 正交相对 Y（以视频中心为原点）
     * @param vidCenterX 视频中心对应的屏幕物理像素 X
     * @param vidCenterY 视频中心对应的屏幕物理像素 Y
     * @param vidHeightPixels 视频在 UI 上的物理像素高度（1 个物理单位对应的像素数，保持各向同性）
     */
    public static float[] toUI_Pixels(double rx, double ry, float vidCenterX, float vidCenterY, float vidHeightPixels) {
        float px = vidCenterX + (float) (rx * vidHeightPixels);
        float py = vidCenterY + (float) (ry * vidHeightPixels);
        return new float[]{px, py};
    }

    /**
     * 重载版本：支持直接传入 frame 边界和视频宽高比
     */
    public static float[] toUI_Pixels(double rx, double ry, float frameX, float frameY, float frameW, float frameH) {
        float cx = frameX + frameW / 2.0f;
        float cy = frameY + frameH / 2.0f;
        return toUI_Pixels(rx, ry, cx, cy, frameH);
    }
}

