package com.plumestweaks.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 冷却数据组件。
 * <p>
 * 记录冷却结束的绝对游戏刻和总冷却刻数，
 * 直接作为 DataComponent 附着在物品上。
 */
public record CooldownData(long endTime, int duration) {

    public static final Codec<CooldownData> CODEC = RecordCodecBuilder.create(
            inst -> inst.group(
                    Codec.LONG.fieldOf("end").forGetter(CooldownData::endTime),
                    Codec.INT.fieldOf("dur").forGetter(CooldownData::duration)
            ).apply(inst, CooldownData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, CooldownData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, CooldownData::endTime,
                    ByteBufCodecs.INT, CooldownData::duration,
                    CooldownData::new
            );

    /** 返回剩余刻数，≤ 0 表示已过期 */
    public long remainingTicks(long gameTime) {
        return endTime - gameTime;
    }
}
