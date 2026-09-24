package com.plumestweaks.network;

import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.component.RiftExitData;
import com.plumestweaks.component.RiftExitEntry;
import com.plumestweaks.item.DimensionalRiftItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 1.20.1 网络层（Forge {@link SimpleChannel}）。
 * <p>
 * 两条消息：
 * <ul>
 *   <li>S2C {@link OpenGui} —— 下发出口点列表并打开传送界面；</li>
 *   <li>C2S {@link Teleport} —— 选定某个维度，请求传送（坐标由服务端从物品 NBT 重读）。</li>
 * </ul>
 * S2C 的客户端处理用反射调用，避免专用服务端加载客户端类。
 */
public final class RiftNetwork {

    private static final String PROTOCOL = "1";

    /** 客户端界面类的反射入口（服务端不加载） */
    private static final String CLIENT_SCREEN = "com.plumestweaks.client.RiftTeleportScreen";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.tryParse(PlumesTweaks.MODID + ":main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private RiftNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, OpenGui.class, OpenGui::encode, OpenGui::decode, OpenGui::handle);
        CHANNEL.registerMessage(id, Teleport.class, Teleport::encode, Teleport::decode, Teleport::handle);
    }

    /** 服务端 → 客户端：打开界面（携带当前物品上记录的全部出口点） */
    public static void sendOpenGui(ServerPlayer player, RiftExitData exits) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenGui(exits));
    }

    /** 客户端 → 服务端：请求传送到某个维度 */
    public static void sendTeleport(String dimensionId) {
        CHANNEL.sendToServer(new Teleport(dimensionId));
    }

    // ========== S2C：打开界面 ==========

    public record OpenGui(RiftExitData exits) {

        static void encode(OpenGui msg, FriendlyByteBuf buf) {
            var ordered = msg.exits.ordered();
            buf.writeVarInt(ordered.size());
            for (RiftExitEntry e : ordered) {
                buf.writeUtf(e.dimensionId());
                buf.writeInt(e.x());
                buf.writeInt(e.y());
                buf.writeInt(e.z());
                buf.writeFloat(e.yaw());
                buf.writeFloat(e.pitch());
            }
        }

        static OpenGui decode(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            Map<String, RiftExitEntry> map = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String dim = buf.readUtf();
                RiftExitEntry entry = new RiftExitEntry(dim,
                        buf.readInt(), buf.readInt(), buf.readInt(),
                        buf.readFloat(), buf.readFloat());
                map.put(dim, entry);
            }
            return new OpenGui(new RiftExitData(map));
        }

        static void handle(OpenGui msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> openScreen(msg.exits));
            context.setPacketHandled(true);
        }

        /** 反射打开客户端界面：服务端永远不会执行到这里（收不到 S2C） */
        private static void openScreen(RiftExitData exits) {
            if (FMLLoader.getDist() != Dist.CLIENT) return;
            try {
                Class<?> screenClass = Class.forName(CLIENT_SCREEN);
                screenClass.getMethod("open", RiftExitData.class).invoke(null, exits);
            } catch (Throwable t) {
                PlumesTweaks.LOGGER.error("[RiftGui] 打开裂隙界面失败", t);
            }
        }
    }

    // ========== C2S：请求传送 ==========

    public record Teleport(String dimensionId) {

        static void encode(Teleport msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.dimensionId);
        }

        static Teleport decode(FriendlyByteBuf buf) {
            return new Teleport(buf.readUtf());
        }

        static void handle(Teleport msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) {
                    DimensionalRiftItem.teleportToRecordedDimension(player, msg.dimensionId);
                }
            });
            context.setPacketHandled(true);
        }
    }
}
