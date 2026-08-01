# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build          # 完整构建
./gradlew runClient      # 启动 Minecraft 客户端
./gradlew runServer      # 启动专用服务器
./gradlew runData        # 运行数据生成器
./gradlew --refresh-dependencies  # 刷新依赖缓存（添加新模组依赖后需要）
```

- 需要 JDK 21（Temurin 推荐）
- NeoForge MDG 2.0.141 + Loom（自动生成 refmap）
- 构建产物 `build/libs/plumestweaks-1.0.0.jar`

## 功能总览

本项目是一个 NeoForge 1.21.1 模组，包含八个独立功能：

| 功能 | 核心类 | 说明 |
|---|---|---|
| 跨模组权限绕过 | `*CompassPlayerUtilsMixin` | 修改两个指南针模组的 `canTeleport()` 始终返回 true |
| /clearitems | `PlumesTweaks` (指令+网络) | 带二次确认弹窗的清理掉落物指令 |
| 马匹增强 | `AbstractHorseMixin` + `LocalPlayerMixin` | 骑乘移速 2x + 75% 减伤 + 蓄力满锁定 + 跳跃高度 1.5x（基于马匹 JUMP_STRENGTH 属性） + 降低奔腾音效 + HUD 蓄力条视觉锁定 |
| 骑乘伤害转移 | `PlayerHorseMixin` | 骑马时玩家所有伤害由马承担 |
| FastMove + Paraglider 耐力 | `FastMoveStaminaMixin` + `ParagliderBridge` | 将滑铲/翻滚/跑墙消耗从饥饿值重定向到耐力条 |
| 临时重生点 + 视角恢复 | `TempRespawnPointItem` + `ServerPlayerRespawnMixin` | 右键设置/取消临时重生点（Boss 检测），回死前视角防晕 |
| 疾跑耐力恢复 | `FastMoveStaminaMixin` | 疾跑时每 tick 恢复 3 耐力 |
| 时空裂隙 | `DimensionalRiftItem` + `PlumesDimensions` + `RiftChunkGenerator` | 右键撕开次元裂隙，传送到自定义空岛（半径64、平原Y=32、环绕山脉高至Y=96、山脉下悬挂深达35格、噪声钟乳石尖刺、4格网格双线性插值、泥土→石头→混合石材方块调色板），再次右键返回原维度，2分钟冷却，绿色耐久条 + tooltip + ActionBar提醒 |
| 骑马保护效果 | `MountProtectionEffect` + `PlayerMountProtectionMixin` | 骑马时获得隐藏效果，持有时取消玩家伤害，下马后 6 秒无敌窗口 |
| 原初之匣（观察者模式） | `TeleportLenItem` + `PlumesTweaks` (事件) | 右键进入 10 秒观察者模式，倒计时恢复原模式 + 5 秒摔落免疫，1 分钟冷却（耐久条 + tooltip），跨维度/重连恢复安全 |

## 项目结构

```
src/main/java/com/plumestweaks/
  PlumesTweaks.java            # @Mod 入口：物品注册、指令、网络、事件
  PlumesTweaksClient.java      # 客户端入口：配置屏幕
  Config.java                  # ModConfigSpec（clearItemsRadius）
  bridge/
    ParagliderBridge.java      # 跨模组反射桥接：运行时通过 Stamina API 访问 Paraglider 耐力
  client/
    ClientPayloadHandler.java  # /clearitems 确认弹窗（客户端侧网络处理）
  effect/
    MountProtectionEffect.java # 骑马保护效果：纯标记 MobEffect，无运行时逻辑
  item/
    DimensionalRiftItem.java      # 时空裂隙：维度传送（空岛维度）
    TeleportLenItem.java          # 原初之匣：临时观察者模式 + 冷却样式
    TempRespawnPointItem.java     # 临时重生点物品实现（设置/取消/Boss 检测）
  mixin/
    ExplorersCompassPlayerUtilsMixin.java   # @Pseudo + @Overwrite 绕过权限
    NaturesCompassPlayerUtilsMixin.java     # @Pseudo + @Overwrite 绕过权限
    AbstractHorseMixin.java                 # 移速2x + 75%减伤 + 跳蓄满锁定 + 跳跃1.5x + 降奔腾音效
    FastMoveStaminaMixin.java               # Player.tick TAIL：动作检测 + 耐力消耗 + 疾跑恢复
    PlayerHorseMixin.java                   # Player.hurt HEAD：骑马时伤害重定向到马
    PlayerMountProtectionMixin.java          # Player.tick TAIL + hurt HEAD：骑马保护效果赋予与伤害取消
    ServerPlayerRespawnMixin.java            # ServerPlayer.findRespawnPosition HEAD：直接返回临时重生点 DimensionTransition
    FoodDataAccessor.java                   # @Accessor 访问 FoodData.exhaustionLevel
  network/
    ClearItemsConfirmPayload.java           # S2C：清理确认请求
    ClearItemsConfirmResponsePayload.java   # C2S：玩家确认响应

src/main/resources/
  plumestweaks.mixins.json      # Mixin 注册清单
  assets/plumestweaks/lang/     # zh_cn.json + en_us.json
  assets/plumestweaks/models/   # 物品模型
```

## 架构要点

### 跨模组 Mixin 模式（指南针 + FastMove）

本模组通过 @Mixin 修改**运行时的其他模组类**，编译期对这些模组无依赖。三种技术路线：

| 方式 | 适用场景 | 示例 |
|---|---|---|
| `@Pseudo + @Overwrite` | 编译期不可见的目标类，直接替换 | 指南针 `PlayerUtils.canTeleport()` |
| `@Mixin + @Inject` | 编译期可见的目标类（Minecraft 本体） | `Player.tick`、`ServerPlayer.copyRespawnPosition` |
| 纯运行时反射 | 跨模组 API 调用，非类改造 | `ParagliderBridge` 通过 `Class.forName` + `Method.invoke` 访问 Stamina API |

### 跨模组类加载（Paraglider 桥接）

`ParagliderBridge` 使用三阶段回退类加载：

```
本模组 ClassLoader → 线程上下文 ClassLoader → 系统 ClassLoader
```

因为 NeoForge MDG 的 `TransformingClassLoader` 会在不同阶段反射时改变类可见性。

安全转换：反射返回 `Object` 后用 `((Number) val).intValue()` 而非 `(int) val`，避免 `Double→Integer` 的 `ClassCastException`。

### 状态跟踪模式（FastMove 动作检测）

`FastMoveStaminaMixin` 使用状态变化检测而非每 tick 重复消耗：

- 记录上一 tick 的 `MoveState` 序数
- 比较 `previous != current` 以在**动作启动瞬间**执行单次消耗（滑铲 333、翻滚 225）
- 跑墙每 tick 消耗 10（持续状态）
- 疾跑恢复独立于动作状态（`isSprinting()` 检查，无条件每 tick +3）

### /clearitems 二次确认系统

```
服务器指令 → S2C 确认弹窗 → 玩家点"是" → C2S 响应 → 执行清理
```

- 每个玩家有独立的 `pendingConfirmations` Map（UUID → radius）
- 玩家断开连接时自动清理未确认状态
- 客户端处理器用反射注册（`Class.forName`），避免服务端加载客户端类

### Boss 检测算法（临时重生点）

通过反射扫描实体的类继承链，检查是否存在 `ServerBossEvent` 类型字段：

```java
while (clazz != null && clazz != Entity.class) {
    for (Field field : clazz.getDeclaredFields()) {
        if (ServerBossEvent.class.isAssignableFrom(field.getType())) return true;
    }
    clazz = clazz.getSuperclass();
}
```

无需硬编码 Boss 实体列表，自动适配任意（含模组添加的）Boss 级生物。

### 临时重生点数据流

```
玩家 NBT 存储 (persistentData):
  plumestweaks:temp_spawn       // int[3] 坐标
  plumestweaks:temp_spawn_dim   // 维度
  plumestweaks:bed_backup       // 复合标签：原版床位置备份
  plumestweaks:death_yaw        // float 死前水平视角（度）
  plumestweaks:death_pitch      // float 死前垂直视角（度）
```

`ServerPlayerRespawnMixin` 注入 `ServerPlayer.findRespawnPositionAndUseSpawnBlock(boolean, PostDimensionTransition, CallbackInfoReturnable)` HEAD + cancellable — 若有临时重生点，直接返回以临时坐标构造的 `DimensionTransition(missingRespawnBlock=false)`，跳过所有原版床/重生锚/方块检测逻辑。备用床位置在首次重生时自动备份，取消时恢复。

**关键修复：** 旧版方案（`PlayerListMixin` 设 forced=true 让原版检测方块`isPossibleToRespawnInThis`）在某些方块下仍会回退世界出生点。本方案直接返回坐标，100% 强制生效。

### 原初之匣（观察者模式状态管理）

`TeleportLenItem` 是纯事件驱动的功能，不依赖 Mixin：

```
右键激活 ──→ TeleportLenItem.use()
                │
                ├─ 检测冷却（DataComponent: TELEPORT_COOLDOWN）
                ├─ 保存 GameType 到 persistentData（防崩溃恢复）
                ├─ 调用 PlumesTweaks.startTeleport()
                ├─ setGameMode(SPECTATOR)
                └─ 设置冷却 DataComponent
                
PlayerTickEvent.Post ──→ 计时器检查
                           │
                           ├─ endTick reached? → setGameMode(originalMode)
                           │                       → 启动 5 秒摔落免疫 fallImmunePlayers
                           │                       → 清除 persistentData 标记
                           │
                           └─ remaining ≤ 60tick? → 每秒倒计时消息 (3, 2, 1)
                           
LivingDamageEvent.Pre ──→ 摔落免疫
                           │
                           └─ IS_FALL + fallImmunePlayers 中有该玩家？
                               → setNewDamage(0)
```

**状态存储三层次：**
| 层次 | 目标 | 说明 |
|---|---|---|
| 冷却 (`CooldownData`) | 物品 `DataComponent` | 每个物品堆独独立冷却，跨维度/会话持久 |
| 计时器 (`TeleportState`) | `HashMap<UUID, TeleportState>` | 观察者模式剩余时间，UUID 键规避重连对象变化 |
| 崩溃恢复 (`persistentData`) | `player.persistentData` | 仅存 `teleport_mode`，登录时检测残留主动恢复 |

### 骑马保护效果

`MountProtectionEffect` 是一个纯标记效果的 `MobEffect`，无任何运行时逻辑。它的存在与否由 `PlayerMountProtectionMixin` 管理：

```
Player.tick() TAIL ──每 20 tick──→ 骑乘 AbstractHorse？→ addEffect(6s, 无粒子, 无图标)
Player.hurt() HEAD ──────────────→ hasEffect？→ cancel (setReturnValue false)
```

- **效果赋予时机：** 每 20 tick 刷新一次，持续 6 秒（120 tick）。只要玩家在马上，效果就不会断。
- **下马无敌窗口：** 下马后效果继续存在约 6 秒，在此期间玩家免疫所有外界伤害。
- **与`PlayerHorseMixin` 的关系：** 两者都注入 `Player.hurt()` HEAD + cancellable，都在同一注入点执行。`PlayerHorseMixin` 优先将伤害转移到马匹（`horse.hurt()`），然后两者都取消玩家伤害（返回 false）。马匹承受伤害不受新增注入的影响。
- **效果注册：** 使用 `DeferredRegister<MobEffect>` 注册，`DeferredHolder` 可直接作为 `Holder<MobEffect>` 传入 `MobEffectInstance` 和 `hasEffect()`。
