package com.plumestweaks.mixin;

import com.plumestweaks.dimension.PlumesDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MC-Prefab 兼容：让蓝图在时空裂隙维度里放水时不被换成圆石。
 * <p>
 * MC-Prefab 的 {@code Structure#WaterReplacedWithCobbleStone} 判定是：
 * <pre>
 * boolean isOverWorld = Level.OVERWORLD.compareTo(world.dimension()) == 0;
 * if (world.dimensionType().ultraWarm()          // 下界
 *         || (!isOverWorld &amp;&amp; config.allowWaterInNonOverworldDimensions)) {
 *     ... 把水 / 含水方块换成圆石 ...
 * }
 * </pre>
 * 裂隙维度既不是主世界、也不是 ultraWarm，于是完全取决于 MC-Prefab 的服务端配置项
 * {@code allowWaterInNonOverworldDimensions}（默认 {@code false}）。
 * 默认配置下其它维度本来就该换圆石，不该为一个维度去改人家的全局开关，
 * 所以这里只针对裂隙维度提前放行。
 * <p>
 * <b>返回 false，不是 true —— 这点很关键。</b>调用点是：
 * <pre>
 * if (!this.WaterReplacedWithCobbleStone(...) &amp;&amp; !this.CustomBlockProcessingHandled(...)) {
 *     ... 正常放置方块 ... world.setBlock(...)
 * }
 * </pre>
 * 方法名问的是「水被换成圆石了吗」，{@code false} 才表示「没换」。
 * 若在这里返回 {@code true}，调用点会认为「这个方块已经处理过了」而整段跳过，
 * 水根本不会被放置 —— 世界里留下的是空洞而不是水。
 * 返回 {@code false} 则让 MC-Prefab 走它原本的常规放置流程
 * （水是 {@code LiquidBlock}，有碰撞，会立刻 {@code world.setBlock} 放下去）。
 * <p>
 * {@link Pseudo} + {@code required:false} 配置：未装 MC-Prefab 时静默失效；
 * MC-Prefab 日后重命名该方法时只跳过处理，不会崩溃。
 * <p>
 * 参数表必须与目标方法完全一致（8 个参数）Mixin 才认得出注入点；
 * 这里只用原版类型，所以本模组编译期不需要 MC-Prefab 依赖。
 * <p>
 * <b>handler 不能加 {@code static}</b>：{@code WaterReplacedWithCobbleStone} 是实例方法，
 * 加 static 会被 Mixin 拒绝（"'static' modifier of handler method does not match target"），
 * 表现为「注入静默失败、水照旧变圆石」。
 */
@Pseudo
@Mixin(targets = "com.wuest.prefab.structures.base.Structure", remap = false)
public abstract class PrefabWaterInRiftMixin {

    @Inject(
            method = "WaterReplacedWithCobbleStone",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            expect = 0
    )
    private void plumes$allowWaterInRift(
            Object configuration,
            Object buildBlock,
            Level world,
            BlockPos originalPos,
            Block foundBlock,
            BlockState blockState,
            Player player,
            CallbackInfoReturnable<Boolean> cir) {

        if (world == null) return;
        if (PlumesDimensions.RIFT_LOCATION.equals(world.dimension().location())) {
            // 裂隙维度：报告「没有换成圆石」，让调用点照常把水放下去
            cir.cancel();
            cir.setReturnValue(false);
        }
    }
}
