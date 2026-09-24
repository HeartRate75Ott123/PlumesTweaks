package com.plumestweaks.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 裂隙「出口点」记录 —— 玩家从某个裂隙外维度进入裂隙时，在该维度留下的坐标。
 * <p>
 * 与 {@link RiftEnterData}（玩家手设在裂隙维度内的落点）互为反向：
 * 本记录描述「回到裂隙外的哪里」。
 * <p>
 * 只在从该维度**进入**裂隙时写入/覆盖；从裂隙传送出去时读取但不修改，
 * 因此同一个维度的记录会一直保留到下次从该维度进入。
 * <p>
 * {@code name} 为可选的显示名缓存：正常情况下留空，由客户端按当前语言把
 * {@code dimension:<ns>.<path>} 解析成可读名字（服务端读不到语言表）；
 * 只有在需要保留某个无法再解析的名字时才会写入。
 */
public record RiftExitEntry(String dimensionId, String name, int x, int y, int z, float yaw, float pitch) {

    public static final Codec<RiftExitEntry> CODEC = RecordCodecBuilder.create(
            inst -> inst.group(
                    Codec.STRING.fieldOf("dim").forGetter(RiftExitEntry::dimensionId),
                    Codec.STRING.optionalFieldOf("name", "").forGetter(RiftExitEntry::name),
                    Codec.INT.fieldOf("x").forGetter(RiftExitEntry::x),
                    Codec.INT.fieldOf("y").forGetter(RiftExitEntry::y),
                    Codec.INT.fieldOf("z").forGetter(RiftExitEntry::z),
                    Codec.FLOAT.optionalFieldOf("yaw", 0.0f).forGetter(RiftExitEntry::yaw),
                    Codec.FLOAT.optionalFieldOf("pitch", 0.0f).forGetter(RiftExitEntry::pitch)
            ).apply(inst, RiftExitEntry::new)
    );

    /** 坐标与朝向（打包为中间类型，规避 StreamCodec.composite 的参数上限） */
    private record Pose(int x, int y, int z, float yaw, float pitch) {

        static final StreamCodec<RegistryFriendlyByteBuf, Pose> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Pose::x,
                        ByteBufCodecs.VAR_INT, Pose::y,
                        ByteBufCodecs.VAR_INT, Pose::z,
                        ByteBufCodecs.FLOAT, Pose::yaw,
                        ByteBufCodecs.FLOAT, Pose::pitch,
                        Pose::new
                );
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RiftExitEntry> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, RiftExitEntry::dimensionId,
                    ByteBufCodecs.STRING_UTF8, RiftExitEntry::name,
                    Pose.STREAM_CODEC, e -> new Pose(e.x(), e.y(), e.z(), e.yaw(), e.pitch()),
                    (dimensionId, name, pose) -> new RiftExitEntry(dimensionId, name,
                            pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch())
            );

    /** 显示名（空则回退维度 id） */
    public String displayName() {
        return name == null || name.isBlank() ? dimensionId : name;
    }
}
