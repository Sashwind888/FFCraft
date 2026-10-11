package sashwind.mc.mod.ffcraft.common.model;

public record UvTransform(
        double offsetU,
        double offsetV,
        double scaleU,
        double scaleV,
        double rotationDegrees,
        boolean flipU,
        boolean flipV,
        boolean is3D
) {
    public static UvTransform createDefault() {
        return new UvTransform(0.0D, 0.0D, 1.0D, 1.0D, 0.0D, false, true, false);
    }

    public static UvTransform createDefault(int screenType) {
        // 球形屏幕 (screenType == 1) 默认开启水平翻转 (flipU = true)
        boolean flipU = (screenType == 1);
        return new UvTransform(0.0D, 0.0D, 1.0D, 1.0D, 0.0D, flipU, true, false);
    }

    public UvTransform(double offsetU, double offsetV, double scaleU, double scaleV, double rotationDegrees) {
        this(offsetU, offsetV, scaleU, scaleV, rotationDegrees, false, true, false);
    }

    public UvTransform(double offsetU, double offsetV, double scaleU, double scaleV, double rotationDegrees, boolean flipU, boolean flipV) {
        this(offsetU, offsetV, scaleU, scaleV, rotationDegrees, flipU, flipV, false);
    }
}