# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **分支说明**：本分支（`forge-1.20.1`）是 **Forge 1.20.1** 版本。
> `main` 分支是 **NeoForge 1.21.1** 版本，两者 API 差异很大，不要互相照抄代码。

## Commands

```bash
./gradlew build          # 完整构建
./gradlew runClient      # 启动 Minecraft 客户端
./gradlew runServer      # 启动专用服务器
./gradlew runData        # 运行数据生成器
./gradlew clean build    # 清理重建（改过 mixin 注解后建议这么做）
```

- 需要 **JDK 17**
- ForgeGradle 6.0.x（`net.minecraftforge.gradle`）+ MixinGradle 0.7.38（`org.spongepowered.mixin`）
- 构建产物 `build/libs/plumestweaks-<版本>.jar`

## 功能总览

本项目是一个 Forge 1.20.1 模组，只包含「时空裂隙」一条主线：

| 功能 | 核心类 | 说明 |
|---|---|---|
| 时空裂隙 | `DimensionalRiftItem` + `PlumesDimensions` + `RiftChunkGenerator` | 右键撕开次元裂隙，传送到自定义空岛维度；再次右键返回 |
| 裂隙返回维度界面 | `RiftTeleportScreen` + `Panel` + `RiftExitData` + `RiftNetwork` | 裂隙内右键开 GUI，选择任一曾进入过的维度传送回去 |
| 首领名单（数据包） | `BossEntityLoader` | `data/plumestweaks/boss_entities/*.json` 声明哪些实体算 Boss |
| 裂隙维度建门 | `RiftNetherPortalMixin` + `TwilightForestPortalMixin` | 裂隙维度内可点燃下界传送门 + 可建暮色森林传送门 |
| MC-Prefab 兼容 | `PrefabWaterInRiftMixin` | 裂隙维度内蓝图放水不再被换成圆石 |

## 项目结构

```
src/main/java/com/plumestweaks/
  PlumesTweaks.java            # @Mod 入口：物品注册、网络注册（FMLCommonSetupEvent）、
                               #   配置、/riftdebug 指令、服务器事件
  Config.java                  # ForgeConfigSpec：riftAllowTwilightPortal（默认 true）
  client/
    ClientDimensionNames.java  # 维度显示名解析（客户端，引用 @OnlyIn(CLIENT) 的 I18n）
    Panel.java                 # 直角面板：纯程序绘制半透明矩形 + 1px 描边
    RiftTeleportScreen.java    # 裂隙返回维度界面
  component/
    RiftExitEntry.java         # 单个出口点（维度 + 坐标 + 朝向）
    RiftExitData.java          # 出口点集合（NBT 键 plumestweaks:rift_exits）
  dimension/
    PlumesDimensions.java      # 维度注册 + RIFT_LOCATION + chunk generator codec
  item/
    DimensionalRiftItem.java   # 时空裂隙道具：进入/返回、Boss 检测、tooltip
  mixin/
    RiftNetherPortalMixin.java      # BaseFireBlock.inPortalDimension：裂隙内允许点下界门
    TwilightForestPortalMixin.java  # @Pseudo：裂隙内放行暮色森林门（反射调 TF 原逻辑）
    PrefabWaterInRiftMixin.java     # @Pseudo：裂隙内 MC-Prefab 蓝图放水不变圆石
  network/
    RiftNetwork.java           # SimpleChannel：S2C 开界面 / C2S 请求传送
  util/
    BossEntityLoader.java      # 数据包首领名单：加载 / 判定 / 半径扫描
    DimensionNames.java        # 维度翻译键（通用侧，不引用客户端类）
  worldgen/
    RiftChunkGenerator.java    # 空岛生成：平原 + 环绕山脉 + 悬挂钟乳石

src/main/resources/
  META-INF/MANIFEST.MF         # ★ 声明 MixinConfigs（dev 环境必需，见下文）
  META-INF/mods.toml
  plumestweaks.mixins.json              # 主配置（required: true）
  plumestweaks.twilightforest.mixins.json  # required: false
  plumestweaks.prefab.mixins.json          # required: false
  assets/plumestweaks/lang/     # zh_cn.json + en_us.json
  assets/plumestweaks/models/item/dimensional_rift.json
  assets/plumestweaks/textures/item/dimensional_rift.png
  data/plumestweaks/boss_entities/bosses.json   # 首领名单（183 条）
  data/plumestweaks/dimension/rift.json
  data/plumestweaks/dimension_type/rift.json
  data/plumestweaks/worldgen/biome/rift_island.json
```

## 构建配置（★ 极易踩坑，务必先读）

1.20.1 的 Mixin 在 **开发环境和生产环境的命名体系完全不同**，配置缺一项就会出现
「dev 正常、生产闪退」或者「生产正常、dev 静默失效」这类问题。

| 环境 | Minecraft 命名 | 例子 |
|---|---|---|
| 开发（`mapping_channel=official`） | Mojang 官方名 | `inPortalDimension` |
| 生产（`client-...-srg.jar`） | SRG 名 | `m_49248_` |

### 1. refmap 必须生成（否则生产环境 FATAL 闪退）

注解里写的是 Mojang 名，生产环境要靠 refmap 翻译成 SRG 名。三样缺一不可：

```groovy
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'org.spongepowered.mixin' version '0.7.38'      // ① 插件
}
dependencies {
    // ② 注解处理器 —— 光有插件不够，不写这行 AP 不跑，refmap 不生成
    annotationProcessor "org.spongepowered:mixin:0.8.5:processor"
}
mixin {
    add sourceSets.main, "${mod_id}.refmap.json"       // 名字要和配置里的 refmap 字段一致
    config "${mod_id}.mixins.json"
    config "${mod_id}.twilightforest.mixins.json"
    config "${mod_id}.prefab.mixins.json"
}
```

MixinGradle 只发布在 Sponge 仓库，`settings.gradle` 的 `pluginManagement.repositories`
里必须有 `https://repo.spongepowered.org/repository/maven-public/`。

产物里应有 `plumestweaks.refmap.json`。**验证方法：直接看 jar 里有没有这个文件。**

缺失时的典型报错（生产环境，直接崩）：

```
Reference map 'plumestweaks.refmap.json' ... could not be read.
FATAL Mixin apply failed ...:RiftNetherPortalMixin -> BaseFireBlock:
  could not find any targets matching 'inPortalDimension(...)'. **No refMap loaded.**
```

### 2. dev 环境要靠 MANIFEST.MF 注册 Mixin

Forge 1.20.1 通过清单属性 `MixinConfigs` 注册 mixin 配置。`build.gradle` 里 jar 任务的
manifest 只作用于**构建产物**，而 dev 运行时加载的是 `build/resources/main/`，那里默认
没有 `MANIFEST.MF`。

Mixin 的 `MainAttributes.getDirAttributes()` 找不到该文件就返回 null，配置**静默不注册**
（日志里连一行都不会有）。

所以 `src/main/resources/META-INF/MANIFEST.MF` 是**必需文件**，`processResources`
会把它复制到 `build/resources/main/`：

```
Manifest-Version: 1.0
MixinConfigs: plumestweaks.mixins.json,plumestweaks.twilightforest.mixins.json,plumestweaks.prefab.mixins.json
```

（单行超 72 字节要折行，续行以单个空格开头。上面的长行在文件里实际是折行的。）

### 3. dev 环境反查 refmap

refmap 里存的是 SRG 名，dev 运行时是官方名，需要让 Mixin 反查：

```groovy
runs {
    configureEach {
        property 'mixin.env.remapRefMap', 'true'
        property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
    }
}
```

> **注意**：这组属性只解决 refmap 的方向问题，**不能**让 dev 环境加载
> `run/mods` 里的生产版模组。见下文「已知限制」。

## 架构要点

### 时空裂隙维度

`PlumesDimensions.RIFT_LOCATION` = `plumestweaks:rift`，维度类型在
`data/plumestweaks/dimension_type/rift.json`：

```json
{ "ultrawarm": false, "has_skylight": true, "height": 512, "min_y": 0, "coordinate_scale": 1.0 }
```

`RiftChunkGenerator` 生成空岛：平原 `Y=128`、半径 64，环绕山脉到 `Y=192`、外缘半径 112，
山体下悬挂钟乳石尖刺（主根 0–50 格 / 次根 0–22 / 细根 0–10）。
以 4 格网格做双线性插值，调色板为泥土 → 石头 → 混合石材。

### 裂隙返回维度界面

数据流：**进入即记录，出去不抹除，只缓存裂隙以外的信息**。

```
裂隙外右键
  └─ 把「当前维度 + 坐标 + 朝向」写回物品 NBT（plumestweaks:rift_exits）
     （同维度重复进入 → 覆盖该维度；其它维度的旧记录不受影响）
  └─ 再执行「传送到裂隙落点」

裂隙内右键
  ├─ 有记录 → S2C RiftNetwork.OpenGui → 客户端 RiftTeleportScreen
  │    └─ 单击条目 → 白框选中；点「传送」→ C2S Teleport(dimensionId)
  │         └─ 服务端从物品 NBT 重新读取坐标后 teleportTo（不信任客户端坐标）
  └─ 无记录 → 直接送回世界重生点，不弹界面（防止玩家被困在裂隙里）
```

物品上的两层数据**互不干扰**：

| NBT 键 | 含义 | 谁改 |
|---|---|---|
| `enter_dim` / `enter_pos` | 裂隙维度内的**落点** | 裂隙内潜行 + 右键 |
| `plumestweaks:rift_exits` | 裂隙**外**各维度的**返回点** | 每次从该维度进入裂隙时自动记录 |

旧版本单一返回点存在 `rift_data` 里，首次在新版本打开界面时由
`DimensionalRiftItem#migrateLegacy` 自动迁移。

**维度显示名格式**：`dimension.<namespace>.<path>`（`DimensionNames.translationKey`），
内置主世界 / 下界 / 末地 / 暮色森林，未定义时回退 `namespace:path`。
记录时不写死显示名（服务端读不到语言表），由客户端按当前语言解析。

**界面构成**（`RiftTeleportScreen` + `Panel`，**不含任何贴图与着色器**）：

| 元素 | 实现 |
|---|---|
| 全屏背景 | **无**。只画中心面板，其余让游戏画面透出来 |
| 中心面板 | 直角矩形，纯程序绘制：底色 `0x99101418`（60% 不透明）+ 1px 半透明白描边 `0x55FFFFFF` |
| 面板尺寸 | 竖版比例 1.2；高度 = 内容高度（202 = 标题 30 + 一页列表 130 + 间隙 8 + 按钮 22 + 底距 12）+ 富余，富余夹在 0~40、总高不超 300；面板整体居中 |
| 列表 | 恒为一页：`PAGE_ROWS(6) × 20 + 5 × 2 = 130`。富余高度全给「列表底 → 按钮」段（最大 40px） |
| 过长条目 | 文本宽度 > 可画宽度时**横向循环滚动**：文本 + 24px 空白组成循环带，画两份错开一个带宽。速度 22 px/s |
| 滚动条 | **内容不足一屏时整条不绘制**（`maxScroll() <= 0` 直接 return） |
| 选中反馈 | 选中行加一圈 1px 纯白描边 |
| 传送按钮 | 面板下方居中，未选中条目时置灰不可点 |

跑马灯两个关键点（都踩过坑）：

- 宽度**惰性测量**——构造函数里 `Screen.font` 还是 null，早期把它缓存成 0 导致永不滚动
- 位移用 **float**（取整会一卡一卡）、时间按**真实帧间隔**累加
  （`Minecraft#getDeltaFrameTime()/20`，夹住 0.1s 上限），与帧率无关

**测试指令**（权限 2）：`/riftdebug add [1~64] | remove | clear | list | gui`。
`add` 会往物品里塞 `plumestweaks:debug_*` 假记录并直接打开界面，
界面检测到调试条目会自动打开左上角调试叠加（条目数 / 选中项 / 滚动偏移 / 可视行范围 / 面板尺寸）。

### 网络层（Forge SimpleChannel）

`RiftNetwork.CHANNEL`（`plumestweaks:main`，协议版本 `"1"`）两条消息：

| 消息 | 方向 | 载荷 |
|---|---|---|
| `OpenGui` | S2C | 全部出口点 → 客户端打开界面 |
| `Teleport` | C2S | `dimensionId` → 服务端重读 NBT 后传送 |

S2C 的客户端处理用**反射**调用 `com.plumestweaks.client.RiftTeleportScreen#open`，
避免专用服务端加载客户端类。注册放在 `FMLCommonSetupEvent`，不在 mod 构造函数里。

### Boss 检测算法（数据包首领名单）

```json
[
  { "id": "minecraft:wither" },
  { "id": "cataclysm:ignis" },
  { "id": "twilightforest:naga" }
]
```

- 加载：`BossEntityLoader extends SimpleJsonResourceReloadListener`，
  在 `AddReloadListenerEvent` 中注册
- 判定：`countsAsBoss(entity)` = 实体存活 && 注册名在名单内
- 扫描：`BossEntityLoader.hasBossNearby(level, center, radius)`，裂隙使用半径 32
- 名单为空（数据包缺失）时**全部放行**，属于「不阻断玩法」的失败方向
- 注册名通过 `ForgeRegistries.ENTITY_TYPES.getKey(type)` 获取

### 裂隙维度内建传送门

| 传送门 | 门禁位置 | 处理 |
|---|---|---|
| 下界传送门 | `BaseFireBlock#inPortalDimension(Level)` | `RiftNetherPortalMixin` 让裂隙维度返回 true |
| 暮色森林传送门 | `TFTickHandler#playerTick` → `checkForPortalCreation` | `TwilightForestPortalMixin` 仅对裂隙维度临时放开 |

#### 下界传送门：`RiftNetherPortalMixin`

原版把「能否形成下界传送门」硬编码为只有主世界与下界：

```java
private static boolean inPortalDimension(Level level) {
    return level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
}
```

该方法被**两处**使用，缺一不可：

1. `BaseFireBlock#onPlace` —— 火焰放置后尝试把黑曜石门框变成传送门
2. `BaseFireBlock#isPortal`（被 `canBePlacedAt` 调用）—— 决定打火石能否点火

所以只给 `onPlace` 放行不够（表现是「打火石点不着」而不是「有火但不成门」）。
HEAD 改写返回值，两处同时放行。进门/出门本来就没有来源维度限制，补上点燃环节即可完整可用。

**注意不要加 `@Pseudo`**：这是原版类、必然存在。

#### 暮色森林传送门：`TwilightForestPortalMixin`

TF 1.20.1 的门禁在 `TFTickHandler#checkForPortalCreation`：

```java
if (world.dimension().location().equals(TFConfig.COMMON_CONFIG.originDimension.get())
        || TFGenerationSettings.isTwilightPortalDestination(world)
        || TFConfig.COMMON_CONFIG.allowPortalsInOtherDimensions.get()) { ...成门... }
```

**1.20.1 与 1.21.1 的门禁位置完全不同，不能照抄**：1.21.1 在
`ProgressionEvents#checkForPortalCreation`，1.20.1 在 `TFTickHandler#playerTick`。

`allowPortalsInOtherDimensions` 默认 false 且是全局开关，不该为一个维度对整个整合包放开。
做法：在 `playerTick` 的 TAIL，若玩家位于裂隙维度，按 TF 自己的节奏
（相同 tick 取模、相同权限门槛、相同半径）重跑 TF 的 `checkForPortalCreation`；
只在这一次调用期间把开关临时置 true，`finally` 立即还原。

全程 `@Pseudo` + 反射 + `try/catch`：未装 TF 时静默失效，TF 改签名只会退化成
「裂隙内建不了暮色门」并打日志，不会崩溃。

#### 关键：handler 修饰符必须与目标方法一致

这是实际踩过的坑——**`PrefabWaterInRiftMixin` 因为写错 `static` 而静默失效**：

```
InvalidInjectionException: 'static' modifier of handler method does not match target
```

| Mixin | 目标方法 | 是否 static |
|---|---|---|
| `RiftNetherPortalMixin` | `BaseFireBlock.inPortalDimension` | **static** |
| `TwilightForestPortalMixin` | `TFTickHandler.playerTick` | **static** |
| `PrefabWaterInRiftMixin` | `Structure.WaterReplacedWithCobbleStone` | **实例方法** |

因为配置是 `required: false` + `expect = 0`，这类错误**只打 WARN 不崩溃**，
表现为「功能静默不生效」。改完 mixin 一定要查日志里有没有
`Mixin apply failed`。

### MC-Prefab 兼容：`PrefabWaterInRiftMixin`

MC-Prefab 的 `Structure#WaterReplacedWithCobbleStone`：

```java
boolean isOverWorld = Level.OVERWORLD.compareTo(world.dimension()) == 0;
if (world.dimensionType().ultraWarm()          // 下界
        || (!isOverWorld && config.allowWaterInNonOverworldDimensions)) {
    ... 把水 / 含水方块换成圆石 ...
}
```

裂隙维度既不是主世界、也不是 `ultraWarm`，于是取决于 MC-Prefab 的服务端配置项
`allowWaterInNonOverworldDimensions`（默认 false）。默认配置下其它维度本来就该换圆石，
不该为一个维度去改人家的全局开关，所以只针对裂隙维度提前放行。

**返回 `false` 而不是 `true`** —— 这点很关键。调用点是：

```java
if (!this.WaterReplacedWithCobbleStone(...) && !this.CustomBlockProcessingHandled(...)) {
    ... 正常放置方块 ... world.setBlock(...)
}
```

方法名问的是「水被换成圆石了吗」，`false` 才表示「没换」。
返回 `true` 会让调用点认为「这个方块已处理」而整段跳过，水根本不会被放置，
世界里留下的是空洞。

参数表必须与目标方法完全一致（8 个参数），但只用原版类型，所以编译期不需要
MC-Prefab 依赖。

## 已知限制

### dev 工作区无法加载 `run/mods` 里的生产版模组

dev 工作区用 `official`（Mojang）映射，而发布版模组 jar 里是 SRG 名，两者不兼容：

```
Failed to create mod instance. ModID: prefab
java.lang.NoSuchFieldError: f_56742_
Failed to create mod instance. ModID: twilightforest
java.lang.NoSuchMethodError: ...GameRules$BooleanValue.m_46252_(...)
Failed to complete lifecycle event CONSTRUCT, 2 errors found
```

这两个模组在**构造阶段就崩**，游戏停在启动画面。这与本模组无关
（`mixin.env.remapRefMap` 那组属性解决不了，因为它们崩在 `NoSuchFieldError` 而非 Mixin 环节）。

**要在 dev 里测，得把生产版模组从 `run/mods` 移出去**；暮色门 / Prefab 放水这类
依赖其他模组的特性，只能在**正式整合包**里验证。

### `mixin.env.refMapRemappingFile`

`build/createSrgToMcp/output.srg` 由 ForgeGradle 的 `createMcpToSrg` 任务生成。
如果删了这个文件，dev 环境反查 refmap 会失败，需要重新跑一次构建。
