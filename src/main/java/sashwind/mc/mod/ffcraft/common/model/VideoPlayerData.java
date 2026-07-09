package sashwind.mc.mod.ffcraft.common.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record VideoPlayerData(
        UUID id,
        String name,
        boolean isPublic,
        Set<UUID> editors,
        List<VideoSource> playlist,
        PlaybackState playbackState,
        List<VideoScreenData> screens
) {
}
