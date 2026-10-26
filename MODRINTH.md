# DH JourneyMap Bridge

**See the whole world on your JourneyMap, not just the parts you've walked.**

Distant Horizons already knows what the terrain looks like far beyond your view distance. JourneyMap only maps
what you have actually loaded. This addon brings the two together: unexplored parts of the JourneyMap fullscreen
map are filled with terrain estimated from Distant Horizons' LOD data, tinted sepia so you always know which parts
are estimated and which are real.

Perfect for servers with a low view distance, where your map would otherwise stay mostly empty.

<!-- SCREENSHOT 1 (hero, most important): the same fullscreen map area side by side,
     left = without the mod (mostly dark/empty), right = with the mod (filled, sepia areas around your explored path).
     Upload to the gallery, then replace the URL below. -->
![Before and after: JourneyMap with and without DH estimates](https://cdn.modrinth.com/REPLACE_ME/before-after.png)

## Features

- **Fills unexplored areas** of the JourneyMap fullscreen map with terrain from Distant Horizons
- **Real map always wins**: JourneyMap's own data stays on top, the estimate only shows where JourneyMap has nothing
- **Clearly marked**: estimated areas get an adjustable sepia tint
- **Looks like DH**: uses Distant Horizons' own block colours, with hill shading so terrain height is readable
- **Biome on hover**: hover an estimated area to see its biome, e.g. *Plains (estimated)*
- **Saved per world**: estimates are cached and show instantly next session, even areas outside DH's current range
- **In-map settings**: one button in the map's addon column, no config files needed
- **Never generates terrain**: it only reads what Distant Horizons has already stored
- **Fail-safe**: if something goes wrong, it logs a message and switches itself off; JourneyMap keeps working

<!-- SCREENSHOT 2: zoomed far out, showing a large estimated area around your base/travel path. -->
![Zoomed-out overview of a world filled with estimated terrain](https://cdn.modrinth.com/REPLACE_ME/overview.png)

<!-- SCREENSHOT 3: close-up of the border between real JourneyMap data and the sepia estimate,
     ideally with the biome tooltip visible over the estimated side. -->
![Close-up of real map data next to the estimate, with biome tooltip](https://cdn.modrinth.com/REPLACE_ME/border-tooltip.png)

## How to use

1. Install JourneyMap and Distant Horizons (see requirements below)
2. Play as usual and let Distant Horizons load distant terrain
3. Open the JourneyMap fullscreen map: unexplored areas fill in, nearest to you first

## Settings

Open the fullscreen map and click the **mountains-and-gear** button in the addon button column, or go to
*Mods → DH JourneyMap Bridge → Config*.

<!-- SCREENSHOT 4: the settings screen opened from the map. -->
![Settings screen](https://cdn.modrinth.com/REPLACE_ME/settings.png)

| Setting | Default | What it does |
|---|---|---|
| Show DH estimate | On | Turn the whole feature on or off |
| Build radius around player | 8192 blocks | New estimates are built only within this distance; saved ones show everywhere |
| Sepia tint | 0.7 | 0 = Distant Horizons colours unchanged, 1 = full sepia |
| Hill shading | On | Shades slopes so terrain height is readable |
| Ignore flowers & grass | On | Uses the ground under flowers, grass etc. to avoid coloured specks |
| Biome on hover | On | Tooltip with the biome over estimated areas |
| Refresh interval | 60 s | How often visible estimates are rebuilt to pick up new DH terrain |
| Max regions in memory | 1024 | About 64 MB video memory + 16 MB RAM when full |
| Background threads | Auto | Threads building estimates (restart needed) |

There is also a **Clear estimated map** button that deletes the saved estimate for the current world.
JourneyMap's real map is never touched.

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.262 or newer |
| [JourneyMap](https://modrinth.com/mod/journeymap) | 6.0.9 (tested) |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | 3.3.3 (tested) |

**Client-side only.** Nothing to install on the server. Works in singleplayer and multiplayer.

## Known limitations

- **Fullscreen surface maps only.** The minimap, cave maps and the Nether are not supported yet.
- **Estimates are approximate.** One map pixel covers 4×4 blocks (8×8 where Distant Horizons only has coarse data),
  so edges look blockier than JourneyMap's real map.
- **Only what Distant Horizons has stored.** Areas DH has never loaded stay empty.
- **Version sensitive.** The mod relies on some internals of JourneyMap and Distant Horizons for performance. Other
  versions may stop the estimate from working; it then switches itself off instead of crashing.

## FAQ

**Does it slow down my game?**
Estimates are built on low-priority background threads and only while the fullscreen map is open. The map itself
draws one texture per 512×512-block region.

**Does it change my JourneyMap data?**
No. Estimates are stored separately in JourneyMap's addon folder for each world
(`journeymap/data/.../addon-data/dhjourneymap`) and can be cleared from the settings at any time.

**Do I need Distant Horizons on the server?**
No. The mod uses whatever your Distant Horizons client has stored, whether it came from your own exploration or
from a server running Distant Horizons.

## Links

- Source code: [GitHub](https://github.com/LoaderB0T/distanthorizons-estimations-journeymap)
- Bug reports and ideas: [Issue tracker](https://github.com/LoaderB0T/distanthorizons-estimations-journeymap/issues)

---

*Unofficial addon. Not affiliated with or endorsed by the JourneyMap or Distant Horizons teams.
Licensed under MIT.*
