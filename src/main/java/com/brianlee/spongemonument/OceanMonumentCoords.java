package com.brianlee.spongemonument;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chunkbase-style candidate generation for ocean monument start chunks.
 *
 * IMPORTANT: This returns *candidates* based on the random-spread placement math (spacing/separation/salt).
 * A candidate region does NOT guarantee a monument will actually generate there (biome checks may fail).
 * The caller can still confirm by checking for an actual StructureStart at the chunk if they want zero false positives.
 */
public final class OceanMonumentCoords {
    private OceanMonumentCoords() {}

    /**
     * Returns candidate monument start chunks within {@code radiusChunks} of {@code centerChunk},
     * sorted by distance to {@code centerChunk}, truncated to {@code maxResults}.
     */
    public static List<ChunkPos> findMonumentStartChunks(
            ServerWorld world,
            ChunkPos centerChunk,
            int radiusChunks,
            int maxResults
    ) {
        final long worldSeed = world.getSeed();

        // Defaults match vanilla RandomSpread placement for monuments.
        final int spacing = 32;
        final int separation = 5;
        final int salt = 10387313;
        final boolean triangular = true;
        final boolean buggyCoordMath = false;

        // Determine region bounds overlapping the search radius.
        int minChunkX = centerChunk.x - radiusChunks;
        int maxChunkX = centerChunk.x + radiusChunks;
        int minChunkZ = centerChunk.z - radiusChunks;
        int maxChunkZ = centerChunk.z + radiusChunks;

        int minRegionX = regionCoordFromChunk(minChunkX, spacing, buggyCoordMath);
        int maxRegionX = regionCoordFromChunk(maxChunkX, spacing, buggyCoordMath);
        int minRegionZ = regionCoordFromChunk(minChunkZ, spacing, buggyCoordMath);
        int maxRegionZ = regionCoordFromChunk(maxChunkZ, spacing, buggyCoordMath);

        List<ChunkPos> out = new ArrayList<>();

        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                ChunkPos start = computeMonumentStartChunk(worldSeed, regionX, regionZ, spacing, separation, salt, triangular);
                ChunkPos refined = refineToLikelyMonumentChunk(world, start);
                if (refined == null) {
                    continue;
                }
                start = refined;

                // Filter within radius (circle) around center.
                // IMPORTANT: apply the radius check after refinement too, since the true center
                // can be a few chunks away from the region anchor.
                int dx = start.x - centerChunk.x;
                int dz = start.z - centerChunk.z;
                if (Math.abs(dx) > radiusChunks || Math.abs(dz) > radiusChunks) {
                    continue;
                }

                out.add(start);
            }
        }

        out.sort(Comparator.comparingLong(c -> distSq(c, centerChunk)));

        if (maxResults > 0 && out.size() > maxResults) {
            return new ArrayList<>(out.subList(0, maxResults));
        }
        return out;
    }

    private static long distSq(ChunkPos a, ChunkPos b) {
        long dx = (long) a.x - b.x;
        long dz = (long) a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static ChunkPos computeMonumentStartChunk(
            long worldSeed,
            int regionX,
            int regionZ,
            int spacing,
            int separation,
            int salt,
            boolean triangular
    ) {
        // Chunkbase/Amidst-style region RNG (matches vanilla RandomSpread placement math).
        // This intentionally uses the legacy 48-bit LCG (java.util.Random compatible) with the
        // region seed formula used by structure placement.
        final long MAGIC1 = 341873128712L;
        final long MAGIC2 = 132897987541L;

        long seed = regionSeed(worldSeed, salt, regionX, regionZ, MAGIC1, MAGIC2);
        FastLCG rand = new FastLCG(seed);

        int bound = spacing - separation;
        int offX = triangular ? (rand.nextInt(bound) + rand.nextInt(bound)) / 2 : rand.nextInt(bound);
        int offZ = triangular ? (rand.nextInt(bound) + rand.nextInt(bound)) / 2 : rand.nextInt(bound);

        int chunkX = regionX * spacing + offX;
        int chunkZ = regionZ * spacing + offZ;
        return new ChunkPos(chunkX, chunkZ);
    }

    private static int regionCoordFromChunk(int chunkCoord, int spacing, boolean buggy) {
        // Mirrors Amidst's coordinate handling for negative values.
        // If buggy=true, use truncating division (historical behavior).
        int modified = buggy ? chunkCoord : modifiedCoord(chunkCoord, spacing);
        return modified / spacing;
    }

    private static int modifiedCoord(int coord, int spacing) {
        // For negative coordinates, shift so integer division behaves like floorDiv.
        return coord < 0 ? coord - spacing + 1 : coord;
    }

    private static long regionSeed(long worldSeed, int salt, int regionX, int regionZ, long magic1, long magic2) {
        return (long) regionX * magic1 + (long) regionZ * magic2 + worldSeed + (long) salt;
    }

    /**
     * Minimal java.util.Random-compatible 48-bit LCG for fast deterministic placement math.
     * (Matches the algorithm used by older structure placement logic and tools like Amidst/Chunkbase.)
     */
    private static final class FastLCG {
        private static final long MULT = 0x5DEECE66DL;
        private static final long ADD  = 0xBL;
        private static final long MASK = (1L << 48) - 1;

        private long seed;

        FastLCG(long seed) {
            // Same scrambling step as java.util.Random
            this.seed = (seed ^ MULT) & MASK;
        }

        private int next(int bits) {
            seed = (seed * MULT + ADD) & MASK;
            return (int) (seed >>> (48 - bits));
        }

        int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");

            // Power-of-two fast path
            int m = bound - 1;
            if ((bound & m) == 0) {
                return (int) ((bound * (long) next(31)) >> 31);
            }

            int u;
            int r;
            do {
                u = next(31);
                r = u % bound;
            } while (u - r + m < 0);
            return r;
        }
    }

    /**
     * If the initially computed candidate chunk is slightly offset from the true monument center,
     * try a small neighborhood and return the first chunk that passes biome checks.
     *
     * This is purely a biome-source heuristic and does not load chunks.
     */
    private static ChunkPos refineToLikelyMonumentChunk(ServerWorld world, ChunkPos candidate) {
        // Fast path: candidate itself passes.
        if (isLikelyMonumentBiomeAtChunk(world, candidate)) {
            return candidate;
        }

        // Some tools/versions effectively anchor monuments at the “center chunk” of the structure.
        // In practice, the random-spread math can land a few chunks off depending on how the icon/center
        // is defined and on footprint checks (29x29).
        // Search a configurable neighborhood around the candidate.
        // Default ±0 disables refinement by default.
        final int refineRadius = Integer.getInteger("sponge.monumentRefineRadius", 0);
        if (refineRadius <= 0) {
            return null;
        }

        // Prefer closer probes first for determinism.
        for (int r = 1; r <= refineRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    // Only test the perimeter of this ring to avoid redundant work.
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    ChunkPos probe = new ChunkPos(candidate.x + dx, candidate.z + dz);
                    if (isLikelyMonumentBiomeAtChunk(world, probe)) {
                        return probe;
                    }
                }
            }
        }

        // No nearby chunk satisfies the biome constraints.
        return null;
    }

    private static boolean isLikelyMonumentBiomeAtChunk(ServerWorld world, ChunkPos chunk) {
        // Chunkbase/Amidst do biome validity checks to reduce false positives.
        // We sample biomes from the ChunkGenerator's BiomeSource (noise-based), which avoids chunk loading.
        // This is a heuristic filter; the caller should still validate via StructureStart if desired.

        BiomeSource source = world.getChunkManager().getChunkGenerator().getBiomeSource();
        NoiseConfig noise = world.getChunkManager().getNoiseConfig();

        // Ocean monument positions are effectively referenced from the middle of the chunk (+8, +8 in block coords).
        int baseX = chunk.x * 16 + 8;
        int baseZ = chunk.z * 16 + 8;
        // Sample at sea level. Using y=0 can produce mismatches vs what tools (and players) observe on the surface.
        int y = world.getSeaLevel();
        if (y <= 0) y = 63;

        // Two sizes from Amidst: center area (16) and full structure footprint (29).
        // We approximate by sampling a small grid within each square.
        if (!checkBiomeSquare(source, noise, baseX, y, baseZ, 16, true)) return false;
        if (!checkBiomeSquare(source, noise, baseX, y, baseZ, 29, false)) return false;

        return true;
    }

    private static boolean checkBiomeSquare(BiomeSource source, NoiseConfig noise, int centerX, int y, int centerZ, int size, boolean middleChunkRules) {
        int half = size / 2;

        // Amidst effectively validates biomes over both the monument center area and the broader footprint.
        // To approximate that without loading chunks, we sample a denser fixed grid across the square.
        // This greatly reduces false positives vs a 3x3 while staying cheap.
        int[] offsets = new int[] {
                -half,
                -half / 2,
                0,
                half / 2,
                half
        };

        for (int dx : offsets) {
            for (int dz : offsets) {
                int x = centerX + dx;
                int z = centerZ + dz;

                // BiomeSource#getBiome expects biome (quart) coordinates, not block coordinates.
                // Convert block coords -> biome coords to avoid false biome readings.
                int bx = BiomeCoords.fromBlock(x);
                int by = BiomeCoords.fromBlock(y);
                int bz = BiomeCoords.fromBlock(z);

                RegistryEntry<Biome> entry = source.getBiome(bx, by, bz, noise.getMultiNoiseSampler());
                if (!isValidMonumentBiome(entry, middleChunkRules)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isValidMonumentBiome(RegistryEntry<Biome> entry, boolean middleChunkRules) {
        // Use the registry key when available (stable across mappings).
        RegistryKey<Biome> biomeKey = entry.getKey().orElse(null);
        if (biomeKey == null) {
            // If we can't resolve a key (rare), be conservative.
            return false;
        }

        Identifier id = biomeKey.getValue();
        String path = id.getPath();

        if (middleChunkRules) {
            // Middle-of-chunk must be deep ocean variants.
            return path.equals("deep_ocean")
                    || path.equals("deep_lukewarm_ocean")
                    || path.equals("deep_cold_ocean")
                    || path.equals("deep_frozen_ocean");
        }

        // Footprint rules (Amidst-style): allow oceans + deep oceans + rivers.
        // This avoids false negatives when the 29x29 monument footprint touches regular ocean/river.
        return path.equals("ocean")
                || path.equals("deep_ocean")
                || path.equals("cold_ocean")
                || path.equals("deep_cold_ocean")
                || path.equals("lukewarm_ocean")
                || path.equals("deep_lukewarm_ocean")
                || path.equals("warm_ocean")
                || path.equals("frozen_ocean")
                || path.equals("deep_frozen_ocean")
                || path.equals("river")
                || path.equals("frozen_river");
    }
}