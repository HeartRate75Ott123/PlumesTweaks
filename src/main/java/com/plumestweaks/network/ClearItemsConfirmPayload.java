package com.plumestweaks.network;

import com.plumestweaks.PlumesTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClearItemsConfirmPayload(int radius) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClearItemsConfirmPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(PlumesTweaks.MODID, "clearitems_confirm"));

    public static final StreamCodec<ByteBuf, ClearItemsConfirmPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ClearItemsConfirmPayload::radius,
                    ClearItemsConfirmPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
