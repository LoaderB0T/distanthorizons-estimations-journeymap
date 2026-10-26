package com.loaderb0t.dhjm;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.resources.ResourceLocation;

/** Everything that talks to JourneyMap through its official addon API. */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class DhMapPlugin implements IClientPlugin {
    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(DhJourneyMap.MOD_ID, "textures/gui/dh_btn.png");
    private static volatile IClientAPI api;

    /** JourneyMap's client API, or null before JourneyMap initialised the plugin. */
    static IClientAPI api() {
        return api;
    }

    /** Fullscreen map state (map type, dimension) from the official API, or null if not available yet. */
    public static UIState fullscreenState() {
        IClientAPI a = api;
        return a == null ? null : a.getUIState(Context.UI.Fullscreen);
    }

    @Override
    public String getModId() {
        return DhJourneyMap.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI clientApi) {
        api = clientApi;
        // Settings button in the fullscreen map's addon button column (where JourneyPAC's OPaC button lives).
        FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(getModId(), event ->
                event.getThemeButtonDisplay().addThemeButton("button.dhjourneymap.settings", ICON, button -> {
                    try {
                        DhJourneyMap.openSettings();
                    } catch (Throwable t) {
                        DhTiles.fail("opening settings", t);
                    }
                }));
        FullscreenEventRegistry.FULLSCREEN_MAP_MOVE_EVENT.subscribe(getModId(), DhHoverTooltip::onMouseMove);
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(getModId(), DhHoverTooltip::onRender);
    }
}
