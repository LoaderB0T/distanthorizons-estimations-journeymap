package com.loaderb0t.dhjm.mixin;

import com.loaderb0t.dhjm.DhMapPlugin;
import com.loaderb0t.dhjm.DhTiles;
import com.mojang.blaze3d.systems.RenderSystem;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.util.UIState;
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
 * <p>
 * The only JourneyMap internals the mod still touches: JM's addon API can only draw overlays on top of the map, which
 * would need several image copies per region (see README). Map type and dimension come from the official UIState.
 */
@Mixin(value = MapRenderer.class, remap = false)
public abstract class MapRendererMixin {
    @Shadow protected int zoom;
    @Shadow protected double centerBlockX;
    @Shadow protected double centerBlockZ;
    @Shadow protected volatile RegionCoord centerRegion;
    @Shadow protected volatile Rectangle2D.Double regionBounds;
    @Shadow @Final protected Context.UI contextUi;
    @Shadow @Final private Point2D.Double centerPixelOffset;

    @Shadow
    public abstract Point2D.Double getBlockPixelInGrid(BlockPos pos);

    private boolean dhjm$active() {
        if (!DhTiles.enabled() || contextUi != Context.UI.Fullscreen || centerRegion == null || regionBounds == null) return false;
        UIState state = DhMapPlugin.fullscreenState();
        return state != null && state.dimension != null && state.mapType != Context.MapType.Underground;
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
            String dim = DhMapPlugin.fullscreenState().dimension.location().toString();
            DhTiles.beginFrame(dim);

            // Mirrors RegionTile.setPosition + render so the estimate lines up with JM's tiles pixel for pixel.
            Point2D.Double corner = getBlockPixelInGrid(BlockPos.containing(centerBlockX - 256.0, 0.0, centerBlockZ - 256.0));
            RegionCoord center = centerRegion;
            Rectangle2D.Double bounds = regionBounds;
            int minX = (int) bounds.x, minZ = (int) bounds.y;

            int cornerX = (int) corner.getX(), cornerY = (int) corner.getY(), size = zoom;
            double pixelOffsetX = centerPixelOffset.x, pixelOffsetY = centerPixelOffset.y;
            DhTiles.drawVisible(graphics, dim, minX, minZ, minX + (int) bounds.width, minZ + (int) bounds.height, size,
                    rx -> (int) (cornerX + size * (rx - center.regionX) + offsetX) + pixelOffsetX,
                    rz -> (int) (cornerY + size * (rz - center.regionZ) + offsetZ) + pixelOffsetY);
            DhTiles.endFrame();
        } catch (Throwable t) {
            DhTiles.fail("drawing the map estimate", t);
        } finally {
            RenderSystem.enableDepthTest();
        }
    }
}
