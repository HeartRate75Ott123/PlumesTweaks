package com.plumestweaks.mixin;

import com.plumestweaks.Config;
import com.plumestweaks.network.CompassFoundPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 Explorer's Compass 的 {@code StructureSearchWorker.succeed} —— 查找到结构的服务端入口。
 * 子类（Generic/ConcentricRings/RandomSpread）均未 override 该方法，注入基类即可覆盖全部。
 * 通过 {@code @Shadow} 取持有搜索的玩家，结构键取自注册表。
 */
@Pseudo
@Mixin(targets = "com.chaosthedude.explorerscompass.worker.StructureSearchWorker", remap = false)
public abstract class ExplorersCompassFoundMixin {

    @Shadow protected Player player;

    @Inject(method = "succeed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)V",
            at = @At("RETURN"))
    private void onSucceed(BlockPos pos, Structure structure, CallbackInfo ci) {
        if (!Config.AUTO_XAERO_WAYPOINT.get()) return;
        if (!(player instanceof ServerPlayer sp)) return;
        try {
            var structReg = sp.serverLevel().registryAccess()
                    .registry(net.minecraft.core.registries.Registries.STRUCTURE);
            ResourceLocation key = structReg.map(reg -> reg.getKey(structure)).orElse(null);
            String name = key != null ? key.toString() : "structure";
            if (key != null && structReg.isPresent()) {
                var struct = structReg.get().get(key);
                if (struct != null) {
                    try {
                        Object comp = struct.getClass().getMethod("getDescription").invoke(struct);
                        if (comp instanceof net.minecraft.network.chat.Component c) {
                            String s = c.getString();
                            if (s != null && !s.isEmpty()) name = s;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            String dim = sp.level().dimension().location().toString();
            PacketDistributor.sendToPlayer(sp,
                    new CompassFoundPayload(pos.getX(), pos.getY(), pos.getZ(), dim, name, (byte) 1));
        } catch (Exception ignored) {
            // 反射/注册表读取失败，静默跳过
        }
    }
}
