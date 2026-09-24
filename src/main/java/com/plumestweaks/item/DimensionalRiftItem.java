package com.plumestweaks.item;

import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.component.ModDataComponents;
import com.plumestweaks.component.RiftEnterData;
import com.plumestweaks.component.RiftExitData;
import com.plumestweaks.component.RiftExitEntry;
import com.plumestweaks.dimension.PlumesDimensions;
import com.plumestweaks.dimension.RiftIslandAllocator;
import com.plumestweaks.network.RiftOpenGuiPayload;
import com.plumestweaks.util.BossEntityLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 时空裂隙 —— 维度传送道具。
 * <p>
 * 右击撕开裂隙进入空岛维度；在裂隙维度内右击打开「返回维度选择界面」，
 * 选中任意一个曾经进入裂隙的维度即可传送到当时的记录点。
 * <p>
 * 数据分两层，互不干扰：
 * <ul>
 *   <li>{@code plumestweaks:rift_enter} —— 裂隙维度内的**落点**，
 *       在裂隙内潜行 + 右键设置（原行为，未改动）；</li>
 *   <li>{@code plumestweaks:rift_exits} —— 裂隙**外**各维度的返回点，
 *       每次从某维度进入裂隙时记录/覆盖该维度，传送出去不清除。</li>
 * </ul>
 * 限制：周围 32 格内有名单内的 Boss（lensouls 虚影幻灵除外）时无法传送。
 */
public class DimensionalRiftItem extends Item {

    /** 旧版本存放单一返回点的物品 CustomData 键（仅用于迁移，见 {@link #migrateLegacy}） */
    private static final String LEGACY_TAG_RIFT = "rift_data";

    /** Boss 检测半径 */
    private static final double BOSS_CHECK_RADIUS = 32.0;

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
        boolean inRiftDim = serverPlayer.level().dimension().equals(PlumesDimensions.riftLevelKey());

        // ========== 1. 裂隙维度内潜行 + 右键：设置裂隙落点（绕过 Boss 检测） ==========
        if (inRiftDim && player.isShiftKeyDown()) {
            saveDefaultEnter(stack, serverPlayer);
            serverPlayer.sendSystemMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.enter_set",
                            (int) player.getX(), (int) player.getY(), (int) player.getZ()), true);
            return InteractionResultHolder.consume(stack);
        }

        // ========== 2. Boss 检测 ==========
        if (hasBossNearby(level, player)) {
            PlumesTweaks.LOGGER.debug("[DimensionalRift] boss nearby, blocking use for {}",
                    player.getName().getString());
            serverPlayer.displayClientMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.boss_nearby"), true);
            return InteractionResultHolder.consume(stack);
        }

        if (inRiftDim) {
            // ========== 3. 裂隙维度内右键：打开返回维度选择界面 ==========
            openExitScreen(stack, serverPlayer);
        } else {
            // ========== 4. 裂隙外右键：记录/覆盖当前维度的返回点，然后进入裂隙 ==========
            enterRift(stack, serverPlayer);
        }

        return InteractionResultHolder.consume(stack);
    }

    // ========== 进入 / 离开 ==========

    /**
     * 从裂隙外维度进入裂隙：先把当前维度与坐标记录为该维度的返回点
     * （同维度重复进入即覆盖；其它维度的旧记录不会因此被清除），再传送。
     */
    private static void enterRift(ItemStack stack, ServerPlayer player) {
        ServerLevel current = player.serverLevel();
        String dimensionId = current.dimension().location().toString();

        RiftExitEntry entry = new RiftExitEntry(
                dimensionId, "",
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) Math.floor(player.getZ()),
                player.getYRot(), player.getXRot());
        // 显示名留空，由客户端按当前语言解析翻译键 dimension.<ns>.<path>
        // （服务端无法读取语言表，写死名字会导致换语言后不同步）
        stack.set(ModDataComponents.RIFT_EXITS, readExits(stack).with(entry));

        PlumesTweaks.LOGGER.debug("[DimensionalRift] recorded {} exit at {},{},{}",
                dimensionId, entry.x(), entry.y(), entry.z());

        teleportToRift(player, stack);
    }

    /**
     * 裂隙维度内右键：
     * <ul>
     *   <li>有记录 → 把出口点列表发给客户端并打开选择界面；</li>
     *   <li>一条记录都没有（例如旧物品首次使用、数据被清空）→ 直接送回世界重生点，
     *       不弹界面，避免玩家被困在裂隙里无事可做。</li>
     * </ul>
     */
    private static void openExitScreen(ItemStack stack, ServerPlayer player) {
        RiftExitData exits = migrateLegacy(stack);
        if (exits.isEmpty()) {
            fallbackToWorldSpawn(player);
            player.displayClientMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.no_record_spawn"), true);
            PlumesTweaks.LOGGER.debug("[DimensionalRift] no recorded exit for {}, returning to world spawn",
                    player.getName().getString());
            return;
        }
        PacketDistributor.sendToPlayer(player, new RiftOpenGuiPayload(exits));
        PlumesTweaks.LOGGER.debug("[DimensionalRift] opening exit screen for {} ({} entries)",
                player.getName().getString(), exits.size());
    }

    /**
     * 传送到某个已记录维度。坐标一律从物品组件重新读取，不信任客户端参数。
     *
     * @return 是否真的完成了传送
     */
    public static boolean teleportToRecordedDimension(ServerPlayer player, String dimensionId) {
        ItemStack stack = findRiftStack(player);
        if (stack == null || stack.isEmpty()) return false;

        RiftExitEntry entry = readExits(stack).get(dimensionId);
        if (entry == null) {
            PlumesTweaks.LOGGER.warn("[DimensionalRift] no recorded exit for {} (player {})",
                    dimensionId, player.getName().getString());
            return false;
        }

        ResourceLocation targetId = ResourceLocation.tryParse(entry.dimensionId());
        if (targetId == null) return false;

        ServerLevel target = player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, targetId));
        if (target == null) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] target dimension not found: {}", entry.dimensionId());
            player.displayClientMessage(
                    Component.translatable("item.plumestweaks.dimensional_rift.dim_missing", entry.displayName()), true);
            return false;
        }

        // 记录的是方块坐标，落在方块中心避免贴墙
        player.teleportTo(target, entry.x() + 0.5, entry.y(), entry.z() + 0.5, entry.yaw(), entry.pitch());
        PlumesTweaks.LOGGER.debug("[DimensionalRift] teleported {} back to {} at {},{},{}",
                player.getName().getString(), entry.dimensionId(), entry.x(), entry.y(), entry.z());
        return true;
    }

    /** 找到玩家身上（背包 / 副手 / 主手）任意一把时空裂隙 */
    private static ItemStack findRiftStack(ServerPlayer player) {
        for (ItemStack s : player.getInventory().items) {
            if (s.getItem() instanceof DimensionalRiftItem) return s;
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (s.getItem() instanceof DimensionalRiftItem) return s;
        }
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof DimensionalRiftItem) return main;
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof DimensionalRiftItem ? off : null;
    }

    // ========== 出口点数据 ==========

    /** 读取出口点数据，缺失时返回空集 */
    public static RiftExitData readExits(ItemStack stack) {
        RiftExitData data = stack.get(ModDataComponents.RIFT_EXITS);
        return data == null ? RiftExitData.EMPTY : data;
    }

    /** 写入出口点数据 */
    public static void writeExits(ItemStack stack, RiftExitData data) {
        stack.set(ModDataComponents.RIFT_EXITS, data);
    }

    /**
     * 旧版本把单一返回点存在物品 CustomData 的 {@code rift_data} 里；
     * 首次在新版本打开界面时把它迁移成出口点，迁移后移除旧标签。
     */
    private static RiftExitData migrateLegacy(ItemStack stack) {
        RiftExitData current = readExits(stack);
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return current;

        CompoundTag tag = customData.copyTag();
        if (!tag.contains(LEGACY_TAG_RIFT)) return current;

        CompoundTag legacy = tag.getCompound(LEGACY_TAG_RIFT);
        String dim = legacy.getString("dim");
        int[] pos = legacy.getIntArray("pos");
        tag.remove(LEGACY_TAG_RIFT);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        if (dim.isEmpty() || pos.length < 3) return current;

        RiftExitEntry entry = new RiftExitEntry(dim, "", pos[0], pos[1], pos[2],
                legacy.getFloat("yaw"), legacy.getFloat("pitch"));
        RiftExitData merged = current.with(entry);
        writeExits(stack, merged);
        PlumesTweaks.LOGGER.info("[DimensionalRift] migrated legacy rift_data to exits: {}", dim);
        return merged;
    }

    /**
     * 检查指定半径内是否有 Boss 级怪物。
     * <p>
     * 判定来源为数据包清单 {@code data/plumestweaks/boss_entities/*.json}
     * （见 {@link BossEntityLoader}），并放行 lensouls 的虚影幻灵
     * （借体 Boss 本体与其召唤物，见 {@link com.plumestweaks.util.PhantomExemption}）。
     */
    private boolean hasBossNearby(Level level, Player player) {
        return BossEntityLoader.hasBossNearby(level, player, BOSS_CHECK_RADIUS);
    }

    // ========== 传送进裂隙 ==========

    /** 将玩家当前位置保存为默认进入裂隙坐标（存于物品 DataComponent，不受死亡影响） */
    private static void saveDefaultEnter(ItemStack stack, ServerPlayer player) {
        stack.set(ModDataComponents.RIFT_ENTER, new RiftEnterData(
                player.level().dimension(),
                (int) player.getX(), (int) player.getY(), (int) player.getZ()));
        PlumesTweaks.LOGGER.info("[DimensionalRift] {} set default rift entry at {}({},{},{})",
                player.getName().getString(), player.level().dimension().location(),
                (int) player.getX(), (int) player.getY(), (int) player.getZ());
    }

    /** 传送到空岛维度（优先物品上的默认进入坐标，否则分配专属岛） */
    private static void teleportToRift(ServerPlayer player, ItemStack stack) {
        ServerLevel riftLevel = player.getServer().getLevel(PlumesDimensions.riftLevelKey());
        if (riftLevel == null) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] Rift dimension not found: {}", PlumesDimensions.riftLevelKey());
            return;
        }

        // 优先：物品上存储的默认进入坐标（裂隙维度内潜行+右键设置）
        RiftEnterData enter = stack.get(ModDataComponents.RIFT_ENTER);
        if (enter != null && enter.y() > 0) {
            ServerLevel targetLevel = player.getServer().getLevel(enter.dimension());
            if (targetLevel != null) {
                PlumesTweaks.LOGGER.debug("[DimensionalRift] teleporting {} to default entry {}({},{},{})",
                        player.getName().getString(), enter.dimension().location(),
                        enter.x(), enter.y(), enter.z());
                player.teleportTo(targetLevel, enter.x() + 0.5, enter.y(), enter.z() + 0.5,
                        player.getYRot(), 0.0f);
                return;
            }
        }

        // 否则：分配/获取玩家专属岛中心（从世界中心向外螺旋，严格一格一玩家，持久化）
        long island = RiftIslandAllocator.allocate(player);
        int centerX = RiftIslandAllocator.centerX(island);
        int centerZ = RiftIslandAllocator.centerZ(island);

        // 在岛中心附近取一个固定方向作为落点（平原边缘，靠近山脉）
        double angle = (player.getUUID().hashCode() & 0x7FFFFFFF) / (double) 0x7FFFFFFF * Math.PI * 2;
        double x = centerX + Math.cos(angle) * RIFT_SPAWN_RADIUS;
        double z = centerZ + Math.sin(angle) * RIFT_SPAWN_RADIUS;
        float yaw = (float) (Math.toDegrees(angle) + 180); // 面朝山脉方向

        PlumesTweaks.LOGGER.debug("[DimensionalRift] teleporting {} to rift island center=({},{}) pos=({},129,{})",
                player.getName().getString(), centerX, centerZ,
                String.format("%.1f", x), String.format("%.1f", z));
        player.teleportTo(riftLevel, x + 0.5, 129.0, z + 0.5, yaw, 0.0f);
    }

    /** 返回数据缺失/无效时兜底：强制传送到主世界共享出生点，防止玩家被困在裂隙维度 */
    public static void fallbackToWorldSpawn(ServerPlayer player) {
        ServerLevel overworld = player.getServer().getLevel(Level.OVERWORLD);
        if (overworld == null) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] overworld level not found, cannot fallback");
            return;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
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

        // 第三行：设置默认进入坐标提示（实时显示绑定的潜行键名）
        tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.set_enter",
                        getShiftKeyName())
                .withStyle(ChatFormatting.GREEN));

        // 第四行：已记录的返回维度数量
        RiftExitData exits = readExits(stack);
        if (!exits.isEmpty()) {
            tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.exit_count",
                            exits.size())
                    .withStyle(ChatFormatting.AQUA));
        }

        // 第五行：实时显示物品上存储的默认进入点（维度 + 坐标）；未设置则不显示
        RiftEnterData enter = stack.get(ModDataComponents.RIFT_ENTER);
        if (enter != null) {
            tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.enter_point",
                            enter.dimension().location().toString(),
                            enter.x(), enter.y(), enter.z())
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /** 获取玩家实际绑定的潜行键显示名（仅客户端），服务端兜底用 "Shift" */
    private static Component getShiftKeyName() {
        if (FMLLoader.getDist() == Dist.CLIENT) {
            return Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage();
        }
        return Component.literal("Shift");
    }
}
