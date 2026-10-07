package sashwind.mc.mod.ffcraft.client.state;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ClientScreenRenderLifecycle {
    private ClientScreenRenderLifecycle() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientScreenRenderManager.syncFromSnapshotIfNeeded());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientScreenRenderManager.clearAll());
    }
}
