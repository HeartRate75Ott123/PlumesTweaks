package com.plumestweaks.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 时空裂隙默认进入点组件。
 * <p>
 * 附着在物品上（而非玩家数据），记录进入裂隙维度时的落点维度与坐标，
 * 随物品持久保存，不受玩家死亡/重连影响。
 */
public record RiftEnterData(ResourceKey<Level> dimension, int x, int y, int z) {

    public static final Codec<RiftEnterData> CODEC = RecordCodecBuilder.create(
            inst -> inst.group(
                    ResourceKey.codec(Registries.DIMENSION).fieldOf("dim").forGetter(RiftEnterData::dimension),
                    Codec.INT.fieldOf("x").forGetter(RiftEnterData::x),
                    Codec.INT.fieldOf("y").forGetter(RiftEnterData::y),
                    Codec.INT.fieldOf("z").forGetter(RiftEnterData::z)
            ).apply(inst, RiftEnterData::new)
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RiftEnterData> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceKey.streamCodec(Registries.DIMENSION), RiftEnterData::dimension,
                    ByteBufCodecs.VAR_INT, RiftEnterData::x,
                    ByteBufCodecs.VAR_INT, RiftEnterData::y,
                    ByteBufCodecs.VAR_INT, RiftEnterData::z,
                    RiftEnterData::new
            );
}
