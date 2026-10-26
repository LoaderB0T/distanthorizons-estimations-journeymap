package com.loaderb0t.dhjm;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import journeymap.api.v2.client.IClientAPI;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists baked estimate tiles so they show instantly next session (and survive DH dropping data).
 * Layout: {@code <JM addon-data folder>/<dim>/<rx>,<rz>.png} + {@code .biomes} (gzip), see {@link #worldDir()}.
 * Only this folder is ever touched; JourneyMap's own map data is never read or written.
 */
final class DhTileStore {
    private static final Logger LOG = LogUtils.getLogger();
    /** 2: one unsigned byte per pixel (0 = none, n = biomes[n-1]). 1: shorts, -1 = none (still readable). */
    private static final int FORMAT = 2;
    private static volatile boolean writeFailLogged;

    record Stored(NativeImage image, ResourceLocation[] biomes, byte[] biomeAt) {}

    private DhTileStore() {}

    /**
     * Cache folder for the current world, or null if there is none (e.g. main menu). Render thread.
     * JourneyMap's official per-world addon folder: {@code journeymap/data/<sp|mp>/<world>/addon-data/dhjourneymap}.
     */
    static Path worldDir() {
        try {
            IClientAPI api = DhMapPlugin.api();
            File dir = api == null ? null : api.getDataPath(DhJourneyMap.MOD_ID);
            return dir == null ? null : dir.toPath();
        } catch (Throwable t) {
            LOG.warn("Can't resolve the estimate cache folder; estimates won't be saved this session", t);
            return null;
        }
    }

    private static Path base(Path worldDir, String dim, int rx, int rz) {
        return worldDir.resolve(dim.replace(':', '~').replace('/', '~')).resolve(rx + "," + rz);
    }

    /** Worker thread. Failures are logged once and otherwise ignored: the cache is an optimisation. */
    static void save(Path worldDir, String dim, int rx, int rz, NativeImage image, ResourceLocation[] biomes, byte[] biomeAt) {
        Path base = base(worldDir, dim, rx, rz);
        try {
            Files.createDirectories(base.getParent());
            Path png = sibling(base, ".png"), pngTmp = sibling(base, ".png.tmp");
            image.writeToFile(pngTmp);
            Path bio = sibling(base, ".biomes"), bioTmp = sibling(base, ".biomes.tmp");
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(bioTmp)))) {
                out.writeInt(FORMAT);
                out.writeInt(biomes.length);
                for (ResourceLocation rl : biomes) out.writeUTF(rl.toString());
                out.writeInt(biomeAt.length);
                out.write(biomeAt);
            }
            // Temp + move so a crash mid-write never leaves a half-written tile behind.
            Files.move(pngTmp, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(bioTmp, bio, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (!writeFailLogged) {
                writeFailLogged = true;
                LOG.warn("Failed to save estimate tile to {} (further failures not logged)", base, e);
            }
        }
    }

    /** Worker thread. Returns null if nothing usable is stored. */
    static Stored load(Path worldDir, String dim, int rx, int rz, int px) {
        Path base = base(worldDir, dim, rx, rz);
        Path png = sibling(base, ".png");
        if (!Files.isRegularFile(png)) return null;
        NativeImage image;
        try (InputStream in = Files.newInputStream(png)) {
            image = NativeImage.read(in);
        } catch (IOException e) {
            return null;
        }
        if (image.getWidth() != px || image.getHeight() != px) {
            image.close();
            return null;
        }
        ResourceLocation[] biomes = new ResourceLocation[0];
        byte[] biomeAt = new byte[px * px];
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(sibling(base, ".biomes"))))) {
            int format = in.readInt();
            if (format == 1 || format == FORMAT) {
                ResourceLocation[] b = new ResourceLocation[in.readInt()];
                for (int i = 0; i < b.length; i++) b[i] = ResourceLocation.parse(in.readUTF());
                if (in.readInt() == biomeAt.length) {
                    if (format == FORMAT) {
                        in.readFully(biomeAt);
                    } else {
                        for (int i = 0; i < biomeAt.length; i++) {
                            int s = in.readShort();
                            biomeAt[i] = s < 0 || s >= 254 ? 0 : (byte) (s + 1); // v1 index -> v2 code
                        }
                    }
                    biomes = b; // codes past biomes.length are ignored on lookup
                }
            }
        } catch (Exception e) {
            Arrays.fill(biomeAt, (byte) 0); // tile still shows, just without hover biomes
        }
        return new Stored(image, biomes, biomeAt);
    }

    /** Packed (rx, rz) of every region saved for one dimension. Any thread; lists one folder, no file reads. */
    static java.util.Set<Long> listSaved(Path worldDir, String dim) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        Path folder = base(worldDir, dim, 0, 0).getParent();
        if (!Files.isDirectory(folder)) return out;
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.endsWith(".png")) return;
                int comma = name.indexOf(',');
                try {
                    out.add(pack(Integer.parseInt(name.substring(0, comma)), Integer.parseInt(name.substring(comma + 1, name.length() - 4))));
                } catch (RuntimeException ignored) {
                    // not one of ours
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to list saved estimate tiles in {}", folder, e);
        }
        return out;
    }

    static long pack(int rx, int rz) {
        return (long) rx << 32 | (rz & 0xFFFFFFFFL);
    }

    /** Deletes every saved estimate tile of one world. Any thread. */
    static void deleteWorld(Path worldDir) {
        if (worldDir == null || !Files.isDirectory(worldDir)) return;
        try (Stream<Path> walk = Files.walk(worldDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            LOG.info("Cleared estimated map cache {}", worldDir);
        } catch (IOException e) {
            LOG.warn("Failed to clear estimated map cache {}", worldDir, e);
        }
    }

    private static Path sibling(Path base, String suffix) {
        return base.resolveSibling(base.getFileName() + suffix);
    }
}
