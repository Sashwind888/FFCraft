package sashwind.mc.mod.ffcraft.client.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public class Three2Flat {

    // 参数设置
    static double minD = (double) 1 /16 - 0.001;

    /**
     * 从点集拆分面
     *
     * @param points 点集，不闭合，必须大于3个 <br/>
     *                 示例：abcdef<br/>
     *                 a - b<br/>
     *                 |    |<br/>
     *                 f - c<br/>
     *                 |    |<br/>
     *                 e - d<br/>
     * @param distances 可空参数，存储每个点对应的玩家朝向，格式：rx,ry,rx,ry.... (n = points.size() * 2)
     * @return 切分后的面，类型存在于子类
     *
     */
    public static List<Plane> splitPlanes(List<Vector3d> points, @Nullable List<Double> distances) {
        int n = points.size();
        if (n < 3) throw new IllegalArgumentException("至少需要3个点");

        List<Vector3d> pointList = new ArrayList<>(points);
        List<Double> distanceList = distances != null ? new ArrayList<>(distances) : null;

        List<Plane> planes = new ArrayList<>();
        int a = n - 1;
        int b = 0;
        int c = 1;

        boolean[] isUsed = new boolean[n];

        Vector3d normal = null;

        try {normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));}
        catch (RuntimeException e) {
            int attempts = 0;
            while (attempts < n && normal == null) {
                attempts++;
                a = (a + 1) % n;
                b = (b + 1) % n;
                c = (c + 1) % n;
                try {normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));}
                catch (RuntimeException ignore) {}
            }
            if (normal == null) return List.of();

            Collections.rotate(pointList, b);
            if (distanceList != null) Collections.rotate(distanceList, b*2);
            a = n - 1;
            b = 0;
            c = 1;
        }

        while (true) {
            try {normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));}
            catch (RuntimeException e) {
                int attempts = 0;
                while (attempts < n) {
                    attempts++;
                    c = (c + 1) % n;
                    try {normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));break;}
                    catch (RuntimeException ignore) {}
                }
                if (normal == null) break;
            }

            List<Vector3d> l_points = new ArrayList<>();
            List<Double> l_distance = new ArrayList<>();

            for (Vector3d p : pointList) {
                int idx = pointList.indexOf(p);
                if (isUsed[idx]) continue;
                if (pointToPlaneDistance(p, pointList.get(b), normal) < minD) {
                    l_points.add(p);
                    isUsed[idx] = true;
                    if (distanceList != null) {
                        l_distance.add(distanceList.get(idx * 2));
                        l_distance.add(distanceList.get(idx * 2 + 1));
                    }
                }
            }

            if (l_points.size() >= 3) {
                mergeCollinearPoints(l_points, l_distance);
                if (l_points.size() >= 3) {
                    try {
                        planes.add(new Plane(l_points, l_distance));
                    } catch (Exception e) {
                        System.err.println("[Three2Flat] Error creating plane: " + e.getMessage());
                    }
                }
            }

            boolean l_isUsed = true;
            for (boolean use : isUsed) {
                if (!use) {l_isUsed = false;break;}
            }

            if (l_isUsed) break;

            for (Vector3d p : l_points) {
                if (pointList.indexOf(p) == pointList.size() - 1) continue;
                try {
                    if (!(pointList.indexOf(p) == pointList.indexOf(l_points.get(l_points.indexOf(p) + 1)) + 1 || pointList.indexOf(p) == pointList.indexOf(l_points.get(l_points.indexOf(p) + 1)) - 1)) {
                        a = pointList.indexOf(p);
                        b = pointList.indexOf(l_points.get(l_points.indexOf(p) + 1));
                        c = a + 1;
                        break;
                    }
                } catch (Exception e) {
                    System.err.println("[Three2Flat] Error finding edge: " + e.getMessage());
                }
            }

            isUsed[a] = false;
            isUsed[b] = false;

        }

        return planes;
    }

    /*public static List<Plane> splitPlanes(List<Vector3d> points, @Nullable List<Double> distances) {
        int n = points.size();
        if (n < 3) throw new IllegalArgumentException("至少需要3个点");

        System.out.println("========== 开始 splitPlanes ==========");
        System.out.println("总点数: " + n);
        for (int i = 0; i < n; i++) {
            System.out.println("  points[" + i + "] = " + points.get(i));
        }
        if (distances != null) {
            System.out.println("distances 长度: " + distances.size());
        } else {
            System.out.println("distances 为 null");
        }

        List<Plane> planes = new ArrayList<>();
        int a = n - 1;
        int b = 0;
        int c = 1;

        boolean[] isUsed = new boolean[n];
        List<Vector3d> pointList = points;
        Vector3d normal = null;

        System.out.println("\n--- 初始基准 ---");
        System.out.println("a=" + a + ", b=" + b + ", c=" + c);

        try {
            normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));
            System.out.println("初始基准有效，法向量=" + normal);
        } catch (RuntimeException e) {
            System.out.println("初始基准共线，开始滚动...");
            while (true) {
                a = (a + 1) % n;
                b = (b + 1) % n;
                c = (c + 1) % n;
                System.out.println("  尝试 a=" + a + ", b=" + b + ", c=" + c);
                try {
                    normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));
                    System.out.println("  找到有效基准，法向量=" + normal);
                    break;
                } catch (RuntimeException ignore) {
                    System.out.println("  共线，继续滚动");
                }
            }
            System.out.println("旋转列表，偏移量 b=" + b);
            Collections.rotate(pointList, b);
            a = n - 1;
            b = 0;
            c = 1;
            System.out.println("旋转后 a=" + a + ", b=" + b + ", c=" + c);
            try {
                normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));
                System.out.println("旋转后法向量=" + normal);
            } catch (RuntimeException e2) {
                System.err.println("旋转后仍共线");
                return planes;
            }
        }

        int round = 0;
        while (true) {
            round++;
            System.out.println("\n========== 第 " + round + " 轮 ==========");
            System.out.println("a=" + a + ", b=" + b + ", c=" + c);
            System.out.println("isUsed: " + Arrays.toString(isUsed));

            try {
                normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));
                System.out.println("法向量=" + normal);
            } catch (RuntimeException e) {
                System.out.println("基准共线，滑动 c...");
                while (true) {
                    c = (c + 1) % n;
                    System.out.println("  尝试 c=" + c);
                    try {
                        normal = calculateNormal(pointList.get(a), pointList.get(b), pointList.get(c));
                        System.out.println("  找到有效 c，法向量=" + normal);
                        break;
                    } catch (RuntimeException ignore) {
                        System.out.println("  共线，继续滑动");
                    }
                }
            }

            List<Vector3d> l_points = new ArrayList<>();
            List<Double> l_distance = new ArrayList<>();

            System.out.println("\n--- 收集点 ---");
            for (Vector3d p : pointList) {
                int idx = pointList.indexOf(p);
                if (isUsed[idx]) {
                    System.out.println("  跳过已使用: idx=" + idx + " " + p);
                    continue;
                }
                double d = pointToPlaneDistance(p, pointList.get(b), normal);
                System.out.println("  idx=" + idx + " " + p + " 距离=" + d + (d < minD ? " ✅ 加入" : " ❌ 跳过"));
                if (d < minD) {
                    l_points.add(p);
                    isUsed[idx] = true;
                    if (distances != null && idx * 2 + 1 < distances.size()) {
                        l_distance.add(distances.get(idx * 2));
                        l_distance.add(distances.get(idx * 2 + 1));
                    } else if (distances != null) {
                        System.err.println("警告: distances 长度不足，idx=" + idx + ", size=" + distances.size());
                    }
                }
            }

            System.out.println("\n本轮收集到 " + l_points.size() + " 个点:");
            for (int i = 0; i < l_points.size(); i++) {
                int idx = pointList.indexOf(l_points.get(i));
                System.out.println("  [" + i + "] 原始索引=" + idx + ", 点=" + l_points.get(i));
            }

            // 检查收集到的点数是否足够
            if (l_points.size() < 3) {
                System.out.println("收集到的点不足3个，无法构成面，切分结束");
                // 回退已标记的点（因为你原本没有回退逻辑，这里保持原样，只加日志）
                // 但为了不污染后续（虽然不会有后续），直接跳出循环
                break;
            }

            planes.add(new Plane(l_points, l_distance));
            System.out.println("创建平面 #" + planes.size() + ", 点数=" + l_points.size());

            boolean allUsed = true;
            for (boolean u : isUsed) {
                if (!u) { allUsed = false; break; }
            }
            System.out.println("allUsed=" + allUsed);
            if (allUsed) {
                System.out.println("所有点已分配，退出循环");
                break;
            }

            System.out.println("\n--- 寻找边界边 ---");
            int edgeA = -1, edgeB = -1;

            System.out.println("l_points.size()=" + l_points.size());
            for (int i = 0; i < l_points.size() - 1; i++) {
                int curIdx = pointList.indexOf(l_points.get(i));
                int nextIdx = pointList.indexOf(l_points.get(i + 1));
                System.out.println("  [" + i + "] curIdx=" + curIdx + ", nextIdx=" + nextIdx + ", 差值=" + (nextIdx - curIdx));
                if (nextIdx - curIdx > 1) {
                    if ((curIdx == a && nextIdx == b) || (curIdx == b && nextIdx == a)) {
                        System.out.println("    是共享边 (a,b)，跳过");
                        continue;
                    }
                    edgeA = curIdx;
                    edgeB = nextIdx;
                    System.out.println("  找到内部边界边: (" + edgeA + "," + edgeB + ")");
                    break;
                }
            }

            if (edgeA == -1) {
                System.out.println("内部未找到，检查首尾");
                int firstIdx = pointList.indexOf(l_points.get(0));
                int lastIdx = pointList.indexOf(l_points.get(l_points.size() - 1));
                int gap = (firstIdx + n - lastIdx) % n;
                System.out.println("  firstIdx=" + firstIdx + ", lastIdx=" + lastIdx + ", gap=" + gap);
                if (gap > 1) {
                    if (!((lastIdx == a && firstIdx == b) || (lastIdx == b && firstIdx == a))) {
                        edgeA = lastIdx;
                        edgeB = firstIdx;
                        System.out.println("  找到环形边界边: (" + edgeA + "," + edgeB + ")");
                    } else {
                        System.out.println("  是共享边，跳过");
                    }
                }
            }

            if (edgeA != -1) {
                a = edgeA;
                b = edgeB;
                c = (b + 1) % n;
                System.out.println("更新基准: a=" + a + ", b=" + b + ", c=" + c);
                isUsed[a] = false;
                isUsed[b] = false;
                System.out.println("重置共享边 used[" + a + "]=false, used[" + b + "]=false");
            } else {
                System.out.println("未找到边界边，退出循环");
                break;
            }
        }

        System.out.println("\n========== 切面完成，共 " + planes.size() + " 个面 ==========");
        for (int i = 0; i < planes.size(); i++) {
            System.out.println("  Plane #" + i + ": " + planes.get(i).points);
        }
        return planes;
    }*/

    // ============================ 工具类 =========================

    private static void mergeCollinearPoints(List<Vector3d> points, @Nullable List<Double> distances) {
        if (points.size() <= 3) return;

        double epsilon = 0.0001;
        boolean changed = true;
        int iterations = 0;

        while (changed && iterations < 100) {
            changed = false;
            iterations++;

            for (int i = 0; i < points.size() - 2; i++) {
                Vector3d p0 = points.get(i);
                Vector3d p1 = points.get(i + 1);
                Vector3d p2 = points.get(i + 2);

                // 用叉积检测三点共线（比距离比较更可靠）
                double ax = p1.x - p0.x, ay = p1.y - p0.y, az = p1.z - p0.z;
                double bx = p2.x - p0.x, by = p2.y - p0.y, bz = p2.z - p0.z;
                double cx = ay * bz - az * by;
                double cy = az * bx - ax * bz;
                double cz = ax * by - ay * bx;
                double crossLen = Math.sqrt(cx * cx + cy * cy + cz * cz);

                if (crossLen < epsilon) {
                    // p1在p0-p2连线上，删除中间点p1
                    points.remove(i + 1);
                    if (distances != null && (i + 1) * 2 + 1 < distances.size()) {
                        distances.remove((i + 1) * 2);
                        distances.remove((i + 1) * 2); // 第二个元素已向前移位
                    }
                    changed = true;
                    break;
                }
            }
        }
    }

    /**
     * 计算法向量
     *
     * @param p1 该面上一个点
     * @param p2 该面上第二个点
     * @param p3 该面上第三个点
     * @return 法向量（X Y Z => A B C）
     */
    public static Vector3d calculateNormal(Vector3d p1, Vector3d p2, Vector3d p3) {
        // 向量 A = p2 - p1
        double ax = p2.x - p1.x;
        double ay = p2.y - p1.y;
        double az = p2.z - p1.z;
        // 向量 B = p3 - p1
        double bx = p3.x - p1.x;
        double by = p3.y - p1.y;
        double bz = p3.z - p1.z;

        // 叉积 N = A × B
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;

        // 归一化
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) throw new RuntimeException("三点共线！"); // 防止三点共线
        return new Vector3d(nx / len, ny / len, nz / len);
    }

    /**
     * 计算点到面距离
     *
     * @param point 要计算的点
     * @param planePoint 平面上一点
     * @param normal 面法向量
     * @return 距离
     */
    public static double pointToPlaneDistance(Vector3d point, Vector3d planePoint, Vector3d normal) {
        // 向量 V = point - planePoint
        double vx = point.x - planePoint.x;
        double vy = point.y - planePoint.y;
        double vz = point.z - planePoint.z;

        // 点积 V · N
        double dot = vx * normal.x + vy * normal.y + vz * normal.z;

        // 法向量长度 |N|
        double nLen = Math.sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
        if (nLen == 0) return 0; // 防止法向量为零向量

        return Math.abs(dot) / nLen;
    }

    public static List<float[]> projectAllPlanesTo2D(List<Vector3d> vertices, List<Double> distances) {
        List<Plane> planes = splitPlanes(vertices, distances);
        List<float[]> result = new ArrayList<>();
        for (Plane p : planes) {
            float[] uv = new float[p.uvs.length];
            for (int i = 0; i < p.uvs.length; i++) {
                uv[i] = (float) p.uvs[i];
            }
            result.add(uv);
        }
        return result;
    }

    /**
     * Splits the polygon into planes, then stitches their 2D projections
     * using the actual cut-edge adjacency from the split algorithm.
     * Returns a single float[] of all stitched 2D vertex coordinates.
     */
    public static List<double[]> getStitchedUVs(List<Vector3d> vertices, List<Double> distances) {
        List<Plane> planes = splitPlanes(vertices, distances);
        if (planes.isEmpty()) return List.of();
        if (planes.size() == 1) {
            // single plane: return as-is
            double[] uv = new double[planes.get(0).uvs.length];
            System.arraycopy(planes.get(0).uvs, 0, uv, 0, uv.length);
            return List.of(uv);
        }

        // track which original vertices each plane contains
        List<Set<Integer>> planeIndices = new ArrayList<>();
        for (Plane p : planes) {
            Set<Integer> indices = new HashSet<>();
            for (Vector3d pt : p.points) {
                int idx = vertices.indexOf(pt);
                if (idx >= 0) indices.add(idx);
            }
            planeIndices.add(indices);
        }

        // find shared edges between consecutive planes
        List<double[]> stitched = new ArrayList<>();
        stitched.add(planes.get(0).uvs.clone());

        for (int pi = 1; pi < planes.size(); pi++) {
            Set<Integer> prevSet = planeIndices.get(pi - 1);
            Set<Integer> curSet = planeIndices.get(pi);

            // find shared vertices
            List<Integer> shared = new ArrayList<>();
            for (int idx : prevSet) {
                if (curSet.contains(idx)) shared.add(idx);
            }

            if (shared.size() >= 2) {
                Plane prev = planes.get(pi - 1);
                Plane cur = planes.get(pi);
                Vector3d sv0 = vertices.get(shared.get(0));
                Vector3d sv1 = vertices.get(shared.get(1));

                int prevI0 = prev.points.indexOf(sv0);
                int prevI1 = prev.points.indexOf(sv1);
                int curI0 = cur.points.indexOf(sv0);
                int curI1 = cur.points.indexOf(sv1);

                if (prevI0 >= 0 && prevI1 >= 0 && curI0 >= 0 && curI1 >= 0) {
                    double[] prevUvs = stitched.get(pi - 1);
                    double[] curUvsRaw = cur.uvs;

                    double pdu = prevUvs[prevI1 * 2] - prevUvs[prevI0 * 2];
                    double pdv = prevUvs[prevI1 * 2 + 1] - prevUvs[prevI0 * 2 + 1];
                    double cdu = curUvsRaw[curI1 * 2] - curUvsRaw[curI0 * 2];
                    double cdv = curUvsRaw[curI1 * 2 + 1] - curUvsRaw[curI0 * 2 + 1];

                    double pAngle = Math.atan2(pdv, pdu);
                    double cAngle = Math.atan2(cdv, cdu);
                    double diff = pAngle - cAngle;
                    while (diff > Math.PI) diff -= 2 * Math.PI;
                    while (diff < -Math.PI) diff += 2 * Math.PI;

                    double cosA = Math.cos(diff), sinA = Math.sin(diff);
                    double cx = curUvsRaw[curI0 * 2], cy = curUvsRaw[curI0 * 2 + 1];
                    double px = prevUvs[prevI0 * 2], py = prevUvs[prevI0 * 2 + 1];

                    double[] aligned = new double[curUvsRaw.length];
                    for (int i = 0; i < curUvsRaw.length / 2; i++) {
                        double u = curUvsRaw[i * 2] - cx, v = curUvsRaw[i * 2 + 1] - cy;
                        aligned[i * 2] = cosA * u - sinA * v + px;
                        aligned[i * 2 + 1] = sinA * u + cosA * v + py;
                    }

                    // overlap check
                    double pcu = 0, pcv = 0;
                    for (int i = 0; i < prevUvs.length / 2; i++) {
                        if (i != prevI0 && i != prevI1) { pcu += prevUvs[i*2]; pcv += prevUvs[i*2+1]; }
                    }
                    pcu /= (prevUvs.length/2 - 2); pcv /= (prevUvs.length/2 - 2);

                    double acu = 0, acv = 0;
                    int acount = 0;
                    for (int i = 0; i < aligned.length / 2; i++) {
                        if (i != curI0 && i != curI1) { acu += aligned[i*2]; acv += aligned[i*2+1]; acount++; }
                    }
                    if (acount > 0) { acu /= acount; acv /= acount; }

                    double eu = prevUvs[prevI1*2] - prevUvs[prevI0*2];
                    double ev = prevUvs[prevI1*2+1] - prevUvs[prevI0*2+1];
                    double ox = prevUvs[prevI0*2], oy = prevUvs[prevI0*2+1];
                    double ps = eu * (pcv - oy) - ev * (pcu - ox);
                    double ns = eu * (acv - oy) - ev * (acu - ox);

                    if (ps * ns > 0) {
                        double mx = (prevUvs[prevI0*2] + prevUvs[prevI1*2]) / 2;
                        double my = (prevUvs[prevI0*2+1] + prevUvs[prevI1*2+1]) / 2;
                        for (int i = 0; i < aligned.length / 2; i++) {
                            aligned[i*2] = 2*mx - aligned[i*2];
                            aligned[i*2+1] = 2*my - aligned[i*2+1];
                        }
                    }
                    stitched.add(aligned);
                    continue;
                }
            }
            // fallback: use raw UVs
            stitched.add(planes.get(pi).uvs.clone());
        }
        return stitched;
    }

    public static List<List<Vector3d>> getPlanePoints(List<Vector3d> vertices, List<Double> distances) {
        List<Plane> planes = splitPlanes(vertices, distances);
        List<List<Vector3d>> result = new ArrayList<>();
        for (Plane p : planes) {
            result.add(new ArrayList<>(p.points));
        }
        return result;
    }
}

