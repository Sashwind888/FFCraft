package sashwind.mc.mod.ffcraft.common.model;

public record PlaybackState(
        PlaybackStatus status,
        PlaybackMode mode,
        int currentIndex,
        int progressSeconds,
        int volume,
        long lastUpdatedEpochSeconds
) {
    public static PlaybackState createDefault() {
        return new PlaybackState(PlaybackStatus.STOPPED, PlaybackMode.SEQUENTIAL, 0, 0, 100, 0L);
    }
}
