package sashwind.mc.mod.ffcraft.client.drawlib;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class WorldDraw {
    lib lib;
    private final Queue<Runnable> RENDER_TASKS = new ConcurrentLinkedQueue<>();
    DynamicTexture dynamicTexture;
    private int currentWidth, currentHeight;

    private static final java.util.concurrent.atomic.AtomicInteger TEXTURE_ID_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final String MOD_ID = "ffcraft";
    private final Identifier VIDEO_TEXTURE_ID;

    public WorldDraw(int x, int y, int z, VertexFormat.Mode VertexFormatMode) {
        VIDEO_TEXTURE_ID = Identifier.fromNamespaceAndPath(MOD_ID, "stream_" + TEXTURE_ID_COUNTER.getAndIncrement());
        lib = new lib(x, y, z, VertexFormatMode);
    }

    public void init() {
        LevelRenderEvents.END_EXTRACTION.register(lib::extractWaypoint);
        // 先 draw（用上帧已上传的纹理），再 flush（为本帧 upload 下帧使用）
        // Blaze3D 不允许同帧创建+使用 GPU 纹理
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(lib::renderAndDrawWaypoint);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::RENDER_TASKS_FLUSH);
    }

    public void addVertices(float x, float y, float z, int light, float u, float v, float r, float g, float b, float a) {
        int l = ((light & 0xFF) << 16) | (light & 0xFF);
        lib.vertices.add(new Vertex(x, y, z, r, g, b, a, u, v, l, 0, 1, 0));
    }

    public void clearVertices() { lib.vertices.clear(); }

    // ──────────────────────────────────────────────
    //  逐屏纹理：copy 数据后 RENDER_TASKS 创建独立 DynamicTexture
    // ──────────────────────────────────────────────
    public void setTexture(int w, int h, NativeImage image) {
        int size = w * h * 4;
        long srcPtr = image.getPointer();
        NativeImage.Format fmt = image.format();
        byte[] rawPixels = new byte[size];
        org.lwjgl.system.MemoryUtil.memByteBuffer(srcPtr, size).get(rawPixels);

        RENDER_TASKS.offer(() -> {
            NativeImage nativeImage = null;
            try {
                nativeImage = new NativeImage(fmt, w, h, false);
                int[] pixels = nativeImage.getPixels();
                ByteBuffer.wrap(rawPixels).asIntBuffer().get(pixels);
                long dstPtr = nativeImage.getPointer();
                org.lwjgl.system.MemoryUtil.memByteBuffer(dstPtr, size).put(rawPixels);
                ensureTexture(w, h);
                dynamicTexture.setPixels(nativeImage);
                dynamicTexture.upload();
                lib.WAYPOINT_TEXTURE = dynamicTexture;
            } finally {
                if (nativeImage != null) nativeImage.close();
            }
        });
    }

    public void setTexture(int w, int h, Buffer[] image) {
        Buffer[] pixels = image.clone();
        RENDER_TASKS.offer(() -> {
            ensureTexture(w, h);
            NativeImage nativeImage = null;
            try {
                nativeImage = bufferArrayToNativeImage(w, h, pixels, false);
                dynamicTexture.setPixels(nativeImage);
                dynamicTexture.upload();
                lib.WAYPOINT_TEXTURE = dynamicTexture;
            } finally {
                if (nativeImage != null) nativeImage.close();
            }
        });
    }

    public void close() {
        RENDER_TASKS.offer(() -> {
            lib.closed = true;
            try {
                if (lib.vertexBuffer != null) { lib.vertexBuffer.close(); lib.vertexBuffer = null; }
                if (dynamicTexture != null) { dynamicTexture.close(); dynamicTexture = null; }
                clearVertices();
                if (lib.WAYPOINT_TEXTURE != null) { lib.WAYPOINT_TEXTURE.close(); lib.WAYPOINT_TEXTURE = null; }
            } finally {
                lib.close();
            }
        });
    }

    // ──────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────
    private void ensureTexture(int w, int h) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        if (dynamicTexture == null || currentWidth != w || currentHeight != h) {
            if (dynamicTexture != null) textureManager.release(VIDEO_TEXTURE_ID);
            dynamicTexture = new DynamicTexture(MOD_ID + ".stream", w, h, true);
            textureManager.register(VIDEO_TEXTURE_ID, dynamicTexture);
            currentWidth = w;
            currentHeight = h;
        }
    }

    private NativeImage bufferArrayToNativeImage(int w, int h, Buffer[] image, boolean swapRB) {
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        int pixelCount = w * h;
        if (image == null || image.length == 0 || image[0] == null) return nativeImage;
        int[] pixels = nativeImage.getPixels();
        Buffer srcBuf = image[0];
        IntBuffer srcIntBuf;
        if (srcBuf instanceof IntBuffer ib) srcIntBuf = ib;
        else if (srcBuf instanceof ByteBuffer bb) srcIntBuf = bb.asIntBuffer();
        else return nativeImage;
        srcIntBuf.rewind();
        IntBuffer dstIntBuf = IntBuffer.wrap(pixels);
        if (swapRB) {
            int[] temp = new int[pixelCount];
            srcIntBuf.get(temp, 0, Math.min(pixelCount, srcIntBuf.remaining()));
            for (int i = 0; i < pixelCount && i < temp.length; i++) {
                int val = temp[i];
                int a = (val >>> 24) & 0xFF, r = (val >>> 16) & 0xFF;
                int g = (val >>> 8) & 0xFF, b = val & 0xFF;
                pixels[i] = (a << 24) | (b << 16) | (g << 8) | r;
            }
        } else {
            dstIntBuf.put(srcIntBuf);
        }
        return nativeImage;
    }

    private void RENDER_TASKS_FLUSH(LevelRenderContext ctx) {
        Runnable task = RENDER_TASKS.poll();
        while (task != null) {
            try { task.run(); } catch (Throwable e) { System.err.println("[RENDER_TASKS_FLUSH] " + e); }
            task = RENDER_TASKS.poll();
        }
    }
}
