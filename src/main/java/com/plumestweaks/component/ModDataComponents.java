package com.plumestweaks.component;

import com.plumestweaks.PlumesTweaks;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.UnaryOperator;

/**
 * 模组自定义 DataComponent 注册表。
 */
public class ModDataComponents {

    private static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, PlumesTweaks.MODID);

    /** 时空裂隙冷却数据：冷却结束刻 + 总刻数 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CooldownData>> RIFT_COOLDOWN =
            register("rift_cooldown", builder -> builder
                    .persistent(CooldownData.CODEC)
                    .networkSynchronized(CooldownData.STREAM_CODEC));

    /** 原初之匣冷却数据：冷却结束刻 + 总刻数 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CooldownData>> TELEPORT_COOLDOWN =
            register("teleport_cooldown", builder -> builder
                    .persistent(CooldownData.CODEC)
                    .networkSynchronized(CooldownData.STREAM_CODEC));

    /** 时空裂隙默认进入点：维度 + 坐标（存于物品，不依赖玩家数据） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RiftEnterData>> RIFT_ENTER =
            register("rift_enter", builder -> builder
                    .persistent(RiftEnterData.CODEC)
                    .networkSynchronized(RiftEnterData.STREAM_CODEC));

    /** 时空裂隙多维出口点：裂隙外各维度最近一次进入裂隙的坐标（存于物品） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RiftExitData>> RIFT_EXITS =
            register("rift_exits", builder -> builder
                    .persistent(RiftExitData.CODEC)
                    .networkSynchronized(RiftExitData.STREAM_CODEC));

    private static <T> DeferredHolder<DataComponentType<?>, DataComponentType<T>> register(
            String name, UnaryOperator<DataComponentType.Builder<T>> builder) {
        return COMPONENTS.register(name, () -> builder.apply(DataComponentType.builder()).build());
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
