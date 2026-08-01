package com.plumestweaks.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家骑乘马匹时，所有伤害由马承担。
 *
 * 注入 Player.hurt() HEAD，若玩家骑乘 {@link AbstractHorse} 且马还活着，
 * 则将伤害重定向到马匹，取消对玩家的伤害。
 * 马的 75% 减伤（{@link AbstractHorseMixin#plumes$reduceHorseDamage}）仍然生效，
 * 因此实际受到的伤害为原始值的 25%。
 */
@Mixin(Player.class)
public class PlayerHorseMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void plumes$redirectDamageToHorse(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide) return;
        if (!self.isPassenger()) return;

        if (self.getVehicle() instanceof AbstractHorse horse && horse.isAlive()) {
            // 将伤害重定向到马匹
            horse.hurt(source, amount);
            // 取消对玩家的伤害
            cir.setReturnValue(false);
        }
    }
}
