package sashwind.mc.mod.ffcraft.client.player;

import org.joml.Vector3d;
import sashwind.mc.mod.drawlib.client.Vertex;
import sashwind.mc.mod.drawlib.client.WorldDraw;

import java.util.ArrayList;
import java.util.List;

/**
 * 专属于 3D 球形屏幕的渲染对象，与 2D 平面多边形 Screen 彻底解耦。
 * 绝不调用 Three2Flat 或任何多边形 UV 剖分逻辑。
 */
public class SphereScreen {
    private final List<Vertex> computedVertices = new ArrayList<>();
    public String name;

    public SphereScreen(Vector3d bottomPoint, double radius, double uvOffU, double uvOffV, double uvScaleU, double uvScaleV, boolean flipU, boolean flipV) {
        System.out.printf("[FFCraft SphereScreen] 创建球形屏幕: bottomPoint=%s, radius=%.2f, flipU=%b, flipV=%b%n",
                bottomPoint, radius, flipU, flipV);
        if (bottomPoint != null) {
            List<Vertex> rawSphere = SphereMeshGenerator.generateInsideSphereVertices(bottomPoint, radius, flipU, flipV);
            for (Vertex v : rawSphere) {
                // 球形全景视频：直接无脑满幅映射，从左上角(0,0)到右下角(1,1)，不受任何2D宽高比或外部scale偏移干扰
                computedVertices.add(new Vertex(
                        v.x, v.y, v.z,
                        v.r, v.g, v.b, v.a,
                        v.u, v.v,
                        15, v.nx, v.ny, v.nz
                ));
            }
            System.out.printf("[FFCraft SphereScreen] 成功生成球体顶点数: %d%n", computedVertices.size());
        }
    }

    public void writeVertices(WorldDraw target) {
        System.out.printf("[FFCraft SphereScreen] 向 WorldDraw 写入球体顶点: %d 个顶点%n", computedVertices.size());
        for (Vertex v : computedVertices) {
            target.addVertices(v.x, v.y, v.z, 15, v.u, v.v, v.r, v.g, v.b, v.a);
        }
    }

    public void close() {
        computedVertices.clear();
    }
}
