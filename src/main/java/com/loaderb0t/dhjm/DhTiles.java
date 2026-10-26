package com.loaderb0t.dhjm;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Bakes one texture per JourneyMap region (512x512 blocks) from Distant Horizons' stored LOD data
 * and draws them. Baking runs on a background thread; all texture work stays on the render thread.
 */
public final class DhTiles {
    private static final Logger LOG = LogUtils.getLogger();

    // DH section detail 8 = 256 blocks wide, 64 columns of 4 blocks -> 128px per region.
    // Detail 9 (one section == one region, 8 blocks/column) is painted first as fallback where 8 is missing.
    private static final byte FINE = 8;
    private static final byte COARSE = 9;
    private static final int PX = 128;
    private static final int BLOCKS_PER_PX = 512 / PX;

    // Textured tiles are capped by DhConfig.MAX_TILES (64 KB VRAM + 16 KB RAM biome codes each). When full, the tile farthest from the
    // player goes, so the estimate always covers the regions nearest the player, independent of where the map is panned.
    private static final int MAX_EMPTY = 65_536;
    private static final int MAX_QUEUE = 256;

    private record Key(String dim, int rx, int rz) {}

    /** Settings that are baked into tile pixels; read once per bake. */
    private record Style(float tint, boolean hillshade, boolean hideDecoration) {
        static Style current() {
            return new Style(DhConfig.TINT.get().floatValue(), DhConfig.HILLSHADE.get(), DhConfig.HIDE_DECORATION.get());
        }
    }

    /**
     * LOAD: disk cache, else build from DH. DISK_ONLY: outside the radius, show what's saved but never build.
     * REFRESH: we already show a tile here, rebuild it from DH (skip the disk cache).
     */
    private enum Mode { LOAD, DISK_ONLY, REFRESH }

    private record Request(Key key, Mode mode) {}

    /** Result of a bake; immutable once handed to the render thread. */
    private record Baked(Key key, int generation, NativeImage image, ResourceLocation[] biomes, byte[] biomeAt, boolean fromDisk) {
        static Baked empty(Key key, int generation) {
            return new Baked(key, generation, null, null, null, false);
        }
    }

    private static final class Tile {
        GpuTexture tex;
        long bakedAt;
        long drawnFrame;
    }

    /** Per-pixel biome of a baked tile, see {@link #biomeCode}. */
    private record BiomeGrid(ResourceLocation[] biomes, byte[] at) {}

    // Render thread only.
    private static final HashMap<Key, Tile> TILES = new HashMap<>();
    private static int playerX, playerZ, playerRx, playerRz;
    /** Regions DH had no data for, with bake time. Kept apart so they don't take texture slots. */
    private static final HashMap<Key, Long> EMPTY = new HashMap<>();
    private static final List<Key> WANTED = new ArrayList<>();
    private static final Map<Key, Mode> WANTED_MODE = new HashMap<>();
    private static Object lastWorld;
    private static long frame;
    // Uploading is cheap, but a burst after a big zoom-out shouldn't land in one frame.
    private static final int MAX_UPLOADS_PER_FRAME = 16;

    // Read by JM's hover-text thread.
    private static final ConcurrentHashMap<Key, BiomeGrid> BIOMES = new ConcurrentHashMap<>();
    private static volatile String lastDim;

    // Shared with the workers.
    private static final LinkedBlockingDeque<Request> QUEUE = new LinkedBlockingDeque<>();
    /** Disk cache folder of the current world, null = don't persist. */
    private static volatile java.nio.file.Path cacheDir;
    private static final Set<Key> IN_FLIGHT = ConcurrentHashMap.newKeySet(); // taken by a worker, not yet consumed
    private static final ConcurrentLinkedQueue<Baked> DONE = new ConcurrentLinkedQueue<>();
    private static volatile boolean colorFailLogged;
    private static volatile boolean disabled;
    private static volatile boolean bakeFailLogged;
    /** Bumped when settings change; bakes started under an older generation are thrown away. */
    private static volatile int generation;
    private static int clearedGeneration; // render thread only

    // Baking is mostly DH database reads + colour lookups; a few threads overlap the waits. Low priority keeps FPS safe.
    private static final int WORKERS = DhConfig.BAKE_THREADS.get() > 0 ? DhConfig.BAKE_THREADS.get()
            : Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));

    static {
        for (int i = 0; i < WORKERS; i++) {
            Thread worker = new Thread(DhTiles::bakeLoop, "DH-JourneyMap baker " + i);
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    private DhTiles() {}

    /** False after an internal error (see {@link #fail}) or when switched off in the settings. */
    public static boolean enabled() {
        try {
            return !disabled && DhConfig.ENABLED.get();
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** Settings changed: rebuild every tile with the new values. Any thread. */
    public static void invalidate() {
        generation++;
    }

    /** Frees all textures and cached data (render thread). Used when the feature is switched off. */
    public static void releaseAll() {
        if (TILES.isEmpty() && EMPTY.isEmpty()) return;
        for (Tile t : TILES.values()) t.tex.close();
        TILES.clear();
        EMPTY.clear();
        BIOMES.clear();
        QUEUE.clear();
    }

    /**
     * Turns the estimate off for the rest of the session after an unexpected error, so a bug here
     * never breaks JourneyMap or the game. Logged once with the full stack trace.
     */
    public static void fail(String where, Throwable t) {
        if (disabled) return;
        disabled = true;
        LOG.error("DH JourneyMap Bridge disabled after an error while {}; JourneyMap keeps working without the DH estimate", where, t);
    }

    // ---------------------------------------------------------------- render thread

    /** Call once per frame before {@link #draw}. Uploads finished bakes, drops everything on world change. */
    public static void beginFrame(String dim) {
        Object world = SharedApi.getAbstractDhWorld();
        int gen = generation;
        if (world != lastWorld || gen != clearedGeneration) {
            if (world != lastWorld) cacheDir = DhTileStore.worldDir();
            lastWorld = world;
            clearedGeneration = gen;
            releaseAll();
        }
        lastDim = dim;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            playerX = player.getBlockX();
            playerZ = player.getBlockZ();
            playerRx = Math.floorDiv(playerX, 512);
            playerRz = Math.floorDiv(playerZ, 512);
        }
        frame++;
        WANTED.clear();
        WANTED_MODE.clear();

        long now = System.currentTimeMillis();
        int maxTiles = DhConfig.MAX_TILES.get();
        Baked b;
        for (int n = 0; n < MAX_UPLOADS_PER_FRAME && (b = DONE.poll()) != null; n++) {
            IN_FLIGHT.remove(b.key);
            if (b.generation != gen) {
                if (b.image != null) b.image.close(); // baked with old settings
                continue;
            }
            Tile existing = TILES.get(b.key);
            if (b.image == null) {
                // Never downgrade a tile we already have because one rebake came back empty (busy/timed-out read).
                if (existing != null) existing.bakedAt = now;
                else {
                    if (EMPTY.size() >= MAX_EMPTY) EMPTY.clear();
                    EMPTY.put(b.key, now);
                }
                continue;
            }
            EMPTY.remove(b.key);
            if (existing == null && TILES.size() >= maxTiles && !makeRoomFor(b.key)) {
                b.image.close(); // budget full of visible tiles nearer the player
                continue;
            }
            Tile t = existing != null ? existing : new Tile();
            if (t.tex != null) t.tex.close();
            t.tex = new GpuTexture(b.image); // takes ownership and frees the image after upload
            t.bakedAt = b.fromDisk ? 0 : now; // disk tiles show instantly, then refresh from DH right away (inside the radius)
            t.drawnFrame = frame; // don't let the next upload in this frame evict it as "off-screen"
            BIOMES.put(b.key, new BiomeGrid(b.biomes, b.biomeAt));
            if (existing == null) TILES.put(b.key, t);
        }
    }

    private static int playerDist(Key k) {
        int dx = k.rx - playerRx, dz = k.rz - playerRz;
        return dx * dx + dz * dz;
    }

    private static boolean offScreen(Tile t) {
        return t.drawnFrame < frame - 1;
    }

    /**
     * Eviction candidate: off-screen tiles first, then the one farthest from the player.
     * So panning to old saved areas swaps tiles in, while on-screen tiles near the player always stay.
     */
    private static Key victim() {
        Key victim = null;
        boolean victimOff = false;
        for (Map.Entry<Key, Tile> e : TILES.entrySet()) {
            boolean off = offScreen(e.getValue());
            if (victim == null || (off && !victimOff) || (off == victimOff && playerDist(e.getKey()) > playerDist(victim))) {
                victim = e.getKey();
                victimOff = off;
            }
        }
        return victim;
    }

    /** Frees one slot for incoming, unless every tile is on screen and nearer the player than it. */
    private static boolean makeRoomFor(Key incoming) {
        Key v = victim();
        if (v == null || (!offScreen(TILES.get(v)) && playerDist(v) <= playerDist(incoming))) return false;
        TILES.remove(v).tex.close();
        BIOMES.remove(v);
        return true;
    }

    /** Draws the DH estimate for region (rx, rz) as a size x size quad at (x, y) in the current pose. */
    public static void draw(GuiGraphics graphics, String dim, int rx, int rz, double x, double y, int size) {
        // The radius limits what gets built from DH; saved tiles show everywhere.
        long dx = rx * 512L + 256 - playerX, dz = rz * 512L + 256 - playerZ, r = DhConfig.RADIUS_BLOCKS.get();
        boolean inRadius = dx * dx + dz * dz <= r * r;

        Key key = new Key(dim, rx, rz);
        Tile tile = TILES.get(key);
        Long bakedAt = tile != null ? Long.valueOf(tile.bakedAt) : EMPTY.get(key);
        boolean stale = bakedAt != null && System.currentTimeMillis() - bakedAt > DhConfig.REFRESH_SECONDS.get() * 1000L;
        if (bakedAt == null) {
            want(key, inRadius ? Mode.LOAD : Mode.DISK_ONLY);
        } else if (stale && tile == null) {
            want(key, inRadius ? Mode.REFRESH : Mode.DISK_ONLY); // empty before: DH or the disk may have it now
        } else if (stale && inRadius) {
            want(key, Mode.REFRESH);
        }
        if (tile == null) return;
        tile.drawnFrame = frame;

        Matrix4f m = graphics.pose().last().pose();
        float x0 = (float) x, y0 = (float) y, x1 = x0 + size, y1 = y0 + size;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tile.tex.getId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.addVertex(m, x0, y0, 0).setUv(0, 0);
        buf.addVertex(m, x0, y1, 0).setUv(0, 1);
        buf.addVertex(m, x1, y1, 0).setUv(1, 1);
        buf.addVertex(m, x1, y0, 0).setUv(1, 0);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void want(Key key, Mode mode) {
        WANTED.add(key);
        WANTED_MODE.put(key, mode);
    }

    /** Replaces the bake queue with this frame's wishes: new tiles before refreshes, nearest to the player first. */
    public static void endFrame() {
        WANTED.removeIf(IN_FLIGHT::contains);
        if (TILES.size() >= DhConfig.MAX_TILES.get()) {
            Key v = victim();
            if (v != null && !offScreen(TILES.get(v))) {
                // Budget full of on-screen tiles: only fetch new ones that would displace one farther from the player.
                int farthest = playerDist(v);
                WANTED.removeIf(k -> WANTED_MODE.get(k) != Mode.REFRESH && playerDist(k) >= farthest);
            }
        }
        WANTED.sort(Comparator.<Key>comparingInt(k -> WANTED_MODE.get(k) == Mode.REFRESH ? 1 : 0)
                .thenComparingInt(DhTiles::playerDist));
        QUEUE.clear();
        for (Key k : WANTED.subList(0, Math.min(WANTED.size(), MAX_QUEUE))) QUEUE.add(new Request(k, WANTED_MODE.get(k)));
    }

    /**
     * Forgets the estimate for the current world: memory and the disk cache. JourneyMap's own map is untouched.
     * Render thread.
     */
    public static void clearEstimate() {
        java.nio.file.Path dir = cacheDir != null ? cacheDir : DhTileStore.worldDir();
        invalidate(); // in-flight bakes are dropped and not saved
        releaseAll();
        java.util.concurrent.CompletableFuture.runAsync(() -> DhTileStore.deleteWorld(dir));
    }

    /** DH biome id at a block in the last drawn dimension, or null if no estimate is loaded there. Any thread. */
    public static ResourceLocation biomeAt(int blockX, int blockZ) {
        String dim = lastDim;
        if (dim == null) return null;
        BiomeGrid g = BIOMES.get(new Key(dim, Math.floorDiv(blockX, 512), Math.floorDiv(blockZ, 512)));
        if (g == null) return null;
        int code = g.at[Math.floorMod(blockZ, 512) / BLOCKS_PER_PX * PX + Math.floorMod(blockX, 512) / BLOCKS_PER_PX] & 0xFF;
        return code == 0 || code > g.biomes.length ? null : g.biomes[code - 1];
    }

    // ---------------------------------------------------------------- worker thread

    private static void bakeLoop() {
        while (true) {
            Request req;
            try {
                req = QUEUE.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            Key key = req.key;
            IN_FLIGHT.add(key);
            int gen = generation;
            java.nio.file.Path dir = cacheDir;
            Baked baked = Baked.empty(key, gen);
            try {
                DhTileStore.Stored stored = req.mode == Mode.REFRESH || dir == null ? null : DhTileStore.load(dir, key.dim, key.rx, key.rz, PX);
                if (stored != null) {
                    baked = new Baked(key, gen, stored.image(), stored.biomes(), stored.biomeAt(), true);
                } else if (req.mode != Mode.DISK_ONLY) {
                    baked = bake(key, gen);
                    // Saved before the render thread gets the image; skipped if settings changed or the cache was cleared meanwhile.
                    if (baked.image != null && dir != null && gen == generation) {
                        DhTileStore.save(dir, key.dim, key.rx, key.rz, baked.image, baked.biomes, baked.biomeAt);
                    }
                }
            } catch (Throwable t) {
                // Bake errors only cost that tile (retried later), so the estimate stays on; log the first in full.
                if (!bakeFailLogged) {
                    bakeFailLogged = true;
                    LOG.warn("Failed to bake DH map tile {} (further bake errors logged at debug)", key, t);
                } else {
                    LOG.debug("Failed to bake DH map tile {}: {}", key, t.toString());
                }
            }
            DONE.add(baked);
        }
    }

    private static Baked bake(Key key, int gen) throws Exception {
        AbstractDhWorld world = SharedApi.getAbstractDhWorld();
        IDhLevel level = null;
        if (world != null) {
            for (IDhLevel l : world.getAllLoadedLevels()) {
                if (key.dim.equals(l.getLevelWrapper().getDimensionName())) level = l;
            }
        }
        if (level == null) return Baked.empty(key, gen);
        Style style = Style.current();

        NativeImage image = new NativeImage(PX, PX, true); // calloc -> fully transparent
        byte[] biomeAt = new byte[PX * PX]; // 0 = no biome
        Map<ResourceLocation, Integer> palette = new HashMap<>();

        int painted = 0;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                painted += paint(image, biomeAt, palette, level, FINE, key.rx * 2 + i, key.rz * 2 + j, key, false, style);
            }
        }
        // Only read the coarse section when fine data has gaps (usual case: it doesn't, saving a DB read).
        if (painted < PX * PX) painted += paint(image, biomeAt, palette, level, COARSE, key.rx, key.rz, key, true, style);
        if (painted == 0) {
            image.close();
            return Baked.empty(key, gen);
        }
        ResourceLocation[] biomes = new ResourceLocation[palette.size()];
        palette.forEach((rl, idx) -> biomes[idx] = rl);
        return new Baked(key, gen, image, biomes, biomeAt, false);
    }

    /**
     * Paints one DH section into the region image and returns the number of pixels painted (0 if DH has no data).
     * With onlyEmpty, pixels already painted by finer data are left alone.
     */
    private static int paint(NativeImage image, byte[] biomeAt, Map<ResourceLocation, Integer> palette,
                             IDhLevel level, byte detail, int sx, int sz, Key key, boolean onlyEmpty, Style style) throws Exception {
        // getAsync runs on DH's own file thread and only reads the database; it never triggers generation.
        // Timeout so a stuck DH read only skips this section instead of stalling the baker forever.
        java.util.concurrent.CompletableFuture<FullDataSourceV2> future =
                level.getFullDataProvider().getAsync(DhSectionPos.encode(detail, sx, sz));
        FullDataSourceV2 ds;
        try {
            ds = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.thenAccept(late -> { if (late != null) late.close(); });
            return 0;
        }
        if (ds == null) return 0;
        try (ds) {
            if (ds.isEmpty) return 0;
            ILevelWrapper lw = level.getLevelWrapper();
            // In singleplayer/LAN the level wraps the server level, which can't compute block colours.
            ILevelWrapper colorLw = level instanceof IDhClientLevel client && client.getClientLevelWrapper() != null
                    ? client.getClientLevelWrapper() : lw;
            int minY = lw.getMinHeight();
            int blocksPerCol = 1 << (detail - 6);
            int pxPerCol = blocksPerCol / BLOCKS_PER_PX;
            int originX = sx * 64 * blocksPerCol;
            int originZ = sz * 64 * blocksPerCol;
            int px0 = (originX - key.rx * 512) / BLOCKS_PER_PX;
            int pz0 = (originZ - key.rz * 512) / BLOCKS_PER_PX;

            int[] color = new int[64 * 64];
            int[] height = new int[64 * 64];
            boolean[] liquid = new boolean[64 * 64];
            byte[] biome = new byte[64 * 64];
            boolean any = false;
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    int i = x * 64 + z;
                    height[i] = Integer.MIN_VALUE;
                    LongArrayList col = ds.tryGetColumnAtRelPos(x, z);
                    if (col == null) continue;
                    // Columns are stored top-down. One LOD column covers several blocks, so a single flower or
                    // tuft of grass would tint the whole pixel; skip non-collidable decoration (except snow layers)
                    // and use the ground below, falling back to the first non-air point if nothing else exists.
                    long surface = 0L;
                    for (int p = 0; p < col.size(); p++) {
                        long dp = col.getLong(p);
                        if (dp == 0L) continue;
                        IBlockStateWrapper block = ds.mapping.getBlockStateWrapper(FullDataPointUtil.getId(dp));
                        if (block.isAir()) continue;
                        if (surface == 0L) surface = dp;
                        if (!style.hideDecoration || isGround(block)) {
                            surface = dp;
                            break;
                        }
                    }
                    if (surface == 0L) continue;
                    int id = FullDataPointUtil.getId(surface);
                    IBlockStateWrapper block = ds.mapping.getBlockStateWrapper(id);
                    IBiomeWrapper biomeWrapper = ds.mapping.getBiomeWrapper(id);
                    int top = FullDataPointUtil.getBottomY(surface) + FullDataPointUtil.getHeight(surface) + minY;
                    color[i] = blockColor(colorLw, block, biomeWrapper, originX + x * blocksPerCol, top - 1, originZ + z * blocksPerCol, ds);
                    height[i] = top;
                    liquid[i] = block.isLiquid();
                    biome[i] = biomeCode(palette, biomeWrapper);
                    any = true;
                }
            }
            if (!any) return 0;

            int painted = 0;
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    int i = x * 64 + z;
                    if (height[i] == Integer.MIN_VALUE) continue;
                    float shade = liquid[i] || !style.hillshade ? 1f : relief(height, i, x, z, blocksPerCol);
                    int abgr = toSepiaAbgr(color[i], shade, style.tint);
                    for (int dx = 0; dx < pxPerCol; dx++) {
                        for (int dz = 0; dz < pxPerCol; dz++) {
                            int px = px0 + x * pxPerCol + dx, pz = pz0 + z * pxPerCol + dz;
                            if (onlyEmpty && (image.getPixelRGBA(px, pz) >>> 24) != 0) continue;
                            image.setPixelRGBA(px, pz, abgr);
                            biomeAt[pz * PX + px] = biome[i];
                            painted++;
                        }
                    }
                }
            }
            return painted;
        }
    }

    /** Collidable blocks, fluids and snow count as ground; flowers, grass, saplings, torches etc. don't. */
    private static boolean isGround(IBlockStateWrapper block) {
        return block.isSolid() || block.isLiquid() || block.getSerialString().contains("snow");
    }

    /** Slope-based hillshade (light from north-west), scaled by column width so coarse LOD noise stays faint. */
    private static float relief(int[] height, int i, int x, int z, int blocksPerCol) {
        int h = height[i];
        int north = z > 0 && height[i - 1] != Integer.MIN_VALUE ? height[i - 1] : h;
        int west = x > 0 && height[i - 64] != Integer.MIN_VALUE ? height[i - 64] : h;
        float slope = (float) ((h - north) + (h - west)) / blocksPerCol;
        return 1f + Math.max(-0.3f, Math.min(0.2f, slope * 0.12f));
    }

    private static int blockColor(ILevelWrapper lw, IBlockStateWrapper block, IBiomeWrapper biome,
                                  int x, int y, int z, FullDataSourceV2 ds) {
        DhApiResult<Color> c = lw.getBlockColorPreApi(block, biome, x, y, z, ds);
        if (c.success && c.payload != null) return c.payload.getRGB();
        if (!colorFailLogged) {
            colorFailLogged = true;
            LOG.warn("DH block colour lookup failed ({}), falling back to vanilla map colours", c.message);
        }
        return block.getWrappedMcObject() instanceof BlockState state
                ? 0xFF000000 | state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col
                : 0xFF7F7F7F;
    }

    /**
     * Per-pixel biome as one unsigned byte: 0 = unknown, 1..255 = palette index + 1. A region with more than
     * 255 distinct biomes (practically impossible) just shows no hover biome for the extra ones.
     */
    private static byte biomeCode(Map<ResourceLocation, Integer> palette, IBiomeWrapper biome) {
        // DH's getName() returns the registry name, and biomes loaded from its database are key-less
        // Holder.Direct, so the serial string ("minecraft:plains") is the only reliable id.
        ResourceLocation id = ResourceLocation.tryParse(biome.getSerialString());
        if (id == null) return 0;
        Integer idx = palette.get(id);
        if (idx == null) {
            if (palette.size() >= MAX_BIOMES) {
                if (!biomeOverflowLogged) {
                    biomeOverflowLogged = true;
                    LOG.debug("More than {} biomes in one region; extra biomes won't show in the hover text", MAX_BIOMES);
                }
                return 0;
            }
            idx = palette.size();
            palette.put(id, idx);
        }
        return (byte) (idx + 1);
    }

    private static final int MAX_BIOMES = 255;
    private static volatile boolean biomeOverflowLogged;

    /** Texture that lives only on the GPU: the image is freed right after upload (DynamicTexture keeps a RAM copy). */
    private static final class GpuTexture extends AbstractTexture {
        GpuTexture(NativeImage image) {
            TextureUtil.prepareImage(getId(), image.getWidth(), image.getHeight());
            bind();
            image.upload(0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), false, true); // autoClose
        }

        @Override
        public void load(ResourceManager manager) {
        }

        @Override
        public void close() {
            releaseId();
        }
    }

    /** tint: 0 = original colour, 1 = full sepia. */
    static int toSepiaAbgr(int argb, float shade, float tint) {
        float r = (argb >> 16 & 0xFF) * shade, g = (argb >> 8 & 0xFF) * shade, b = (argb & 0xFF) * shade;
        float sr = 0.393f * r + 0.769f * g + 0.189f * b;
        float sg = 0.349f * r + 0.686f * g + 0.168f * b;
        float sb = 0.272f * r + 0.534f * g + 0.131f * b;
        int R = clamp(r + (sr - r) * tint), G = clamp(g + (sg - g) * tint), B = clamp(b + (sb - b) * tint);
        return 0xFF000000 | B << 16 | G << 8 | R;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }
}
