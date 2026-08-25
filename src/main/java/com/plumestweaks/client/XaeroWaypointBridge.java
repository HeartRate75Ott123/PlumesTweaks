package com.plumestweaks.client;

import com.plumestweaks.PlumesTweaks;

import java.util.HashSet;
import java.util.Set;

/**
 * 客户端侧桥接：通过反射把路径点写入 Xaero 地图（Minimap/World Map 共享的 waypoint 存储）。
 * <p>
 * 全程反射、零编译期依赖 Xaero；Xaero 未安装或 API 变更时静默跳过，不影响其他功能。
 * 坐标级去重，避免同一次游戏会话内重复搜索刷路径点。
 */
public final class XaeroWaypointBridge {

    /** 已添加的 "维度:块X:块Z" 集合（会话级去重） */
    private static final Set<String> KNOWN = new HashSet<>();

    private XaeroWaypointBridge() {}

    public static void addWaypoint(int x, int y, int z, String name, String dimId) {
        try {
            String dedupeKey = dimId + ":" + (x >> 4) + ":" + (z >> 4);
            if (!KNOWN.add(dedupeKey)) return;

            // Xaero Minimap 的会话入口（World Map 复用同一 waypoint 存储）
            Class<?> minimapModules = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Object minimap = minimapModules.getField("MINIMAP").get(null);
            Object session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) return;
            Object worldManager = session.getClass().getMethod("getWorldManager").invoke(session);
            Object world = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
            if (world == null) return;
            Object set = world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
            if (set == null) return;

            Class<?> wpClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class<?> colorClass = Class.forName("xaero.common.minimap.waypoints.WaypointColor");
            Class<?> purposeClass = Class.forName("xaero.common.minimap.waypoints.WaypointPurpose");
            Object color = colorClass.getMethod("getRandom").invoke(null);
            Object purpose = Enum.valueOf((Class<? extends Enum>) purposeClass, "NORMAL");
            String symbol = name.length() > 1 ? name.substring(0, 2) : name;
            Object waypoint = wpClass.getConstructor(int.class, int.class, int.class, String.class,
                            String.class, colorClass, purposeClass)
                    .newInstance(x, y, z, name, symbol, color, purpose);
            set.getClass().getMethod("add", wpClass).invoke(set, waypoint);
        } catch (Throwable t) {
            // Xaero 未安装 / 仅装 World Map 缺共享库 / API 变更 —— 静默降级
            PlumesTweaks.LOGGER.debug("[Xaero] addWaypoint skipped: {}", t.getMessage());
        }
    }
}
