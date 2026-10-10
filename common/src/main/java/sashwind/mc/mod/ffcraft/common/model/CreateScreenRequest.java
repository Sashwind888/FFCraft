package sashwind.mc.mod.ffcraft.common.model;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

public record CreateScreenRequest(
        UUID playerId,
        String name,
        ResourceKey<Level> dimension,
        List<ScreenVertex> vertices,
        int screenType,
        double radius
) {
    public CreateScreenRequest(UUID playerId, String name, ResourceKey<Level> dimension, List<ScreenVertex> vertices) {
        this(playerId, name, dimension, vertices, 0, 5.0);
    }
}
