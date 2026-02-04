package com.brianlee.spongemonument;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.ArrayList;
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
        // Match Amidst/Chunkbase types (OceanMonumentProducer_Fixed): salt=long, spacing/separation=byte.
        final byte spacing = (byte) 32;
        final byte separation = (byte) 5;
        final long salt = 10387313L;
        final boolean triangular = true;
        final boolean buggyCoordMath = false;

        // Scan chunk-grid points exactly like Amidst's RegionalStructureProducer:
        // iterate in steps of `spacing` over the square area, call getPossibleLocation(chunkX, chunkZ),
        // then apply the (optional) biome check.
        final int s = Byte.toUnsignedInt(spacing);

        int minChunkX = centerChunk.x - radiusChunks;
        int maxChunkX = centerChunk.x + radiusChunks;
        int minChunkZ = centerChunk.z - radiusChunks;
        int maxChunkZ = centerChunk.z + radiusChunks;

        // Align the scan grid to multiples of spacing (in chunk coords).
        int startX = floorToGrid(minChunkX, s);
        int startZ = floorToGrid(minChunkZ, s);

        List<ChunkPos> out = new ArrayList<>();

        for (int chunkX = startX; chunkX <= maxChunkX; chunkX += s) {
            for (int chunkZ = startZ; chunkZ <= maxChunkZ; chunkZ += s) {
                ChunkPos start = getPossibleLocation(worldSeed, chunkX, chunkZ, spacing, separation, salt, triangular, buggyCoordMath);
                if (!isLikelyMonumentBiomeAtChunk(world, start)) {
                    continue;
                }

                // Keep within the square radius around center (your Slimefinder-style square search).
                int dx = start.x - centerChunk.x;
                int dz = start.z - centerChunk.z;
                if (Math.abs(dx) > radiusChunks || Math.abs(dz) > radiusChunks) {
                    continue;
                }

                out.add(start);
            }
        }

        // out.sort(Comparator.comparingLong(c -> distSq(c, centerChunk)));

        if (maxResults > 0 && out.size() > maxResults) {
            return new ArrayList<>(out.subList(0, maxResults));
        }
        return out;
    }

    private static int floorToGrid(int v, int step) {
        // floor division for negatives, then multiply back to a grid-aligned coordinate.
        int q = Math.floorDiv(v, step);
        return q * step;
    }

    private static int getRegionCoord(int coordinate, byte spacing, boolean buggyStructureCoordinateMath) {
        return getModifiedCoord(coordinate, spacing, buggyStructureCoordinateMath) / spacing;
    }

    private static int getModifiedCoord(int coordinate, byte spacing, boolean buggyStructureCoordinateMath) {
        if (coordinate < 0) {
            if (buggyStructureCoordinateMath) {
                // Bug MC-131462.
                return coordinate - spacing - 1;
            } else {
                return coordinate - spacing + 1;
            }
        }
        return coordinate;
    }

    private static long getRegionSeed(long worldSeed, long salt, int value1, int value2) {
        // Matches Amidst RegionalStructureProducer#getRegionSeed
        final long MAGIC_NUMBER_1 = 341873128712L;
        final long MAGIC_NUMBER_2 = 132897987541L;
        return value1 * MAGIC_NUMBER_1
             + value2 * MAGIC_NUMBER_2
             + worldSeed
             + salt;
    }

    private static int getStructCoordInRegion(FastLCG random, int value, byte spacing, byte separation, boolean triangular) {
        int result = value * spacing;
        int bound = (spacing - separation);
        if (triangular) {
            result += (random.nextInt(bound) + random.nextInt(bound)) / 2;
        } else {
            result += random.nextInt(bound);
        }
        return result;
    }

    /**
     * Amidst RegionalStructureProducer#getPossibleLocation equivalent.
     *
     * Input: chunk grid coordinate (chunkX, chunkZ) used as the scan point.
     * Output: the StructureStart-equivalent chunk position for the region containing that scan point.
     */
    private static ChunkPos getPossibleLocation(
            long worldSeed,
            int chunkX,
            int chunkZ,
            byte spacing,
            byte separation,
            long salt,
            boolean triangular,
            boolean buggyStructureCoordinateMath
    ) {
        int value1 = getRegionCoord(chunkX, spacing, buggyStructureCoordinateMath);
        int value2 = getRegionCoord(chunkZ, spacing, buggyStructureCoordinateMath);

        FastLCG random = new FastLCG(getRegionSeed(worldSeed, salt, value1, value2));

        value1 = getStructCoordInRegion(random, value1, spacing, separation, triangular);
        value2 = getStructCoordInRegion(random, value2, spacing, separation, triangular);

        return new ChunkPos(value1, value2);
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

        public FastLCG(long seed) {
            // Same scrambling step as java.util.Random
            this.seed = initialScramble(seed);
        }
        private long initialScramble(long seed) {
            return (seed ^ MULT) & MASK;
        }

        public void advance() {
            seed = (seed * MULT + ADD) & MASK;
        }

        private int next(int bits) {
            advance();
            return (int) (seed >>> (48 - bits));
        }

        int nextInt(int bound) {
            bound = Math.max(1, bound);
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");

            int r = next(31);
            int m = bound - 1;

            if ((bound & m) == 0) { // i.e., bound is a power of 2
                r = (int) ((bound * (long) r) >> 31);
            } else {
                int u = r;
                while (u - (r = u % bound) + m < 0) {
                    u = next(31);
                }
            }

            return r;
        }
    }

    private static boolean isLikelyMonumentBiomeAtChunk(ServerWorld world, ChunkPos chunk) {
        // Chunkbase/Amidst reduce false positives by validating biomes over two squares:
        //  - STRUCTURE_CENTER_SIZE (16) must be deep-ocean variants
        //  - STRUCTURE_SIZE (29) may include oceans + deep oceans + rivers
        //
        // Amidst's BiomeDataOracle uses quarter-resolution sampling and a middle-of-chunk offset of 9.
        // We mirror that here by sampling *every* quart cell in the square at quarter resolution.

        BiomeSource source = world.getChunkManager().getChunkGenerator().getBiomeSource();
        NoiseConfig noise = world.getChunkManager().getNoiseConfig();

        int centerX = (chunk.x << 4) + 9;
        int centerZ = (chunk.z << 4) + 9;

        // Two checks:
        //  1) Middle-of-chunk biome must be a deep-ocean variant.
        //     In modern Minecraft, biomes can vary with Y and can transition within a small radius,
        //     so requiring the *entire* 16-radius square to be deep ocean causes false negatives.
        //     We therefore check only the middle-of-chunk sample for the deep-ocean requirement.
        //  2) The broader footprint (29-radius) must be composed of ocean/deep-ocean/river biomes.
        if (!checkMiddleOfChunkBiomeQuarterRes(source, noise, centerX, centerZ)) return false;
        if (!checkBiomeSquareQuarterResExact(source, noise, centerX, centerZ, 29, false)) return false;

        return true;
    }

    /**
     * Middle-of-chunk biome check at quarter resolution.
     *
     * This mirrors the *intent* of Amidst's middle-chunk requirement (deep ocean at the center),
     * but avoids false negatives caused by small biome transitions within the 16-radius square.
     */
    private static boolean checkMiddleOfChunkBiomeQuarterRes(
            BiomeSource source,
            NoiseConfig noise,
            int centerXBlock,
            int centerZBlock
    ) {
        int qx = centerXBlock >> 2;
        int qz = centerZBlock >> 2;

        // Choose a surface-ish quart Y to avoid cave biomes (lush caves, dripstone, etc.).
        // 63 blocks -> 15 in quart coords.
        int qy = 63 >> 2;

        RegistryEntry<Biome> entry = source.getBiome(qx, qy, qz, noise.getMultiNoiseSampler());
        return isValidMonumentBiome(entry, true);
    }

    /**
     * Exact Amidst-style biome validation at quarter resolution.
     *
     * Amidst's BiomeDataOracle checks the quarter-res biome grid that covers (centerX±size, centerZ±size)
     * and requires every sampled biome in that rectangle to be in the allowed set.
     *
     * We replicate that here using the server's BiomeSource + NoiseConfig WITHOUT loading chunks.
     */
    private static boolean checkBiomeSquareQuarterResExact(
            BiomeSource source,
            NoiseConfig noise,
            int centerXBlock,
            int centerZBlock,
            int size,
            boolean middleChunkRules
    ) {
        // Match BiomeDataOracle.isValidBiomeForStructure():
        // left = (x - size) >> 2, right = (x + size) >> 2, inclusive
        int left = (centerXBlock - size) >> 2;
        int top = (centerZBlock - size) >> 2;
        int right = (centerXBlock + size) >> 2;
        int bottom = (centerZBlock + size) >> 2;
        // Use a surface-ish quart Y to avoid cave biomes.
        int by = 63 >> 2;

        for (int qx = left; qx <= right; qx++) {
            for (int qz = top; qz <= bottom; qz++) {
                RegistryEntry<Biome> entry = source.getBiome(qx, by, qz, noise.getMultiNoiseSampler());
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
            // Match Amidst DefaultVersionFeatures (includes warm/deep_warm variants).
            // Sometimes the biome source may return regular ocean variants, creating false positives.
            return path.equals("deep_ocean")
                    || path.equals("deep_lukewarm_ocean")
                    || path.equals("deep_warm_ocean")
                    || path.equals("deep_cold_ocean")
                    || path.equals("deep_frozen_ocean"); //do we include biome filtering?
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
                || path.equals("deep_warm_ocean")
                || path.equals("frozen_ocean")
                || path.equals("deep_frozen_ocean")
                || path.equals("river")
                || path.equals("frozen_river");
    }
}