package com.plumestweaks.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 覆盖 Nature's Compass 的权限检查 — 允许任何玩家在开启 allowTeleport 配置后传送。
 * <p>
 * 原逻辑 ({@code cheatModeEnabled || isOp || hasPermission}) 在 allowTeleport 已在 TeleportPacket
 * 中被检查的前提下过于严格。将 canTeleport 直接返回 true，由配置统一控制。
 *
 * @see com.chaosthedude.naturescompass.network.TeleportPacket
 */
@Pseudo
@Mixin(targets = "com.chaosthedude.naturescompass.util.PlayerUtils", remap = false)
public abstract class NaturesCompassPlayerUtilsMixin {

    /**
     * @reason 移除 OP / 权限限制；allowTeleport 配置已在 {@code TeleportPacket.handle()} 中先检查，
     *         此处直接放通是安全的。
     */
    @Overwrite
    public static boolean canTeleport(MinecraftServer server, Player player) {
        return true;
    }
}
