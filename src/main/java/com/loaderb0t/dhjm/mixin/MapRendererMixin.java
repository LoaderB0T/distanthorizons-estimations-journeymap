package com.loaderb0t.dhjm.mixin;

import com.loaderb0t.dhjm.DhTiles;
import com.mojang.blaze3d.systems.RenderSystem;
import journeymap.api.v2.client.display.Context;
import journeymap.client.model.map.MapType;
import journeymap.client.model.region.RegionCoord;
import journeymap.client.render.map.MapRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * Draws DH estimated terrain underneath JourneyMap's fullscreen region tiles. JM tiles are transparent
 * where nothing was mapped, so the estimate only shows through in unexplored areas.
 */
@Mixin(value = MapRenderer.class, remap = false)
public abstract class MapRendererMixin {
    @Shadow protected int zoom;
    @Shadow protected double centerBlockX;
    @Shadow protected double centerBlockZ;
    @Shadow protected volatile RegionCoord centerRegion;
    @Shadow protected volatile Rectangle2D.Double regionBounds;
    @Shadow protected volatile MapType mapType;
    @Shadow @Final protected Context.UI contextUi;
    @Shadow @Final private Point2D.Double centerPixelOffset;

    @Shadow
    public abstract Point2D.Double getBlockPixelInGrid(BlockPos pos);

    private boolean dhjm$active() {
        return DhTiles.enabled() && contextUi == Context.UI.Fullscreen && mapType != null && !mapType.isUnderground()
                && centerRegion != null && regionBounds != null;
    }

    // Fullscreen draws tiles at 80% alpha; that would let the estimate bleed through real map data.
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private float dhjm$opaqueTiles(float alpha) {
        return dhjm$active() ? 1f : alpha;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void dhjm$drawEstimate(GuiGraphics graphics, MultiBufferSource.BufferSource buffers, double offsetX, double offsetZ,
                                   float alpha, boolean showGrid, CallbackInfo ci) {
        if (!dhjm$active()) {
            if (!DhTiles.enabled()) DhTiles.releaseAll(); // switched off in settings: free the textures
            return;
        }
        RenderSystem.disableDepthTest();
        try {
            String dim = mapType.dimension.location().toString();
            DhTiles.beginFrame(dim);

            // Mirrors RegionTile.setPosition + render so the estimate lines up with JM's tiles pixel for pixel.
            Point2D.Double corner = getBlockPixelInGrid(BlockPos.containing(centerBlockX - 256.0, 0.0, centerBlockZ - 256.0));
            RegionCoord center = centerRegion;
            Rectangle2D.Double bounds = regionBounds;
            int minX = (int) bounds.x, minZ = (int) bounds.y;

            for (int rx = minX; rx < minX + (int) bounds.width; rx++) {
                for (int rz = minZ; rz < minZ + (int) bounds.height; rz++) {
                    int x = (int) ((int) corner.getX() + zoom * (rx - center.regionX) + offsetX);
                    int y = (int) ((int) corner.getY() + zoom * (rz - center.regionZ) + offsetZ);
                    DhTiles.draw(graphics, dim, rx, rz, x + centerPixelOffset.x, y + centerPixelOffset.y, zoom);
                }
            }
            DhTiles.endFrame();
        } catch (Throwable t) {
            DhTiles.fail("drawing the map estimate", t);
        } finally {
            RenderSystem.enableDepthTest();
        }
    }
}
