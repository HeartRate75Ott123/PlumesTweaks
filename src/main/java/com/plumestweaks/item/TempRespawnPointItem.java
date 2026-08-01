package com.plumestweaks.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.List;

public class TempRespawnPointItem extends Item {

    private static final double BOSS_CHECK_RADIUS = 64.0;
    private static final String TAG_TEMP_SPAWN = "plumestweaks:temp_spawn";
    private static final String TAG_TEMP_SPAWN_DIM = "plumestweaks:temp_spawn_dim";
    private static final String TAG_BED_BACKUP = "plumestweaks:bed_backup";
    private static final String TAG_BED_BACKUP_POS = "bed_pos";
    private static final String TAG_BED_BACKUP_DIM = "bed_dim";
    private static final String TAG_BED_BACKUP_FORCED = "bed_forced";

    public TempRespawnPointItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        ServerPlayer serverPlayer = (ServerPlayer) player;

        // ===== 蹲下 + 右键：取消临时重生点 =====
        if (player.isShiftKeyDown()) {
            clearTempSpawn(serverPlayer);
            serverPlayer.sendSystemMessage(
                    Component.translatable("item.plumestweaks.temp_respawn_point.cancelled"), true);
            return InteractionResultHolder.success(stack);
        }

        // ===== 右键：设置临时重生点 =====
        // 检查周围是否有 Boss 级怪物
        if (hasBossNearby(level, player)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("item.plumestweaks.temp_respawn_point.boss_nearby"), true);
            return InteractionResultHolder.fail(stack);
        }

        // 存储临时重生点
        setTempSpawn(serverPlayer, player.blockPosition(), level.dimension().location().toString());
        serverPlayer.sendSystemMessage(
                Component.translatable("item.plumestweaks.temp_respawn_point.set",
                        player.blockPosition().getX(),
                        player.blockPosition().getY(),
                        player.blockPosition().getZ()), true);
        // 不消耗物品
        return InteractionResultHolder.success(stack);
    }

    /**
     * 检查 64 格范围内是否有 Boss 级怪物（顶部血条）。
     */
    private boolean hasBossNearby(Level level, Player player) {
        AABB box = player.getBoundingBox().inflate(BOSS_CHECK_RADIUS);
        List<? extends Entity> entities = level.getEntitiesOfClass(Entity.class, box,
                e -> e != player && e.isAlive() && hasBossBarField(e.getClass()));
        return !entities.isEmpty();
    }

    private static boolean hasBossBarField(Class<?> clazz) {
        while (clazz != null && clazz != Entity.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (ServerBossEvent.class.isAssignableFrom(field.getType())) {
                    return true;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private void setTempSpawn(ServerPlayer player, BlockPos pos, String dimension) {
        var tag = player.getPersistentData();
        tag.putIntArray(TAG_TEMP_SPAWN, new int[]{pos.getX(), pos.getY(), pos.getZ()});
        tag.putString(TAG_TEMP_SPAWN_DIM, dimension);
    }

    /**
     * 清除临时重生点，从备份恢复原版重生点。
     */
    private void clearTempSpawn(ServerPlayer player) {
        var tag = player.getPersistentData();
        tag.remove(TAG_TEMP_SPAWN);
        tag.remove(TAG_TEMP_SPAWN_DIM);

        // 从备份恢复原版床/重生锚位置
        if (tag.contains(TAG_BED_BACKUP)) {
            var backup = tag.getCompound(TAG_BED_BACKUP);
            int[] bedPos = backup.getIntArray(TAG_BED_BACKUP_POS);
            BlockPos pos = bedPos.length >= 3 ? new BlockPos(bedPos[0], bedPos[1], bedPos[2]) : null;
            String dim = backup.getString(TAG_BED_BACKUP_DIM);
            boolean forced = backup.getBoolean(TAG_BED_BACKUP_FORCED);
            if (pos != null && !dim.isEmpty()) {
                player.setRespawnPosition(
                        ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim)),
                        pos, 0.0f, forced, false);
            } else {
                player.setRespawnPosition(
                        net.minecraft.world.level.Level.OVERWORLD, null, 0.0f, false, false);
            }
            tag.remove(TAG_BED_BACKUP);
        }
    }
}
