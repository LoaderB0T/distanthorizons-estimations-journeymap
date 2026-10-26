# DH JourneyMap Bridge

Client-only NeoForge 1.21.1 mod. Fills unexplored areas of the JourneyMap **fullscreen** map with
sepia-tinted terrain estimated from Distant Horizons' stored LOD data.

- `MapRendererMixin` hooks `journeymap.client.render.map.MapRenderer#render` (fullscreen only), draws the
  estimate under JM's tiles and forces JM tiles to full opacity so explored areas cover it.
- `DhTiles` bakes one 128px texture per JM region (512 blocks) from DH sections at detail 9 (fallback)
  and 8 (4 blocks/px) on a background thread. Reads DH's database only, never triggers generation.

Built against the exact jars in `libs/` (copied from the profile, gitignored): JourneyMap 6.0.9 +
its API 2.0.0, Distant Horizons 3.3.3. Uses DH core internals, so expect breakage on DH/JM updates.

```
gradlew build              # -> build/libs/dhjourneymap-1.0.0.jar
gradlew installToProfile   # copies jar into the Modrinth profile mods folder
```
Needs JDK 21 (`JAVA_HOME`).
