package com.plumestweaks.client;

import com.plumestweaks.network.ClearItemsConfirmPayload;
import com.plumestweaks.network.ClearItemsConfirmResponsePayload;
import com.plumestweaks.network.CompassFoundPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.resources.language.I18n;
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

        // 指南针查找到 → Xaero 路径点
        registrar.playToClient(
                CompassFoundPayload.TYPE,
                CompassFoundPayload.STREAM_CODEC,
                ClientPayloadHandler::handleCompassFound
        );
    }

    private static void handleCompassFound(CompassFoundPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> XaeroWaypointBridge.addWaypoint(
                payload.x(), payload.y(), payload.z(), resolveCompassName(payload), payload.dimId()));
    }

    /**
     * 客户端解析指南针目标名称：优先使用翻译键（支持资源包汉化），未定义时回退 {@code namespace:path}。
     * <p>
     * 结构：{@code structure.<ns>.<path>}；群系：{@code biome.<ns>.<path>}。
     */
    private static String resolveCompassName(CompassFoundPayload payload) {
        String id = payload.id();
        if (id == null || id.isEmpty()) return id;
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String prefix = payload.kind() == 1 ? "structure" : "biome";
        String key = prefix + "." + id.substring(0, colon) + "." + id.substring(colon + 1);
        String translated = I18n.get(key);
        return translated.equals(key) ? id : translated;
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
