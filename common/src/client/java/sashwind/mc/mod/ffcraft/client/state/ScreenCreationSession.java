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
    private final int screenType;
    private final double radius;

    public ScreenCreationSession(UUID playerId, String screenName, ResourceKey<Level> dimension, int screenType, double radius) {
        this.playerId = playerId;
        this.screenName = screenName;
        this.dimension = dimension;
        this.screenType = screenType;
        this.radius = radius;
    }

    public ScreenCreationSession(UUID playerId, String screenName, ResourceKey<Level> dimension) {
        this(playerId, screenName, dimension, 0, 5.0);
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

    public int screenType() {
        return screenType;
    }

    public double radius() {
        return radius;
    }

    public void addVertex(ScreenVertex vertex) {
        vertices.add(vertex);
    }

    public CreateScreenRequest toRequest() {
        return new CreateScreenRequest(playerId, screenName, dimension, List.copyOf(vertices), screenType, radius);
    }
}
