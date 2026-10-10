package sashwind.mc.mod.ffcraft.client.player;

import org.joml.Vector2d;
import org.joml.Vector3d;

import java.util.List;

public class Projection3DHelper {

    /**
     * 计算屏幕基准点（底端中心点）：
     * X = 平均值(X)
     * Z = 平均值(Z)
     * Y = 最小值(Y)
     */
    public static Vector3d calculateBottomCenter(List<Vector3d> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return new Vector3d(0, 0, 0);
        }
        double sumX = 0;
        double sumZ = 0;
        double minY = Double.MAX_VALUE;

        for (Vector3d v : vertices) {
            sumX += v.x;
            sumZ += v.z;
            if (v.y < minY) {
                minY = v.y;
            }
        }
        return new Vector3d(sumX / vertices.size(), minY, sumZ / vertices.size());
    }

    /**
     * 独立的 3D 球面/极坐标 UV 转换逻辑
     * @param vertexPos 顶点的 3D 世界坐标
     * @param basePoint 屏幕底端中心基准点
     * @param is3D 是否开启 3D 全景模式
     * @param baseU 基础 (原始/重写后) UV 的 U 坐标
     * @param baseV 基础 (原始/重写后) UV 的 V 坐标
     * @return 最终变换后的 (u, v) 坐标
     */
    public static Vector2d apply3DUv(Vector3d vertexPos, Vector3d basePoint, boolean is3D, double baseU, double baseV) {
        if (!is3D) {
            return new Vector2d(baseU, baseV);
        }

        // 计算顶点相对于底端中心基准点的方向向量
        double dx = vertexPos.x - basePoint.x;
        double dy = vertexPos.y - basePoint.y;
        double dz = vertexPos.z - basePoint.z;

        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            return new Vector2d(baseU, baseV);
        }

        dx /= len;
        dy /= len;
        dz /= len;

        // 计算极坐标角度
        double theta = Math.atan2(dx, dz); // [-PI, PI]
        double phi = Math.asin(Math.max(-1.0, Math.min(1.0, dy))); // [-PI/2, PI/2]

        // 将极坐标角度归一化映射到 [0, 1] 视轨
        double u = (theta + Math.PI) / (2.0 * Math.PI);
        double v = (phi + Math.PI / 2.0) / Math.PI;

        return new Vector2d(u, v);
    }
}
