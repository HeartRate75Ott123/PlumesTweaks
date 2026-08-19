package com.plumestweaks.item;

import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.dimension.PlumesDimensions;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/**
 * 时空裂隙 —— 维度传送道具。
 * <p>
 * 右击撕开裂隙进入空岛维度，再次右击返回原维度。
 * 返回位置数据存储在物品自身的 CustomData 中，随玩家跨维度移动。
 * 限制：周围 32 格内有 Boss 级怪物时无法使用。
 */
public class DimensionalRiftItem extends Item {

    // 物品 CustomData 键
    private static final String ITEM_TAG_RIFT = "rift_data";
    private static final String TAG_DIM = "dim";
    private static final String TAG_POS = "pos";
    private static final String TAG_YAW = "yaw";
    private static final String TAG_PITCH = "pitch";

    /** Boss 检测半径 */
    private static final double BOSS_CHECK_RADIUS = 32.0;

    /** 每个玩家的出生半径（平原边缘，靠近山脉） */
    private static final double RIFT_SPAWN_RADIUS = 38.0;

    /** 网格单元间距：与 {@link com.plumestweaks.worldgen.RiftChunkGenerator} 的 GRID 保持一致 */
    private static final int GRID = 1024;
    /** UUID 哈希分片位数：15 位索引 → 每轴最多 2^15 个网格单元（坐标 ±~1.6 千万，int 安全区） */
    private static final int GRID_INDEX_BITS = 15;

    public DimensionalRiftItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.consume(stack);
        }

        ServerPlayer serverPlayer = (ServerPlayer) player;
        PlumesTweaks.LOGGER.info("[DimensionalRift] use() called by {} in dim {}",
                player.getName().getString(), level.dimension().location());

        // ========== 1. Boss 检测（两个维度通用） ==========
        if (hasBossNearby(level, player)) {
            PlumesTweaks.LOGGER.info("[DimensionalRift] boss nearby, blocking use for {}",
                    player.getName().getString());
            serverPlayer.displayClientMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.boss_nearby"), true);
            return InteractionResultHolder.consume(stack);
        }

        // ========== 2. 判断当前维度决定行为 ==========
        ResourceKey<Level> currentDim = serverPlayer.level().dimension();
        boolean inRiftDim = currentDim.equals(PlumesDimensions.riftLevelKey());

        // ========== 3. 从物品 CustomData 读取位置数据 ==========
        CompoundTag data = readRiftData(stack);

        if (inRiftDim) {
            // === 在空岛维度：返回原维度 ===
            if (data.isEmpty()) {
                // 异常情况：在裂隙维度但物品无数据 → 强制回主世界出生点
                PlumesTweaks.LOGGER.warn("[DimensionalRift] player in rift but no data found → fallback to overworld spawn");
                ServerLevel overworld = serverPlayer.getServer().getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    BlockPos spawn = overworld.getSharedSpawnPos();
                    serverPlayer.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
                }
                clearRiftData(stack);
            } else {
                PlumesTweaks.LOGGER.info("[DimensionalRift] player in rift → returning to original dimension");
                restoreLocation(serverPlayer, data);
                clearRiftData(stack);
            }

            serverPlayer.sendSystemMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.return"), true);
        } else {
            // === 在其他维度：进入空岛 ===
            if (data.isEmpty()) {
                // 首次使用：绑定 + 传送空岛
                PlumesTweaks.LOGGER.info("[DimensionalRift] first use → initializing for {}",
                        player.getName().getString());
                saveRiftData(stack, serverPlayer);
                teleportToRift(serverPlayer);

                serverPlayer.sendSystemMessage(
                        Component.translatable("item.plumestweaks.dimensional_rift.bind",
                                player.getName().getString()), true);
            } else {
                // 已有数据：更新位置再传送到空岛（覆盖旧数据）
                PlumesTweaks.LOGGER.info("[DimensionalRift] player in overworld → saving location and entering rift");
                saveRiftData(stack, serverPlayer);
                teleportToRift(serverPlayer);
            }
        }

        return InteractionResultHolder.consume(stack);
    }

    /**
     * 检查指定半径内是否有 Boss 级怪物（拥有 ServerBossEvent 字段的实体）。
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

    // ========== 物品 CustomData 存储 ==========

    /** 将当前位置保存到物品 CustomData */
    private static void saveRiftData(ItemStack stack, ServerPlayer player) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag data = new CompoundTag();
        data.putString(TAG_DIM, player.level().dimension().location().toString());
        data.putIntArray(TAG_POS, new int[]{
                (int) player.getX(), (int) player.getY(), (int) player.getZ()
        });
        data.putFloat(TAG_YAW, player.getYRot());
        data.putFloat(TAG_PITCH, player.getXRot());
        tag.put(ITEM_TAG_RIFT, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        PlumesTweaks.LOGGER.info("[DimensionalRift] saved rift data: dim={}, pos={},{},{}",
                data.getString(TAG_DIM),
                data.getIntArray(TAG_POS)[0],
                data.getIntArray(TAG_POS)[1],
                data.getIntArray(TAG_POS)[2]);
    }

    /** 从物品 CustomData 读取存储数据 */
    private static CompoundTag readRiftData(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return new CompoundTag();
        return customData.copyTag().getCompound(ITEM_TAG_RIFT);
    }

    /** 清除物品中的位置数据 */
    private static void clearRiftData(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.remove(ITEM_TAG_RIFT);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        PlumesTweaks.LOGGER.debug("[DimensionalRift] cleared rift data from item");
    }

    // ========== 传送 ==========

    /** 从物品数据恢复玩家位置 */
    private static void restoreLocation(ServerPlayer player, CompoundTag data) {
        String dimStr = data.getString(TAG_DIM);
        int[] pos = data.getIntArray(TAG_POS);
        float yaw = data.getFloat(TAG_YAW);
        float pitch = data.getFloat(TAG_PITCH);

        if (dimStr.isEmpty() || pos.length < 3) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] invalid rift data: dim='{}' pos={}", dimStr, pos);
            return;
        }

        ResourceKey<Level> dimKey = ResourceKey.create(
                Registries.DIMENSION, ResourceLocation.parse(dimStr));
        ServerLevel targetLevel = player.getServer().getLevel(dimKey);
        if (targetLevel == null) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] target dimension not found: {}", dimStr);
            return;
        }

        PlumesTweaks.LOGGER.info("[DimensionalRift] teleporting {} to dim={} pos={},{},{}",
                player.getName().getString(), dimStr, pos[0], pos[1], pos[2]);
        player.teleportTo(targetLevel, pos[0] + 0.5, pos[1], pos[2] + 0.5, yaw, pitch);
    }

    /** 由玩家 UUID 确定性计算其专属岛的网格中心世界坐标 */
    private static int islandCenterFromUuid(UUID uuid, boolean xAxis) {
        int hash = uuid.hashCode();
        int idx;
        if (xAxis) {
            idx = (hash & 0x7FFF);            // 低 15 位
        } else {
            idx = (hash >>> 15) & 0x7FFF;     // 高 15 位
        }
        return idx * GRID;
    }

    /** 传送到空岛维度（每位玩家按 UUID 分配不同网格单元中的专属岛） */
    private static void teleportToRift(ServerPlayer player) {
        ServerLevel riftLevel = player.getServer().getLevel(PlumesDimensions.riftLevelKey());
        if (riftLevel == null) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] Rift dimension not found: {}", PlumesDimensions.riftLevelKey());
            return;
        }

        // 每位玩家固定一个专属岛中心（由 UUID 哈希派生网格索引）
        UUID uuid = player.getUUID();
        int centerX = islandCenterFromUuid(uuid, true);
        int centerZ = islandCenterFromUuid(uuid, false);

        // 在岛中心附近取一个固定方向作为落点（平原边缘，靠近山脉）
        double angle = (uuid.hashCode() & 0x7FFFFFFF) / (double) 0x7FFFFFFF * Math.PI * 2;
        double x = centerX + Math.cos(angle) * RIFT_SPAWN_RADIUS;
        double z = centerZ + Math.sin(angle) * RIFT_SPAWN_RADIUS;
        float yaw = (float) (Math.toDegrees(angle) + 180); // 面朝山脉方向

        PlumesTweaks.LOGGER.info("[DimensionalRift] teleporting {} to rift island center=({},{}) pos=({},129,{})",
                player.getName().getString(), centerX, centerZ,
                String.format("%.1f", x), String.format("%.1f", z));
        player.teleportTo(riftLevel, x + 0.5, 129.0, z + 0.5, yaw, 0.0f);
    }

    // ========== Tooltip ==========

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipComponents, flag);

        // 第一行：功能描述
        tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.desc1")
                .withStyle(ChatFormatting.GREEN));

        // 第二行：Boss 限制提示
        tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.boss_limit")
                .withStyle(ChatFormatting.RED));
    }
}