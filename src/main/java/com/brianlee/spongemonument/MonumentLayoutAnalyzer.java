package com.brianlee.spongemonument;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.util.math.BlockBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

public final class MonumentLayoutAnalyzer {

    // --- Memory/throughput knobs (best-effort) ---
    // When scanning many monuments, forcing STRUCTURE_STARTS loads can accumulate a lot of chunk/holder state.
    // These knobs are intentionally conservative and mapping-agnostic (reflection-based) so they don't break across Yarn updates.

    // Call counter for optional periodic GC.
    private static final AtomicInteger ANALYZE_CALLS = new AtomicInteger(0);

    // If set (e.g. -Dsponge.gcEvery=250), triggers System.gc() every N analyses.
    private static final int GC_EVERY = Integer.getInteger("sponge.gcEvery", 200);

    // If true (default), attempt a best-effort chunk unload hint after analysis.
    private static final boolean UNLOAD_HINT = Boolean.parseBoolean(System.getProperty("sponge.unloadHint", "true"));

    private static void maybePeriodicGc() {
        if (GC_EVERY <= 0) return;
        int n = ANALYZE_CALLS.incrementAndGet();
        if (n % GC_EVERY == 0) {
            try {
                System.gc();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Best-effort attempt to let the server unload the analyzed chunk sooner.
     * There is no stable public API for this across versions/mappings, so we use reflection and fail silently.
     *
     * This is not a guarantee; it is only a hint to reduce long-lived chunk retention during huge scans.
     */
    private static void tryUnloadHint(ServerWorld world, ChunkPos pos) {
        if (!UNLOAD_HINT || world == null || pos == null) return;
        try {
            Object cm = world.getChunkManager();
            if (cm == null) return;

            // Try a few likely method names/signatures across versions.
            // 1) unloadChunk(int, int)
            if (invokeIfExists(cm, "unloadChunk", new Class<?>[]{int.class, int.class}, new Object[]{pos.x, pos.z})) return;

            // 2) unload(int, int)
            if (invokeIfExists(cm, "unload", new Class<?>[]{int.class, int.class}, new Object[]{pos.x, pos.z})) return;

            // 3) removeTicket / addTicket-style APIs sometimes exist on the loading manager.
            // Try to reach an inner loading manager getter and call a remove method if present.
            Object loadingManager = null;
            // getChunkLoadingManager()
            Method m = findNoArgMethod(cm.getClass(), "getChunkLoadingManager");
            if (m != null) {
                loadingManager = m.invoke(cm);
            }
            if (loadingManager != null) {
                // removeTicket(?, ChunkPos, int, ?)
                // We can't reliably construct TicketType, so we only try common simple signatures.
                invokeIfExists(loadingManager, "removeTicket", new Class<?>[]{ChunkPos.class}, new Object[]{pos});
                invokeIfExists(loadingManager, "remove", new Class<?>[]{ChunkPos.class}, new Object[]{pos});
            }
        } catch (Throwable ignored) {
            // No-op on failure.
        }
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            try {
                Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static boolean invokeIfExists(Object target, String name, Class<?>[] paramTypes, Object[] args) {
        try {
            Method m;
            try {
                m = target.getClass().getMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                m = target.getClass().getDeclaredMethod(name, paramTypes);
            }
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // Logging is always sponge-only. No debug/trace/runtime flags.
    private static BlockBox tryGetBoundingBox(StructurePiece piece) {
        if (piece == null) return null;
        try {
            // Prefer reflection to avoid Yarn name differences.
            Field bb = findField(piece.getClass(), "boundingBox");
            Object v = bb == null ? null : bb.get(piece);
            return (v instanceof BlockBox b) ? b : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void debugMonumentBaseInternals(StructurePiece piece) {
        // No-op: logging disabled except for sponge rooms.
    }

    public static void debugMonumentBaseInternals(String label, StructurePiece piece) {
        // No-op: logging disabled except for sponge rooms.
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }


    private static Object getOceanMonumentPieceSetting(StructurePiece piece) {
        // In Yarn mappings, OceanMonumentGenerator$Piece has a field named "setting".
        // We reflect it from the actual runtime class hierarchy.
        Field f = findField(piece.getClass(), "setting");
        if (f == null) return null;
        try {
            return f.get(piece);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<StructurePiece> getMonumentRoomPiecesFromStart(StructureStart start) {
        // In modern Minecraft, the StructureStart's children list usually contains a single OceanMonumentGenerator$Base.
        // The *actual* per-room pieces live inside Base.children (a List<StructurePiece>).
        if (start == null) return List.of();

        List<StructurePiece> top = start.getChildren();
        if (top == null || top.isEmpty()) return List.of();

        // Find the Base piece (usually child[0])
        StructurePiece base = null;
        for (StructurePiece p : top) {
            if (p == null) continue;
            String n = p.getClass().getName();
            if (n.contains("OceanMonumentGenerator$Base") || p.getClass().getSimpleName().equals("Base")) {
                base = p;
                break;
            }
        }
        if (base == null) base = top.get(0);

        // Reflect Base.children
        try {
            Field f = findField(base.getClass(), "children");
            if (f != null) {
                Object v = f.get(base);
                if (v instanceof List<?> list) {
                    // Best-effort cast; contents should be StructurePiece
                    return (List<StructurePiece>) (List<?>) list;
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback: return the top-level list
        return top;
    }


    private static Integer tryGetIntField(Object obj, String... names) {
        if (obj == null) return null;
        for (String n : names) {
            Field f = findField(obj.getClass(), n);
            if (f == null) continue;
            try {
                Object v = f.get(obj);
                if (v instanceof Integer i) return i;
                if (f.getType() == int.class) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static int countSpongeRoomsFromStart(ServerWorld world, ChunkPos chunkPos, Structure structure) {
        try {
            world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS, true);

            StructureAccessor accessor = world.getStructureAccessor();
            List<StructureStart> starts = accessor.getStructureStarts(chunkPos, s -> s == structure);
            // If there is no actual monument start in this chunk, treat it as a non-monument candidate.
            // This allows caller to filter out Chunkbase-style candidates that fail biome/placement checks.
            if (starts.isEmpty()) return -1;

            StructureStart start = starts.get(0);

            // The StructureStart typically has a single OceanMonumentGenerator$Base child.
            // The per-room pieces are stored inside Base.children.
            // No debugMonumentBaseInternals call.

            List<StructurePiece> roomPieces = getMonumentRoomPiecesFromStart(start);

            int spongeRoomCount = 0;
            int idx = 0;
            for (StructurePiece piece : roomPieces) {
                if (piece == null) {
                    idx++;
                    continue;
                }

                String simple = piece.getClass().getSimpleName();

                // Bounding box
                BlockBox bb = tryGetBoundingBox(piece);

                // PieceSetting summary (only roomIndex needed for log)
                Object setting = getOceanMonumentPieceSetting(piece);
                Integer roomIndex = null;
                if (setting != null) {
                    roomIndex = tryGetIntField(setting, "roomIndex");
                }

                // Sponge-room inference (fast): the generated monument layout includes a SimpleRoomTop
                // piece for each sponge room. No block scanning needed.
                boolean isSpongeRoom = simple.equals("SimpleRoomTop") || piece.getClass().getName().contains("SimpleRoomTop");

                if (isSpongeRoom) {
                    spongeRoomCount++;
                    // Only log sponge rooms
                    SpongeMonumentMod.LOGGER.info(
                            "[MonumentDebug] spongeRoom idx={} class={} roomIndex={} bb={}",
                            idx,
                            simple,
                            roomIndex,
                            bb
                    );
                }
                idx++;
            }

            if (spongeRoomCount > 0) {
                SpongeMonumentMod.LOGGER.info("[MonumentDebug] spongeRoomsDetected={}", spongeRoomCount);
            }

            return spongeRoomCount;
        } finally {
            // Best-effort pressure relief for very large scans.
            tryUnloadHint(world, chunkPos);
            maybePeriodicGc();
        }
    }
}