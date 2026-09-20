package com.plumestweaks.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;

/**
 * 计算指南针“点传送”的真实落点高度 Y。
 * <p>
 * 自然/探险者指南针的传送并不使用 {@code succeed} 传入的坐标 Y，而是各自
 * {@code TeleportPacket.findValidTeleportHeight(Level, int, int)} 在 (x,z) 列上重新计算合法落点。
 * 为让 Xaero 路径点高度与“点传送”落点一致，优先反射调用该方法；失败时回退到地表站立高度。
 */
public final class CompassTeleportHeight {
    private CompassTeleportHeight() {
    }

    public static int computeY(ServerLevel level, int x, int z, String teleportPacketClass) {
        try {
            Class<?> c = Class.forName(teleportPacketClass);
            Object pkt = c.getDeclaredConstructor().newInstance();
            Method m = c.getDeclaredMethod("findValidTeleportHeight", Level.class, int.class, int.class);
            m.setAccessible(true);
            int y = (int) m.invoke(pkt, level, x, z);
            if (y > level.getMinBuildHeight()) return y;
        } catch (Throwable ignored) {
            // 反射失败（如模组未安装/方法变更），回退地表高度
        }
        int floor = (int) level.getBlockFloorHeight(new BlockPos(x, 0, z));
        return floor + 1;
    }
}
