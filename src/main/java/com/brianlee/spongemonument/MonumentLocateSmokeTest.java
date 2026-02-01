package com.brianlee.spongemonument;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;


import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;

public final class MonumentLocateSmokeTest {
    private MonumentLocateSmokeTest() {}

    /** Old single-hit behavior (kept for convenience). */
    public static void run(MinecraftServer server, ServerWorld world, BlockPos start, int radiusBlocks) {
        runEnumerate(server, world, start, radiusBlocks, 1, true);
    }

    /**
     * Enumerate multiple ocean monuments (via the ocean-explorer-map tag).
     * Uses skipReferencedStructures + incrementReferences so each subsequent locate finds a new one.
     *
     * NOTE: This mutates StructureStart.references in the dev world save (fine for runServer testing).
     */
    public static void runEnumerate(
            MinecraftServer server,
            ServerWorld world,
            BlockPos center,
            int radiusBlocks,
            int maxResults,
            boolean stopServerAfter
    ) {
        Logger log = SpongeMonumentMod.LOGGER;
        List<MonumentResult> results = new ArrayList<>();

        final boolean logSpongeRoomsOnly =
                !"0".equals(System.getProperty("sponge.logSpongeRoomsOnly", "1"));

        // locateStructure radius is in CHUNKS, not blocks. (Ceiling div)
        int radiusChunks = Math.max(1, (radiusBlocks + 15) / 16);
        // For now, I define radiusChunks in a square, not in a circle

        Registry<Structure> structureRegistry = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);

        // Resolve the structure instance once; it is constant for monuments.
        RegistryKey<Structure> monumentKey = RegistryKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "monument"));
        Structure monumentStructure = structureRegistry.getOrThrow(monumentKey).value();

        // locateStructure radius is in CHUNKS, not blocks. (Ceiling div)
        ChunkPos centerChunk = new ChunkPos(center);
        List<ChunkPos> candidates = OceanMonumentCoords.findMonumentStartChunks(
                world,
                centerChunk,
                radiusChunks,
                maxResults
        );

        log.info("[SpongeMonument] Enumerating ocean monuments from (x={}, z={}) (radius={} blocks ~= {} chunks), maxResults={}",
                center.getX(), center.getZ(), radiusBlocks, radiusChunks, maxResults);
        log.info("[SpongeMonument] Found {} candidate monument start chunk(s) to analyze.", candidates.size());

        Set<Long> seenChunks = new HashSet<>();
        int foundCount = 0;

        for (ChunkPos foundChunk : candidates) {
            long key = foundChunk.toLong();

            // Safety against any weird repeats.
            if (!seenChunks.add(key)) {
                continue;
            }

            // Chunkbase reports coordinates at chunk center (+8, +8) compared to chunk start.
            // In my code, we want the chunk start position, so we will not need to add offsets.
            BlockPos foundPos = foundChunk.getStartPos().add(0, 0, 0);


            var id = structureRegistry.getId(monumentStructure);
            int spongeRooms = MonumentLayoutAnalyzer.countSpongeRoomsFromStart(world, foundChunk, monumentStructure); // Responsible for heap problem
            if (spongeRooms >= 0){            
                foundCount++;
                results.add(new MonumentResult(
                        foundPos.getX(),
                        foundPos.getZ(),
                        spongeRooms
                ));

                if (!logSpongeRoomsOnly || spongeRooms > 0) {
                    log.info("[SpongeMonument]   -> inferredSpongeRooms={}", spongeRooms);
                    log.info("[SpongeMonument] #{} at (x={}, z={}) structure={} ",
                            foundCount,
                            foundPos.getX(),
                            foundPos.getZ(),
                            (id == null ? "<unknown>" : id.toString()));
                }

                if (foundCount >= maxResults) {
                    break;
                }
            }
            else{
                log.info("[SpongeMonument]   -> No valid monument structure start found at (x={}, z={})",
                        foundPos.getX(),
                        foundPos.getZ());
            }
        }

        log.info("[SpongeMonument] Enumeration complete. Found {} structure(s).", foundCount);

        results.sort(
            Comparator
                .comparingInt(MonumentResult::spongeRooms).reversed()
                .thenComparingLong(MonumentResult::distanceSq)
        );

        String projectDirProp = System.getProperty("spongemonument.projectDir");

        Path baseDir;
        if (projectDirProp != null && !projectDirProp.isBlank()) {
            baseDir = Path.of(projectDirProp);
        } else {
            // Loom's runServer typically runs with working dir = <project>/run
            Path cwd = Path.of(System.getProperty("user.dir"));
            if (cwd.getFileName() != null && cwd.getFileName().toString().equalsIgnoreCase("run") && cwd.getParent() != null) {
                baseDir = cwd.getParent();
            } else {
                baseDir = cwd;
            }
        }

        Path out = baseDir.resolve("results.csv");

        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("x,z,inferred_sponge_rooms\n");
            for (MonumentResult r : results) {
                w.write(
                        r.x() + "," +
                        r.z() + "," +
                        r.spongeRooms() + "\n"
                );
            }
            log.info("[SpongeMonument] Wrote {} row(s) to {}", results.size(), out.toAbsolutePath());
        } catch (IOException e) {
            log.error("[SpongeMonument] Failed to write results.csv", e);
        }

        if (stopServerAfter) {
            requestStop(server, log);
        }
    }

    private static void requestStop(MinecraftServer server, Logger log) {
        // Request a normal stop on the server thread.
        server.execute(() -> {
            log.info("[SpongeMonument] Smoke test complete; stopping dev server.");
            System.exit(0);
        });
    }

}