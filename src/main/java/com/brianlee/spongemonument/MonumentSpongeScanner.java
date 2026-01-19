package com.brianlee.spongemonument;

import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

public final class MonumentSpongeScanner {

    private MonumentSpongeScanner() {}

    public static int countWetSponges(ServerWorld world, BlockPos center) {
        // Force-load all chunks covering the 58x58 area
        int minChunkX = (center.getX() - 29) >> 4;
        int maxChunkX = (center.getX() + 29) >> 4;
        int minChunkZ = (center.getZ() - 29) >> 4;
        int maxChunkZ = (center.getZ() + 29) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }

        int wetSponges = 0;

        for (int dx = -29; dx <= 28; dx++) {
            for (int dz = -29; dz <= 28; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;

                for (int y = 40; y <= 55; y++) {
                    if (world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.WET_SPONGE)) {
                        wetSponges++;
                    }
                }
            }
        }

        return wetSponges;
    }

    public static int inferSpongeRooms(int wetSponges) {
        return Math.round(wetSponges / 30.0f);
    }
}