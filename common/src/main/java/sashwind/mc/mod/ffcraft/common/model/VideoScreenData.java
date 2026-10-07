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
        boolean uvManuallyEdited
) {
}
