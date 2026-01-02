package com.example.quartzhighlighter;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class QuartzHighlighterMod implements ClientModInitializer {

    // Configuration
    private static final int HIGHLIGHT_RANGE = 32;  // Blocks to scan
    private static final boolean ONLY_IN_NETHER = true;
    private static final float OUTLINE_THICKNESS = 2.0f;

    // White color (RGBA: 1.0, 1.0, 1.0, 0.9 = white, 90% opaque)
    private static final float OUTLINE_R = 1.0f;
    private static final float OUTLINE_G = 1.0f;
    private static final float OUTLINE_B = 1.0f;
    private static final float OUTLINE_A = 0.9f;

    // Performance caching
    private final List<BlockPos> cachedOrePositions = new ArrayList<>();
    private int lastUpdateTick = 0;
    private BlockPos lastPlayerPos = BlockPos.ORIGIN;

    @Override
    public void onInitializeClient() {
        System.out.println("[QuartzHighlighter] Mod initialized!");

        // Register render event
        WorldRenderEvents.AFTER_TRANSLUCENT.register(this::renderOreOutlines);

        // Register tick event for cache updates
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Update cache every 20 ticks (1 second) or when player moves significantly
            BlockPos currentPos = client.player.getBlockPos();
            if (client.world.getTime() - lastUpdateTick > 20 ||
                    currentPos.getSquaredDistance(lastPlayerPos) > 64) {  // 8 blocks
                updateOreCache(client.world, currentPos);
                lastUpdateTick = (int) client.world.getTime();
                lastPlayerPos = currentPos;
            }
        });
    }

    private void renderOreOutlines(WorldRenderContext worldRenderContext) {

    }

    private void updateOreCache(World world, BlockPos center) {
        cachedOrePositions.clear();

        // Only scan in Nether if configured
        if (ONLY_IN_NETHER && !world.getRegistryKey().equals(World.NETHER)) {
            return;
        }

        // Calculate chunk range to scan
        int chunkRadius = (HIGHLIGHT_RANGE / 16) + 1;

        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                Chunk chunk = world.getChunk(
                        (center.getX() >> 4) + cx,  // Convert block pos to chunk pos
                        (center.getZ() >> 4) + cz
                );

                if (chunk != null) {
                    scanChunkForOres(world, chunk, center);
                }
            }
        }

        // Debug output
        if (!cachedOrePositions.isEmpty()) {
            System.out.println("[QuartzHighlighter] Found " + cachedOrePositions.size() + " quartz ore blocks");
        }
    }

    private void scanChunkForOres(World world, Chunk chunk, BlockPos center) {
        net.minecraft.util.math.ChunkPos chunkPos = chunk.getPos();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        int rangeSquared = HIGHLIGHT_RANGE * HIGHLIGHT_RANGE;

        // Scan all blocks in this chunk
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Nether quartz only spawns between Y=10 and Y=128 in the Nether
                for (int y = world.getBottomY(); y <= world.getTopY(); y++) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);

                    // Check if within range
                    if (pos.getSquaredDistance(center) > rangeSquared) continue;

                    // Check if it's nether quartz ore
                    if (chunk.getBlockState(pos).getBlock() == Blocks.NETHER_QUARTZ_ORE) {
                        cachedOrePositions.add(pos.toImmutable());
                    }
                }
            }
        }
    }

    private void renderOreOutlines(WorldRenderEvents.WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        // Check dimension
        if (ONLY_IN_NETHER && !client.player.getWorld().getRegistryKey().equals(World.NETHER)) {
            return;
        }

        // Don't render if no ores found
        if (cachedOrePositions.isEmpty()) return;

        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        MatrixStack matrices = context.matrixStack();

        // Move to world coordinates
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Setup rendering
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth(OUTLINE_THICKNESS);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // Begin drawing lines
        buffer.begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Matrix3f normalMatrix = matrices.peek().getNormalMatrix();

        // Draw outlines for all cached ore positions
        for (BlockPos pos : cachedOrePositions) {
            drawBlockOutline(buffer, matrix, normalMatrix, pos);
        }

        tessellator.draw();

        // Restore rendering state
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);

        matrices.pop();
    }

    private void drawBlockOutline(BufferBuilder buffer, Matrix4f matrix,
                                  Matrix3f normalMatrix, BlockPos pos) {
        // Slightly expand the box to prevent z-fighting with blocks
        Box box = new Box(pos).expand(0.002);

        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        // Draw all 12 edges of the cube
        // Bottom face
        addEdge(buffer, matrix, normalMatrix, minX, minY, minZ, maxX, minY, minZ);
        addEdge(buffer, matrix, normalMatrix, maxX, minY, minZ, maxX, minY, maxZ);
        addEdge(buffer, matrix, normalMatrix, maxX, minY, maxZ, minX, minY, maxZ);
        addEdge(buffer, matrix, normalMatrix, minX, minY, maxZ, minX, minY, minZ);

        // Top face
        addEdge(buffer, matrix, normalMatrix, minX, maxY, minZ, maxX, maxY, minZ);
        addEdge(buffer, matrix, normalMatrix, maxX, maxY, minZ, maxX, maxY, maxZ);
        addEdge(buffer, matrix, normalMatrix, maxX, maxY, maxZ, minX, maxY, maxZ);
        addEdge(buffer, matrix, normalMatrix, minX, maxY, maxZ, minX, maxY, minZ);

        // Vertical edges
        addEdge(buffer, matrix, normalMatrix, minX, minY, minZ, minX, maxY, minZ);
        addEdge(buffer, matrix, normalMatrix, maxX, minY, minZ, maxX, maxY, minZ);
        addEdge(buffer, matrix, normalMatrix, maxX, minY, maxZ, maxX, maxY, maxZ);
        addEdge(buffer, matrix, normalMatrix, minX, minY, maxZ, minX, maxY, maxZ);
    }

    private void addEdge(BufferBuilder buffer, Matrix4f matrix, Matrix3f normalMatrix,
                         float x1, float y1, float z1, float x2, float y2, float z2) {
        // Calculate direction for normal
        float nx = x2 - x1;
        float ny = y2 - y1;
        float nz = z2 - z1;

        buffer.vertex(matrix, x1, y1, z1)
                .color(OUTLINE_R, OUTLINE_G, OUTLINE_B, OUTLINE_A)
                .normal(normalMatrix, nx, ny, nz)
                .next();

        buffer.vertex(matrix, x2, y2, z2)
                .color(OUTLINE_R, OUTLINE_G, OUTLINE_B, OUTLINE_A)
                .normal(normalMatrix, nx, ny, nz)
                .next();
    }
}