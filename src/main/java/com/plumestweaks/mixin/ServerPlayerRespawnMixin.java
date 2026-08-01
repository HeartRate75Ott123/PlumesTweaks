package com.plumestweaks.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 临时重生点 + 死前视角恢复 Mixin。
 *
 * 注入点一 {@link ServerPlayer#die}：将死前 yaw/pitch 存入 persistentData。
 * 注入点二 {@link ServerPlayer#findRespawnPositionAndUseSpawnBlock} HEAD + cancellable：
 * 若有临时重生点，直接返回以临时坐标 + 死前视角构造的 {@link DimensionTransition}。
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerRespawnMixin {

    @Unique
    private static final String TAG_TEMP_SPAWN = "plumestweaks:temp_spawn";
    @Unique
    private static final String TAG_TEMP_SPAWN_DIM = "plumestweaks:temp_spawn_dim";
    @Unique
    private static final String TAG_BED_BACKUP = "plumestweaks:bed_backup";
    @Unique
    private static final String TAG_BED_BACKUP_POS = "bed_pos";
    @Unique
    private static final String TAG_BED_BACKUP_DIM = "bed_dim";
    @Unique
    private static final String TAG_BED_BACKUP_FORCED = "bed_forced";
    @Unique
    private static final String TAG_DEATH_YAW = "plumestweaks:death_yaw";
    @Unique
    private static final String TAG_DEATH_PITCH = "plumestweaks:death_pitch";

    // ===== 注入点一：死前保存视角 =====

    /**
     * 每次死亡时将死前 yaw/pitch 存入 persistentData。
     * 无条件保存——无论是否启用临时重生点都记录，简化逻辑。
     */
    @Inject(method = "die", at = @At("HEAD"))
    private void plumes$saveDeathRotation(DamageSource source, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        CompoundTag tag = self.getPersistentData();
        tag.putFloat(TAG_DEATH_YAW, self.getYRot());
        tag.putFloat(TAG_DEATH_PITCH, self.getXRot());
    }

    // ===== 注入点二：临时重生点 + 视角恢复 =====

    /**
     * 在 {@link ServerPlayer#findRespawnPositionAndUseSpawnBlock} 的 HEAD 处取消，
     * 直接返回以临时坐标 + 死前视角构造的 DimensionTransition。
     *
     * 跳过所有原版床/重生锚/方块检测逻辑，强制使用临时重生点。
     * 重生视角恢复为 {@link #plumes$saveDeathRotation} 保存的值，
     * 防止玩家晕头转向。
     */
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private void plumes$overrideRespawnPositionAndRotation(boolean alive, DimensionTransition.PostDimensionTransition postTransition, CallbackInfoReturnable<DimensionTransition> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        CompoundTag tag = self.getPersistentData();
        if (!tag.contains(TAG_TEMP_SPAWN)) return;

        int[] pos = tag.getIntArray(TAG_TEMP_SPAWN);
        String dimStr = tag.getString(TAG_TEMP_SPAWN_DIM);
        if (pos.length < 3 || dimStr.isEmpty()) return;

        // 首次重生时备份原版床位置
        if (!tag.contains(TAG_BED_BACKUP)) {
            CompoundTag backup = new CompoundTag();
            BlockPos bedPos = self.getRespawnPosition();
            if (bedPos != null) {
                backup.putIntArray(TAG_BED_BACKUP_POS, new int[]{bedPos.getX(), bedPos.getY(), bedPos.getZ()});
            }
            backup.putString(TAG_BED_BACKUP_DIM, self.getRespawnDimension().location().toString());
            backup.putBoolean(TAG_BED_BACKUP_FORCED, self.isRespawnForced());
            tag.put(TAG_BED_BACKUP, backup);
        }

        // 直接构造 DimensionTransition，跳过所有原版方块检测
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimStr));
        ServerLevel level = self.server.getLevel(dim);
        if (level == null) return;

        BlockPos spawnPos = new BlockPos(pos[0], pos[1], pos[2]);
        Vec3 respawnPos = new Vec3(spawnPos.getX() + 0.5, spawnPos.getY() + 0.1, spawnPos.getZ() + 0.5);

        // 恢复死前视角（CompoundTag.getFloat 在缺键时返回 0.0f，即为默认朝南平视）
        float yaw = tag.getFloat(TAG_DEATH_YAW);
        float pitch = tag.getFloat(TAG_DEATH_PITCH);

        cir.setReturnValue(new DimensionTransition(level, respawnPos, Vec3.ZERO, yaw, pitch, postTransition));
    }
}
