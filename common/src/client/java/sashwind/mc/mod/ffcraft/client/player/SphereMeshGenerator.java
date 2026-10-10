package sashwind.mc.mod.ffcraft.client.player;

import org.joml.Vector3d;
import sashwind.mc.mod.drawlib.client.Vertex;

import java.util.ArrayList;
import java.util.List;

public class SphereMeshGenerator {

    public static final int SEGMENTS = 32; // 32 经线
    public static final int RINGS = 16;   // 16 纬线

    /**
     * 生成球体内壁 QUADS 四边形网格顶点列表
     */
    public static List<Vertex> generateInsideSphereVertices(Vector3d bottomPoint, double radius, boolean flipU, boolean flipV) {
        List<Vertex> vertices = new ArrayList<>();

        // 球心坐标
        double cx = bottomPoint.x;
        double cy = bottomPoint.y + radius;
        double cz = bottomPoint.z;

        VertexPoint[][] grid = new VertexPoint[RINGS + 1][SEGMENTS + 1];

        for (int i = 0; i <= RINGS; i++) {
            double phi = (double) i / RINGS * Math.PI; // [0, PI]
            double sinPhi = Math.sin(phi);
            double cosPhi = Math.cos(phi);

            for (int j = 0; j <= SEGMENTS; j++) {
                // 当 j == SEGMENTS 时精确复用 j == 0 的角度，几何位置 100% 绝对重合
                double theta = (j == SEGMENTS) ? 0.0 : (double) j / SEGMENTS * 2.0 * Math.PI; // [0, 2PI]
                double sinTheta = Math.sin(theta);
                double cosTheta = Math.cos(theta);

                // 世界坐标 (底部在 y0)
                double x = cx + radius * sinPhi * sinTheta;
                double y = cy - radius * cosPhi;
                double z = cz + radius * sinPhi * cosTheta;

                // 内壁法线指向球心
                double nx = (cx - x) / radius;
                double ny = (cy - y) / radius;
                double nz = (cz - z) / radius;

                // 全景极坐标 UV：u 偏移 0.002，v 偏移 0.001
                // 彻底越过 GPU 边缘过滤插值可能产生的采样跳变黑边与极点裂缝
                double rawU = (double) j / SEGMENTS;
                double u = 0.0008 + rawU * (0.9992 - 0.0008);
                double rawV = (double) i / RINGS;
                double v = 0.001 + rawV * (0.999 - 0.001);

                if (flipU) u = 1.0 - u;
                if (flipV) v = 1.0 - v;

                grid[i][j] = new VertexPoint((float) x, (float) y, (float) z, (float) u, (float) v, (float) nx, (float) ny, (float) nz);
            }
        }

        // 导出 TRIANGLES 三角形面（反转环绕顶点顺序，使其正面完全朝向球体内部）
        for (int i = 0; i < RINGS; i++) {
            for (int j = 0; j < SEGMENTS; j++) {
                VertexPoint p00 = grid[i][j];
                VertexPoint p01 = grid[i][j + 1];
                VertexPoint p11 = grid[i + 1][j + 1];
                VertexPoint p10 = grid[i + 1][j];

                // 反转顺规三角形 1: p00 -> p11 -> p01
                addVertex(vertices, p00);
                addVertex(vertices, p11);
                addVertex(vertices, p01);

                // 反转顺规三角形 2: p00 -> p10 -> p11
                addVertex(vertices, p00);
                addVertex(vertices, p10);
                addVertex(vertices, p11);
            }
        }

        return vertices;
    }

    private static void addVertex(List<Vertex> list, VertexPoint vp) {
        list.add(new Vertex(
                vp.x, vp.y, vp.z,
                1f, 1f, 1f, 1f,
                vp.u, vp.v,
                15, vp.nx, vp.ny, vp.nz
        ));
    }

    private record VertexPoint(float x, float y, float z, float u, float v, float nx, float ny, float nz) {}
}
