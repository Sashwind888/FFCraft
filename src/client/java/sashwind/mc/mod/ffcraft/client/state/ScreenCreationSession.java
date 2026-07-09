package sashwind.mc.mod.ffcraft.client.state;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import sashwind.mc.mod.ffcraft.common.model.CreateScreenRequest;
import sashwind.mc.mod.ffcraft.common.model.ScreenVertex;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ScreenCreationSession {
    private final UUID playerId;
    private final String screenName;
    private final ResourceKey<Level> dimension;
    private final List<ScreenVertex> vertices = new ArrayList<>();

    public ScreenCreationSession(UUID playerId, String screenName, ResourceKey<Level> dimension) {
        this.playerId = playerId;
        this.screenName = screenName;
        this.dimension = dimension;
    }

    public UUID playerId() {
        return playerId;
    }

    public String screenName() {
        return screenName;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public List<ScreenVertex> vertices() {
        return vertices;
    }

    public void addVertex(ScreenVertex vertex) {
        vertices.add(vertex);
    }

    public CreateScreenRequest toRequest() {
        return new CreateScreenRequest(playerId, screenName, dimension, List.copyOf(vertices));
    }
}
