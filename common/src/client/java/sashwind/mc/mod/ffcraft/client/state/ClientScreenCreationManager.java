package sashwind.mc.mod.ffcraft.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import sashwind.mc.mod.ffcraft.common.model.ScreenVertex;

import java.util.UUID;

public final class ClientScreenCreationManager {
    private static ScreenCreationSession activeSession;

    private ClientScreenCreationManager() {
    }

    public static boolean isActive() {
        return activeSession != null;
    }

    public static ScreenCreationSession getActiveSession() {
        return activeSession;
    }

    public static boolean start(UUID playerId, String screenName) {
        return start(playerId, screenName, 0, 5.0);
    }

    public static boolean start(UUID playerId, String screenName, int screenType, double radius) {
        if (activeSession != null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        ResourceKey<Level> dimension = minecraft.level.dimension();
        activeSession = new ScreenCreationSession(playerId, screenName, dimension, screenType, radius);
        return true;
    }

    public static void addVertex(double x, double y, double z, double pitch, double yaw) {
        if (activeSession == null) {
            return;
        }
        activeSession.addVertex(new ScreenVertex(x, y, z, pitch, yaw));
    }

    public static ScreenCreationSession finish() {
        ScreenCreationSession finished = activeSession;
        activeSession = null;
        return finished;
    }

    public static void cancel() {
        activeSession = null;
    }
}
