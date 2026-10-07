package sashwind.mc.mod.ffcraft.server.state;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import sashwind.mc.mod.ffcraft.common.model.ScreenChannelState;
import sashwind.mc.mod.ffcraft.common.model.ScreenVertex;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ServerVideoScreen(
        UUID id,
        UUID playerId,
        String name,
        ResourceKey<Level> dimension,
        List<ScreenVertex> vertices,
        UvTransform uvTransform,
        ScreenChannelState channelState,
        boolean uvManuallyEdited
) {
    public ServerVideoScreen {
        vertices = new ArrayList<>(vertices);
    }
}
