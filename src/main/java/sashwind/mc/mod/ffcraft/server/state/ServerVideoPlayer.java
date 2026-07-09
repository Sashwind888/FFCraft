package sashwind.mc.mod.ffcraft.server.state;

import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.VideoSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ServerVideoPlayer(
        UUID id,
        String name,
        boolean isPublic,
        Set<UUID> editors,
        List<VideoSource> playlist,
        PlaybackState playbackState,
        List<ServerVideoScreen> screens
) {
    public ServerVideoPlayer {
        editors = new LinkedHashSet<>(editors);
        playlist = new ArrayList<>(playlist);
        screens = new ArrayList<>(screens);
    }
}
