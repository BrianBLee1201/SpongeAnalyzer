package com.brianlee.spongemonument;

import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.structure.Structure;
import org.slf4j.Logger;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

        // locateStructure radius is in CHUNKS, not blocks. (Ceiling div)
        int radiusChunks = Math.max(1, (radiusBlocks + 15) / 16);

        Registry<Structure> structureRegistry = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
        RegistryEntryList<Structure> targets = structureRegistry.getOrThrow(StructureTags.ON_OCEAN_EXPLORER_MAPS);

        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        StructureAccessor accessor = world.getStructureAccessor();

        log.info("[SpongeMonument] Enumerating ocean monuments from (x={}, z={}) (radius={} blocks ~= {} chunks), maxResults={}",
                center.getX(), center.getZ(), radiusBlocks, radiusChunks, maxResults);

        Set<Long> seenChunks = new HashSet<>();
        int foundCount = 0;

        for (int i = 0; i < maxResults; i++) {
            // This returns both position + WHICH structure was found.  [oai_citation:1‡Maven FabricMC](https://maven.fabricmc.net/docs/yarn-1.21.11%2Bbuild.4/net/minecraft/world/gen/chunk/ChunkGenerator.html)
            Pair<BlockPos, RegistryEntry<Structure>> hit =
                    generator.locateStructure(world, targets, center, radiusChunks, true);

            if (hit == null) break;

            BlockPos foundPos = hit.getFirst();
            Structure foundStructure = hit.getSecond().value();

            ChunkPos foundChunk = new ChunkPos(foundPos);
            long key = foundChunk.toLong();

            // Safety against any weird repeats.
            if (!seenChunks.add(key)) {
                log.warn("[SpongeMonument] Duplicate hit at chunk {} — stopping to avoid a loop.", foundChunk);
                break;
            }

            foundCount++;
            var id = structureRegistry.getId(foundStructure);
            // Scan the monument area for wet sponges (Carpet-style world inspection)
            int spongeRooms = MonumentLayoutAnalyzer.countSpongeRoomsFromStart(world, foundChunk, foundStructure);
            results.add(new MonumentResult(
                foundPos.getX(),
                foundPos.getZ(),
                spongeRooms
            ));

            log.info(
                    "[SpongeMonument]   -> inferredSpongeRooms={}",
                    spongeRooms
            );
            log.info(
                "[SpongeMonument] #{} at (x={}, z={}) structure={}",
                foundCount,
                foundPos.getX(),
                foundPos.getZ(),
                (id == null ? "<unknown>" : id.toString())
            );

            // Mark as “referenced” so skipReferencedStructures will avoid it next time.  [oai_citation:2‡Maven FabricMC](https://maven.fabricmc.net/docs/yarn-1.21.11%2Bbuild.4/net/minecraft/world/gen/StructureAccessor.html)
            List<StructureStart> starts = accessor.getStructureStarts(foundChunk, s -> s == foundStructure);
            if (starts.isEmpty()) {
                log.warn("[SpongeMonument] Could not resolve StructureStart(s) for {}; stopping.", foundChunk);
                break;
            }
            for (StructureStart start : starts) {
                accessor.incrementReferences(start);
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

        // In dev runs, chunk scanning can make the save-on-stop phase very slow.
        // Add a fail-safe: if the server hasn't exited after a while, force an exit.
        // (This avoids leaving Gradle's runServer task hanging forever.)
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Thread t = new Thread(() -> {
                try {
                    TimeUnit.SECONDS.sleep(30);
                } catch (InterruptedException ignored) {
                    return;
                }

                if (!server.isStopped()) {
                    log.warn("[SpongeMonument] Server still stopping after 30s; forcing shutdown to avoid hang.");
                    try {
                        server.shutdown();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    try {
                        System.exit(0);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }, "SpongeMonument-StopFailsafe");
            t.setDaemon(true);
            t.start();
        }
    }

}