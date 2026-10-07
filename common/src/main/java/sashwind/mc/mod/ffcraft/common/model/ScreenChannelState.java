package sashwind.mc.mod.ffcraft.common.model;

public record ScreenChannelState(
        boolean leftEnabled,
        boolean rightEnabled
) {
    public static ScreenChannelState createDefault() {
        return new ScreenChannelState(true, true);
    }
}
