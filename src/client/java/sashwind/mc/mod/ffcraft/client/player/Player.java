package sashwind.mc.mod.ffcraft.client.player;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import sashwind.mc.mod.drawlib.client.TopologyCompat;
import sashwind.mc.mod.drawlib.client.WorldDraw;
import sashwind.mc.mod.ffcraft.client.state.ClientScreenCreationManager;
import sashwind.mc.mod.ffcraft.client.state.ScreenCreationSession;
import sashwind.mc.mod.ffcraft.client.net.VideoPlayerClientNetworking;

import java.util.ArrayList;
import java.util.List;

import static sashwind.mc.mod.ffcraft.client.DataKeeper.pos_precision;

public class Player {
    public String name;
    private static Player isSetVertices;

    private int verticesCount;
    private Vector3d firstVertices;
    private Vector3d localVertices;
    private Runnable callback;

    private List<Vector3d> vertices = new ArrayList<>();
    private List<Double> distances = new ArrayList<Double>();
    private List<Screen> screens = new ArrayList<>();

    private static WorldDraw wd;
    private static WorldDraw wd2;

    public Player(String playerName) {
        name = playerName;
    }

    public boolean addVertices(Runnable callBack) {
        if (isSetVertices == null) {
            isSetVertices = this;
            wd.clearVertices();
            isSetVertices.verticesCount = 0;
            isSetVertices.callback = callBack;
            return true;
        } else {
            return false;
        }
    }

    public void close() {
        for (Screen s : screens) {
            s.close();
        }
        screens.clear();
    }

    public static void clientEndTick() {
        if (isSetVertices == null) return;

        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            Vec3 hitLocation = blockHit.getLocation();

            double x = Math.round(hitLocation.x / pos_precision) * pos_precision;
            double y = Math.round(hitLocation.y / pos_precision) * pos_precision;
            double z = Math.round(hitLocation.z / pos_precision) * pos_precision;
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                drawBlock((float) x, (float) y, (float) z);
            }
        }
    }

    public static boolean useBlockCallback() {
        Player currentPlayer = isSetVertices;
        if (currentPlayer == null) return true;

        HitResult hitResult = Minecraft.getInstance().hitResult;
        net.minecraft.world.entity.player.Player MCplayer = Minecraft.getInstance().player;

        if (currentPlayer.verticesCount > 64) {
            attackBlockCallback();
            return false;
        }
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            Vec3 hitLocation = blockHit.getLocation();
            Direction face = blockHit.getDirection();

            float x = (float) (Math.round(hitLocation.x / pos_precision) * pos_precision);
            float y = (float) (Math.round(hitLocation.y / pos_precision) * pos_precision);
            float z = (float) (Math.round(hitLocation.z / pos_precision) * pos_precision);

            double offset = 0.01;
            x += (float) (face.getStepX() * offset);
            y += (float) (face.getStepY() * offset);
            z += (float) (face.getStepZ() * offset);

            if (currentPlayer.localVertices != null && currentPlayer.localVertices.x == x && currentPlayer.localVertices.y == y && currentPlayer.localVertices.z == z) return false;

            wd.addVertices(x, y, z, 15, 0, 0, 1.0f, 1.0f, 0.0f, 1.0f);

            if (currentPlayer.verticesCount == 0)
                currentPlayer.firstVertices = new Vector3d(x, y, z);
            currentPlayer.verticesCount += 1;
            currentPlayer.localVertices = new Vector3d(x, y, z);

            if (currentPlayer.verticesCount > 2 && currentPlayer.firstVertices.x == x && currentPlayer.firstVertices.y == y && currentPlayer.firstVertices.z == z) {
                attackBlockCallback();
                return false;
            }

            currentPlayer.vertices.add(new Vector3d(x, y, z));
            currentPlayer.distances.add((double) MCplayer.getXRot());
            currentPlayer.distances.add((double) MCplayer.getYRot());

            ClientScreenCreationManager.addVertex(x, y, z, MCplayer.getXRot(), MCplayer.getYRot());
        }
        return true;
    }

    public static boolean attackBlockCallback() {
        if (isSetVertices != null) {
            Player currentPlayer = isSetVertices;
            wd.clearVertices();

            // 顶点数不足 3 → 左键取消创建，不提交
            if (currentPlayer.verticesCount < 3) {
                ClientScreenCreationManager.cancel();
                isSetVertices = null;
                currentPlayer.callback.run();
                return false;
            }

            currentPlayer.callback.run();

            ScreenCreationSession session = ClientScreenCreationManager.finish();
            if (session != null && session.vertices().size() >= 3) {
                VideoPlayerClientNetworking.createScreen(session.toRequest());
            }

            isSetVertices = null;
            return false;
        }
        return true;
    }

    public static boolean startVertexPlacement(Runnable callback) {
        if (isSetVertices != null) {
            return false;
        }
        Player tempPlayer = new Player("screen-builder");
        return tempPlayer.addVertices(callback);
    }

    public static void clientStart() {
        wd = new WorldDraw(0, 0, 0, TopologyCompat.DEBUG_LINE_STRIP);
        wd2 = new WorldDraw(0, 0, 0, TopologyCompat.DEBUG_LINE_STRIP);

        wd.init();
        wd2.init();
    }

    /** 客户端停止时清理静态 WorldDraw 资源，避免 GPU 资源泄露 */
    public static void clientStop() {
        if (wd != null) { wd.close(); wd = null; }
        if (wd2 != null) { wd2.close(); wd2 = null; }
        // 清理可能残留的顶点放置状态
        isSetVertices = null;
    }

    private static void drawBlock(float x, float y, float z) {
        wd2.clearVertices();

        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 1.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y + 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y + 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);

        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y + 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y + 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y - 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);

        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y + 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y + 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);

        wd2.addVertices((float) (x + 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y - 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y + 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y + 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);

        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y - 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y - 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);

        wd2.addVertices((float) (x - 0.07f), (float) (y + 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y + 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y + 0.07f), (float) (z + 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x + 0.07f), (float) (y + 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
        wd2.addVertices((float) (x - 0.07f), (float) (y + 0.07f), (float) (z - 0.07f), 15, 0.0F, 0.0F, 0.0f, 0.55f, 0.0f, 1.0f);
    }

    public static void HUDrender(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        if (isSetVertices != null) {
            graphics.text(font, Component.translatable("key.hud.createplayer.line1").getString(), 10, 10, 0xffffffff);
            graphics.text(font, Component.translatable("key.hud.createplayer.vertices_count").getString() + isSetVertices.verticesCount + " / 64", 10, 20, 0xffffffff);
        }
    }
}
