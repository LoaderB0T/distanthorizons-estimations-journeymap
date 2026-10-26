package com.loaderb0t.dhjm.mixin;

import com.loaderb0t.dhjm.DhConfig;
import com.loaderb0t.dhjm.DhTiles;
import journeymap.client.model.region.RegionCoord;
import journeymap.client.ui.fullscreen.layer.BlockInfoLayer;
import journeymap.common.helper.BiomeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fills the fullscreen hover text's biome from DH where JM has no chunk data. Runs on a JM background thread. */
@Mixin(value = BlockInfoLayer.class, remap = false)
public abstract class BlockInfoLayerMixin {
    // getBlockInfo is overloaded; target the String-returning one explicitly.
    @Inject(method = "getBlockInfo(Lnet/minecraft/core/BlockPos;Ljava/lang/String;Ljourneymap/client/model/region/RegionCoord;)Ljava/lang/String;",
            at = @At("RETURN"), cancellable = true)
    private void dhjm$estimatedBiome(BlockPos blockPos, String biomeName, RegionCoord regionCoord, CallbackInfoReturnable<String> cir) {
        if (!DhTiles.enabled() || !biomeName.isEmpty()) return;
        try {
            if (!DhConfig.BIOME_ON_HOVER.get()) return;
            ResourceLocation biome = DhTiles.biomeAt(blockPos.getX(), blockPos.getZ());
            if (biome == null) return;
            String name = BiomeHelper.getTranslatedBiomeName(biome) + " (est.)";

            // JM builds "<coords> <biome> <region>", or just "<biome>" when coordinates are hidden.
            String text = cir.getReturnValue();
            String region = "  Region: x:" + regionCoord.regionX + " z:" + regionCoord.regionZ;
            if (text.isEmpty()) {
                cir.setReturnValue(name);
            } else if (text.endsWith(region)) {
                cir.setReturnValue(text.substring(0, text.length() - region.length()) + " " + name + region.substring(1));
            }
        } catch (Throwable t) {
            DhTiles.fail("biome hover text", t);
        }
    }
}
