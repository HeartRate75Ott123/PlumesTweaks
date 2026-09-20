package com.plumestweaks.mixin;

import com.plumestweaks.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 Explorer's Compass 的传送按钮对非 op 可用（受 {@code freeCompassTeleport} 开关控制）。
 * <p>
 * 原逻辑为 {@code cheatModeEnabled || isOp}；开关开启时强制返回 true，
 * 关闭时不干预，保留原版限制。
 *
 * @see com.chaosthedude.explorerscompass.network.TeleportPacket
 */
@Pseudo
@Mixin(targets = "com.chaosthedude.explorerscompass.util.PlayerUtils", remap = false)
public abstract class ExplorersCompassPlayerUtilsMixin {

    @Inject(method = "canTeleport", at = @At("HEAD"), cancellable = true, remap = false)
    private static void plumes$freeTeleport(MinecraftServer server, Player player,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (Config.FREE_COMPASS_TELEPORT.get()) {
            cir.setReturnValue(true);
        }
    }
}
