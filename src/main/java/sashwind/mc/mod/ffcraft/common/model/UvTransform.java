package sashwind.mc.mod.ffcraft.common.model;

public record UvTransform(
        double offsetU,
        double offsetV,
        double scaleU,
        double scaleV,
        double rotationDegrees,
        boolean flipU,
        boolean flipV
) {
    public static UvTransform createDefault() {
        return new UvTransform(0.0D, 0.0D, 1.0D, 1.0D, 0.0D, false, true);
    }

    public UvTransform(double offsetU, double offsetV, double scaleU, double scaleV, double rotationDegrees) {
        this(offsetU, offsetV, scaleU, scaleV, rotationDegrees, false, true);
    }
}