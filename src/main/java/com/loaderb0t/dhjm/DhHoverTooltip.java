package com.loaderb0t.dhjm;

import com.mojang.logging.LogUtils;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/**
 * Biome tooltip for estimated areas, built on JourneyMap's official fullscreen events: the mouse-move event says
 * whether JM has data under the cursor, the render event draws a tooltip there when it doesn't.
 */
final class DhHoverTooltip {
    private static final Logger LOG = LogUtils.getLogger();
    private record Hover(ResourceLocation biome, double guiX, double guiY) {}

    private static volatile Hover hover;
    private static volatile boolean generatorFailLogged;

    private DhHoverTooltip() {}

    /** JM fires this on a background thread whenever the hovered block changes. */
    static void onMouseMove(FullscreenMapEvent.MouseMoveEvent event) {
        try {
            // A block in JM's info means JM has real map data there, which always wins over the estimate.
            boolean jmHasData = event.getInfo() != null && event.getInfo().getBlock() != null;
            ResourceLocation biome = null;
            if (!jmHasData && DhTiles.enabled() && DhConfig.BIOME_ON_HOVER.get()) {
                int x = event.getLocation().getX(), z = event.getLocation().getZ();
                biome = DhTiles.biomeAt(x, z);
                if (!DhTiles.isRealBiome(biome)) biome = null;
                // DH sometimes loses biome names (seen with its own world generator); ask the real one if we can.
                if (biome == null && DhTiles.estimateAt(x, z)) biome = generatorBiome(event.getLevel(), x, z);
            }
            double scale = Minecraft.getInstance().getWindow().getGuiScale(); // JM reports window pixels
            hover = biome == null ? null : new Hover(biome, event.getMouseX() / scale, event.getMouseY() / scale);
        } catch (Throwable t) {
            DhTiles.fail("biome hover", t);
        }
    }

    /**
     * Singleplayer/LAN host only (the client has no world seed otherwise): the biome the world generator puts at the
     * surface here. Pure noise sampling, no chunk is loaded or generated. Null if not available.
     */
    private static ResourceLocation generatorBiome(ResourceKey<Level> dimension, int x, int z) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || dimension == null) return null;
        try {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) return null;
            ServerChunkCache chunks = level.getChunkSource();
            ChunkGenerator generator = chunks.getGenerator();
            int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, chunks.randomState()) - 1;
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), chunks.randomState().sampler());
            return biome.unwrapKey().map(ResourceKey::location).orElse(null);
        } catch (Throwable t) {
            // Some custom generators may not like being asked off-thread; just go without a name then.
            if (!generatorFailLogged) {
                generatorFailLogged = true;
                LOG.warn("Couldn't ask the world generator for a biome; estimated areas without a DH biome stay unnamed", t);
            }
            return null;
        }
    }

    /** Render thread, after JM drew the map. */
    static void onRender(FullscreenRenderEvent event) {
        Hover h = hover;
        if (h == null) return;
        try {
            // No move event fires while the cursor stays in one block or leaves the map (toolbars), so hide the
            // tooltip once the cursor is clearly elsewhere than where it was last reported.
            double scale = Minecraft.getInstance().getWindow().getGuiScale();
            double blockGui = event.getFullscreen().getUiState().blockSize / scale;
            double dx = event.getMouseX() - h.guiX, dy = event.getMouseY() - h.guiY;
            double maxDist = Math.max(6, blockGui * 1.5);
            if (dx * dx + dy * dy > maxDist * maxDist) return;
            event.getGraphics().renderTooltip(Minecraft.getInstance().font,
                    Component.translatable("dhjourneymap.hover.biome", biomeName(h.biome)), event.getMouseX(), event.getMouseY());
        } catch (Throwable t) {
            DhTiles.fail("drawing the biome tooltip", t);
        }
    }

    private static String biomeName(ResourceLocation id) {
        String key = Util.makeDescriptionId("biome", id);
        return I18n.exists(key) ? I18n.get(key) : id.getPath();
    }
}
