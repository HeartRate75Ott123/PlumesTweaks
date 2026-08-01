package com.plumestweaks.item;

import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.component.CooldownData;
import com.plumestweaks.component.ModDataComponents;
import com.plumestweaks.dimension.PlumesDimensions;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 时空裂隙 —— 维度传送道具。
 * <p>
 * 右击撕开裂隙进入空岛维度，再次右击返回原维度并进入冷却。
 * 返回位置数据存储在物品自身的 CustomData 中，随玩家跨维度移动。
 */
public class DimensionalRiftItem extends Item {

    private static final int COOLDOWN_TICKS = 2400; // 2 分钟 = 120 秒

    // 物品 CustomData 键
    private static final String ITEM_TAG_RIFT = "rift_data";
    private static final String TAG_DIM = "dim";
    private static final String TAG_POS = "pos";
    private static final String TAG_YAW = "yaw";
    private static final String TAG_PITCH = "pitch";

    /** 每个玩家的出生半径（平原边缘，靠近山脉） */
    private static final double RIFT_SPAWN_RADIUS = 38.0;

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

        // ========== 1. 冷却检测 ==========
        CooldownData cooldown = stack.get(ModDataComponents.RIFT_COOLDOWN.get());
        if (cooldown != null && cooldown.remainingTicks(level.getGameTime()) > 0) {
            long remaining = cooldown.remainingTicks(level.getGameTime());
            int seconds = (int) ((remaining + 19) / 20);
            PlumesTweaks.LOGGER.debug("[DimensionalRift] cooldown active: {}s remaining", seconds);
            serverPlayer.displayClientMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.cooldown", seconds), true);
            return InteractionResultHolder.consume(stack);
        }

        // ========== 2. 从物品 CustomData 读取位置数据 ==========
        CompoundTag data = readRiftData(stack);

        if (data.isEmpty()) {
            // ---- 首次使用（或数据已清空）：绑定 + 传送空岛 ----
            PlumesTweaks.LOGGER.info("[DimensionalRift] no rift data found → initializing for {}",
                    player.getName().getString());

            saveRiftData(stack, serverPlayer);
            teleportToRift(serverPlayer);

            serverPlayer.sendSystemMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.bind",
                            player.getName().getString()), true);
            return InteractionResultHolder.consume(stack);
        }

        // ---- 已有存储数据 ----
        ResourceKey<Level> currentDim = serverPlayer.level().dimension();

        if (currentDim.equals(PlumesDimensions.riftLevelKey())) {
            // === 在空岛中：返回原维度 ===
            PlumesTweaks.LOGGER.info("[DimensionalRift] player in rift → returning to original dimension");
            restoreLocation(serverPlayer, data);
            clearRiftData(stack);

            // 启动冷却
            stack.set(ModDataComponents.RIFT_COOLDOWN.get(),
                    new CooldownData(level.getGameTime() + COOLDOWN_TICKS, COOLDOWN_TICKS));
            PlumesTweaks.LOGGER.info("[DimensionalRift] cooldown started: {} ticks ({}s)",
                    COOLDOWN_TICKS, COOLDOWN_TICKS / 20);

            serverPlayer.sendSystemMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.return"), true);
        } else {
            // === 在其他维度：更新位置再传送到空岛 ===
            PlumesTweaks.LOGGER.info("[DimensionalRift] player in overworld → saving location and entering rift");
            saveRiftData(stack, serverPlayer);
            teleportToRift(serverPlayer);
        }

        return InteractionResultHolder.consume(stack);
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

    /** 传送到空岛维度（每位玩家按 UUID 分配不同角度位置） */
    private static void teleportToRift(ServerPlayer player) {
        ServerLevel riftLevel = player.getServer().getLevel(PlumesDimensions.riftLevelKey());
        if (riftLevel == null) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] Rift dimension not found: {}", PlumesDimensions.riftLevelKey());
            return;
        }

        // 从 UUID 哈希计算角度，每位玩家固定一个方向
        double angle = (player.getUUID().hashCode() & 0x7FFFFFFF) / (double) 0x7FFFFFFF * Math.PI * 2;
        double x = Math.cos(angle) * RIFT_SPAWN_RADIUS;
        double z = Math.sin(angle) * RIFT_SPAWN_RADIUS;
        float yaw = (float) (Math.toDegrees(angle) + 180); // 面朝山脉方向

        PlumesTweaks.LOGGER.info("[DimensionalRift] teleporting {} to rift at angle={} pos=({},129,{})",
                player.getName().getString(), Math.toDegrees(angle), String.format("%.1f", x), String.format("%.1f", z));
        player.teleportTo(riftLevel, x + 0.5, 129.0, z + 0.5, yaw, 0.0f);
    }

    // ========== 冷却视觉条（绿色耐久条，仅客户端渲染） ==========

    @Override
    public boolean isBarVisible(ItemStack stack) {
        CooldownData cool = stack.get(ModDataComponents.RIFT_COOLDOWN.get());
        if (cool == null) return false;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            return mc.level != null && cool.remainingTicks(mc.level.getGameTime()) > 0;
        } catch (NoClassDefFoundError ignored) {
            return true;
        }
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        CooldownData cool = stack.get(ModDataComponents.RIFT_COOLDOWN.get());
        if (cool == null) return 0;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return 13;
            long remaining = cool.remainingTicks(mc.level.getGameTime());
            if (remaining > 0) {
                return Math.round(13.0f * (cool.duration() - remaining) / cool.duration());
            }
        } catch (NoClassDefFoundError ignored) {}
        return 0;
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x44CC44; // 绿色
    }

    // ========== Tooltip ==========

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipComponents, flag);

        // 第一行：功能描述
        tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.desc1")
                .withStyle(ChatFormatting.GREEN));

        // 第二行：冷却中 → 实时倒计时；未冷却 → 冷却说明
        CooldownData cool = stack.get(ModDataComponents.RIFT_COOLDOWN.get());
        if (cool != null) {
            Level level = context.level();
            if (level != null) {
                long remaining = cool.remainingTicks(level.getGameTime());
                if (remaining > 0) {
                    int seconds = (int) ((remaining + 19) / 20);
                    tooltipComponents.add(
                            Component.translatable("item.plumestweaks.dimensional_rift.cooldown", seconds)
                                    .withStyle(ChatFormatting.YELLOW));
                    return;
                }
            }
        }
    }
}
