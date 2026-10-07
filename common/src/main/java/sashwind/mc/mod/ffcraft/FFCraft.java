package sashwind.mc.mod.ffcraft;

import net.fabricmc.api.ModInitializer;
import sashwind.mc.mod.ffcraft.server.VideoPlayerCommands;
import sashwind.mc.mod.ffcraft.server.VideoPlayerServerLifecycle;
import sashwind.mc.mod.ffcraft.server.VideoPlayerServerNetworking;

public class FFCraft implements ModInitializer {
    public static final String MOD_ID = FFCraftConstants.MOD_ID;

    @Override
    public void onInitialize() {
        VideoPlayerServerLifecycle.register();
        VideoPlayerServerNetworking.register();
        VideoPlayerCommands.register();
    }
}
