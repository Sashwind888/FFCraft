package sashwind.mc.mod.ffcraft.common.model;

public record VideoSource(
        String url,
        int targetWidth,
        int targetHeight,
        Integer targetFps,
        int originalWidth,
        int originalHeight
) {
    public VideoSource(String url, int targetWidth, int targetHeight, Integer targetFps) {
        this(url, targetWidth, targetHeight, targetFps, 0, 0);
    }
}
