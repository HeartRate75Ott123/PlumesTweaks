package com.plumestweaks.mixin;

import com.plumestweaks.Config;
import com.plumestweaks.network.CompassFoundPayload;
import com.plumestweaks.util.CompassTeleportHeight;
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
 * <p>
 * 只发送结构的注册键（{@code namespace:path}），显示名由客户端用翻译键
 * {@code structure.<ns>.<path>} 解析，以兼容资源包汉化。
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
            if (key == null) return;

            String dim = sp.level().dimension().location().toString();
            int wx = pos.getX();
            int wz = pos.getZ();
            int wy = CompassTeleportHeight.computeY(sp.serverLevel(), wx, wz,
                    "com.chaosthedude.explorerscompass.network.TeleportPacket");
            PacketDistributor.sendToPlayer(sp,
                    new CompassFoundPayload(wx, wy, wz, dim, key.toString(), (byte) 1));
        } catch (Exception ignored) {
            // 反射/注册表读取失败，静默跳过
        }
    }
}
