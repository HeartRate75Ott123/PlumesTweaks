package com.plumestweaks.mixin;

import com.plumestweaks.Config;
import com.plumestweaks.network.CompassFoundPayload;
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
 * 取玩家、坐标与群系键，发 {@link CompassFoundPayload} 给该玩家。
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
            String name = biomeKey != null ? biomeKey.toString() : "biome";
            if (biomeKey != null) {
                var biome = sp.serverLevel().registryAccess()
                        .registry(net.minecraft.core.registries.Registries.BIOME)
                        .map(reg -> reg.get(biomeKey)).orElse(null);
                if (biome != null) {
                    try {
                        Object comp = biome.getClass().getMethod("getDescription").invoke(biome);
                        if (comp instanceof net.minecraft.network.chat.Component c) {
                            String s = c.getString();
                            if (s != null && !s.isEmpty()) name = s;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            String dim = sp.level().dimension().location().toString();
            PacketDistributor.sendToPlayer(sp, new CompassFoundPayload(x, 64, z, dim, name, (byte) 0));
        } catch (Exception ignored) {
            // 反射调用指南针类失败，静默跳过
        }
    }
}
