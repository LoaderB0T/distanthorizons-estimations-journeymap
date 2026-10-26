# DH JourneyMap Bridge

**Unofficial** client-side NeoForge 1.21.1 addon that fills the unexplored parts of the
[JourneyMap](https://modrinth.com/mod/journeymap) fullscreen map with terrain estimated from
[Distant Horizons](https://modrinth.com/mod/distanthorizons) LOD data. Estimated areas get a sepia tint so
you can tell them apart from real map data, which always stays on top.

Handy on servers with a low view distance: DH already knows the terrain far away, JourneyMap doesn't.

## Features

- Estimated terrain underneath JourneyMap's real tiles (fullscreen surface maps only; minimap and cave/Nether maps untouched)
- Distant Horizons' own block colours, hill shading, flowers/grass filtered out to avoid specks
- Biome tooltip when hovering estimated areas, e.g. `Plains (estimated)`
- Estimates are saved per world and show instantly next time, even outside DH's current range
- In-map settings button (next to the other addon buttons): radius, tint, shading, refresh rate, memory, clear estimate
- Fail-safe: if anything goes wrong it logs once and turns itself off; JourneyMap keeps working

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.262+ |
| JourneyMap | 6.0.9 (tested) |
| Distant Horizons | 3.3.3 (tested) |

Client-only. Not needed (and does nothing) on servers.

Settings button, biome tooltip, map state and the cache folder all use JourneyMap's official addon API. Two
things still rely on internals, because the official routes would cost far more memory or speed:

- **JourneyMap:** one mixin draws the estimate *under* JourneyMap's tiles. The addon API can only add overlays on
  top, which would need several image copies per region (roughly 300 KB instead of 80 KB).
- **Distant Horizons:** LOD sections are read directly. DH's public terrain API works per block column at full
  detail (262k lookups per region instead of ~4 section reads).

Other versions of either mod may therefore stop the estimate from working. It then disables itself and logs a
warning instead of crashing; JourneyMap keeps working normally.

## Settings

Fullscreen map → button with the mountains and gear, or Mods → DH JourneyMap Bridge → Config.
Saved to `config/dhjourneymap-client.toml`. Cached estimates live in JourneyMap's per-world addon folder,
`journeymap/data/<sp|mp>/<world>/addon-data/dhjourneymap/`.

## Building

Needs JDK 21. Everything else (NeoForge, JourneyMap, Distant Horizons) is downloaded by Gradle:

```
./gradlew build
```

The jar ends up in `build/libs/`. To copy it straight into a game instance:

```
./gradlew installToProfile -PinstallDir="/path/to/instance/mods"
```

(or put `installDir=...` into `~/.gradle/gradle.properties`).

Dependency versions are in `gradle.properties`. JourneyMap and Distant Horizons are compile-only and pulled from
[Modrinth's Maven](https://support.modrinth.com/en/articles/8801191-modrinth-maven) and JourneyMap's API Maven;
they are never bundled.

### How it works

- `MapRendererMixin` draws the estimate under JourneyMap's fullscreen region tiles (JM tiles are transparent where
  unexplored) and makes those tiles opaque so real data fully covers it.
- `DhTiles` schedules and draws one 128 px texture per JourneyMap region (512 blocks), nearest to the player first.
  Background threads read DH's stored LOD sections (detail 8, falling back to 9). This never triggers DH world
  generation.
- `DhTileStore` saves and loads finished tiles; `DhMapPlugin` / `DhHoverTooltip` handle everything that goes through
  JourneyMap's official API (button, events, data folder, map state).

## Licence

MIT, see [LICENSE](LICENSE). Third-party notices, including how JourneyMap (All Rights Reserved) and Distant
Horizons (LGPL-3.0) are used, are in [THIRD_PARTY.md](THIRD_PARTY.md).

Not affiliated with or endorsed by the JourneyMap or Distant Horizons teams.
