package com.brianlee.spongemonument;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import java.util.concurrent.TimeUnit;

public class SpongeMonumentMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("SpongeMonument");

    @Override
    public void onInitialize() {
        // Prepare run/server.properties *before* the dedicated server starts/binds its port.
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);

        // Register server-start callback
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);

        // In dev (Loom runServer), clean up the generated test world after stopping
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
    }

    private void onServerStarting(MinecraftServer server) {
        // ---- REQUIRED SEED CHECK (must be here, not at class scope) ----
        String seedProp = System.getProperty("sponge.seed");
        if (seedProp == null || seedProp.isBlank()) {
            LOGGER.error("[SpongeMonument] Missing required JVM property -Dsponge.seed=<worldSeed>. Example: ./gradlew -Dsponge.seed=15 runServer");
            throw new IllegalStateException("Missing required JVM property -Dsponge.seed=<worldSeed>");
        }

        final long expectedSeed;
        try {
            expectedSeed = Long.parseLong(seedProp.trim());
        } catch (NumberFormatException e) {
            LOGGER.error("[SpongeMonument] Invalid -Dsponge.seed value '{}'; must be a valid long.", seedProp);
            throw new IllegalStateException("Invalid -Dsponge.seed value", e);
        }
        // -------------------------------------------------------------

        // Port can be overridden for dev runs.
        int port = Integer.getInteger("sponge.port", 25565);

        // If the port is already in use, fail fast with a helpful message.
        if (!isPortAvailable(port)) {
            String msg = "Port " + port + " is already in use. " +
                    "On macOS you can find/kill the process with: " +
                    "lsof -nP -iTCP:" + port + " -sTCP:LISTEN  (then kill -9 <PID>). " +
                    "Or run with a different port: ./gradlew -Dsponge.port=25566 ... runServer";
            LOGGER.error("[SpongeMonument] {}", msg);
            throw new IllegalStateException(msg);
        }

        // In dev, optionally delete the world folder before the server creates/loads it.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Path runDir = FabricLoader.getInstance().getGameDir();
            Path worldDir = runDir.resolve("world");
            try {
                deleteRecursively(worldDir);
                LOGGER.info("[SpongeMonument] (dev) Deleted world folder before start: {}", worldDir.toAbsolutePath());
            } catch (Exception e) {
                LOGGER.warn("[SpongeMonument] (dev) Failed deleting world folder before start: {}", worldDir.toAbsolutePath(), e);
            }
        }

        // Ensure server.properties has the seed and port for this run.
        // (Your project already logs when it writes this; keep it consistent.)
        try {
            Path runDir = FabricLoader.getInstance().getGameDir();
            Path props = runDir.resolve("server.properties");
            Files.createDirectories(runDir);

            java.util.Properties p = new java.util.Properties();

            // Load existing if present
            if (Files.exists(props)) {
                try (var in = Files.newInputStream(props)) {
                    p.load(in);
                }
            }

            // Set/override only what we need
            p.setProperty("level-seed", Long.toString(expectedSeed));
            p.setProperty("server-port", Integer.toString(port));

            // Strongly recommended for this tool (mass structure analysis)
            p.setProperty("view-distance", "2");
            p.setProperty("simulation-distance", "2");
            p.setProperty("sync-chunk-writes", "false");

            try (var out = Files.newOutputStream(props)) {
                p.store(out, "SpongeMonument dev server settings");
            }

            LOGGER.info("[SpongeMonument] Updated run/server.properties seed={} port={} view-distance={} sim-distance={}",
                expectedSeed, port, p.getProperty("view-distance"), p.getProperty("simulation-distance"));
        } catch (IOException e) {
            LOGGER.error("[SpongeMonument] Failed writing run/server.properties", e);
            throw new IllegalStateException("Failed writing run/server.properties", e);
        }
    }

    private void onServerStarted(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        if (overworld == null) return;

        // ---- REQUIRED SEED CHECK (must be here, not at class scope) ----
        String seedProp = System.getProperty("sponge.seed");
        if (seedProp == null || seedProp.isBlank()) {
            LOGGER.error("[SpongeMonument] Missing required JVM property -Dsponge.seed=<worldSeed>. Example: ./gradlew -Dsponge.seed=15 runServer");
            requestStop(server);
            return;
        }

        final long expectedSeed;
        try {
            expectedSeed = Long.parseLong(seedProp.trim());
        } catch (NumberFormatException e) {
            LOGGER.error("[SpongeMonument] Invalid -Dsponge.seed value '{}'; must be a valid long.", seedProp);
            requestStop(server);
            return;
        }

        long actualSeed = overworld.getSeed();

        if (expectedSeed != actualSeed) {
            LOGGER.error("[SpongeMonument] World seed mismatch! Expected {} but world has {}.)", expectedSeed, actualSeed);
            return;
        }
        // -------------------------------------------------------------

        LOGGER.info("[SpongeMonument] Server started. Overworld seed = {}", actualSeed);

        int radiusBlocks = Integer.getInteger("sponge.radiusBlocks", 20000);
        int maxResults = Integer.getInteger("sponge.maxResults", 100);
        int batchSize = Integer.getInteger("sponge.batchSize", 1000);

        // Internal orchestration for Gradle's runAll task.
        // Users should not need to set these manually.
        String mode = System.getProperty("sponge.mode", "coords").trim().toLowerCase();
        int batchStart = Integer.getInteger("sponge.batchStart", 0);

        // Output files always live at the project root (same convention as results.csv).
        // runAll will read/write these files between phases.
        Path baseDir;
        String projectDirProp = System.getProperty("spongemonument.projectDir");
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

        Path candidatesPath = baseDir.resolve("candidates.csv");

        LOGGER.info(
                "[SpongeMonument] mode={} radiusBlocks={} maxResults={} batchStart={} batchSize={}",
                mode,
                radiusBlocks,
                maxResults,
                batchStart,
                batchSize
        );

        BlockPos center = new BlockPos(0, 64, 0);

        switch (mode) {
            case "coords" -> MonumentLocateSmokeTest.runCoordsOnly(
                    overworld,
                    center,
                    radiusBlocks,
                    maxResults,
                    candidatesPath
            );

            case "analyze" -> MonumentLocateSmokeTest.runAnalyzeBatch(
                    overworld,
                    candidatesPath,
                    batchStart,
                    batchSize,
                    baseDir
            );

            case "merge" -> MonumentLocateSmokeTest.runMerge(
                    baseDir
            );

            default -> {
                LOGGER.warn(
                        "[SpongeMonument] Unknown sponge.mode='{}' (expected coords|analyze|merge). Defaulting to analyze.",
                        mode
                );
                MonumentLocateSmokeTest.runAnalyzeBatch(
                        overworld,
                        candidatesPath,
                        batchStart,
                        batchSize,
                        baseDir
                );
            }
        }

        // Dev-only: this project treats the run/world as disposable output.
        // Always hard-exit after the analysis to skip the expensive save-on-stop phase.
        // NOTE: Runtime.halt(...) bypasses shutdown hooks (including world-save), which is exactly what we want for disposable dev worlds.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            LOGGER.info("[SpongeMonument] Analysis complete; hard-exiting JVM (skipping world save). ");

            // Give the logger a moment to flush to console before halting.
            new Thread(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException ignored) {
                }
                Runtime.getRuntime().halt(0);
            }, "SpongeMonument-HardExit").start();
        }
    }

    private void onServerStopped(MinecraftServer server) {
        // Only do this in dev runs; never delete real server worlds.
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }

        Path runDir = FabricLoader.getInstance().getGameDir();
        Path worldDir = runDir.resolve("world");

        try {
            deleteRecursively(worldDir);
            LOGGER.info("[SpongeMonument] Deleted dev world folder: {}", worldDir.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("[SpongeMonument] Failed deleting dev world folder: {}", worldDir.toAbsolutePath(), e);
        }
    }

    private static void requestStop(MinecraftServer server) {
        server.execute(() -> server.stop(false));
    }

    private static boolean isPortAvailable(int port) {
        // Bind to loopback only; the dedicated server will bind on wildcard.
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}