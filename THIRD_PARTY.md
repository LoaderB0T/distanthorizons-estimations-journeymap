# Third-party notices

This repository contains no code or binaries from JourneyMap or Distant Horizons. Both are
downloaded at build time as compile-only dependencies and are never bundled into the mod jar.
Players install them separately.

| Component | Licence | How it is used |
|---|---|---|
| [JourneyMap](https://modrinth.com/mod/journeymap) | All Rights Reserved | Required at runtime. Uses its public addon API (`info.journeymap:journeymap-api-neoforge`); one rendering mixin is compiled against the released jar from Modrinth's Maven. No JourneyMap code is copied or redistributed. |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | LGPL-3.0-only | Required at runtime. Its public API and some core classes are called at runtime ("work that uses the Library" in LGPL terms). No Distant Horizons code is copied or redistributed. |
| [NeoForge MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle) | MIT, see [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt) | `build.gradle`, `settings.gradle`, `gradle.properties` and the metadata template started from the MDK. |
| [Gradle wrapper](https://gradle.org) | Apache-2.0 | `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, unmodified. |

This mod is an unofficial addon and is not affiliated with or endorsed by the JourneyMap or
Distant Horizons teams.
