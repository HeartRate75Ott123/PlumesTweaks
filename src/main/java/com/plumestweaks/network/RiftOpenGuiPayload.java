package com.plumestweaks.network;

import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.component.RiftExitData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：打开裂隙传送选择界面，并携带当前物品上已记录的全部出口点。
 * <p>
 * 数据直接随包下发（而不是让客户端自己读物品组件），原因有二：
 * <ul>
 *   <li>潜行右键设置的默认进入点可能在副手/其它槽位，客户端持有的栈与服务端不一定一致；</li>
 *   <li>服务端解析好的维度显示名可以随包下发，卸载维度模组后仍能显示可读名字。</li>
 * </ul>
 *
 * @param exits 已记录的出口点（裂隙外各维度最近一次的进入坐标）
 */
public record RiftOpenGuiPayload(RiftExitData exits) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RiftOpenGuiPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(PlumesTweaks.MODID, "rift_open_gui"));

    /**
     * {@code RiftExitData.STREAM_CODEC} 的缓冲类型是 {@code RegistryFriendlyByteBuf}
     * （它的 map 键值编解码器需要注册表），而本 payload 走普通 {@link ByteBuf}。
     * 两者在运行期是同一个对象，这里做一次收窄转换让泛型对上。
     */
    @SuppressWarnings("unchecked")
    private static final StreamCodec<ByteBuf, RiftExitData> EXITS_CODEC =
            (StreamCodec<ByteBuf, RiftExitData>) (StreamCodec<?, RiftExitData>) RiftExitData.STREAM_CODEC;

    public static final StreamCodec<ByteBuf, RiftOpenGuiPayload> STREAM_CODEC =
            StreamCodec.composite(
                    EXITS_CODEC, RiftOpenGuiPayload::exits,
                    RiftOpenGuiPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
