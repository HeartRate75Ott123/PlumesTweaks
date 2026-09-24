package com.plumestweaks.item;

import com.plumestweaks.Config;
import com.plumestweaks.PlumesTweaks;
import com.plumestweaks.component.RiftExitData;
import com.plumestweaks.component.RiftExitEntry;
import com.plumestweaks.dimension.PlumesDimensions;
import com.plumestweaks.network.RiftNetwork;
import com.plumestweaks.util.BossEntityLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 时空裂隙 —— 维度传送道具（1.20.1 / Forge 版）。
 * <p>
 * 右击撕开裂隙进入空岛维度；在裂隙维度内右击打开「返回维度选择界面」，
 * 选中任一曾进入过裂隙的维度即可传送到当时的记录点。
 * <p>
 * 物品 NBT 里有两层数据，互不干扰：
 * <ul>
 *   <li>{@code rift_enter} —— 裂隙维度内的**落点**，在裂隙内潜行 + 右键设置（旧行为）；</li>
 *   <li>{@code plumestweaks:rift_exits} —— 裂隙**外**各维度的**返回点**，
 *       每次从某维度进入裂隙时记录/覆盖，传送出去不清除。</li>
 * </ul>
 * 限制：周围 32 格内有名单内的 Boss 时无法传送。
 */
public class DimensionalRiftItem extends Item {

    /** 裂隙内落点（旧行为，保持不变） */
    private static final String TAG_ENTER_DIM = "enter_dim";
    private static final String TAG_ENTER_POS = "enter_pos";

    /** 旧版本单一返回点：{@code rift_data} 下的 dim/pos/yaw/pitch，首次用时迁移 */
    private static final String LEGACY_TAG_RIFT = "rift_data";
    private static final String LEGACY_TAG_DIM = "dim";
    private static final String LEGACY_TAG_POS = "pos";
    private static final String LEGACY_TAG_YAW = "yaw";
    private static final String LEGACY_TAG_PITCH = "pitch";

    /** Boss 检测半径 */
    private static final double BOSS_CHECK_RADIUS = 32.0;

    /** 每个玩家的出生半径（平原边缘，靠近山脉） */
    private static final double RIFT_SPAWN_RADIUS = 38.0;

    public DimensionalRiftItem() {
        super(new Item.Properties().stacksTo(1));
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
            serverPlayer.displayClientMessage(
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
     * （同维度重复进入即覆盖；其它维度的旧记录不受影响），再传送。
     */
    private static void enterRift(ItemStack stack, ServerPlayer player) {
        ServerLevel current = player.serverLevel();
        String dimensionId = current.dimension().location().toString();

        RiftExitEntry entry = new RiftExitEntry(dimensionId,
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) Math.floor(player.getZ()),
                player.getYRot(), player.getXRot());
        writeExits(stack, readExits(stack).with(entry));

        PlumesTweaks.LOGGER.debug("[DimensionalRift] recorded {} exit at {},{},{}",
                dimensionId, entry.x(), entry.y(), entry.z());

        teleportToRift(player, stack);
    }

    /**
     * 裂隙维度内右键：
     * <ul>
     *   <li>有记录 → 把出口点列表发给客户端并打开选择界面；</li>
     *   <li>一条记录都没有 → 直接送回世界重生点，不弹界面。</li>
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
        RiftNetwork.sendOpenGui(player, exits);
        PlumesTweaks.LOGGER.debug("[DimensionalRift] opening exit screen for {} ({} entries)",
                player.getName().getString(), exits.size());
    }

    /**
     * 传送到某个已记录维度。坐标一律从物品 NBT 重新读取，不信任客户端参数。
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
                    Component.translatable("item.plumestweaks.dimensional_rift.dim_missing", entry.dimensionId()),
                    true);
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

    // ========== 出口点数据（物品 NBT） ==========

    /** 读取出口点数据，缺失时返回空集 */
    public static RiftExitData readExits(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? RiftExitData.EMPTY : RiftExitData.load(tag);
    }

    /** 写入出口点数据；集合为空时直接删掉这个键 */
    public static void writeExits(ItemStack stack, RiftExitData data) {
        CompoundTag tag = stack.getOrCreateTag();
        if (data.isEmpty()) {
            tag.remove(RiftExitData.NBT_KEY);
        } else {
            data.save(tag);
        }
    }

    /**
     * 旧版本把单一返回点存在物品 NBT 的 {@code rift_data} 里；
     * 首次在新版本打开界面时把它迁移成出口点，迁移后移除旧标签。
     */
    private static RiftExitData migrateLegacy(ItemStack stack) {
        RiftExitData current = readExits(stack);
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(LEGACY_TAG_RIFT)) return current;

        CompoundTag legacy = tag.getCompound(LEGACY_TAG_RIFT);
        String dim = legacy.getString(LEGACY_TAG_DIM);
        int[] pos = legacy.getIntArray(LEGACY_TAG_POS);
        tag.remove(LEGACY_TAG_RIFT);

        if (dim.isEmpty() || pos.length < 3) return current;

        RiftExitEntry entry = new RiftExitEntry(dim, pos[0], pos[1], pos[2],
                legacy.getFloat(LEGACY_TAG_YAW), legacy.getFloat(LEGACY_TAG_PITCH));
        RiftExitData merged = current.with(entry);
        writeExits(stack, merged);
        PlumesTweaks.LOGGER.info("[DimensionalRift] migrated legacy rift_data to exits: {}", dim);
        return merged;
    }

    /**
     * 检查指定半径内是否有 Boss 级怪物。
     * <p>
     * 判定来源为数据包清单 {@code data/plumestweaks/boss_entities/*.json}
     * （见 {@link BossEntityLoader}）。
     */
    private boolean hasBossNearby(Level level, Player player) {
        return BossEntityLoader.hasBossNearby(level, player, BOSS_CHECK_RADIUS);
    }

    // ========== 传送进裂隙 ==========

    /** 将玩家当前位置保存为裂隙内落点（存于物品 NBT，不受死亡影响） */
    private static void saveDefaultEnter(ItemStack stack, ServerPlayer player) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_ENTER_DIM, player.level().dimension().location().toString());
        tag.putIntArray(TAG_ENTER_POS, new int[]{
                (int) player.getX(), (int) player.getY(), (int) player.getZ()
        });
        PlumesTweaks.LOGGER.info("[DimensionalRift] {} set default rift entry at {}({},{},{})",
                player.getName().getString(), player.level().dimension().location(),
                (int) player.getX(), (int) player.getY(), (int) player.getZ());
    }

    /** 传送到空岛维度（优先物品上存的裂隙内落点，否则按 UUID 分配固定落点） */
    private static void teleportToRift(ServerPlayer player, ItemStack stack) {
        ServerLevel riftLevel = player.getServer().getLevel(PlumesDimensions.riftLevelKey());
        if (riftLevel == null) {
            PlumesTweaks.LOGGER.error("[DimensionalRift] Rift dimension not found: {}", PlumesDimensions.riftLevelKey());
            return;
        }

        // 优先：物品上存的裂隙内落点（裂隙维度内潜行 + 右键设置）
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_ENTER_POS)) {
            String dimStr = tag.getString(TAG_ENTER_DIM);
            int[] pos = tag.getIntArray(TAG_ENTER_POS);
            ResourceLocation dimId = ResourceLocation.tryParse(dimStr);
            if (dimId != null && pos.length >= 3) {
                ServerLevel targetLevel = player.getServer().getLevel(
                        ResourceKey.create(Registries.DIMENSION, dimId));
                if (targetLevel != null) {
                    PlumesTweaks.LOGGER.debug("[DimensionalRift] teleporting {} to default entry {}({},{},{})",
                            player.getName().getString(), dimStr, pos[0], pos[1], pos[2]);
                    player.teleportTo(targetLevel, pos[0] + 0.5, pos[1], pos[2] + 0.5,
                            player.getYRot(), 0.0f);
                    return;
                }
            }
        }

        // 否则：按 UUID 哈希取固定方向（1.20.1 版为空岛单中心 + 环形落点）
        double angle = (player.getUUID().hashCode() & 0x7FFFFFFF) / (double) 0x7FFFFFFF * Math.PI * 2;
        double x = Math.cos(angle) * RIFT_SPAWN_RADIUS;
        double z = Math.sin(angle) * RIFT_SPAWN_RADIUS;
        float yaw = (float) (Math.toDegrees(angle) + 180); // 面朝山脉方向

        PlumesTweaks.LOGGER.debug("[DimensionalRift] teleporting {} to rift at angle={} pos=({},129,{})",
                player.getName().getString(), Math.toDegrees(angle),
                String.format("%.1f", x), String.format("%.1f", z));
        player.teleportTo(riftLevel, x + 0.5, 129.0, z + 0.5, yaw, 0.0f);
    }

    /** 返回数据缺失时兜底：强制传送到主世界共享出生点，防止玩家被困在裂隙维度 */
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
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltipComponents, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltipComponents, flag);

        // 第一行：功能描述
        tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.desc1")
                .withStyle(ChatFormatting.GREEN));

        // 第二行：Boss 限制
        tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.boss_limit")
                .withStyle(ChatFormatting.RED));

        // 第三行：设置裂隙内落点的操作提示（实时显示绑定的潜行键名）
        tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.set_enter",
                        getShiftKeyName())
                .withStyle(ChatFormatting.GREEN));

        // 第四行：已记录的返回维度数量（没记录就不显示）
        RiftExitData exits = readExits(stack);
        if (!exits.isEmpty()) {
            tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.exit_count",
                            exits.size())
                    .withStyle(ChatFormatting.AQUA));
        }

        // 第五行：物品上存着的裂隙内落点（维度 + 坐标）；未设置则不显示
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_ENTER_POS)) {
            int[] pos = tag.getIntArray(TAG_ENTER_POS);
            String dimStr = tag.getString(TAG_ENTER_DIM);
            if (pos.length >= 3 && !dimStr.isEmpty()) {
                tooltipComponents.add(Component.translatable("item.plumestweaks.dimensional_rift.enter_point",
                                dimStr, pos[0], pos[1], pos[2])
                        .withStyle(ChatFormatting.AQUA));
            }
        }
    }

    /**
     * 取潜行键的显示名（仅客户端）；服务端/专用服务端兜底为 "Shift"。
     * <p>
     * 1.20.1 的按键名从 {@code Options.keyShift.getTranslatedKeyMessage()} 取，
     * 与 1.21 的 {@code FMLLoader.getDist()} 判断等价的是 {@code FMLEnvironment.dist}。
     */
    private static Component getShiftKeyName() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                return Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage();
            } catch (Throwable ignored) {
                // 客户端未就绪时退回字面量
            }
        }
        return Component.literal("Shift");
    }
}
