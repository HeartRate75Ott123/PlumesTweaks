package com.plumestweaks.mixin;

import com.plumestweaks.Config;
import com.plumestweaks.network.CompassFoundPayload;
import com.plumestweaks.util.CompassTeleportHeight;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 注入 Nature's Compass 的 {@code succeed} —— 查找到群系的服务端入口。
 * <p>
 * 只发送群系的注册键（{@code namespace:path}），显示名由客户端用翻译键
 * {@code biome.<ns>.<path>} 解析，以兼容资源包汉化。
 */
@Pseudo
@Mixin(targets = "com.chaosthedude.naturescompass.items.NaturesCompassItem", remap = false)
public abstract class NaturesCompassFoundMixin {

    @Inject(method = "succeed(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;IILjava/util/List;IZ)V",
            at = @At("RETURN"))
    private void onSucceed(ItemStack stack, Player player, int x, int z, List<BlockPos> prevPos,
                           int samples, boolean track, CallbackInfo ci) {
        if (!Config.AUTO_XAERO_WAYPOINT.get()) return;
        if (!(player instanceof ServerPlayer sp)) return;
        try {
            ResourceLocation biomeKey = (ResourceLocation) Class.forName(
                            "com.chaosthedude.naturescompass.items.NaturesCompassItem")
                    .getMethod("getBiomeKey", ItemStack.class)
                    .invoke(stack.getItem(), stack);
            if (biomeKey == null) return;

            String dim = sp.level().dimension().location().toString();
            BlockPos target = (prevPos != null && !prevPos.isEmpty()) ? prevPos.get(0) : new BlockPos(x, 0, z);
            int wx = target.getX();
            int wz = target.getZ();
            int wy = CompassTeleportHeight.computeY(sp.serverLevel(), wx, wz,
                    "com.chaosthedude.naturescompass.network.TeleportPacket");
            PacketDistributor.sendToPlayer(sp, new CompassFoundPayload(wx, wy, wz, dim, biomeKey.toString(), (byte) 0));
        } catch (Exception ignored) {
            // 反射调用指南针类失败，静默跳过
        }
    }
}
