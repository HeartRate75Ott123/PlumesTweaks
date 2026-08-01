package com.plumestweaks.mixin;

import com.plumestweaks.bridge.ParagliderBridge;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FastMove + Paraglider 耐力集成 Mixin。
 *
 * 通过 FastMove IFastPlayer 接口检测动作状态变化，将消耗从饥饿值重定向到
 * Paraglider 耐力条。在 tick TAIL 比较上一 tick 的动作状态和当前状态。
 *
 * 动作 -> 耐力消耗映射：
 *   滑铲启动 -> 333
 *   翻滚启动 -> 225
 *   跑墙每 tick -> 10
 *
 * 耐力不足时阻止动作（三层防线）：
 *   <ul>
 *     <li>{@code tick()} HEAD（早于 {@code travel()}）：设
 *         {@code diveCooldown = 1}，使 FastMove 在 {@code travel()} HEAD 检查
 *         {@code diveCooldown == 0} 时不满足条件，翻滚根本不会触发。</li>
 *     <li>{@code travel()} TAIL：通过 IFastPlayer.fastmove_setMoveState 将
 *         状态复位为 NONE，同时清零 bonusVelocity 和对应冷却。</li>
 *     <li>服务端 tick TAIL：与客户端相同逻辑，作为服务端后备。</li>
 *   </ul>
 */
@Mixin(value = Player.class, priority = 2000)
public class FastMoveStaminaMixin {

    private static final int STATE_NONE = 0;
    private static final int STATE_SLIDING = 1;
    private static final int STATE_ROLLING = 3;
    private static final int STATE_WALLRUNNING_LEFT = 4;
    private static final int STATE_WALLRUNNING_RIGHT = 5;

    private static final int COST_SLIDE = 333;
    private static final int COST_ROLL = 225;
    private static final int COST_WALL_RUN_TICK = 10;
    /** 疾跑每 tick 恢复耐力值（初始耐力 1000，+6/tick ≈ 每秒 120，满耐力需 ~8.3 秒） */
    private static final int SPRINT_RECOVERY = 6;

    private static final float EXHAUSTION_SLIDE = 10.0f;
    private static final float EXHAUSTION_ROLL = 50.0f;
    private static final float EXHAUSTION_WALL_RUN = 0f;

    @Unique
    private int plumes$prevStateOrdinal = 0;

    @Unique
    private int plumes$prevClientState = 0;

    /**
     * 客户端侧：在 {@code tick()} HEAD 检测耐力不足时设对应冷却为 1，
     * 使 FastMove 在 {@code travel()} HEAD 的 {@code xxxCooldown == 0} 条件不满足，
     * 滑铲/翻滚根本不会被触发，彻底避免动画闪烁。
     */
    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void plumes$preventClientActionFlicker(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide) return;
        if (!ParagliderBridge.isAvailable()) return;

        int stamina = ParagliderBridge.getStaminaValue(self);
        if (stamina < COST_ROLL) {
            setCooldownIfZero(self, STATE_ROLLING);
        }
        if (stamina < COST_SLIDE) {
            setCooldownIfZero(self, STATE_SLIDING);
        }
    }

    /**
     * 客户端侧：在 {@code travel()} TAIL 检测滑铲/翻滚刚触发但耐力不够时，
     * 复位到 NONE 并清除速度和冷却。
     *
     * 只在状态切换瞬间检查耐力（例如 NONE→SLIDING 的首 tick），
     * 动作已在持续时不重复检查，避免耐力同步滞后导致动作中途被错误取消。
     */
    @Inject(method = "travel", at = @At("TAIL"), remap = false)
    private void plumes$cancelActionOnClient(Vec3 movementInput, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide) return;
        if (!ParagliderBridge.isAvailable()) return;

        int state = getFastMoveStateOrdinal(self);
        int cost = getStaminaCost(state);
        if (cost < 0) {
            plumes$prevClientState = state;
            return;
        }

        // 只在切换瞬间检查（NONE→SLIDING/ROLLING 的首 tick），不中断进行中的动作
        if (state == plumes$prevClientState) {
            plumes$prevClientState = state;
            return;
        }
        plumes$prevClientState = state;

        int stamina = ParagliderBridge.getStaminaValue(self);
        if (stamina >= cost) return;

        setFastMoveState(self, STATE_NONE);
        clearBonusVelocity(self);
        resetActionCooldown(self, state);
        self.setPose(net.minecraft.world.entity.Pose.STANDING);
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void plumes$onTickTail(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide) return;

        int current = getFastMoveStateOrdinal(self);
        int previous = plumes$prevStateOrdinal;
        plumes$prevStateOrdinal = current;

        if (!ParagliderBridge.isAvailable()) return;

        // === FastMove 动作消耗 ===
        if (current > STATE_NONE) {
            FoodData fd = self.getFoodData();
            if (current == STATE_SLIDING && previous != STATE_SLIDING) {
                int stamina = ParagliderBridge.getStaminaValue(self);
                if (stamina < COST_SLIDE) {
                    setFastMoveState(self, STATE_NONE);
                    resetActionCooldown(self, current);
                    plumes$prevStateOrdinal = STATE_NONE;
                } else {
                    int taken = ParagliderBridge.takeStamina(self, COST_SLIDE);
                    if (taken > 0) {
                        ((FoodDataAccessor) fd).setExhaustion(Math.max(0, ((FoodDataAccessor) fd).getExhaustion() - EXHAUSTION_SLIDE));
                    }
                }
            } else if (current == STATE_ROLLING && previous != STATE_ROLLING) {
                int stamina = ParagliderBridge.getStaminaValue(self);
                if (stamina < COST_ROLL) {
                    setFastMoveState(self, STATE_NONE);
                    resetActionCooldown(self, current);
                    plumes$prevStateOrdinal = STATE_NONE;
                    self.setPose(net.minecraft.world.entity.Pose.STANDING);
                } else {
                    int taken = ParagliderBridge.takeStamina(self, COST_ROLL);
                    if (taken > 0) {
                        ((FoodDataAccessor) fd).setExhaustion(Math.max(0, ((FoodDataAccessor) fd).getExhaustion() - EXHAUSTION_ROLL));
                    }
                }
            } else if (current == STATE_WALLRUNNING_LEFT || current == STATE_WALLRUNNING_RIGHT) {
                int taken = ParagliderBridge.takeStamina(self, COST_WALL_RUN_TICK);
                if (taken > 0) {
                    ((FoodDataAccessor) fd).setExhaustion(Math.max(0, ((FoodDataAccessor) fd).getExhaustion() - EXHAUSTION_WALL_RUN));
                }
            }
        }

        // === 疾跑耐力恢复 ===
        if (self.isSprinting() && ParagliderBridge.getStaminaValue(self) < ParagliderBridge.getMaxStamina(self)) {
            ParagliderBridge.giveStamina(self, SPRINT_RECOVERY);
        }
    }

    /** 清除 FastMove 注入的 bonusVelocity，防止取消动作后仍有速度加成。 */
    @Unique
    private static void clearBonusVelocity(Player player) {
        for (String name : new String[]{"bonusVelocity", "playermixin$bonusVelocity"}) {
            try {
                java.lang.reflect.Field f = Player.class.getDeclaredField(name);
                f.setAccessible(true);
                f.set(player, Vec3.ZERO);
                return;
            } catch (Exception ignored) {}
        }
    }

    /** 复位 FastMove 动作的冷却字段（slideCooldown / diveCooldown），使玩家可以立即重试。 */
    @Unique
    private static void resetActionCooldown(Player player, int state) {
        String primary, alt;
        if (state == STATE_SLIDING) {
            primary = "slideCooldown";
            alt = "playermixin$slideCooldown";
        } else if (state == STATE_ROLLING) {
            primary = "diveCooldown";
            alt = "playermixin$diveCooldown";
        } else {
            return;
        }
        for (String name : new String[]{primary, alt}) {
            try {
                java.lang.reflect.Field f = Player.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(player, 0);
                return;
            } catch (Exception ignored) {}
        }
    }

    /**
     * 如果指定动作的冷却字段当前 <= 0，设其为 1。
     * 用于 {@code tick()} HEAD 中提前阻止 FastMove 的 {@code xxxCooldown == 0} 条件，
     * 使动作根本不会被触发。
     */
    @Unique
    private static void setCooldownIfZero(Player player, int state) {
        String primary, alt;
        if (state == STATE_SLIDING) {
            primary = "slideCooldown";
            alt = "playermixin$slideCooldown";
        } else if (state == STATE_ROLLING) {
            primary = "diveCooldown";
            alt = "playermixin$diveCooldown";
        } else {
            return;
        }
        for (String name : new String[]{primary, alt}) {
            try {
                java.lang.reflect.Field f = Player.class.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getInt(player) <= 0) {
                    f.setInt(player, 1);
                }
                return;
            } catch (Exception ignored) {}
        }
    }

    /** 根据动作状态返回需要的耐力消耗，未知动作返回 -1。 */
    @Unique
    private static int getStaminaCost(int state) {
        if (state == STATE_SLIDING) return COST_SLIDE;
        if (state == STATE_ROLLING) return COST_ROLL;
        return -1;
    }

    // ═══════════════════════════════════════════
    //  跨模组类加载 + FastMove 反射工具
    // ═══════════════════════════════════════════

    /** 跨 ClassLoader 查找 FastMove 类（客户端需要 fallback）。 */
    @Unique
    private static Class<?> fmClass(String name) {
        try { return Class.forName(name, false, FastMoveStaminaMixin.class.getClassLoader()); }
        catch (ClassNotFoundException e1) {
            try {
                ClassLoader ctx = Thread.currentThread().getContextClassLoader();
                return Class.forName(name, false, ctx);
            } catch (ClassNotFoundException | NullPointerException e2) {
                try { return Class.forName(name, false, ClassLoader.getSystemClassLoader()); }
                catch (ClassNotFoundException e3) { return null; }
            }
        }
    }

    /** 通过反射调用 IFastPlayer.fastmove_setMoveState。 */
    @Unique
    private static void setFastMoveState(Player player, int ordinal) {
        try {
            Class<?> ifp = fmClass("io.github.beeebea.fastmove.IFastPlayer");
            if (ifp == null || !ifp.isInstance(player)) return;
            Class<?> ms = fmClass("io.github.beeebea.fastmove.MoveState");
            if (ms == null) return;
            Object target = ms.getMethod("STATE", int.class).invoke(null, ordinal);
            ifp.getMethod("fastmove_setMoveState", ms).invoke(player, target);
        } catch (Exception ignored) {}
    }

    @Unique
    private static int getFastMoveStateOrdinal(Player player) {
        try {
            Class<?> ifp = fmClass("io.github.beeebea.fastmove.IFastPlayer");
            if (ifp == null || !ifp.isInstance(player)) return STATE_NONE;

            Object current = ifp.getMethod("fastmove_getMoveState").invoke(player);
            if (current == null) return STATE_NONE;

            Class<?> msClass = current.getClass();
            if (msClass.getField("SLIDING").get(null) == current) return STATE_SLIDING;
            if (msClass.getField("ROLLING").get(null) == current) return STATE_ROLLING;
            if (msClass.getField("WALLRUNNING_LEFT").get(null) == current) return STATE_WALLRUNNING_LEFT;
            if (msClass.getField("WALLRUNNING_RIGHT").get(null) == current) return STATE_WALLRUNNING_RIGHT;

        } catch (Exception ignored) {}
        return STATE_NONE;
    }
}
