package com.plumestweaks.client;

import com.plumestweaks.network.ClearItemsConfirmPayload;
import com.plumestweaks.network.ClearItemsConfirmResponsePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientPayloadHandler {

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("plumestweaks");
        registrar.playToClient(
                ClearItemsConfirmPayload.TYPE,
                ClearItemsConfirmPayload.STREAM_CODEC,
                ClientPayloadHandler::handleConfirmRequest
        );
    }

    private static void handleConfirmRequest(ClearItemsConfirmPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(new ConfirmScreen(
                    result -> {
                        if (result) {
                            PacketDistributor.sendToServer(new ClearItemsConfirmResponsePayload());
                        }
                        mc.setScreen(null);
                    },
                    Component.empty(),
                    Component.translatable("gui.plumestweaks.clearitems.confirm.message", payload.radius())
            ));
        });
    }
}
