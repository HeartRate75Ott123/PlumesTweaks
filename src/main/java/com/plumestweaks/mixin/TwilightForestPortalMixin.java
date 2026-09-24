package com.plumestweaks.mixin;

import com.plumestweaks.Config;
import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.dimension.PlumesDimensions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.TickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 让暮色森林（Twilight Forest）传送门可以在时空裂隙维度内建造。
 * <p>
 * <b>1.20.1 与 1.21.1 的门禁位置完全不同</b>，不能照抄：
 * <ul>
 *   <li>1.21.1：{@code ProgressionEvents#checkForPortalCreation}，由
 *       {@code performProtectionAndPortalChecks} 调用；</li>
 *   <li>1.20.1：{@code TFTickHandler#checkForPortalCreation}，由
 *       {@code TFTickHandler#playerTick(TickEvent.PlayerTickEvent)} 调用。</li>
 * </ul>
 * TF 1.20.1 的维度判定是：
 * <pre>
 * if (world.dimension().location().equals(TFConfig.COMMON_CONFIG.originDimension.get())
 *         || TFGenerationSettings.isTwilightPortalDestination(world)
 *         || TFConfig.COMMON_CONFIG.allowPortalsInOtherDimensions.get()) { ...找激活物、成门... }
 * </pre>
 * 裂隙维度三者皆不满足，所以把暮色门激活物（钻石）丢进水池不会成门。
 * <p>
 * <b>做法：</b>在 {@code playerTick} 的 TAIL 处，若玩家位于裂隙维度，就按 TF 自己的节奏
 * （相同的 tick 取模、相同的权限门槛、相同的半径）重跑一遍 TF 的
 * {@code checkForPortalCreation}；为了越过维度判定，只在这一次调用期间把
 * {@code allowPortalsInOtherDimensions} 临时置 true，调用结束立刻还原 ——
 * 不动 TF 的全局配置，也不复制它的门生成算法。
 * <p>
 * 全程 {@link Pseudo} + 反射 + try/catch：未装 TF 时静默失效；
 * TF 若改了字段名或方法签名，只会退化成「裂隙内建不了暮色门」，不会崩溃。
 */
@Pseudo
@Mixin(targets = "twilightforest.TFTickHandler", remap = false)
public abstract class TwilightForestPortalMixin {

    private static boolean reflected;
    private static boolean loggedFailure;

    private static ForgeConfigSpec.BooleanValue allowOtherDim;
    private static ForgeConfigSpec.BooleanValue disablePortal;
    private static ForgeConfigSpec.BooleanValue checkDestination;
    private static ForgeConfigSpec.BooleanValue adminOnly;
    /** {@code TFTickHandler#checkForPortalCreation(ServerPlayer, Level, float)} */
    private static Method checkPortal;

    private static synchronized void reflect() {
        if (reflected) return;
        reflected = true;
        try {
            Class<?> tfConfig = Class.forName("twilightforest.TFConfig");
            Object common = tfConfig.getField("COMMON_CONFIG").get(null);
            Class<?> commonClass = common.getClass();

            allowOtherDim = bool(commonClass, common, "allowPortalsInOtherDimensions");
            disablePortal = bool(commonClass, common, "disablePortalCreation");
            checkDestination = bool(commonClass, common, "checkPortalDestination");
            adminOnly = bool(commonClass, common, "adminOnlyPortals");

            Method m = Class.forName("twilightforest.TFTickHandler").getDeclaredMethod(
                    "checkForPortalCreation", ServerPlayer.class, Level.class, float.class);
            m.setAccessible(true);
            checkPortal = m;

            if (allowOtherDim == null || disablePortal == null) {
                PlumesTweaks.LOGGER.warn("[TFPortal] 未找到 TF 的传送门配置字段，裂隙内将无法建暮色门");
            }
        } catch (Throwable t) {
            allowOtherDim = null;
            disablePortal = null;
            checkPortal = null;
            PlumesTweaks.LOGGER.warn("[TFPortal] 反射 TF 传送门接口失败（裂隙内将无法建暮色门）：{}", t.toString());
        }
    }

    private static ForgeConfigSpec.BooleanValue bool(Class<?> owner, Object instance, String field) {
        try {
            Object value = owner.getField(field).get(instance);
            return value instanceof ForgeConfigSpec.BooleanValue b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTrue(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        if (value == null) return fallback;
        try {
            Boolean v = value.get();
            return v != null ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    @Inject(method = "playerTick", at = @At("TAIL"), remap = false, expect = 0)
    private static void plumes$allowPortalInRift(TickEvent.PlayerTickEvent event, CallbackInfo ci) {
        try {
            if (event == null || event.phase != TickEvent.Phase.END) return;
            if (!(event.player instanceof ServerPlayer player)) return;
            if (!(player.level() instanceof ServerLevel level)) return;
            if (!PlumesDimensions.RIFT_LOCATION.equals(level.dimension().location())) return;
            if (!Config.RIFT_ALLOW_TWILIGHT_PORTAL.get()) return;

            reflect();
            if (checkPortal == null || allowOtherDim == null) return;

            // TF 全局已禁用建门 → 尊重它
            if (isTrue(disablePortal, false)) return;
            // 全局已放开其它维度 → TF 自己会处理，无需插手
            if (isTrue(allowOtherDim, false)) return;

            // 与 TF 完全一致的节流：checkPortalDestination 为真时每 100 tick，否则每 20 tick
            int interval = isTrue(checkDestination, false) ? 100 : 20;
            if (player.tickCount % interval != 0) return;

            MinecraftServer server = level.getServer();
            if (server == null) return;

            // 与 TF 一致的权限门槛与半径
            boolean adminOnlyPortals = isTrue(adminOnly, false);
            if (adminOnlyPortals && server.getProfilePermissions(player.getGameProfile()) != 0) return;
            float range = adminOnlyPortals ? 4.0F : 32.0F;

            allowOtherDim.set(true);
            try {
                checkPortal.invoke(null, player, level, range);
            } finally {
                allowOtherDim.set(false);
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                PlumesTweaks.LOGGER.warn("[TFPortal] 裂隙内建暮色传送门的兼容处理失败（不再重试）：{}", t.toString());
            }
        }
    }
}
