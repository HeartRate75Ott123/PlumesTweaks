package com.plumestweaks.mixin;

import com.plumestweaks.PlumesTweaks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * 原版把「能否形成下界传送门」硬编码成只有主世界与下界，判定点在
 * {@code BaseFireBlock#inPortalDimension(Level)}：
 * <pre>
 * private static boolean inPortalDimension(Level level) {
 *     return level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
 * }
 * </pre>
 * 该方法被两处使用，缺一不可：
 * <ol>
 *   <li>{@code BaseFireBlock#onPlace} —— 火焰被放置后尝试把黑曜石门框变成传送门；
 *       非主世界/下界直接跳过，因此打火石点火后连传送门方块都不会生成；</li>
 *   <li>{@code BaseFireBlock#isPortal(Level, BlockPos, Direction)} ——
 *       {@code canBePlacedAt} 用它判断「打火石能否在这个位置点着火」，
 *       非主世界/下界直接返回 false，于是打火石连火都点不起来。</li>
 * </ol>
 * 因此仅给裂隙维度放行 {@code onPlace} 是不够的；本 Mixin 直接改写
 * {@code inPortalDimension} 的返回值，让两处判定同时生效。
 * <p>
 * 注意：{@code NetherPortalBlock#getPortalDestination} 只判断「目标维度是不是下界」，
 * 不限制来源维度，所以进门/出门的维度传送本来就没有维度限制，
 * 唯一缺失的就是这里的「点燃」环节。
 */
@Mixin(BaseFireBlock.class)
public abstract class RiftNetherPortalMixin {

    @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
    private static void plumes$allowRiftDimension(Level level, CallbackInfoReturnable<Boolean> cir) {
        ResourceLocation dimensionId = level.dimension().location();
        if (PlumesTweaks.MODID.equals(dimensionId.getNamespace())
                && "rift".equals(dimensionId.getPath())) {
            cir.setReturnValue(true);
        }
    }
}
