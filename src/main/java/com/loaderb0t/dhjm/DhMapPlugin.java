package com.loaderb0t.dhjm;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.resources.ResourceLocation;

/** Adds the settings button to the fullscreen map's addon button column (where JourneyPAC's OPaC button lives). */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class DhMapPlugin implements IClientPlugin {
    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(DhJourneyMap.MOD_ID, "textures/gui/dh_btn.png");

    @Override
    public String getModId() {
        return DhJourneyMap.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI api) {
        FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(getModId(), event ->
                event.getThemeButtonDisplay().addThemeButton("button.dhjourneymap.settings", ICON, button -> {
                    try {
                        DhJourneyMap.openSettings();
                    } catch (Throwable t) {
                        DhTiles.fail("opening settings", t);
                    }
                }));
    }
}
