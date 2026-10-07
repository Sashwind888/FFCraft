package sashwind.mc.mod.ffcraft.common.model;

import org.joml.Vector3d;

public record ScreenVertex(
        double x,
        double y,
        double z,
        double pitch,
        double yaw
) {
    public Vector3d toVector() {
        return new Vector3d(x, y, z);
    }
}
