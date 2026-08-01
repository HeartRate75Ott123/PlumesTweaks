package com.plumestweaks.mixin;

import com.plumestweaks.PlumesTweaks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 骑马保护效果赋予 + 伤害取消 Mixin。
 *
 * <p>注入 {@link Player#tick} TAIL：每 20 tick 为骑乘 {@link AbstractHorse} 的玩家
 * 刷新隐藏效果（通过 NeoForge {@code IClientMobEffectExtensions} 完全隐藏图标和粒子）。
 * 下马后效果继续持续约 4 秒（80 tick），提供短暂的无敌窗口。
 *
 * <p>注入 {@link Player#hurt} HEAD：持有效果时取消对玩家的伤害。
 * 与 {@link PlayerHorseMixin} 共存——该注入点在 {@link PlayerHorseMixin} 之后执行，
 * 两者都取消玩家伤害（返回 false），但马匹伤害转移已在头一个注入中完成，不受影响。
 */
@Mixin(Player.class)
public class PlayerMountProtectionMixin {

    @Unique
    private static final int EFFECT_DURATION = 80;   // 4 秒（含下马后保护窗口）
    @Unique
    private static final int EFFECT_INTERVAL = 20;    // 每 20 tick 刷新一次

    @Unique
    private int plumes$mountProtectionCounter = 0;

    // ===== 注入点一：效果赋予 =====

    /**
     * 每 20 tick 为骑乘 AbstractHorse 的玩家刷新完全隐藏的保护效果。
     * 效果通过 {@code IClientMobEffectExtensions} 隐藏图标和粒子，
     * 在背包效果栏和 HUD 均不可见。
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void plumes$applyMountProtection(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide) return;

        plumes$mountProtectionCounter++;
        if (plumes$mountProtectionCounter % EFFECT_INTERVAL != 0) return;

        if (self.isPassenger() && self.getVehicle() instanceof AbstractHorse) {
            self.addEffect(new MobEffectInstance(
                    PlumesTweaks.MOUNT_PROTECTION,
                    EFFECT_DURATION,
                    0,        // amplifier
                    false,    // ambient
                    false,    // showParticles
                    false     // showIcon（NeoForge 扩展也已隐藏）
            ));
        }
    }

    // ===== 注入点二：伤害取消 =====

    /**
     * 持有效果时取消对玩家的伤害。
     *
     * 该注入与 {@link PlayerHorseMixin#plumes$redirectDamageToHorse} 在同一注入点
     * （{@code Player.hurt} HEAD + cancellable）。两个 mixin 均执行，都返回 false，
     * 因此玩家不承受伤害。马匹伤害转移已在 {@link PlayerHorseMixin} 中完成，不受影响。
     *
     * 下马后效果剩余的 4 秒内，此注入独立提供保护。
     */
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void plumes$cancelDamageWhenProtected(DamageSource source, float amount,
                                                   CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide) return;
        if (!self.hasEffect(PlumesTweaks.MOUNT_PROTECTION)) return;

        cir.setReturnValue(false);
    }
}
