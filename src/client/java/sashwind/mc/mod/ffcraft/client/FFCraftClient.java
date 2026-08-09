package sashwind.mc.mod.ffcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import sashwind.mc.mod.ffcraft.FFCraft;
import sashwind.mc.mod.drawlib.client.TopologyCompat;
import sashwind.mc.mod.drawlib.client.WorldDraw;
import sashwind.mc.mod.drawlib.client.lib;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking;
import sashwind.mc.mod.ffcraft.client.player.MpvNativeLoader;
import sashwind.mc.mod.ffcraft.client.player.MpvPlayer;
import sashwind.mc.mod.ffcraft.client.player.Player;
import sashwind.mc.mod.ffcraft.client.screens.MainScreen;
import sashwind.mc.mod.ffcraft.client.screens.MpvInstallScreen;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class FFCraftClient implements ClientModInitializer {

    public WorldDraw wd;
    public WorldDraw wd2;
    public WorldDraw wd3;

    private final ConcurrentLinkedQueue<NativeImage> frameQueue = new ConcurrentLinkedQueue<>();

    /** 标记初始纹理是否已设置（必须延迟到渲染线程执行） */
    private final AtomicBoolean texturesInitialized = new AtomicBoolean(false);

    /** 缓存在非渲染线程加载的初始图片，供渲染线程使用 */
    private NativeImage pendingBackgroundImage;
    private NativeImage pendingPlaceholderImage;

    private static FFCraftClient instance;//单例
    public static FFCraftClient getInstance() {
        return instance;
    }

    /** TitleScreenMixin 登记的 libmpv 安装检查请求，由 END_CLIENT_TICK 消费（构造完成后才安全） */
    private static boolean mpvInstallCheckPending = false;

    /** 在标题画面初始化时调用：登记安装检查，推迟到第一 tick 执行 */
    public static void requestMpvInstallCheck() {
        mpvInstallCheckPending = true;
    }

    private static void checkMpvInstallPrompt(Minecraft client) {
        // 只弹一次
        if (MpvInstallScreen.hasShown()) return;
        // 已安装则跳过
        if (MpvPlayer.isAvailable()) {
            MpvInstallScreen.markShown();
            return;
        }
        // 不支持自动下载的平台也跳过
        MpvNativeLoader.State state = MpvNativeLoader.getState();
        if (state == MpvNativeLoader.State.UNSUPPORTED) {
            MpvInstallScreen.markShown();
            return;
        }

        MpvInstallScreen.markShown();
        lib.setScreenCompat(client, new MpvInstallScreen());
    }

    KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(FFCraft.MOD_ID, "custom_category")
    );
    KeyMapping openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ffcraft.opengui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            this.CATEGORY
    ));


    @Override
    public void onInitializeClient() {
        instance = this;
        VideoPlayerClientNetworking.register();
        sashwind.mc.mod.ffcraft.client.state.ClientScreenRenderLifecycle.register();

        // MainScreen 已改为原版 Minecraft GUI，不再需要 ImGui 字体配置

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(FFCraft.MOD_ID, "before_chat"), HudRender::extract);

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            if(Player.attackBlockCallback()) return InteractionResult.PASS;
            else return InteractionResult.FAIL;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) return InteractionResult.PASS;
            if(Player.useBlockCallback()) return InteractionResult.PASS;
            else return InteractionResult.FAIL;
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            wd = new WorldDraw(0, 100, 0, TopologyCompat.TRIANGLES);
            wd2 = new WorldDraw(0, 100, 0, TopologyCompat.DEBUG_LINE_STRIP);
            wd3 = new WorldDraw(0,0,0, TopologyCompat.DEBUG_LINE_STRIP);
            wd.init();
            wd2.init();
            wd3.init();

            Player.clientStart();

            try {
                ResourceManager manager = Minecraft.getInstance().getResourceManager();
                Identifier textureId = Identifier.fromNamespaceAndPath("ffcraft", "ffcraft.png");
                TextureContents background_pic = TextureContents.load(manager, textureId);
                pendingBackgroundImage = background_pic.image();

//                pendingPlaceholderImage = createPlaceholderImage(16, 16);

//                wd.addVertices(0, 100, 0, 15, 0, 0, 1f, 1f, 1f, 1f);
//                wd.addVertices(0, 96, 0, 15, 0, 1, 1f, 1f, 1f, 1f);
//                wd.addVertices(4, 96, 0, 15, 1, 1, 1f, 1f, 1f, 1f);
//                wd.addVertices(0, 100, 0, 15, 0, 0, 1f, 1f, 1f, 1f);
//                wd.addVertices(4, 96, 0, 15, 1, 1, 1f, 1f, 1f, 1f);
//                wd.addVertices(4, 100, 0, 15, 1, 0, 1f, 1f, 1f, 1f);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        });

        // 客户端停止时清理所有 GPU 资源和事件监听器
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (wd != null) { wd.close(); wd = null; }
            if (wd2 != null) { wd2.close(); wd2 = null; }
            if (wd3 != null) { wd3.close(); wd3 = null; }
            Player.clientStop();
            lib.cleanupStatics();
            if (pendingBackgroundImage != null) {
                pendingBackgroundImage.close();
                pendingBackgroundImage = null;
            }
            if (pendingPlaceholderImage != null) {
                pendingPlaceholderImage.close();
                pendingPlaceholderImage = null;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Player.clientEndTick();

            // libmpv 安装检查：由 TitleScreenMixin 登记，这里消费。
            // 必须延迟到第一 tick —— Minecraft.<init> 构造早期直接 setScreenAndShow
            // 会立即渲染一帧，此时 framerateLimitTracker 等字段尚未初始化，
            // dynamic_fps 等 mod 的 renderFrame mixin 会 NPE。
            if (mpvInstallCheckPending) {
                mpvInstallCheckPending = false;
                checkMpvInstallPrompt(client);
            }

            while (this.openGuiKey.consumeClick()) {
                if (client.player != null) {
                    lib.setScreenCompat(client, new MainScreen());
                }
            }

            if (!texturesInitialized.getAndSet(true)) {
//                if (pendingPlaceholderImage != null) {
//                    wd.setTexture(
//                        pendingPlaceholderImage.getWidth(),
//                        pendingPlaceholderImage.getHeight(),
//                        pendingPlaceholderImage
//                    );
//                    pendingPlaceholderImage.close();
//                    pendingPlaceholderImage = null;
//                }
            }
        });

        sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.register();
    }

    private NativeImage createPlaceholderImage(int width, int height) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        long ptr = img.getPointer();
        java.nio.ByteBuffer buf = MemoryUtil.memByteBuffer(ptr, width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buf.put((byte) 0xff);
                buf.put((byte) 0x00);
                buf.put((byte) 0x00);
                buf.put((byte) 0xff);
            }
        }
        return img;
    }

}

