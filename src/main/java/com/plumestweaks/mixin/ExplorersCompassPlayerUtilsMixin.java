package com.plumestweaks.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 覆盖 Explorer's Compass 的权限检查 — 允许任何玩家在开启 allowTeleport 配置后传送。
 * <p>
 * 原逻辑 ({@code cheatModeEnabled || isOp}) 在 allowTeleport 已在 TeleportPacket 中被检查的前提下过于严格。
 * 将 canTeleport 直接返回 true，由 {@code ConfigHandler.GENERAL.allowTeleport} 在调用方统一控制。
 *
 * @see com.chaosthedude.explorerscompass.network.TeleportPacket
 */
@Pseudo
@Mixin(targets = "com.chaosthedude.explorerscompass.util.PlayerUtils", remap = false)
public abstract class ExplorersCompassPlayerUtilsMixin {

    /**
     * @reason 移除 OP / 作弊模式限制；allowTeleport 配置已在 {@code TeleportPacket.handle()} 中先检查，
     *         此处直接放通是安全的。
     */
    @Overwrite
    public static boolean canTeleport(MinecraftServer server, Player player) {
        return true;
    }
}
