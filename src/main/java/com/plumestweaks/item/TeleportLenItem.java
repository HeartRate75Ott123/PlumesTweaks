package com.plumestweaks.item;

import com.plumestweaks.component.CooldownData;
import com.plumestweaks.component.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 原初之匣 —— 临时观察者模式道具。
 * <p>
 * 右键激活后立即切换为观察者模式，持续 10 秒后自动恢复原游戏模式。
 * 结束前 3、2、1 秒分别发送醒目提示。
 * 恢复后获得 5 秒摔落免疫，防止身处高空时摔死。
 * 冷却时间 20 秒，冷却样式与时空裂隙相同（耐久条 + tooltip）。
 * <p>
 * 核心逻辑（计时、摔落免疫）通过 PlumesTweaks 的事件处理器管理，
 * 跨维度、重进存档均安全。
 */
public class TeleportLenItem extends Item {

    /** 观察者模式持续时间（tick） */
    static final int SPECTATOR_DURATION = 200; // 10 秒
    /** 冷却时间（tick） */
    static final int COOLDOWN_TICKS = 400; // 20 秒

    public TeleportLenItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.consume(stack);
        }

        ServerPlayer serverPlayer = (ServerPlayer) player;

        // 已经是观察者则不做任何事
        if (serverPlayer.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            return InteractionResultHolder.fail(stack);
        }

        // 冷却检测
        CooldownData cooldown = stack.get(ModDataComponents.TELEPORT_COOLDOWN.get());
        if (cooldown != null && cooldown.remainingTicks(level.getGameTime()) > 0) {
            long remaining = cooldown.remainingTicks(level.getGameTime());
            int seconds = (int) ((remaining + 19) / 20);
            serverPlayer.displayClientMessage(
                    Component.translatable("item.plumestweaks.teleport_len.cooldown", seconds), true);
            return InteractionResultHolder.consume(stack);
        }

        // 保存原游戏模式
        GameType originalMode = serverPlayer.gameMode.getGameModeForPlayer();

        // 存储到 persistentData（防服务器崩溃/异常断开导致玩家卡在观察者模式）
        serverPlayer.getPersistentData().putInt("plumestweaks:teleport_mode", originalMode.getId());

        // 启动计时器（通过 PlumesTweaks 的事件管理器）
        com.plumestweaks.PlumesTweaks.startTeleport(serverPlayer, originalMode,
                level.getGameTime() + SPECTATOR_DURATION);

        // 切换观察者模式
        serverPlayer.setGameMode(GameType.SPECTATOR);

        // 启动冷却（记录在物品 DataComponent 上，独立于玩家）
        stack.set(ModDataComponents.TELEPORT_COOLDOWN.get(),
                new CooldownData(level.getGameTime() + COOLDOWN_TICKS, COOLDOWN_TICKS));

        serverPlayer.displayClientMessage(
                Component.translatable("item.plumestweaks.teleport_len.activated"), false);

        return InteractionResultHolder.consume(stack);
    }

    // ========== 冷却视觉条（绿色耐久条，仅客户端渲染） ==========

    @Override
    public boolean isBarVisible(ItemStack stack) {
        CooldownData cool = stack.get(ModDataComponents.TELEPORT_COOLDOWN.get());
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
        CooldownData cool = stack.get(ModDataComponents.TELEPORT_COOLDOWN.get());
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
        return 0x44CC44; // 绿色，与时空裂隙一致
    }

    // ========== Tooltip ==========

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltipComponents, flag);

        // 功能描述
        tooltipComponents.add(Component.translatable("item.plumestweaks.teleport_len.desc1")
                .withStyle(ChatFormatting.GREEN));

        // 冷却中 → 实时倒计时
        CooldownData cool = stack.get(ModDataComponents.TELEPORT_COOLDOWN.get());
        if (cool != null) {
            Level level = context.level();
            if (level != null) {
                long remaining = cool.remainingTicks(level.getGameTime());
                if (remaining > 0) {
                    int seconds = (int) ((remaining + 19) / 20);
                    tooltipComponents.add(
                            Component.translatable("item.plumestweaks.teleport_len.cooldown", seconds)
                                    .withStyle(ChatFormatting.YELLOW));
                }
            }
        }
    }
}
