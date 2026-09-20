package com.plumestweaks;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.slf4j.Logger;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;

import com.plumestweaks.component.ModDataComponents;
import com.plumestweaks.dimension.PlumesDimensions;
import com.plumestweaks.dimension.RiftIslandAllocator;
import com.plumestweaks.effect.MountProtectionEffect;
import com.plumestweaks.item.DimensionalRiftItem;
import com.plumestweaks.item.TeleportLenItem;
import com.plumestweaks.item.TempRespawnPointItem;
import com.plumestweaks.network.ClearItemsConfirmPayload;
import com.plumestweaks.network.ClearItemsConfirmResponsePayload;
import com.plumestweaks.network.CompassFoundPayload;
import com.plumestweaks.util.BossEntityLoader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Mod(PlumesTweaks.MODID)
public class PlumesTweaks {
    public static final String MODID = "plumestweaks";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** pending clear-item confirmations: player UUID → radius */
    private static final Map<UUID, Integer> pendingConfirmations = new HashMap<>();

    /** 物品注册表 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, MODID);

    /** 临时重生点道具 */
    public static final Supplier<Item> TEMP_RESPAWN_POINT =
            ITEMS.register("temp_respawn_point", TempRespawnPointItem::new);

    /** 时空裂隙道具 */
    public static final Supplier<Item> DIMENSIONAL_RIFT =
            ITEMS.register("dimensional_rift", DimensionalRiftItem::new);

    /** 原初之匣 —— 临时观察者模式 */
    public static final Supplier<Item> TELEPORT_LEN =
            ITEMS.register("teleport_len", TeleportLenItem::new);

    /** 效果注册表 */
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(BuiltInRegistries.MOB_EFFECT, MODID);

    /** 骑马保护效果 — 无粒子无图标，持有时取消外界伤害 */
    public static final DeferredHolder<MobEffect, MountProtectionEffect> MOUNT_PROTECTION =
            MOB_EFFECTS.register("mount_protection", MountProtectionEffect::new);

    // ========== 原初之匣（观察者模式）状态管理 ==========

    /** 正在观察者模式计时中的玩家 */
    private static final Map<UUID, TeleportState> teleportingPlayers = new HashMap<>();
    /** 摔落免疫玩家（UUID → 结束 tick） */
    private static final Map<UUID, Long> fallImmunePlayers = new HashMap<>();

    private record TeleportState(GameType originalMode, long endTick) {}

    /**
     * 启动观察者模式计时。
     * 由 {@link TeleportLenItem#use} 调用。
     */
    public static void startTeleport(ServerPlayer player, GameType originalMode, long endTick) {
        teleportingPlayers.put(player.getUUID(), new TeleportState(originalMode, endTick));
    }

    public PlumesTweaks(IEventBus modEventBus, ModContainer modContainer) {
        // 注册物品、效果、组件、生物群系、维度
        ITEMS.register(modEventBus);
        MOB_EFFECTS.register(modEventBus);
        ModDataComponents.register(modEventBus);
        PlumesDimensions.CHUNK_GENERATORS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::onCreativeTabBuild);

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 注册物品到 DeferredRegister 由 NeoForge 自动完成
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(MODID);

        // C2S: 客户端确认响应
        registrar.playToServer(
                ClearItemsConfirmResponsePayload.TYPE,
                ClearItemsConfirmResponsePayload.STREAM_CODEC,
                (payload, context) -> handleConfirmResponse(context)
        );

        // S2C: 确认请求，使用反射桥接客户端处理器（避免服务端加载客户端类）
        if (FMLLoader.getDist() == Dist.CLIENT) {
            try {
                Class.forName("com.plumestweaks.client.ClientPayloadHandler")
                        .getMethod("onRegisterPayloads", RegisterPayloadHandlersEvent.class)
                        .invoke(null, event);
            } catch (Exception e) {
                LOGGER.error("Failed to register client payloads", e);
            }
        } else {
            // 服务端必须注册 S2C 通道以完成握手；占位 handler 永不执行（服务端不接收 S2C）
            registrar.playToClient(
                    ClearItemsConfirmPayload.TYPE,
                    ClearItemsConfirmPayload.STREAM_CODEC,
                    (payload, context) -> {}
            );
            // 指南针路径点通道占位（客户端在 ClientPayloadHandler 中注册真实 handler）
            registrar.playToClient(
                    CompassFoundPayload.TYPE,
                    CompassFoundPayload.STREAM_CODEC,
                    (payload, context) -> {}
            );
        }
    }

    /**
     * 将实用物品加入原版"工具与实用物品"创造标签页。
     */
    private void onCreativeTabBuild(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.TOOLS_AND_UTILITIES.equals(event.getTabKey())) {
            event.accept(TEMP_RESPAWN_POINT.get());
            event.accept(DIMENSIONAL_RIFT.get());
            event.accept(TELEPORT_LEN.get());
        }
    }

    private void handleConfirmResponse(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                var radius = pendingConfirmations.remove(serverPlayer.getUUID());
                if (radius != null) {
                    executeClear(serverPlayer, radius);
                }
            }
        });
    }

    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        // 首领实体清单（数据包）：data/plumestweaks/boss_entities/*.json
        event.addListener(new BossEntityLoader());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        var server = event.getServer();
        RiftIslandAllocator.load(server);
        var riftLevel = server.getLevel(PlumesDimensions.riftLevelKey());
        var overworld = server.overworld();
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
    public void onServerStopping(ServerStoppingEvent event) {
        RiftIslandAllocator.save();
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
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel riftLevel &&
            riftLevel.dimension().location().equals(PlumesDimensions.RIFT_LOCATION)) {
            ServerLevel overworld = riftLevel.getServer().overworld();
            if (overworld != null) {
                // 每 tick 同步天气状态
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

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("clearitems")
                        .requires(source -> source.hasPermission(0))
                        .executes(ctx -> requestClear(ctx.getSource(), Config.CLEAR_ITEMS_RADIUS.get()))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> requestClear(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))
                        )
        );

        // /pts checkspawnpoint — 检查玩家是否设置了临时重生点
        dispatcher.register(
                Commands.literal("pts")
                        .requires(source -> source.hasPermission(0))
                        .then(Commands.literal("checkspawnpoint")
                                .executes(ctx -> checkSpawnPoint(ctx.getSource()))
                        )
        );
    }

    @SubscribeEvent
    public void onPlayerDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        pendingConfirmations.remove(uuid);
        teleportingPlayers.remove(uuid);
        fallImmunePlayers.remove(uuid);
    }

    // ========== 原初之匣事件处理器 ==========

    /**
     * 玩家登录时检查：如果 persistentData 中有残留的观察者状态（服务器崩溃），
     * 自动恢复为生存模式。
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            if (sp.getPersistentData().contains("plumestweaks:teleport_mode")) {
                int modeId = sp.getPersistentData().getInt("plumestweaks:teleport_mode");
                GameType originalMode = GameType.byId(modeId);
                sp.setGameMode(originalMode);
                sp.getPersistentData().remove("plumestweaks:teleport_mode");
                sp.sendSystemMessage(
                        Component.translatable("item.plumestweaks.teleport_len.crash_restore"), true);
                LOGGER.info("[TeleportLen] Restored player {} to {} after crash",
                        sp.getName().getString(), originalMode);
            }
        }
    }

    /**
     * 每 tick 检查观察者模式计时 + 摔落免疫 fallDistance 重置。
     * 使用 Post 阶段，在游戏逻辑处理完成后执行。
     */
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        UUID uuid = sp.getUUID();
        long gameTime = sp.serverLevel().getGameTime();

        // === 观察者模式计时恢复 ===
        TeleportState state = teleportingPlayers.get(uuid);
        if (state != null) {
            long remaining = state.endTick - gameTime;
            if (remaining <= 0) {
                // 时间到：恢复原游戏模式
                sp.setGameMode(state.originalMode);
                teleportingPlayers.remove(uuid);
                sp.getPersistentData().remove("plumestweaks:teleport_mode");
                sp.displayClientMessage(
                        Component.translatable("item.plumestweaks.teleport_len.restored"), true);

                // 启动 5 秒摔落免疫（100 tick）
                fallImmunePlayers.put(uuid, gameTime + 100);

                LOGGER.info("[TeleportLen] Player {} restored to {}",
                        sp.getName().getString(), state.originalMode);
            } else if (remaining <= 60 && remaining % 20 == 0) {
                // 倒计时提示：3 秒、2 秒、1 秒前提醒
                int seconds = (int) ((remaining + 19) / 20);
                sp.sendSystemMessage(
                        Component.translatable("item.plumestweaks.teleport_len.countdown", seconds), true);
            }
        }

        // === 摔落免疫：每 tick 重置 fallDistance ===
        Long immuneUntil = fallImmunePlayers.get(uuid);
        if (immuneUntil != null) {
            if (gameTime >= immuneUntil) {
                fallImmunePlayers.remove(uuid);
            } else {
                sp.fallDistance = 0;
            }
        }
    }

    /**
     * 摔落免疫期间取消所有摔落伤害。
     */
    @SubscribeEvent
    public void onLivingDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            if (fallImmunePlayers.containsKey(sp.getUUID())
                    && event.getSource().is(DamageTypeTags.IS_FALL)) {
                event.getContainer().setNewDamage(0);
            }
        }
    }

    private int requestClear(CommandSourceStack source, int radius) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        pendingConfirmations.put(player.getUUID(), radius);
        PacketDistributor.sendToPlayer(player, new ClearItemsConfirmPayload(radius));
        return 0;
    }

    private static final String TAG_TEMP_SPAWN = "plumestweaks:temp_spawn";
    private static final String TAG_TEMP_SPAWN_DIM = "plumestweaks:temp_spawn_dim";

    private int checkSpawnPoint(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var tag = player.getPersistentData();

        if (!tag.contains(TAG_TEMP_SPAWN)) {
            player.sendSystemMessage(
                    Component.translatable("commands.plumestweaks.checkspawnpoint.not_set"));
            return 0;
        }

        int[] pos = tag.getIntArray(TAG_TEMP_SPAWN);
        String dimStr = tag.getString(TAG_TEMP_SPAWN_DIM);
        if (pos.length < 3 || dimStr.isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable("commands.plumestweaks.checkspawnpoint.invalid"));
            return 0;
        }

        // 将维度 ResourceLocation 翻译为可读名称
        String dimKey = dimStr.contains(":") ? dimStr.split(":")[1] : dimStr;
        player.sendSystemMessage(
                Component.translatable("commands.plumestweaks.checkspawnpoint.set",
                        pos[0], pos[1], pos[2], dimKey));
        return 1;
    }

    private void executeClear(ServerPlayer player, int radius) {
        var pos = player.position();
        var aabb = player.getBoundingBox().inflate(radius);
        var items = player.serverLevel().getEntitiesOfClass(ItemEntity.class, aabb,
                e -> e.distanceToSqr(pos) <= radius * radius);

        items.forEach(ItemEntity::discard);

        player.sendSystemMessage(
                Component.translatable("commands.plumestweaks.clearitems.success", items.size(), radius));
    }
}
