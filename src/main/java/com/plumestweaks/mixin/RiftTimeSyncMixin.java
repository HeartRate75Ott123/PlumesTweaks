package com.plumestweaks.mixin;

import com.plumestweaks.PlumesTweaks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 裂隙维度时间/天气同步 Mixin。
 * <p>
 * 在每个 {@link ServerLevel#tick} 开始前，将裂隙维度的时间（dayTime）
 * 和天气状态同步为主世界的值。这样睡觉推进主世界时间后，裂隙维度
 * 也会同步更新，且无额外每 tick 事件开销——Mix-in 到现有 tick 循环中。
 */
@Mixin(ServerLevel.class)
public class RiftTimeSyncMixin {

    @Shadow
    private ServerLevelData serverLevelData;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        ResourceLocation dimId = self.dimension().location();
        if (!PlumesTweaks.MODID.equals(dimId.getNamespace())
                || !"rift".equals(dimId.getPath())) {
            return;
        }

        var server = self.getServer();
        if (server == null) return;

        var overworld = server.overworld();
        if (overworld == null) return;

        var owData = (ServerLevelData) overworld.getLevelData();

        // 同步昼光时间（睡觉推进主世界时间后裂隙自动跟随）
        this.serverLevelData.setDayTime(owData.getDayTime());

        // 同步游戏时间（可选，不影响昼夜，仅保持数据一致）
        // gameTime 是 Level 的 protected 字段，此处不处理

        // 同步天气
        this.serverLevelData.setRaining(owData.isRaining());
        this.serverLevelData.setThundering(owData.isThundering());
        this.serverLevelData.setRainTime(owData.getRainTime());
        this.serverLevelData.setThunderTime(owData.getThunderTime());
        this.serverLevelData.setClearWeatherTime(owData.getClearWeatherTime());
    }
}
