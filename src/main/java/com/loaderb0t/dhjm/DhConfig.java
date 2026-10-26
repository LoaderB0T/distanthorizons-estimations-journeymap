package com.loaderb0t.dhjm;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config, edited through NeoForge's config screen (map button or Mods list). Saved to config/dhjourneymap-client.toml. */
public final class DhConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = B
            .comment("Show Distant Horizons terrain in unexplored parts of the fullscreen map.")
            .translation("dhjourneymap.configuration.enabled")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue RADIUS_BLOCKS = B
            .comment("Build new estimates from DH only within this many blocks of the player. Saved estimates show everywhere.")
            .translation("dhjourneymap.configuration.radiusBlocks")
            .defineInRange("radiusBlocks", 8192, 512, 65536);

    public static final ModConfigSpec.DoubleValue TINT = B
            .comment("Sepia tint marking estimated areas. 0 = DH colours unchanged, 1 = full sepia.")
            .translation("dhjourneymap.configuration.tint")
            .defineInRange("tint", 0.7, 0.0, 1.0);

    public static final ModConfigSpec.BooleanValue HILLSHADE = B
            .comment("Shade slopes so terrain height is readable.")
            .translation("dhjourneymap.configuration.hillshade")
            .define("hillshade", true);

    public static final ModConfigSpec.BooleanValue HIDE_DECORATION = B
            .comment("Ignore flowers, grass, saplings etc. on top of the ground. Avoids coloured specks, since one map pixel covers 4x4 blocks.")
            .translation("dhjourneymap.configuration.hideDecoration")
            .define("hideDecoration", true);

    public static final ModConfigSpec.BooleanValue BIOME_ON_HOVER = B
            .comment("Show the DH biome in the map's hover text where JourneyMap has no data.")
            .translation("dhjourneymap.configuration.biomeOnHover")
            .define("biomeOnHover", true);

    public static final ModConfigSpec.IntValue REFRESH_SECONDS = B
            .comment("How often visible estimated tiles are rebuilt to pick up newly generated DH terrain.")
            .translation("dhjourneymap.configuration.refreshSeconds")
            .defineInRange("refreshSeconds", 60, 5, 3600);

    public static final ModConfigSpec.IntValue MAX_TILES = B
            .comment("Most estimated regions kept in memory (about 64 KB video memory + 16 KB RAM each). Off-screen ones are dropped first; saved ones reload from disk.")
            .translation("dhjourneymap.configuration.maxTiles")
            .defineInRange("maxTiles", 1024, 64, 8192);

    public static final ModConfigSpec.IntValue BAKE_THREADS = B
            .comment("Background threads building estimated tiles. 0 = automatic (2-4 depending on CPU).")
            .translation("dhjourneymap.configuration.bakeThreads")
            .gameRestart()
            .defineInRange("bakeThreads", 0, 0, 8);

    public static final ModConfigSpec SPEC = B.build();

    private DhConfig() {}
}
