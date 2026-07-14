package sashwind.mc.mod.ffcraft.client.player;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryUtil;
import sashwind.mc.mod.drawlib.client.WorldDraw;
import sashwind.mc.mod.drawlib.client.Vertex;

import java.util.ArrayList;
import java.util.List;

public class Screen {
    private final List<Vector3d> vertices = new ArrayList<>();
    private final List<Vertex> computedVertices = new ArrayList<>();
    public String name;

    private static NativeImage cachedPlaceholder;

    public Screen(List<Vector3d> vertices, List<Double> distances,
                  double uvOffU, double uvOffV, double uvScaleU, double uvScaleV, double uvRotDeg) {
        this(vertices, distances, uvOffU, uvOffV, uvScaleU, uvScaleV, uvRotDeg, false, false);
    }

    public Screen(List<Vector3d> vertices, List<Double> distances,
                  double uvOffU, double uvOffV, double uvScaleU, double uvScaleV, double uvRotDeg,
                  boolean flipU, boolean flipV) {
        this.vertices.addAll(vertices);

        List<Plane> planes = Three2Flat.splitPlanes(vertices, distances);
        double cosR = Math.cos(Math.toRadians(uvRotDeg));
        double sinR = Math.sin(Math.toRadians(uvRotDeg));
        List<double[]> stitchedUvs = Plane.stitchAllPlanes(planes);

        for (int pi = 0; pi < planes.size(); pi++) {
            Plane p = planes.get(pi);
            double[] uvs = stitchedUvs.get(pi);
            for (int idx : p.earcutResultIDs) {
                double u = uvs[idx * 2], v = uvs[idx * 2 + 1];
                if (flipU) u = 1.0 - u;
                if (flipV) v = 1.0 - v;
                double cu = u - 0.5, cv = v - 0.5;
                double ru = cosR * cu - sinR * cv;
                double rv = sinR * cu + cosR * cv;
                ru = ru * uvScaleU + uvOffU;
                rv = rv * uvScaleV + uvOffV;
                ru += 0.5; rv += 0.5;
                computedVertices.add(new Vertex(
                        (float) p.points.get(idx).x, (float) p.points.get(idx).y, (float) p.points.get(idx).z,
                        1f, 1f, 1f, 1f,
                        (float) ru, (float) rv,
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
