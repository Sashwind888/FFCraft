package sashwind.mc.mod.ffcraft.client.player;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryUtil;

import sashwind.mc.mod.drawlib.client.Vertex;
import sashwind.mc.mod.drawlib.client.WorldDraw;

import java.util.ArrayList;
import java.util.List;

public class Screen {
    private final List<Vector3d> vertices = new ArrayList<>();
    private final List<Vertex> computedVertices = new ArrayList<>();
    public String name;

    private static NativeImage cachedPlaceholder;

    public Screen(List<Vector3d> vertices, List<Double> distances,
                  double uvOffU, double uvOffV, double uvScaleU, double uvScaleV, double uvRotDeg) {
        this(vertices, distances, uvOffU, uvOffV, uvScaleU, uvScaleV, uvRotDeg, false, false, false);
    }

    public Screen(List<Vector3d> vertices, List<Double> distances,
                  double uvOffU, double uvOffV, double uvScaleU, double uvScaleV, double uvRotDeg,
                  boolean flipU, boolean flipV) {
        this(vertices, distances, uvOffU, uvOffV, uvScaleU, uvScaleV, uvRotDeg, flipU, flipV, false);
    }

    public Screen(List<Vector3d> vertices, List<Double> distances,
                  double uvOffU, double uvOffV, double uvScaleU, double uvScaleV, double uvRotDeg,
                  boolean flipU, boolean flipV, boolean is3D) {
        this(vertices, distances, uvOffU, uvOffV, uvScaleU, uvScaleV, uvRotDeg, flipU, flipV, is3D, 16.0 / 9.0);
    }

    public Screen(List<Vector3d> vertices, List<Double> distances,
                  double uvOffU, double uvOffV, double uvScaleU, double uvScaleV, double uvRotDeg,
                  boolean flipU, boolean flipV, boolean is3D, double videoAspect) {
        this.vertices.addAll(vertices);

        Vector3d basePoint = Projection3DHelper.calculateBottomCenter(vertices);

        List<Plane> planes = Three2Flat.splitPlanes(vertices, distances);
        List<double[]> stitchedUvs = Plane.stitchAllPlanes(planes);

        for (int pi = 0; pi < planes.size(); pi++) {
            Plane p = planes.get(pi);
            double[] uvs = stitchedUvs.get(pi);
            for (int idx : p.earcutResultIDs) {
                double rawU = uvs[idx * 2], rawV = uvs[idx * 2 + 1];

                // 1. 调用统一刚体坐标系进行欧拉纯角度旋转、缩放与偏移 (缩放强制等比 uvScaleU，消除旧存档残留非等比拉伸)
                org.joml.Vector2d rigid = MetricCoordinateSystem.transformRigid(
                        rawU, rawV, uvOffU, uvOffV, uvScaleU, uvScaleU, uvRotDeg, flipU, flipV);

                // 2. 映射为对应视频宽高比的 GPU 贴图采样 UV (Letterbox / Pillarbox)
                org.joml.Vector2d fittedUV = MetricCoordinateSystem.toGPU_UV(
                        rigid.x, rigid.y, videoAspect);

                Vector3d vertPos = p.points.get(idx);
                org.joml.Vector2d finalUv = Projection3DHelper.apply3DUv(vertPos, basePoint, is3D, fittedUV.x, fittedUV.y);

                computedVertices.add(new Vertex(
                        (float) vertPos.x, (float) vertPos.y, (float) vertPos.z,
                        1f, 1f, 1f, 1f,
                        (float) finalUv.x, (float) finalUv.y,
                        15, 0, 1, 0));
            }
        }
    }

    /** 将此屏幕的顶点写入目标 WorldDraw */
    public void writeVertices(WorldDraw target) {
        for (Vertex v : computedVertices) {
            target.addVertices(v.x, v.y, v.z, v.light, v.u, v.v, v.r, v.g, v.b, v.a);
        }
    }

    public void close() {
        computedVertices.clear();
        vertices.clear();
    }
}
