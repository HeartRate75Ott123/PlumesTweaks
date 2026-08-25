package com.plumestweaks.dimension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.plumestweaks.PlumesTweaks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 时空裂隙专属岛分配器。
 * <p>
 * 由服务器统一分配：玩家首次进入裂隙时，从世界边界中心 (默认 0,0) 附近的网格
 * 开始按环向外螺旋搜索一个未被占用的网格单元作为专属岛；网格索引持久化到
 * 服务器存档目录下的 {@code plumestweaks/rift_islands.json}，重启后恢复，
 * 保证同一玩家每次进入都回到自己的岛。严格一格一玩家。
 * <p>
 * 网格单元与 {@link RiftChunkGenerator} 的 GRID 一致：岛中心 = 网格索引 * GRID，
 * 生成器对任意坐标经 gridCenter 都会落回同一中心，二者天然一致。
 */
public final class RiftIslandAllocator {

    /** 网格单元间距（与 RiftChunkGenerator.GRID 保持一致） */
    public static final int GRID = 1024;

    /** 岛半径 116 + 生成缓冲，确保岛屿完整在世界边界墙内 */
    private static final int BORDER_MARGIN = 128;

    /** 兜底落点：中心网格 (0,0) 对应世界坐标 (0,0) */
    private static final long FALLBACK_GRID = packGrid(0, 0);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 玩家 UUID → 打包网格索引 ((gx<<32)|gz) */
    private static final Map<UUID, Long> ASSIGNMENTS = new HashMap<>();

    private static MinecraftServer server;

    private RiftIslandAllocator() {}

    // ========== 生命周期 ==========

    /** 服务器启动时加载已分配记录 */
    public static void load(MinecraftServer server) {
        RiftIslandAllocator.server = server;
        ASSIGNMENTS.clear();
        Path file = dataFile(server);
        if (!Files.exists(file)) {
            PlumesTweaks.LOGGER.info("[RiftIslandAllocator] No assignment file yet, starting fresh");
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                int gx = o.get("x").getAsInt();
                int gz = o.get("z").getAsInt();
                ASSIGNMENTS.put(UUID.fromString(entry.getKey()), packGrid(gx, gz));
            }
            PlumesTweaks.LOGGER.info("[RiftIslandAllocator] Loaded {} island assignments", ASSIGNMENTS.size());
        } catch (Exception e) {
            PlumesTweaks.LOGGER.error("[RiftIslandAllocator] Failed to load assignment file, starting fresh", e);
            ASSIGNMENTS.clear();
        }
    }

    /** 服务器停止时落盘（分配时已即时写盘，此处兜底） */
    public static void save() {
        if (server == null) return;
        writeFile(server, ASSIGNMENTS);
    }

    // ========== 分配 ==========

    /**
     * 获取玩家专属岛的网格索引。已有映射则直接返回；否则从中心向外螺旋搜索
     * 一个未被占用且在世界边界内的网格；若整个可用范围均无空位，兜底返回中心岛。
     */
    public static long allocate(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Long existing = ASSIGNMENTS.get(uuid);
        if (existing != null) return existing;

        MinecraftServer srv = player.getServer();
        WorldBorder border = srv.overworld().getWorldBorder();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        double half = border.getSize() / 2.0 - BORDER_MARGIN;
        int maxRing = (int) Math.floor(half / GRID);
        if (maxRing < 0) maxRing = 0;

        long chosen = FALLBACK_GRID;
        boolean found = false;
        for (int ring = 0; ring <= maxRing && !found; ring++) {
            if (ring == 0) {
                if (inBorder(centerX, centerZ, half, 0, 0)
                        && !ASSIGNMENTS.containsValue(packGrid(0, 0))) {
                    chosen = packGrid(0, 0);
                    found = true;
                }
                continue;
            }
            // Chebyshev 环：螺旋顺序逐格检查
            for (int i = -ring; i <= ring && !found; i++) {
                if (inBorder(centerX, centerZ, half, i, -ring)
                        && !ASSIGNMENTS.containsValue(packGrid(i, -ring))) {
                    chosen = packGrid(i, -ring);
                    found = true;
                }
            }
            for (int i = -ring; i <= ring && !found; i++) {
                if (inBorder(centerX, centerZ, half, ring, i)
                        && !ASSIGNMENTS.containsValue(packGrid(ring, i))) {
                    chosen = packGrid(ring, i);
                    found = true;
                }
            }
            for (int i = -ring; i <= ring && !found; i++) {
                if (inBorder(centerX, centerZ, half, i, ring)
                        && !ASSIGNMENTS.containsValue(packGrid(i, ring))) {
                    chosen = packGrid(i, ring);
                    found = true;
                }
            }
            for (int i = -ring; i <= ring && !found; i++) {
                if (inBorder(centerX, centerZ, half, -ring, i)
                        && !ASSIGNMENTS.containsValue(packGrid(-ring, i))) {
                    chosen = packGrid(-ring, i);
                    found = true;
                }
            }
        }

        if (found) {
            ASSIGNMENTS.put(uuid, chosen);
            writeFile(srv, ASSIGNMENTS);
            PlumesTweaks.LOGGER.info("[RiftIslandAllocator] Assigned {} island grid=({},{})",
                    player.getName().getString(), gridX(chosen), gridZ(chosen));
        } else {
            PlumesTweaks.LOGGER.warn("[RiftIslandAllocator] No free island found for {}, falling back to center",
                    player.getName().getString());
        }
        return chosen;
    }

    // ========== 工具 ==========

    private static boolean inBorder(double centerX, double centerZ, double half, int gx, int gz) {
        return Math.abs(gx * GRID - centerX) <= half && Math.abs(gz * GRID - centerZ) <= half;
    }

    /** 打包网格索引为 long：高 32 位 gx，低 32 位 gz */
    private static long packGrid(int gx, int gz) {
        return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
    }

    public static int gridX(long packed) { return (int) (packed >> 32); }
    public static int gridZ(long packed) { return (int) packed; }

    /** 网格索引 → 岛中心世界坐标 */
    public static int centerX(long packed) { return gridX(packed) * GRID; }
    public static int centerZ(long packed) { return gridZ(packed) * GRID; }

    private static Path dataFile(MinecraftServer srv) {
        return srv.getWorldPath(LevelResource.ROOT).resolve("plumestweaks").resolve("rift_islands.json");
    }

    private static void writeFile(MinecraftServer srv, Map<UUID, Long> map) {
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, Long> e : map.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", gridX(e.getValue()));
            o.addProperty("z", gridZ(e.getValue()));
            root.add(e.getKey().toString(), o);
        }
        Path file = dataFile(srv);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            PlumesTweaks.LOGGER.error("[RiftIslandAllocator] Failed to write assignment file", ex);
        }
    }
}