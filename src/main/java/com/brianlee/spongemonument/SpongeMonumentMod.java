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
        boolean cleanOnStart = Boolean.parseBoolean(System.getProperty("sponge.cleanWorldOnStart", "true"));
        if (cleanOnStart && FabricLoader.getInstance().isDevelopmentEnvironment()) {
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

            // Write minimal properties needed; overwrite is fine for dev runs.
            String content = "level-seed=" + expectedSeed + "\n" +
                    "server-port=" + port + "\n";
            Files.createDirectories(runDir);
            Files.writeString(props, content);

            LOGGER.info("[SpongeMonument] Wrote run/server.properties level-seed={} server-port={}", expectedSeed, port);
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
            LOGGER.error("[SpongeMonument] World seed mismatch! Expected {} but world has {}. (Dev world will be cleaned on start if -Dsponge.cleanWorldOnStart=true.)", expectedSeed, actualSeed);
            return;
        }
        // -------------------------------------------------------------

        LOGGER.info("[SpongeMonument] Server started. Overworld seed = {}", actualSeed);

        int radiusBlocks = Integer.getInteger("sponge.radiusBlocks", 200000);
        int maxResults   = Integer.getInteger("sponge.maxResults", 100);
        if (radiusBlocks <= 0) radiusBlocks = 200000;
        if (maxResults <= 0) maxResults = 100;
        boolean stopServerAfter = Boolean.parseBoolean(System.getProperty("sponge.stopServerAfter", "false"));

        MonumentLocateSmokeTest.runEnumerate(
                server,
                overworld,
                new BlockPos(0, 64, 0),
                radiusBlocks,
                maxResults,
                stopServerAfter
        );

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