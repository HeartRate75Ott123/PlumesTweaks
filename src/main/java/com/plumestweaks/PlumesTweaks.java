package com.plumestweaks;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import com.plumestweaks.component.RiftExitData;
import com.plumestweaks.component.RiftExitEntry;
import com.plumestweaks.dimension.PlumesDimensions;
import com.plumestweaks.item.DimensionalRiftItem;
import com.plumestweaks.network.RiftNetwork;
import com.plumestweaks.util.BossEntityLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.SleepFinishedTimeEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(PlumesTweaks.MODID)
public class PlumesTweaks {
    public static final String MODID = "plumestweaks";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 物品注册表 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    /** 时空裂隙道具 */
    public static final RegistryObject<Item> DIMENSIONAL_RIFT =
            ITEMS.register("dimensional_rift", DimensionalRiftItem::new);

    /** 死亡时被暂存的时空裂隙数量（key: 玩家 UUID，死亡重生时归还） */
    private final Map<UUID, Integer> pendingRiftReturns = new ConcurrentHashMap<>();

    public PlumesTweaks(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 注册物品与区块生成器编解码器
        ITEMS.register(modEventBus);
        PlumesDimensions.CHUNK_GENERATORS.register(modEventBus);

        // 加入创造物品栏标签页
        modEventBus.addListener(this::onCreativeTabBuild);

        // 配置（用 FMLJavaModLoadingContext#registerConfig，ModLoadingContext.get() 在本版本已标记移除）
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 网络通道放在 common setup 阶段注册（Forge 推荐；构造函数里注册会有时序风险）
        modEventBus.addListener(this::onCommonSetup);

        // 注册服务器/游戏事件
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** 注册网络消息（必须在 mod 构造之后、服务器启动之前） */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(RiftNetwork::register);
    }

    /**
     * 将时空裂隙道具加入原版"工具与实用物品"创造标签页。
     */
    private void onCreativeTabBuild(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.TOOLS_AND_UTILITIES.equals(event.getTabKey())) {
            event.accept(DIMENSIONAL_RIFT.get());
        }
    }

    /** 注册首领实体名单（数据包）重载监听：data/plumestweaks/boss_entities/*.json */
    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BossEntityLoader());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        // /riftdebug add [n] | remove | clear | list | gui — 裂隙传送界面的测试指令
        dispatcher.register(Commands.literal("riftdebug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .executes(ctx -> riftDebugAdd(ctx.getSource(), 12))
                        .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> riftDebugAdd(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("remove").executes(ctx -> riftDebugRemove(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> riftDebugClear(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> riftDebugList(ctx.getSource())))
                .then(Commands.literal("gui").executes(ctx -> riftDebugGui(ctx.getSource()))));
    }

    // ========== /riftdebug ==========

    /** 调试条目前缀（客户端据此自动打开调试叠加） */
    private static final String DEBUG_DIM_PREFIX = MODID + ":debug_";

    private static final String[] DEBUG_DIM_NAMES = {
            "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
            "twilightforest:twilight_forest"
    };

    private static ItemStack findRiftStack(ServerPlayer player) {
        for (ItemStack s : player.getInventory().items) {
            if (s.getItem() instanceof DimensionalRiftItem) return s;
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (s.getItem() instanceof DimensionalRiftItem) return s;
        }
        return null;
    }

    private int riftDebugAdd(CommandSourceStack source, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = findRiftStack(player);
        if (stack == null) {
            player.displayClientMessage(Component.translatable("commands.plumestweaks.riftdebug.no_item"), false);
            return 0;
        }

        RiftExitData data = DimensionalRiftItem.readExits(stack);
        for (int i = 1; i <= count; i++) {
            String dimId;
            if (i <= DEBUG_DIM_NAMES.length) {
                String real = DEBUG_DIM_NAMES[i - 1];
                dimId = DEBUG_DIM_PREFIX + real.substring(real.indexOf(':') + 1);
            } else {
                dimId = DEBUG_DIM_PREFIX + String.format("%02d", i);
            }
            data = data.with(new RiftExitEntry(dimId,
                    (i * 137) % 2000 - 1000,
                    64 + (i % 8) * 4,
                    (i * 311) % 2000 - 1000,
                    (i * 37f) % 360f, 0f));
        }
        DimensionalRiftItem.writeExits(stack, data);

        player.displayClientMessage(Component.translatable(
                "commands.plumestweaks.riftdebug.added", count, data.size()), false);
        RiftNetwork.sendOpenGui(player, data);
        return count;
    }

    private int riftDebugRemove(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = findRiftStack(player);
        if (stack == null) {
            player.displayClientMessage(Component.translatable("commands.plumestweaks.riftdebug.no_item"), false);
            return 0;
        }

        RiftExitData data = DimensionalRiftItem.readExits(stack);
        int removed = 0;
        for (String dimId : List.copyOf(data.dimensionIds())) {
            if (dimId.startsWith(DEBUG_DIM_PREFIX)) {
                data = data.without(dimId);
                removed++;
            }
        }
        DimensionalRiftItem.writeExits(stack, data);
        player.displayClientMessage(Component.translatable(
                "commands.plumestweaks.riftdebug.removed", removed, data.size()), false);
        RiftNetwork.sendOpenGui(player, data);
        return removed;
    }

    private int riftDebugClear(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = findRiftStack(player);
        if (stack == null) {
            player.displayClientMessage(Component.translatable("commands.plumestweaks.riftdebug.no_item"), false);
            return 0;
        }
        DimensionalRiftItem.writeExits(stack, RiftExitData.EMPTY);
        player.displayClientMessage(Component.translatable("commands.plumestweaks.riftdebug.cleared"), false);
        return 1;
    }

    private int riftDebugList(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = findRiftStack(player);
        if (stack == null) {
            player.displayClientMessage(Component.translatable("commands.plumestweaks.riftdebug.no_item"), false);
            return 0;
        }
        RiftExitData data = DimensionalRiftItem.readExits(stack);
        player.displayClientMessage(Component.translatable(
                "commands.plumestweaks.riftdebug.list_header", data.size()), false);
        for (RiftExitEntry entry : data.ordered()) {
            player.displayClientMessage(Component.literal(String.format(
                    " §7- §f%s §8(%d, %d, %d)",
                    entry.dimensionId(), entry.x(), entry.y(), entry.z())), false);
        }
        return data.size();
    }

    private int riftDebugGui(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = findRiftStack(player);
        if (stack == null) {
            player.displayClientMessage(Component.translatable("commands.plumestweaks.riftdebug.no_item"), false);
            return 0;
        }
        RiftNetwork.sendOpenGui(player, DimensionalRiftItem.readExits(stack));
        return 1;
    }

    // ========== 服务器事件 ==========

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ServerLevel riftLevel = event.getServer().getLevel(PlumesDimensions.riftLevelKey());
        ServerLevel overworld = event.getServer().overworld();
        if (riftLevel != null && overworld != null) {
            // 初始同步主世界时间和天气
            riftLevel.setDayTime(overworld.getDayTime());
            riftLevel.setWeatherParameters(
                    12000, 0, overworld.isRaining(), overworld.isThundering());
            LOGGER.info("[Rift] Initial time/weather synced with overworld");
        }
        LOGGER.info("Plume's Tweaks loaded");
    }

    @SubscribeEvent
    public void onSleepFinished(SleepFinishedTimeEvent event) {
        if (event.getLevel() instanceof ServerLevel level &&
            level.dimension().location().equals(PlumesDimensions.RIFT_LOCATION)) {
            // 在裂隙维度睡觉也推进主世界时间
            ServerLevel overworld = level.getServer().overworld();
            if (overworld != null) {
                overworld.setDayTime(event.getNewTime());
                LOGGER.debug("[Rift] Sleep advanced overworld time to {}", event.getNewTime());
            }
        }
    }

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        // 时空裂隙道具死亡永不掉落，改为暂存待重生归还
        if (event.getEntity() instanceof Player player) {
            int kept = 0;
            for (ItemEntity drop : event.getDrops()) {
                if (drop.getItem().is(DIMENSIONAL_RIFT.get())) {
                    kept += drop.getItem().getCount();
                    drop.discard();
                }
            }
            if (kept > 0) {
                event.getDrops().removeIf(d -> d.isRemoved() || d.getItem().is(DIMENSIONAL_RIFT.get()));
                pendingRiftReturns.merge(player.getUUID(), kept, Integer::sum);
                LOGGER.debug("[Rift] Kept {} rift item(s) on death for {}", kept, player.getName().getString());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        // 仅死亡重生时归还裂隙道具；跨维度克隆不处理
        if (!event.isWasDeath()) return;
        UUID uuid = event.getOriginal().getUUID();
        Integer count = pendingRiftReturns.remove(uuid);
        if (count != null && count > 0) {
            event.getEntity().getInventory().add(new ItemStack(DIMENSIONAL_RIFT.get(), count));
            LOGGER.debug("[Rift] Returned {} rift(s) to {}", count, event.getEntity().getName().getString());
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        Player player = event.getEntity();
        // 重生在裂隙维度且没有任何裂隙道具则补发
        if (player.level().dimension().location().equals(PlumesDimensions.RIFT_LOCATION)
            && !player.getInventory().hasAnyMatching(stack -> stack.is(DIMENSIONAL_RIFT.get()))) {
            player.getInventory().add(new ItemStack(DIMENSIONAL_RIFT.get(), 1));
            LOGGER.debug("[Rift] Granted a rift on respawn to {}", player.getName().getString());
        }
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.level instanceof ServerLevel riftLevel &&
            riftLevel.dimension().location().equals(PlumesDimensions.RIFT_LOCATION)) {
            ServerLevel overworld = riftLevel.getServer().overworld();
            if (overworld != null) {
                // 每 tick 同步昼夜时间与天气
                riftLevel.setDayTime(overworld.getDayTime());
                boolean raining = overworld.isRaining();
                boolean thundering = overworld.isThundering();
                if (riftLevel.isRaining() != raining || riftLevel.isThundering() != thundering) {
                    if (raining) {
                        riftLevel.setWeatherParameters(0, 6000, true, thundering);
                    } else {
                        riftLevel.setWeatherParameters(12000, 0, false, false);
                    }
                }
            }
        }
    }
}
