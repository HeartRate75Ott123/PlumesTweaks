package com.plumestweaks.network;

import com.plumestweaks.PlumesTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClearItemsConfirmResponsePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ClearItemsConfirmResponsePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(PlumesTweaks.MODID, "clearitems_confirm_response"));

    public static final StreamCodec<ByteBuf, ClearItemsConfirmResponsePayload> STREAM_CODEC =
            StreamCodec.unit(new ClearItemsConfirmResponsePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
