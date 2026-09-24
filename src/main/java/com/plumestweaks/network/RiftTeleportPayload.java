package com.plumestweaks.network;

import com.plumestweaks.PlumesTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：在裂隙传送界面上选定了某个维度，请求传送到该维度记录的坐标。
 * <p>
 * 服务端不信任客户端传来的坐标，只接受维度 id，实际坐标从物品组件
 * {@code plumestweaks:rift_exits} 中重新读取，避免客户端伪造传送目标。
 *
 * @param dimensionId 目标维度 id（{@code namespace:path}）
 */
public record RiftTeleportPayload(String dimensionId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RiftTeleportPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(PlumesTweaks.MODID, "rift_teleport"));

    public static final StreamCodec<ByteBuf, RiftTeleportPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, RiftTeleportPayload::dimensionId,
                    RiftTeleportPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
