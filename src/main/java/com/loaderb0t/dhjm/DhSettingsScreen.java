package com.loaderb0t.dhjm;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/** NeoForge's config section screen plus a "Clear estimated map" button below the options. */
final class DhSettingsScreen extends ConfigurationScreen.ConfigurationSectionScreen {
    DhSettingsScreen(Screen parent, ModConfig.Type type, ModConfig config, Component title) {
        super(parent, type, config, title);
    }

    // rebuild() repopulates the option list (also after undo/reset), so the button is appended here.
    @Override
    protected ConfigurationScreen.ConfigurationSectionScreen rebuild() {
        super.rebuild();
        if (list != null) {
            Button clear = Button.builder(Component.translatable("dhjourneymap.configuration.clearEstimate"), b -> confirmClear())
                    .width(310)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("dhjourneymap.configuration.clearEstimate.tooltip")))
                    .build();
            clear.active = minecraft != null && minecraft.level != null; // needs a world to know which cache to clear
            list.addSmall(clear, null);
        }
        return this;
    }

    private void confirmClear() {
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) DhTiles.clearEstimate();
            minecraft.setScreen(this);
        }, Component.translatable("dhjourneymap.clearEstimate.confirm.title"),
                Component.translatable("dhjourneymap.clearEstimate.confirm.message")));
    }
}
