package sashwind.mc.mod.ffcraft.client.player;

import earcut4j.Earcut;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

public class Plane {
    public List<Vector3d> points = new ArrayList<>();
    public double[] uvs;
    public List<Integer> earcutResultIDs = new ArrayList<>();
    public List<Double> distances = new ArrayList<>();

    public Plane(List<Vector3d> points, List<Double> distances) {
        this.distances = distances;
        this.points = new ArrayList<>(points);
        if (shouldReverse(this.points, distances.stream().mapToDouble(Double::doubleValue).toArray())) {
            java.util.Collections.reverse(this.points);
        }
        this.uvs = D3D22D(this.points);
        this.earcutResultIDs = earcutFn(this.uvs);
    }

    private static double[] D3D22D(List<Vector3d> points) {
        if (points.size() < 3) throw new IllegalArgumentException("至少需要3个点");
        Vector3d normal = Three2Flat.calculateNormal(points.get(0), points.get(1), points.get(2));
        Vector3d origin = points.get(0);
        Vector3d uAxis = new Vector3d(points.get(1)).sub(origin).normalize();
        Vector3d vAxis = new Vector3d(normal).cross(uAxis).normalize();
        uAxis = new Vector3d(vAxis).cross(normal).normalize();

        double[] coords = new double[points.size() * 2];
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;

        for (int i = 0; i < points.size(); i++) {
            Vector3d rel = new Vector3d(points.get(i)).sub(origin);
            double u = rel.dot(uAxis);
            double v = rel.dot(vAxis);
            coords[2 * i] = u;
            coords[2 * i + 1] = v;
            if (u < minU) minU = u; if (u > maxU) maxU = u;
            if (v < minV) minV = v; if (v > maxV) maxV = v;
        }

        double sizeU = maxU - minU;
        double sizeV = maxV - minV;
        if (sizeU < 0.0001) sizeU = 1.0;
        if (sizeV < 0.0001) sizeV = 1.0;

        for (int i = 0; i < points.size(); i++) {
            coords[2 * i] = (coords[2 * i] - minU) / sizeU;
            coords[2 * i + 1] = (coords[2 * i + 1] - minV) / sizeV;
        }
        return coords;
    }

    private static List<Integer> earcutFn(double[] coords) {
        return Earcut.earcut(coords, null, 2);
    }

    public static boolean shouldReverse(List<Vector3d> points, double[] distances) {
        if (points.size() < 3 || distances.length < 2) return false;
        Vector3d normal;
        try {
            normal = Three2Flat.calculateNormal(points.get(0), points.get(1), points.get(2));
        } catch (RuntimeException e) {
            return false;
        }

        double[] avg = averageAngles(distances);
        float pitch = (float) avg[0];
        float yaw = (float) avg[1];
        float radPitch = (float) Math.toRadians(pitch);
        float radYaw = (float) Math.toRadians(yaw);
        double x = -Math.sin(radYaw) * Math.cos(radPitch);
        double y = -Math.sin(radPitch);
        double z =  Math.cos(radYaw) * Math.cos(radPitch);
        Vector3d targetDir = new Vector3d(x, y, z).normalize();

        double dot = normal.dot(targetDir);
        return dot > 0;
    }

    private static double[] averageAngles(double[] distances) {
        if (distances.length < 4) return new double[]{distances[0], distances[1]};
        double sumPitch = 0, sumYaw = 0;
        for (int i = 0; i < distances.length; i += 2) {
            sumPitch += distances[i];
            sumYaw += distances[i+1];
        }
        return new double[]{sumPitch / ((double) distances.length / 2), sumYaw / ((double) distances.length / 2)};
    }

    // --- stitching helpers ---

    public int[] findSharedEdge(Plane other) {
        // Step 1: find matching vertex pairs between the two planes
        List<int[]> matches = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int k = 0; k < other.points.size(); k++) {
                if (points.get(i).distance(other.points.get(k)) < 0.01) {
                    matches.add(new int[]{i, k});
                }
            }
        }

        // Step 2: find which edge in EACH plane connects two matching vertices
        double bestLen = -1;
        int[] bestEdge = null;

        for (int mi = 0; mi < matches.size(); mi++) {
            for (int mj = mi + 1; mj < matches.size(); mj++) {
                int thisI = matches.get(mi)[0];
                int thisJ = matches.get(mj)[0];
                int otherI = matches.get(mi)[1];
                int otherJ = matches.get(mj)[1];

                // check if thisI and thisJ are consecutive in this plane
                boolean thisConsecutive = (thisJ == (thisI + 1) % points.size()) || (thisI == (thisJ + 1) % points.size());
                // check if otherI and otherJ are consecutive in other plane
                boolean otherConsecutive = (otherJ == (otherI + 1) % other.points.size()) || (otherI == (otherJ + 1) % other.points.size());

                if (thisConsecutive && otherConsecutive) {
                    double len = points.get(thisI).distance(points.get(thisJ));
                    if (len > bestLen) {
                        bestLen = len;
                        if (points.get(thisI).distance(other.points.get(otherI)) < 0.01 &&
                            points.get(thisJ).distance(other.points.get(otherJ)) < 0.01) {
                            bestEdge = new int[]{thisI, thisJ, otherI, otherJ};
                        } else {
                            bestEdge = new int[]{thisI, thisJ, otherJ, otherI};
                        }
                    }
                }
            }
        }

        return bestEdge;
    }

    public static List<double[]> stitchAllPlanes(List<Plane> planes) {
        if (planes.isEmpty()) return List.of();
        List<double[]> stitched = new ArrayList<>();
        double[] first = planes.get(0).uvs.clone();
        stitched.add(first);

        for (int pi = 1; pi < planes.size(); pi++) {
            Plane prev = planes.get(pi - 1);
            Plane cur = planes.get(pi);
            int[] edge = prev.findSharedEdge(cur);
            double[] prevUvs = stitched.get(pi - 1);
            double[] curUvsRaw = cur.uvs;

            if (edge != null) {
                int prevI = edge[0], prevJ = edge[1];
                int curK = edge[2], curL = edge[3];

                double prevDu = prevUvs[prevJ * 2] - prevUvs[prevI * 2];
                double prevDv = prevUvs[prevJ * 2 + 1] - prevUvs[prevI * 2 + 1];
                double curDu = curUvsRaw[curL * 2] - curUvsRaw[curK * 2];
                double curDv = curUvsRaw[curL * 2 + 1] - curUvsRaw[curK * 2 + 1];

                double prevAngle = Math.atan2(prevDv, prevDu);
                double curAngle = Math.atan2(curDv, curDu);
                double angleDiff = prevAngle - curAngle;
                while (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
                while (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

                double cosA = Math.cos(angleDiff);
                double sinA = Math.sin(angleDiff);

                double cx = curUvsRaw[curK * 2];
                double cy = curUvsRaw[curK * 2 + 1];
                double px = prevUvs[prevI * 2];
                double py = prevUvs[prevI * 2 + 1];

                double[] aligned = new double[curUvsRaw.length];
                for (int i = 0; i < curUvsRaw.length / 2; i++) {
                    double u = curUvsRaw[i * 2] - cx;
                    double v = curUvsRaw[i * 2 + 1] - cy;
                    double ru = cosA * u - sinA * v;
                    double rv = sinA * u + cosA * v;
                    aligned[i * 2] = ru + px;
                    aligned[i * 2 + 1] = rv + py;
                }

                double cPrevU = 0, cPrevV = 0, cAlignedU = 0, cAlignedV = 0;
                for (int i = 0; i < prevUvs.length / 2; i++) {
                    if (i != prevI && i != prevJ) { cPrevU += prevUvs[i*2]; cPrevV += prevUvs[i*2+1]; }
                }
                int nPrevNonShared = prevUvs.length/2 - 2;
                if (nPrevNonShared > 0) { cPrevU /= nPrevNonShared; cPrevV /= nPrevNonShared; }

                for (int i = 0; i < aligned.length / 2; i++) {
                    if (i != curK) { cAlignedU += aligned[i*2]; cAlignedV += aligned[i*2+1]; }
                }
                int nCurNonShared = aligned.length/2 - 1;
                if (nCurNonShared > 0) { cAlignedU /= nCurNonShared; cAlignedV /= nCurNonShared; }

                double eu = prevUvs[prevJ*2] - prevUvs[prevI*2];
                double ev = prevUvs[prevJ*2+1] - prevUvs[prevI*2+1];
                double ox = prevUvs[prevI*2], oy = prevUvs[prevI*2+1];
                double prevCross = eu * (cPrevV - oy) - ev * (cPrevU - ox);
                double curCross  = eu * (cAlignedV - oy) - ev * (cAlignedU - ox);

                boolean sameSide = prevCross * curCross > 0;
                boolean curNearOrigin = Math.abs(curCross) < Math.abs(prevCross) * 0.1;

                if (sameSide || curNearOrigin) {
                    double mx = (prevUvs[prevI*2] + prevUvs[prevJ*2]) / 2;
                    double my = (prevUvs[prevI*2+1] + prevUvs[prevJ*2+1]) / 2;
                    for (int i = 0; i < aligned.length / 2; i++) {
                        aligned[i*2] = 2*mx - aligned[i*2];
                        aligned[i*2+1] = 2*my - aligned[i*2+1];
                    }
                }

                stitched.add(aligned);
            } else {
                stitched.add(curUvsRaw.clone());
            }
        }
        return stitched;
    }
}
