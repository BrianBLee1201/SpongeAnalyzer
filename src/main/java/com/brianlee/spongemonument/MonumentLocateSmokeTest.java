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

    public static void runCoordsOnly(
        ServerWorld world,
        BlockPos center,
        int radiusBlocks,
        int maxResults,
        Path candidatesOut
    ) {
        Logger log = SpongeMonumentMod.LOGGER;

        // ---- CLEANUP STALE INTERMEDIATE FILES ----
        try {
            // Delete candidates.csv if it exists
            if (Files.exists(candidatesOut)) {
                try {
                    Files.delete(candidatesOut);
                    log.info("[SpongeMonument] (coords) Deleted stale file: {}", candidatesOut.getFileName());
                } catch (IOException e) {
                    log.warn("[SpongeMonument] (coords) Failed to delete stale file: {}", candidatesOut.getFileName(), e);
                }
            }
            // Delete results_part_*.csv in parent directory
            Path parentDir = candidatesOut.getParent();
            if (parentDir != null && Files.exists(parentDir)) {
                try (var stream = Files.list(parentDir)) {
                    stream.forEach(p -> {
                        String name = p.getFileName().toString();
                        if (name.startsWith("results_part_") && name.endsWith(".csv")) {
                            try {
                                Files.deleteIfExists(p);
                                log.info("[SpongeMonument] (coords) Deleted stale file: {}", p.getFileName());
                            } catch (IOException e) {
                                log.warn("[SpongeMonument] (coords) Failed to delete stale file: {}", p.getFileName(), e);
                            }
                        }
                    });
                } catch (IOException e) {
                    log.warn("[SpongeMonument] (coords) Cleanup scan failed", e);
                }
            }
        } catch (Exception e) {
            log.warn("[SpongeMonument] (coords) Exception during cleanup", e);
        }

        int radiusChunks = Math.max(1, (radiusBlocks + 15) / 16);
        ChunkPos centerChunk = new ChunkPos(center);

        List<ChunkPos> candidates = OceanMonumentCoords.findMonumentStartChunks(
                world, centerChunk, radiusChunks, maxResults
        );

        log.info("[SpongeMonument] (coords) Found {} candidate monument start chunk(s). Writing to {}",
                candidates.size(), candidatesOut.toAbsolutePath());

        try {
            Files.createDirectories(candidatesOut.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(candidatesOut)) {
                w.write("chunk_x,chunk_z\n");
                for (ChunkPos c : candidates) {
                    w.write(c.x + "," + c.z + "\n");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed writing candidates file: " + candidatesOut.toAbsolutePath(), e);
        }

        log.info("[SpongeMonument] (coords) Wrote {} row(s).", candidates.size());
    }

    private static List<ChunkPos> readCandidates(Path file) {
    try {
        List<String> lines = Files.readAllLines(file);
        List<ChunkPos> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).trim();
            if (s.isEmpty()) continue;
            if (i == 0 && s.toLowerCase().contains("chunk_x")) continue; // header
            String[] parts = s.split(",");
            int cx = Integer.parseInt(parts[0].trim());
            int cz = Integer.parseInt(parts[1].trim());
            out.add(new ChunkPos(cx, cz));
        }
        return out;
        } catch (IOException e) {
            throw new RuntimeException("Failed reading candidates: " + file.toAbsolutePath(), e);
        }
    }

    private static void writeResultsCsv(Path out, List<MonumentResult> results) {
        try {
            Files.createDirectories(out.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(out)) {
                w.write("x,z,inferred_sponge_rooms\n");
                for (MonumentResult r : results) {
                    w.write(r.x() + "," + r.z() + "," + r.spongeRooms() + "\n");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed writing results: " + out.toAbsolutePath(), e);
        }
    }

    public static void runAnalyzeBatch(
        ServerWorld world,
        Path candidatesFile,
        int batchStart,
        int batchSize,
        Path outDir
    ) {
        Logger log = SpongeMonumentMod.LOGGER;

        if (!Files.exists(candidatesFile)) {
            throw new IllegalStateException("candidates file not found: " + candidatesFile.toAbsolutePath());
        }

        List<ChunkPos> candidates = readCandidates(candidatesFile);
        int end = Math.min(candidates.size(), batchStart + Math.max(0, batchSize));

        if (batchStart < 0 || batchStart >= candidates.size()) {
            log.warn("[SpongeMonument] (analyze) batchStart={} out of range (candidates={}); skipping.", batchStart, candidates.size());
            return;
        }

        Registry<Structure> structureRegistry = world.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
        RegistryKey<Structure> monumentKey = RegistryKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "monument"));
        Structure monumentStructure = structureRegistry.getOrThrow(monumentKey).value();

        List<MonumentResult> results = new ArrayList<>();

        log.info("[SpongeMonument] (analyze) candidates={} batchStart={} batchEnd={} batchSize={}",
                candidates.size(), batchStart, end, batchSize);

        for (int i = batchStart; i < end; i++) {
            ChunkPos foundChunk = candidates.get(i);
            BlockPos foundPos = foundChunk.getStartPos();

            int spongeRooms = MonumentLayoutAnalyzer.countSpongeRoomsFromStart(world, foundChunk, monumentStructure);
            if (spongeRooms >= 0) {
                results.add(new MonumentResult(foundPos.getX(), foundPos.getZ(), spongeRooms));
            }
            else{
                log.info("[SpongeMonument] (analyze) No valid monument structure start found at (x={}, z={})",
                        foundPos.getX(),
                        foundPos.getZ());
            }
        }

        Path part = outDir.resolve("results_part_" + batchStart + ".csv");
        writeResultsCsv(part, results);

        log.info("[SpongeMonument] (analyze) Wrote {} row(s) to {}", results.size(), part.toAbsolutePath());
    }

    public static void runMerge(Path outDir) {
        Logger log = SpongeMonumentMod.LOGGER;

        List<MonumentResult> all = new ArrayList<>();

        try (var stream = Files.list(outDir)) {
            List<Path> parts = stream
                    .filter(p -> p.getFileName().toString().startsWith("results_part_"))
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();

            if (parts.isEmpty()) {
                log.warn("[SpongeMonument] (merge) No results_part_*.csv files found in {}", outDir.toAbsolutePath());
                return;
            }

            for (Path p : parts) {
                all.addAll(readResultsCsv(p));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed listing merge inputs in: " + outDir.toAbsolutePath(), e);
        }

        all.sort(
                Comparator
                        .comparingInt(MonumentResult::spongeRooms).reversed()
                        .thenComparingLong(MonumentResult::distanceSq)
        );

        Path finalOut = outDir.resolve("results.csv");
        writeResultsCsv(finalOut, all);

        log.info("[SpongeMonument] (merge) Wrote merged results: {} row(s) -> {}", all.size(), finalOut.toAbsolutePath());

        // ---- CLEANUP INTERMEDIATE FILES ----
        try (var stream = Files.list(outDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.equals("candidates.csv") || name.startsWith("results_part_")) {
                    try {
                        Files.deleteIfExists(p);
                        log.info("[SpongeMonument] (merge) Deleted intermediate file: {}", p.getFileName());
                    } catch (IOException e) {
                        log.warn("[SpongeMonument] (merge) Failed deleting {}", p.getFileName(), e);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("[SpongeMonument] (merge) Cleanup scan failed", e);
        }

        // ---- SUMMARY STATS ----
        // Distribution: spongeRooms -> frequency
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        long estimatedWetSponges = 0L;
        for (MonumentResult r : all) {
            int rooms = r.spongeRooms();
            freq.merge(rooms, 1, Integer::sum);
        }

        // Print distribution sorted by sponge rooms (descending)
        java.util.List<Integer> keys = new java.util.ArrayList<>(freq.keySet());
        keys.sort(java.util.Comparator.reverseOrder());

        log.info("[SpongeMonument] ===== Sponge room distribution =====");
        for (int rooms : keys) {
            int count = freq.getOrDefault(rooms, 0);
            log.info("[SpongeMonument] {} : {}", rooms, count);
            // Each sponge room produces ~30 wet sponges.
            estimatedWetSponges += (long) rooms * (long) count * 30L;
        }
        log.info("[SpongeMonument] Estimated total wet sponges from sponge rooms is (rooms * count * 30): {}", estimatedWetSponges);
        log.info("[SpongeMonument] If you taken account for killing 3 elder guardians in an ocean monument, this gives exactly {} wet sponges.", 3 * all.size());
        log.info("[SpongeMonument] Altogether, you get approximately {} wet sponges.", estimatedWetSponges + 3 * all.size());
    }

    private static List<MonumentResult> readResultsCsv(Path p) {
        try {
            List<String> lines = Files.readAllLines(p);
            List<MonumentResult> out = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String s = lines.get(i).trim();
                if (s.isEmpty()) continue;
                if (i == 0 && s.toLowerCase().contains("inferred_sponge_rooms")) continue; // header
                String[] parts = s.split(",");
                int x = Integer.parseInt(parts[0].trim());
                int z = Integer.parseInt(parts[1].trim());
                int rooms = Integer.parseInt(parts[2].trim());
                out.add(new MonumentResult(x, z, rooms));
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Failed reading results part: " + p.toAbsolutePath(), e);
        }
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
                log.info("[SpongeMonument] Skipping duplicate candidate chunk at (x={}, z={})",
                        foundChunk.x, foundChunk.z);
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