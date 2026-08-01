package com.plumestweaks.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 马匹增强 Mixin：
 * 1. 骑乘移速 2x（getRiddenSpeed() 返回值拦截）
 * 2. 所有伤害减免 75%
 * 3. 跳跃蓄力满蓄锁定 + 跳跃高度 1.5x
 * 4. 降低奔腾音效播放频次（基于步数间隔）
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin {

    @Shadow
    protected int gallopSoundCounter;

    @Shadow
    protected float playerJumpPendingScale;

    @Shadow
    protected boolean allowStandSliding;

    @Shadow
    public abstract void standIfPossible();

    @Shadow
    public abstract boolean isSaddled();

    @Shadow
    protected abstract void setIsJumping(boolean jumping);

    /**
     * 上次播放奔腾音效时的 gallopSoundCounter 值。
     */
    @Unique
    private int plumes$lastGallopStep = 0;

    // ═══════════════════════════════════════════
    //  1. 骑乘移速 2x
    // ═══════════════════════════════════════════

    @Inject(method = "getRiddenSpeed", at = @At("RETURN"), cancellable = true)
    private void plumes$horseSpeed(Player player, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(cir.getReturnValue() * 2.0f);
    }

    // ═══════════════════════════════════════════
    //  2. 伤害减免 75%
    // ═══════════════════════════════════════════

    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true, remap = false)
    private float plumes$reduceHorseDamage(float amount) {
        if (amount > 0.0f) {
            return amount * 0.25f;
        }
        return amount;
    }

    // ═══════════════════════════════════════════
    //  3. 跳跃蓄力满蓄锁定 + 跳跃高度 1.5x
    // ═══════════════════════════════════════════

    /**
     * onPlayerJump：保持原版比例（jumpPower 0-89 线性增长，≥90 满蓄）。
     * 客户端 LocalPlayerMixin 锁定 getJumpRidingScale() 后，
     * 蓄力到 tick >= 10 后 jumpPower 始终为 100，无需卡时机。
     */
    @Inject(method = "onPlayerJump", at = @At("HEAD"), cancellable = true)
    private void plumes$lockFullCharge(int jumpPower, CallbackInfo ci) {
        if (!this.isSaddled()) return;

        if (jumpPower >= 0) {
            this.allowStandSliding = true;
            this.standIfPossible();
        }

        if (jumpPower >= 90) {
            this.playerJumpPendingScale = 1.0f;
        } else {
            this.playerJumpPendingScale = 0.4f + 0.4f * Math.max(jumpPower, 0) / 90.0f;
        }

        ci.cancel();
    }

    /**
     * 替换 executeRidersJump，在原版跳跃初速度上乘以 1.5 倍。
     *
     * 原版 getJumpPower() 是 LivingEntity 的 protected 方法，
     * 无法 @Shadow（继承方法 refmap 问题），
     * 此处用 @Inject HEAD + cancellable 替换整个方法，
     * 其中 jumpPower 通过公开 API 重新计算。
     */
    @Inject(method = "executeRidersJump", at = @At("HEAD"), cancellable = true)
    private void plumes$scaledJumpHeight(float jumpScale, Vec3 travelVector, CallbackInfo ci) {
        Entity self = (Entity)(Object)this;
        LivingEntity living = (LivingEntity)(Object)this;

        // 复现原版 getJumpPower(float) 计算：
        // JUMP_STRENGTH × jumpScale × blockJumpFactor + jumpBoostPower
        // getBlockJumpFactor() 是 protected（无法从 mixin 调用），此处省去
        float vanillaPower = (float)living.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH)
            * jumpScale + living.getJumpBoostPower();

        double jumpVelocity = vanillaPower * 1.5;

        Vec3 motion = self.getDeltaMovement();
        self.setDeltaMovement(motion.x, jumpVelocity, motion.z);
        this.setIsJumping(true);
        self.hasImpulse = true;
        if (travelVector.z > 0.0) {
            float sin = Mth.sin(self.getYRot() * (float)(Math.PI / 180.0));
            float cos = Mth.cos(self.getYRot() * (float)(Math.PI / 180.0));
            self.setDeltaMovement(
                self.getDeltaMovement().add(-0.4F * sin * jumpScale, 0.0, 0.4F * cos * jumpScale)
            );
        }
        ci.cancel();
    }

    // ═══════════════════════════════════════════
    //  4. 降低奔腾音效频次
    // ═══════════════════════════════════════════

    @Inject(
        method = "playStepSound",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/animal/horse/AbstractHorse;playGallopSound(Lnet/minecraft/world/level/block/SoundType;)V"
        ),
        cancellable = true
    )
    private void plumes$reduceGallopFrequency(BlockPos pos, BlockState state, CallbackInfo ci) {
        int stepGap = this.gallopSoundCounter - this.plumes$lastGallopStep;
        if (stepGap < 4) {
            ci.cancel();
        } else {
            this.plumes$lastGallopStep = this.gallopSoundCounter;
        }
    }
}
