package com.plumestweaks.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 锁定客户端马匹跳跃蓄力条。
 *
 * <p>{@link LocalPlayer#aiStep()} 中蓄力条计算公式在 tick >= 10 后会衰减：
 * {@code scale = 0.8 + 2.0 / (tick - 9) * 0.1}，导致客户端蓄力条回弹
 * 且 {@code onPlayerJump} 收到的 {@code jumpPower} 偏低。
 *
 * <p>本 Mixin 注入 {@link LocalPlayer#getJumpRidingScale()} RETURN，
 * 一旦蓄力达到最大值（{@code jumpRidingTicks >= 10}）即锁定返回 1.0F，
 * 同时修复 HUD 蓄力条视觉和跳跃力度。
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    @Shadow
    private int jumpRidingTicks;

    @Inject(method = "getJumpRidingScale", at = @At("RETURN"), cancellable = true)
    private void plumes$lockJumpScale(CallbackInfoReturnable<Float> cir) {
        if (this.jumpRidingTicks >= 10) {
            cir.setReturnValue(1.0F);
        }
    }
}
