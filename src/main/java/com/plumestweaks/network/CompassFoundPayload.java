package com.plumestweaks.network;

import com.plumestweaks.PlumesTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：指南针查找到目标后，通知客户端在 Xaero 地图添加路径点。
 * <p>
 * {@code type}：0 = 群系（Nature's Compass），1 = 结构（Explorer's Compass）。
 */
public record CompassFoundPayload(int x, int y, int z, String dimId, String name, byte kind)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CompassFoundPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(PlumesTweaks.MODID, "compass_found"));

    public static final StreamCodec<ByteBuf, CompassFoundPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CompassFoundPayload::x,
                    ByteBufCodecs.VAR_INT, CompassFoundPayload::y,
                    ByteBufCodecs.VAR_INT, CompassFoundPayload::z,
                    ByteBufCodecs.STRING_UTF8, CompassFoundPayload::dimId,
                    ByteBufCodecs.STRING_UTF8, CompassFoundPayload::name,
                    ByteBufCodecs.BYTE, CompassFoundPayload::kind,
                    CompassFoundPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
