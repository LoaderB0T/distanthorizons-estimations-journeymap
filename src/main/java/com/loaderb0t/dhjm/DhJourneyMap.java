package com.loaderb0t.dhjm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = DhJourneyMap.MOD_ID, dist = Dist.CLIENT)
public class DhJourneyMap {
    public static final String MOD_ID = "dhjourneymap";
    private static ModContainer container;

    public DhJourneyMap(IEventBus modBus, ModContainer container) {
        DhJourneyMap.container = container;
        container.registerConfig(ModConfig.Type.CLIENT, DhConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> settingsScreen(parent));
        // Tint, shading etc. are baked into the tiles, so any settings change rebuilds them.
        modBus.addListener(ModConfigEvent.Reloading.class, e -> {
            if (e.getConfig().getSpec() == DhConfig.SPEC) DhTiles.invalidate();
        });
    }

    /** Opens the settings screen; closing it returns to whatever screen was open (the fullscreen map). */
    public static void openSettings() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(settingsScreen(mc.screen));
    }

    private static Screen settingsScreen(Screen parent) {
        return new ConfigurationScreen(container, parent, (screen, type, config, title) -> new DhSettingsScreen(screen, type, config, title));
    }
}
