package com.plumestweaks.dimension;

import com.mojang.serialization.MapCodec;
import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.worldgen.RiftChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 时空裂隙维度注册。
 * <p>
 * {@link net.minecraft.world.level.dimension.DimensionType} 和
 * {@link net.minecraft.world.level.dimension.LevelStem} 通过数据包 JSON 声明：
 * <ul>
 *   <li>{@code data/plumestweaks/dimension_type/rift.json}</li>
 *   <li>{@code data/plumestweaks/dimension/rift.json}</li>
 * </ul>
 * 此处仅注册 {@link RiftChunkGenerator} 的编解码器，供 JSON 文件引用。
 */
public class PlumesDimensions {

    public static final ResourceLocation RIFT_LOCATION =
            ResourceLocation.parse(PlumesTweaks.MODID + ":rift");

    /** 区块生成器编解码器注册表 */
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, PlumesTweaks.MODID);

    /** 注册 rift 区块生成器编解码器，使 JSON 中的 "type": "plumestweaks:rift" 可被解析 */
    public static final Supplier<MapCodec<? extends ChunkGenerator>> RIFT_CODEC =
            CHUNK_GENERATORS.register("rift", () -> RiftChunkGenerator.CODEC);

    /** 获取玩家可传送的维度 ResourceKey */
    public static ResourceKey<net.minecraft.world.level.Level> riftLevelKey() {
        return ResourceKey.create(Registries.DIMENSION, RIFT_LOCATION);
    }
}
