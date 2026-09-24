package com.plumestweaks.mixin;

import com.plumestweaks.PlumesTweaks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让下界传送门可以在时空裂隙维度内点燃。
 * <p>
 * 原版（含 Forge 1.20.1）把「能否形成下界传送门」硬编码成只有主世界与下界，
 * 判定点在 {@code BaseFireBlock#inPortalDimension(Level)}：
 * <pre>
 * private static boolean inPortalDimension(Level level) {
 *     return level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
 * }
 * </pre>
 * 该方法被两处使用，缺一不可：
 * <ol>
 *   <li>{@code BaseFireBlock#onPlace} —— 火焰被放置后尝试把黑曜石门框变成传送门；</li>
 *   <li>{@code BaseFireBlock#isPortal} —— 被 {@code canBePlacedAt} 调用，
 *       决定打火石能否在这个位置点着火。</li>
 * </ol>
 * 因此只给 {@code onPlace} 放行是不够的（表现是「打火石点不着」而不是「有火但不成门」）。
 * 本 Mixin 直接改写 {@code inPortalDimension} 的返回值，两处判定同时放行。
 * <p>
 * <b>注意不要加 {@code @Pseudo}</b>：这是原版类、必然存在，
 * 加了反而会被 Mixin 当成"可能缺失的可选目标"而弱化处理（实测会导致注入不生效）。
 */
@Mixin(BaseFireBlock.class)
public abstract class RiftNetherPortalMixin {

    @Inject(
            method = "inPortalDimension(Lnet/minecraft/world/level/Level;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void plumes$allowRiftDimension(Level level, CallbackInfoReturnable<Boolean> cir) {
        ResourceLocation dimensionId = level.dimension().location();
        if (PlumesTweaks.MODID.equals(dimensionId.getNamespace())
                && "rift".equals(dimensionId.getPath())) {
            cir.setReturnValue(true);
        }
    }
}
