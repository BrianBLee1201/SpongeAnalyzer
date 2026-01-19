package com.brianlee.spongemonument;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.util.List;

public final class MonumentLayoutAnalyzer {

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

    private static int countWetSpongesInBox(ServerWorld world, BlockBox bb) {
        if (world == null || bb == null) return 0;

        // Ocean monument sponges typically appear between ~Y40..55, but we keep it generic and clamp to the box.
        int minX = bb.getMinX();
        int maxX = bb.getMaxX();
        int minY = bb.getMinY();
        int maxY = bb.getMaxY();
        int minZ = bb.getMinZ();
        int maxZ = bb.getMaxZ();

        // Safety: avoid pathological huge scans
        long volume = (long) (maxX - minX + 1) * (long) (maxY - minY + 1) * (long) (maxZ - minZ + 1);
        if (volume > 2_000_000L) {
            // Too large to be a single room; bail out.
            return 0;
        }

        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    m.set(x, y, z);
                    if (world.getBlockState(m).isOf(Blocks.WET_SPONGE)) {
                        count++;
                    }
                }
            }
        }
        return count;
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
        world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS, true);

        StructureAccessor accessor = world.getStructureAccessor();
        List<StructureStart> starts = accessor.getStructureStarts(chunkPos, s -> s == structure);
        if (starts.isEmpty()) return 0;

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

            // Detect sponge content by scanning only the room bounding box.
            int wetInRoom = 0;
            if (bb != null) {
                wetInRoom = countWetSpongesInBox(world, bb);
            }
            boolean isSpongeRoom = wetInRoom > 0;
            if (isSpongeRoom) {
                spongeRoomCount++;
                // Only log sponge rooms
                SpongeMonumentMod.LOGGER.info(
                        "[MonumentDebug] spongeRoom idx={} class={} wetSponges={} roomIndex={} bb={}",
                        idx,
                        simple,
                        wetInRoom,
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
    }
}