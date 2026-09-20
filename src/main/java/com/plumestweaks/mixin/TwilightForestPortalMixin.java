package com.plumestweaks.mixin;

import com.plumestweaks.dimension.PlumesDimensions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 让暮色森林传送门可以在时空裂隙维度内建造。
 * <p>
 * 暮色森林（Twilight Forest）的门禁在
 * {@code twilightforest.events.ProgressionEvents#checkForPortalCreation}：
 * <pre>
 * if (world.dimension().location().equals(TFConfig.originDimension)
 *         || TFDimension.isTwilightPortalDestination(world)
 *         || TFConfig.allowPortalsInOtherDimensions) { ... }
 * </pre>
 * 裂隙维度三者皆不满足，因此在裂隙空岛上把暮色传送门激活物（钻石）丢进水池不会成门。
 * 配置项 {@code allowPortalsInOtherDimensions} 默认 false，而且它是全局开关，
 * 不该为了一个维度对整个整合包放开。
 * <p>
 * 本 Mixin 注入该检查的调用点
 * {@code ProgressionEvents#performProtectionAndPortalChecks(PlayerTickEvent.Post)}
 * 的 TAIL：若玩家正处于裂隙维度，则在「本次检查」内临时把
 * {@code TFConfig.allowPortalsInOtherDimensions} 置为 true 后调用暮色森林原有的
 * {@code checkForPortalCreation}，随即恢复原值——既不改动全局配置，
 * 也不复制暮色森林的门生成 / 安全落点算法。
 * 检查半径、检查频率、{@code portalCreationPermission} 权限门槛均沿用暮色森林原逻辑。
 * <p>
 * 通过 {@link Pseudo} + 反射实现，未安装暮色森林时该 Mixin 静默失效；即便暮色森林
 * 日后改动该方法签名或字段名，也只会跳过处理而不会导致崩溃。
 * <p>
 * 说明：下界传送门无需特殊处理——原版
 * {@code NetherPortalBlock#getPortalDestination} 只判断「目标维度是否为下界」，
 * 不限制来源维度，所以在裂隙维度内用黑曜石 + 打火石建门可以正常往返。
 */
@Pseudo
@Mixin(targets = "twilightforest.events.ProgressionEvents", remap = false)
public abstract class TwilightForestPortalMixin {

    /** 暮色森林 {@code TFConfig} 中控制「其它维度也可建门」的字段名 */
    private static final String TF_FIELD_ALLOW_OTHER_DIM = "allowPortalsInOtherDimensions";

    /** {@code twilightforest.config.TFConfig} */
    private static Class<?> plumes$tfConfig;
    /** {@code TFConfig#allowPortalsInOtherDimensions} */
    private static Field plumes$allowOtherDim;
    /** {@code TFConfig#disablePortalCreation} */
    private static Field plumes$disablePortal;
    /** {@code TFConfig#checkPortalPlacement} */
    private static Field plumes$checkPlacement;
    /** {@code TFConfig#portalCreationPermission} */
    private static Field plumes$permission;
    /** {@code ProgressionEvents#checkForPortalCreation(ServerPlayer, Level, float)} */
    private static Method plumes$checkPortal;

    static {
        try {
            plumes$tfConfig = Class.forName("twilightforest.config.TFConfig");
            plumes$allowOtherDim = accessible(TF_FIELD_ALLOW_OTHER_DIM);
            plumes$disablePortal = accessible("disablePortalCreation");
            plumes$checkPlacement = accessible("checkPortalPlacement");
            plumes$permission = accessible("portalCreationPermission");
        } catch (Throwable t) {
            plumes$tfConfig = null;
        }
        try {
            Method method = Class.forName("twilightforest.events.ProgressionEvents")
                    .getDeclaredMethod("checkForPortalCreation", ServerPlayer.class, Level.class, float.class);
            method.setAccessible(true);
            plumes$checkPortal = method;
        } catch (Throwable t) {
            plumes$checkPortal = null;
        }
    }

    private static Field accessible(String name) {
        try {
            Field field = plumes$tfConfig.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    @Inject(method = "performProtectionAndPortalChecks", at = @At("TAIL"), remap = false, expect = 0)
    private void plumes$allowPortalInRift(PlayerTickEvent.Post event, CallbackInfo ci) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (plumes$checkPortal == null || plumes$allowOtherDim == null) return;

        Level level = serverPlayer.level();
        if (!PlumesDimensions.RIFT_LOCATION.equals(level.dimension().location())) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        try {
            if (plumes$disablePortal != null && plumes$disablePortal.getBoolean(null)) return;

            // 全局已放开「其它维度建门」时暮色森林自己就能处理，无需插手
            if (plumes$allowOtherDim.getBoolean(null)) return;

            // 与暮色森林原逻辑一致：检查频率 + 权限门槛
            boolean checkPlacement = plumes$checkPlacement == null || plumes$checkPlacement.getBoolean(null);
            if (player.tickCount % (checkPlacement ? 20 : 100) != 0) return;

            int requiredPermission = plumes$permission == null ? 0 : plumes$permission.getInt(null);
            if (server.getProfilePermissions(serverPlayer.getGameProfile()) < requiredPermission) return;

            plumes$allowOtherDim.setBoolean(null, true);
            try {
                // 与原调用点相同的半径策略：admit/owner 级别时缩小到 4 格
                float range = requiredPermission >= 3 ? 4.0F : 32.0F;
                plumes$checkPortal.invoke(this, serverPlayer, level, range);
            } finally {
                plumes$allowOtherDim.setBoolean(null, false);
            }
        } catch (Throwable t) {
            // 兜底：绝不残留「全局放开」状态
            try {
                plumes$allowOtherDim.setBoolean(null, false);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
