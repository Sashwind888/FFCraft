package sashwind.mc.mod.ffcraft.client.drawlib;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.logging.Logger;

public class lib {
    private static final Logger LOGGER = Logger.getLogger("FFCraft-lib");

    boolean closed = false;
    int POSx, POSy, POSz;
    TopologyCompat VFM;
    public ArrayList<Vertex> vertices = new ArrayList<>();
    public AbstractTexture WAYPOINT_TEXTURE;
    public RenderPipeline FILLED_THROUGH_WALLS;
    public WaypointRenderState waypointState;

    private static final java.util.concurrent.atomic.AtomicInteger PLACEHOLDER_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final String MOD_ID = "ffcraft";
    private int frameSkip; // 每60帧打印一次，避免刷屏

    private static AbstractTexture OVERLAY_WHITE;

    // ──────────── TopologyCompat → PrimitiveTopology 映射 ────────────

    private static PrimitiveTopology toPrimitiveTopology(TopologyCompat t) {
        return switch (t) {
            case TRIANGLES -> PrimitiveTopology.TRIANGLES;
            case LINES -> PrimitiveTopology.LINES;
            case DEBUG_LINE_STRIP -> PrimitiveTopology.DEBUG_LINE_STRIP;
            case QUADS -> PrimitiveTopology.QUADS;
        };
    }

    // ──────────── 业务逻辑 ────────────

    private static void ensureOverlayWhite() {
        if (OVERLAY_WHITE != null) return;
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "_overlay_white");
        DynamicTexture dt = new DynamicTexture(MOD_ID + ".overlay", 1, 1, true);
        tm.register(id, dt);
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        img.setPixel(0, 0, 0xFFFFFFFF);
        dt.setPixels(img);
        dt.upload();
        img.close();
        OVERLAY_WHITE = dt;
    }

    public lib(int x, int y, int z, TopologyCompat topo) {
        POSx = x; POSy = y; POSz = z;
        VFM = topo;

        if (VFM.isLine()) {
            PrimitiveTopology pt = toPrimitiveTopology(VFM);
            RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_EMISSIVE_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/entity_translucent_emissive"))
                    .withVertexBinding(0, DefaultVertexFormat.ENTITY)
                    .withPrimitiveTopology(pt)
                    .withShaderDefine("NO_OVERLAY")
                    .withShaderDefine("NO_FOG")
                    .build();
            FILLED_THROUGH_WALLS = pipeline;
            LOGGER.info("[ctor] Line pipeline built: " + VFM + " loc=" + FILLED_THROUGH_WALLS.getLocation());
        } else {
            FILLED_THROUGH_WALLS = RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE;
            LOGGER.info("[ctor] Using ENTITY_TRANSLUCENT_EMISSIVE for " + VFM);
        }
    }

    public void extractWaypoint(LevelExtractionContext context) {
        if (closed) return;
        waypointState = new WaypointRenderState(POSx, POSy, POSz, 1f, 1f, 1f, 1f);
    }

    public final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    public final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    public final Vector3f MODEL_OFFSET = new Vector3f();
    public final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    public BufferBuilder buffer;
    public MappableRingBuffer vertexBuffer;

    public void renderAndDrawWaypoint(LevelRenderContext context) {
        if (closed) return;
        ensureOverlayWhite();
        if (WAYPOINT_TEXTURE == null) {
            Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "_placeholder" + PLACEHOLDER_COUNTER.getAndIncrement());
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            DynamicTexture dt = new DynamicTexture(MOD_ID + ".placeholder", 1, 1, true);
            textureManager.register(id, dt);
            NativeImage img = createPlaceholderImage(1, 1);
            dt.setPixels(img);
            dt.upload();
            img.close();
            WAYPOINT_TEXTURE = dt;
        }
        renderWaypoint(context);
        drawFilledThroughWalls(Minecraft.getInstance(), FILLED_THROUGH_WALLS);
    }

    public void renderWaypoint(LevelRenderContext context) {
        if (closed) return;
        try {
            ALLOCATOR.clear();
        } catch (IllegalStateException e) {
            LOGGER.warning("ALLOCATOR.clear() failed: " + e);
            buffer = null;
            return;
        }
        if (vertices.isEmpty() || WAYPOINT_TEXTURE == null) {
            buffer = null;
            if (frameSkip++ % 60 == 0) LOGGER.info("[render] SKIP v=" + vertices.size() + " tex=" + (WAYPOINT_TEXTURE != null));
            return;
        }
        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        buffer = new BufferBuilder(ALLOCATOR, toPrimitiveTopology(VFM), FILLED_THROUGH_WALLS.getVertexFormatBinding(0));
        renderFilledBox(matrices.last().pose(), buffer, waypointState.r(), waypointState.g(), waypointState.b(), waypointState.a());
        matrices.popPose();
        if (frameSkip++ % 60 == 0) {
            Vertex v0 = vertices.get(0);
            LOGGER.info("[render] v=" + vertices.size() + " topo=" + VFM
                + " cam=" + String.format("%.1f,%.1f,%.1f", camera.x, camera.y, camera.z)
                + " pos0=" + String.format("%.1f,%.1f,%.1f", v0.x, v0.y, v0.z)
                + " fmtSz=" + FILLED_THROUGH_WALLS.getVertexFormatBinding(0).getVertexSize());
        }
    }

    public void renderFilledBox(Matrix4f positionMatrix, BufferBuilder buffer, float red, float green, float blue, float alpha) {
        if (closed) return;
        for (Vertex v : vertices) {
            buffer.addVertex(positionMatrix, v.x, v.y, v.z)
                    .setColor(1f, 1f, 1f, v.a)
                    .setUv(v.u, v.v)
                    .setUv1(0, 0)
                    .setUv2(255, 255)
                    .setNormal(v.nx, v.ny, v.nz);
        }
    }

    public void drawFilledThroughWalls(Minecraft client, RenderPipeline pipeline) {
        if (closed || vertices.isEmpty() || WAYPOINT_TEXTURE == null || buffer == null) return;
        MeshData builtBuffer = buffer.buildOrThrow();
        MeshData.DrawState drawParameters = builtBuffer.drawState();
        VertexFormat format = drawParameters.format();
        int vc = drawParameters.vertexCount();
        int ic = drawParameters.indexCount();
        if (frameSkip++ % 60 == 0) LOGGER.info("[draw] vc=" + vc + " ic=" + ic + " topo=" + VFM + " fmt=" + format.getVertexSize());

        int vertexBufferSize = vc * format.getVertexSize();
        int bufferCapacity = vertexBufferSize + (vertexBufferSize >> 2);
        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> MOD_ID + " example render pipeline",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bufferCapacity);
            if (frameSkip++ % 60 == 0) LOGGER.info("[draw] new MappableRingBuffer size=" + bufferCapacity);
        }
        GpuBuffer verts = vertexBuffer.currentBuffer();
        GpuBufferSlice uploadSlice = verts.slice(0, builtBuffer.vertexBuffer().remaining());
        try (GpuBufferSlice.MappedView mv = uploadSlice.map(false, true)) {
            MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mv.data());
        } catch (Exception e) {
            LOGGER.warning("[draw] vertex upload failed: " + e);
        }

        if (closed) return;
        GpuBuffer indices; IndexType indexType;
        if (VFM.isQuads()) {
            builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = RenderSystem.getDevice().createBuffer(
                    () -> "immediate_index", GpuBuffer.USAGE_INDEX, builtBuffer.indexBuffer());
            indexType = drawParameters.indexType();
        } else if (VFM == TopologyCompat.LINES) {
            int vc2 = drawParameters.vertexCount();
            ByteBuffer idxBuffer = MemoryUtil.memAlloc(vc2 * 2);
            for (int i = 0; i < vc2; i++) idxBuffer.putShort((short) i);
            idxBuffer.flip();
            indices = RenderSystem.getDevice().createBuffer(
                    () -> "immediate_line_index", GpuBuffer.USAGE_INDEX, idxBuffer);
            indexType = IndexType.SHORT;
            MemoryUtil.memFree(idxBuffer);
        } else {
            RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer =
                    RenderSystem.getSequentialBuffer(toPrimitiveTopology(VFM));
            indices = shapeIndexBuffer.getBuffer(ic);
            indexType = shapeIndexBuffer.type();
        }
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        com.mojang.blaze3d.pipeline.RenderTarget rt = client.gameRenderer.mainRenderTarget();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (frameSkip++ % 60 == 0) LOGGER.info("[draw] RT hasDepth=" + (rt.getDepthTexture() != null) + " colorView=" + (rt.getColorTextureView() != null));
        try (RenderPass renderPass = encoder.createRenderPass(
                () -> MOD_ID + " example render pipeline rendering",
                client.gameRenderer.mainRenderTarget().getColorTextureView(), Optional.empty(),
                client.gameRenderer.mainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            GpuSampler pointSampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, false);
            renderPass.bindTexture("Sampler0", WAYPOINT_TEXTURE.getTextureView(), pointSampler);
            renderPass.bindTexture("Sampler2", client.gameRenderer.lightmap(), pointSampler);
            renderPass.bindTexture("Sampler1", OVERLAY_WHITE.getTextureView(), pointSampler);
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, verts.slice());
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(ic, 1, 0, 0, 0);
            if (frameSkip++ % 60 == 0) LOGGER.info("[draw] DONE: drawIndexed(count=" + ic + " inst=1)");
        } catch (Exception e) {
            LOGGER.warning("[draw] render pass failed: " + e);
        }
        builtBuffer.close();
        if (vertexBuffer != null) vertexBuffer.rotate();
    }

    public static void setScreenCompat(Minecraft client, net.minecraft.client.gui.screens.Screen screen) {
        client.setScreenAndShow(screen);
    }

    public record WaypointRenderState(int x, int y, int z, float r, float g, float b, float a) {}

    private NativeImage createPlaceholderImage(int width, int height) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        long ptr = img.getPointer();
        ByteBuffer buf = MemoryUtil.memByteBuffer(ptr, width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buf.put((byte) 0xFF); buf.put((byte) 0xFF);
                buf.put((byte) 0xFF); buf.put((byte) 0xFF);
            }
        }
        return img;
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (vertexBuffer != null) { vertexBuffer.close(); vertexBuffer = null; }
        try { if (ALLOCATOR != null) ALLOCATOR.close(); } catch (Exception e) {}
        if (vertices != null) vertices.clear();
        WAYPOINT_TEXTURE = null;
        buffer = null;
    }

}
