package com.plumestweaks.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 滑铲/翻滚摔落保护 Mixin。
 * <p>
 * 玩家在滑铲或翻滚期间及结束后 5 秒内，最大安全摔落高度增加 6 格（3 → 9）。
 * 覆盖"滑铲/翻滚加速 → 跳跃 → 高速落地"的典型场景。
 * <p>
 * 通过反射检测 FastMove 的动作状态，无 FastMove 时静默跳过。
 */
@Mixin(LivingEntity.class)
public class SlideFallProtectionMixin {

    /** 动作结束后保护缓冲持续时间（tick = 5 秒） */
    private static final int GRACE_TICKS = 100;

    /** 每个玩家最后一次动作时的 game time，WeakHashMap 自动清理已下线玩家 */
    private static final Map<Player, Long> lastActionTime = new WeakHashMap<>();

    // ==================== 状态追踪 ====================

    /**
     * 每 tick 检测滑铲/翻滚状态，更新最近动作时间戳。
     * 注入 {@link LivingEntity#tick()} HEAD——对 Player 来说运行在 {@code super.tick()} 内，
     * 晚于 {@link FastMoveStaminaMixin} 的 {@code Player.tick()} HEAD 注入，此时动作状态已检测完毕。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void plumes$trackActionState(CallbackInfo ci) {
        if (!(((Object) this) instanceof Player player)) return;
        if (plumes$isActiveAction(player)) {
            lastActionTime.put(player, player.level().getGameTime());
        }
    }

    // ==================== 摔落伤害改写 ====================

    /**
     * 拦截 {@link LivingEntity#calculateFallDamage(float, float)} 的 fallDistance 参数，
     * 在保护期内减去 6 格，使安全高度从 3 提升到 9 格。
     * <p>
     * 使用 {@link ModifyVariable} 而非 {@link Inject}(cancellable) 是因为后者与
     * {@link Mth#ceil(float)}（返回 int）组合会产生 ClassCastException
     * （Mixin 的 CIR 错误地将 float 当作 int 处理）。
     */
    @ModifyVariable(method = "calculateFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float plumes$reduceFallDistanceForSlide(float fallDistance) {
        if (!(((Object) this) instanceof Player player)) return fallDistance;
        if (plumes$isWithinGrace(player) || plumes$isActiveAction(player)) {
            // fallDistance - 6 → 安全高度从 3 变为 9
            return Math.max(0, fallDistance - 6.0f);
        }
        return fallDistance;
    }

    /** 是否在动作结束后的保护缓冲期内 */
    @Unique
    private static boolean plumes$isWithinGrace(Player player) {
        Long last = lastActionTime.get(player);
        return last != null && (player.level().getGameTime() - last) < GRACE_TICKS;
    }

    // ==================== FastMove 反射桥接 ====================

    /**
     * 通过反射检测玩家当前是否为 FastMove 滑铲或翻滚状态。
     * 跨类加载器安全，FastMove 不存在时始终返回 false。
     */
    @Unique
    private static boolean plumes$isActiveAction(Player player) {
        try {
            Class<?> ifp = Class.forName("io.github.beeebea.fastmove.IFastPlayer",
                    false, player.getClass().getClassLoader());
            if (ifp == null || !ifp.isInstance(player)) return false;

            Object current = ifp.getMethod("fastmove_getMoveState").invoke(player);
            if (current == null) return false;

            Class<?> msClass = current.getClass();
            return msClass.getField("SLIDING").get(null) == current
                    || msClass.getField("ROLLING").get(null) == current;
        } catch (Exception e) {
            return false;
        }
    }
}
