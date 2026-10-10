package sashwind.mc.mod.ffcraft.common.model;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public record VideoScreenData(
        UUID id,
        UUID playerId,
        String name,
        ResourceKey<Level> dimension,
        List<ScreenVertex> vertices,
        UvTransform uvTransform,
        ScreenChannelState channelState,
        boolean uvManuallyEdited,
        int screenType,
        double radius
) {
    public static final int TYPE_POLYGON = 0;
    public static final int TYPE_SPHERE = 1;

    public VideoScreenData(UUID id, UUID playerId, String name, ResourceKey<Level> dimension,
                           List<ScreenVertex> vertices, UvTransform uvTransform,
                           ScreenChannelState channelState, boolean uvManuallyEdited) {
        this(id, playerId, name, dimension, vertices, uvTransform, channelState, uvManuallyEdited, TYPE_POLYGON, 5.0);
    }

    public boolean isSphere() {
        return screenType == TYPE_SPHERE;
    }
}
