package com.plumestweaks.mixin;

import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FTB Quests 的 {@code PermissionsHelper.hasEditorPermission(CommandSourceStack)} 直接调用
 * {@code player.hasPermissions(2)} 查 ops.json，完全忽略 {@code CommandSourceStack} 的权限等级。
 * <p>
 * 这导致 {@link ServerGamePacketListenerImplMixin} 中通过 {@code withPermission(2)} 提升的
 * source 权限等级对 FTB Quests 的命令守卫（{@code requires()}）无效。
 * <p>
 * 本 mixin 在 {@code hasEditorPermission} 入口处补上 {@code source.hasPermission(2)} 检查，
 * 使 brigadier 命令树的权限校验能正确识别我们提权后的 CommandSourceStack。
 */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.integration.PermissionsHelper", remap = false)
public abstract class PermissionsHelperMixin {

    @Inject(
            method = "hasEditorPermission(Lnet/minecraft/commands/CommandSourceStack;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void checkSourcePermission(CommandSourceStack source,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (source.hasPermission(2)) {
            cir.setReturnValue(true);
        }
    }
}
