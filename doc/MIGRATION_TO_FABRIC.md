# ChatExchange 平台迁移计划：NeoForge → Fabric

> 目标版本：Minecraft **26.2**（采用模板 `FABRIC_TEMPLATE` 现成版本；与 NeoForge 现状 26.1.2 不同步，属预期）
> 当前平台：NeoForge（`net.neoforged.moddev` 2.0.141，neo 26.1.2.76，KotlinForForge 6.1.0a，Kotlin 2.3.0，Java 25）
> 目标平台：Fabric（`fabric-loom`，Fabric Loader，`fabric-language-kotlin`，`fabric-api`）

### 已确认的选型决策（已锁定）
- **MC 版本**：26.2（模板现成依赖版本，开箱即用）
- **ktor 分发**：Loom `include` 嵌套 jar
- **配置系统**：~~Cloth Config API + Mod Menu~~ → **改用 ForgeConfigAPIPort (FCAP)**（见下方"选型变更"）
- **包名**：重构为全小写 `nomathexpectation.chatexchange`（去除平台台词）

### 选型变更：Cloth Config → ForgeConfigAPIPort（实施期决策）
- **原因**：实施时验证 cloth-config 26.2.155 经 Loom remap 后，其捆绑的 `me.shedaniel.autoconfig.*`（配置 I/O 序列化器）在编译期**不可访问**（`ConfigBuilder` GUI 可访问，但 `AutoConfig`/`GsonConfigSerializer` 不可解析），导致无法用 cloth-config 做"不自写代码"的配置 I/O。
- **改用 FCAP**（`fuzs.forgeconfigapiport:forgeconfigapiport-fabric:26.2.1`，经 Modrinth maven 拉取）：
  - FCAP 以**与 NeoForge 完全相同的包名**（`net.neoforged.neoforge.common.ModConfigSpec`、`net.neoforged.fml.config.ModConfig`）vendor 了 NeoForge 的配置类——原 `ModConfigSpec` 配置代码可**几乎逐行复用**。
  - 注册方式：`ConfigRegistry.INSTANCE.register(modId, ModConfig.Type.COMMON, spec)`（替代 NeoForge 的 `ModLoadingContext.activeContainer.registerConfig(...)`）。FCAP 在 Fabric 上注册后配置立即可读（`.get()` 立即可用）。
  - **无内置配置 GUI**：FCAP 仅提供配置后端（文件读写/TOML）。配置通过文件编辑（`config/chatexchange-common.toml`）。NeoForge 的 `ConfigurationScreen`/`IConfigScreenFactory` 客户端 GUI 在 Fabric 上无等价物，已移除（`environment` 仍为 `"*"`，核心逻辑服务端运行）。
  - `fabric.mod.json`：`depends` 增加 `"forgeconfigapiport": ">=26.2"`；移除 cloth-config/modmenu。
  - **结论修正**：此前"cloth-config 是服务端必需硬依赖（因其做配置 I/O）"的判断不适用于本版本（autoconfig 不可用）；改用 FCAP 后，配置 I/O 由 FCAP 承担，FCAP 为硬依赖。

---

## 0. 模组功能概述（迁移基线）

ChatExchange 是一个**服务端**模组：在本机开启一个 TCP Socket 服务（基于 `ktor-network`），把服务端的聊天/加入/离开/死亡/进度等事件以 JSON 事件的形式广播给外部连接的客户端；同时接收外部发来的消息并以系统消息形式投递给在线玩家。还带有可选的 ChatImage（CICode）图片解析、自定义语言解析、忽略机器人、广播前缀触发、`/chatexchange` 指令等功能。

**平台强相关（必须重写）的模块：**
1. 构建系统（`build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`）
2. 模组元数据（`neoforge.mods.toml` → `fabric.mod.json`）
3. 入口点与生命周期（`@Mod` / `@EventBusSubscriber` / `@SubscribeEvent`）
4. **事件系统**（NeoForge Events → Fabric API 回调 / Mixin）
5. **配置系统**（`ModConfigSpec` → 第三方库或自实现）
6. 数据持久化（`SavedData` / `SavedDataType`）
7. 模组检测（`ModList` → `FabricLoader`）
8. 国际化加载（NeoForge `I18nManager`）
9. 客户端配置界面（`IConfigScreenFactory` / `ConfigurationScreen`）

**平台无关（基本可保留）的模块：**
- `ExchangeEvents.kt`（纯 Kotlin 事件序列化 + ktor IO，仅依赖 ktor）
- `ExchangeServer.kt` 的网络收发主体逻辑（仅其中调用的部分 MC API 需核对）
- `ChatImageSupportInternal.kt`（依赖 `ChatImageCode` 库，与平台无关）
- `CustomLanguage.kt` 的语言加载主体（需替换 `I18nManager` 调用）

---

## 1. 总体架构差异速览

| 维度 | NeoForge（现状） | Fabric（目标） |
|------|------------------|----------------|
| 构建插件 | `net.neoforged.moddev` | `net.fabricmc.fabric-loom` |
| Kotlin 运行时 | `KotlinForForge`（`kotlinforforge-neoforge`） | `fabric-language-kotlin` |
| Kotlin 序列化 | `kotlin("plugin.serialization")` | 同（运行时由 fabric-language-kotlin 提供 kotlinx.serialization） |
| 模组元数据 | `src/main/templates/META-INF/neoforge.mods.toml` | `src/main/resources/fabric.mod.json` |
| 入口点 | `@Mod` 注解类 + 事件总线 | `fabric.mod.json` 的 `entrypoints.main`（`adapter: "kotlin"`）实现 `ModInitializer` |
| 事件 | `EventBusSubscriber` + `@SubscribeEvent` | Fabric API 回调 + Mixin |
| 配置 | `ModConfigSpec`（TOML + 内置 GUI） | **ForgeConfigAPIPort**（drop-in `ModConfigSpec`，TOML，**无 GUI**） |
| 配置 GUI | `IConfigScreenFactory` + `ConfigurationScreen` | 无（FCAP 仅后端；如需 GUI 另接 Cloth Config/YACL） |
| 第三方库打包 | `jarJar(...)`（带版本范围） | Loom 的 `include(...)` 配置项（嵌套 jar） |
| 模组存在性检测 | `ModList.get().isLoaded(id)` | `FabricLoader.getInstance().isModLoaded(id)` |
| Mixin 注册 | `neoforge.mods.toml` 的 `[[mixins]]` | `fabric.mod.json` 的 `"mixins"` 数组 |
| 映射 | NeoForge Mojang mappings | Loom 1.17：自动应用 Mojang 官方映射（mojmap），**无需显式声明** |
| 依赖配置 | — | Loom 1.17：使用标准 `implementation`/`api`，**已移除 `modImplementation`/`modApi`** |

---

## 2. 构建系统迁移

### 2.1 `settings.gradle.kts`

替换插件仓库与插件声明（参照 `FABRIC_TEMPLATE/settings.gradle.kts`）：

- 插件仓库增加 Fabric Maven（`https://maven.fabricmc.net/`）。
- 用 `loom_version`（来自 `gradle.properties`）声明 `net.fabricmc.fabric-loom` 插件。
- 移除 NeoForge Maven（`https://maven.neoforged.net/releases`），保留 `gradlePluginPortal()` 与 `mavenCentral()`。
- `rootProject.name = "chatexchange"`。

### 2.2 `gradle.properties`

参考模板并补齐：

```properties
# Fabric Properties（已锁定 26.2，沿用模板现成版本）
minecraft_version=26.2
loader_version=0.19.3
loom_version=1.17-SNAPSHOT
fabric_kotlin_version=1.13.13+kotlin.2.4.10

# 依赖（版本已实测可解析）
fabric_api_version=0.155.2+26.2

# Mod Properties
mod_version=0.2
maven_group=nomathexpectation.chatexchange
```

> 模板 Kotlin 为 `2.4.10`，NeoForge 现状为 `2.3.0`。`fabric-language-kotlin` 捆绑的 Kotlin 版本必须 ≥ 编译所用版本，统一用 `2.4.10`。
> 配置依赖 ForgeConfigAPIPort 经 **Modrinth maven** 拉取（坐标 `maven.modrinth:ohNO6lps:rSd3GiG8`），不占 `gradle.properties` 版本位（见 §2.3）。

### 2.3 `build.gradle.kts`

**插件块：**
```kotlin
plugins {
    id("net.fabricmc.fabric-loom")
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}
```
- 移除：`net.neoforged.moddev`、`java-library`、`idea`（按需保留）。

**依赖（Loom 1.17 实测写法）：**
```kotlin
dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    // Loom 1.17 自动应用 Mojang 官方映射（mojmap），无需显式 mappings(...)
    // 且 Loom 1.17 已移除 modImplementation/modApi，统一用 implementation/api

    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")

    // ktor（已决策：用 include 嵌套打包）
    include(implementation("io.ktor:ktor-io:2.3.13")!!)
    include(implementation("io.ktor:ktor-utils:2.3.13")!!)
    include(implementation("io.ktor:ktor-network:2.3.13")!!)

    // 配置系统：ForgeConfigAPIPort（drop-in NeoForge ModConfigSpec/ModConfig），经 Modrinth maven 拉取
    implementation("maven.modrinth:ohNO6lps:rSd3GiG8")

    // ChatImageCode（compileOnly，运行时由可选的 ChatImage 模组提供）
    compileOnly("io.github.kituin:ChatImageCode:0.12.1")
}
```

要点：
- NeoForge 的 `jarJar(implementation(...){ version{ strictly; prefer } })` → Fabric Loom 的 `include(implementation(...))`。`include` 会把依赖作为嵌套 jar 放进最终模组 jar，并由 Fabric Loader 在类路径加载。**版本范围协商（strictly/prefer）在 Fabric 无直接等价物，固定版本**；若实测与其他打包 ktor 的模组冲突，再评估 shadow+relocate 方案。
- Loom 自动添加下载 MC 与库的 maven 仓库，自定义仓库仅用于第三方依赖：Modrinth maven（FCAP，`exclusiveContent` + `includeGroup("maven.modrinth")`）、ChatImageCode 的 `https://maven.kituin.fun/releases`。
- **Loom 1.17 关键变化（实测）**：① 自动应用 Mojang 官方映射，**不要**再写 `mappings(loom.officialMojangMappings())`（会报 "Cannot use Mojang mappings in a non-obfuscated environment"）；② **已移除 `modImplementation`/`modApi`**（会报 "Configuration not found"），统一改用标准 `implementation`/`api`。
- FCAP 单个自包含 jar 提供 `ModConfigSpec`/`ModConfig`/`ConfigRegistry` + night-config（TOML I/O），用 `implementation` 即可（编译期+运行期）。**注意**：FCAP 官方 maven（`raw.githubusercontent.com/Fuzss/modresources`）Gradle 无法 GET，`jsdelivr` 亦失败，故改用 Modrinth maven。
- **移除** NeoForge 专有内容：`neoForge { ... }`、`jarJar`、`sourceSets["main"].resources { srcDir("src/generated/resources") }`（除非有数据生成）、`generateModMetadata`、`neoForge.ideSyncTask(...)`、`LOADING_CONTEXT` 相关。

**资源处理：** 保留对 `fabric.mod.json` 的版本占位符替换：
```kotlin
tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}
```

**工具链：** 保留 Java 25 / Kotlin JVM 25 目标（与现状一致）。

---

## 3. 模组元数据迁移

### 3.1 删除
- `src/main/templates/` 整个目录（NeoForge 专用元数据生成，Fabric 不需要）。
- `src/main/resources/`（NeoForge 根）下若存在 `pack.mcmeta` 等 NeoForge 特定文件需核对。

### 3.2 `src/main/resources/fabric.mod.json`（实际内容）

```jsonc
{
  "schemaVersion": 1,
  "id": "chatexchange",
  "version": "${version}",
  "name": "ChatExchange",
  "description": "A mod to share chat.",
  "authors": ["NoMathExpectation"],
  "license": "All Rights Reserved",
  "environment": "*",
  "entrypoints": {
    "main": [ { "value": "nomathexpectation.chatexchange.ChatExchange", "adapter": "kotlin" } ]
  },
  "mixins": [ "chatexchange.mixins.json" ],
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~26.2",
    "java": ">=25",
    "fabric-api": "*",
    "fabric-language-kotlin": "*",
    "forgeconfigapiport": ">=26.2"
  },
  "recommends": { "chatimage": "*" }
}
```

要点：
- 仅 `main` entrypoint（`ChatExchange : ModInitializer`，kotlin adapter）。**无 `client`/`modmenu` entrypoint**——FCAP 不提供 GUI，配置经文件编辑。
- `environment: "*"`：核心逻辑服务端运行，亦可在集成服务端/客户端环境加载。
- `forgeconfigapiport` 为硬依赖（`depends`）：配置文件 I/O 由其 vendor 的 `ModConfigSpec`/`autoconfig` 序列化器承担，服务端必需。
- `chatimage` 为可选（`recommends`），运行时经 `FabricLoader.isModLoaded` 检测。
- ktor 三件套作为 `META-INF/jars/ktor-*-jvm-2.3.13.jar` 内嵌，`fabric.mod.json` 的 `"jars"` 数组由 Loom `include` 自动生成。

---

## 4. 包名与目录重构（已锁定：重构）

现状包名 `NoMathExpectation.chatExchange.neoForged`（含大写、含 `neoForged` 平台词）。**已确认重构为全小写 `nomathexpectation.chatexchange`。**

- 统一改为 `nomathexpectation.chatexchange`（去除平台词，便于多平台共存）。
- 子包：`.mixin`（Mixin）、`.config`（配置）、`.net`（ExchangeServer/事件）、`.chatimage`（ChatImage 支持）、`.i18n`（语言）等。
- Mixin 的 `package` 字段同步更新。
- **联动影响（务必全局核对）**：Kotlin 顶层函数/扩展合成的 `*Kt` 类名（如 `ChatExchangeConfigKt`、`NeoForgeEventsKt`、`ChatExchangeDataKt`）、Kotlin `object` 的 `INSTANCE` 访问（Java Mixin 中用到）、Mixin 的 `@Shadow`/`package`、以及任何字符串形式的包名引用。建议借助 IDE 的全局重命名（Rename Package / Move）一次性完成，再编译排错。
- **Windows 大小写不敏感约束**：本机文件系统大小写不敏感，`nomathexpectation` 与 `NoMathExpectation`、`chatexchange` 与 `chatExchange` 会被折叠为同一物理目录，无法同时并存。因此小写化重命名不能在旧目录尚有内容时直接完成，必须：① 先把所有源文件迁移到目标 package（声明用小写）；② 清空并删除旧 `neoForged` 目录与旧 `NoMathExpectation` 树；③ 用两步 `git mv`（先改为临时名再改为小写）归一化目录大小写。详见 §5 与 §14。

---

## 5. 入口点与初始化迁移（✅ 已完成）

> 状态：入口点已迁移并通过编译验证（新文件 0 错误；剩余错误全部来自旧 `neoForged` 包文件）。

### 现状（迁移前 `ChatExchange.kt`）
```kotlin
@Mod(ChatExchange.ID)
@EventBusSubscriber(modid = ChatExchange.ID)
object ChatExchange {
    init { ChatExchangeConfig.register() }
    @SubscribeEvent fun onCommonSetup(event: FMLCommonSetupEvent) { ... }
}
```

### 已实现（迁移后）
- `nomathexpectation.chatexchange.ChatExchange`：`object ChatExchange : ModInitializer`，`const val MOD_ID = "chatexchange"`，`onInitialize()` 内调用 `ChatExchangeConfig.register()` + `ExchangeHooks.register()`。
- `nomathexpectation.chatexchange.ExchangeHooks`：注册 `ServerLifecycleEvents.SERVER_STARTED/STOPPING`（启停 `ExchangeServer`）与 `CommandRegistrationCallback`（调 `registerCommands`）。
- 已删除旧 NeoForge 入口 `NoMathExpectation.chatExchange.neoForged.ChatExchange`。

### 关键变更（影响后续迁移）
- **常量重命名**：`ChatExchange.ID` → `ChatExchange.MOD_ID`，全量更新（6 处引用已全部迁移）。
- **日志门面**：入口点用 slf4j（`LoggerFactory`），其余文件沿用 log4j（`LogManager`，MC 自带）。
- **`replaceModId()` 已删除**：原 `internal fun String.replaceModId()` 从未被引用，随旧入口移除。
- **包名归一化已完成**：Windows 大小写不敏感环境下，先在 package 声明用小写、旧 `neoForged` 树清空后，以两步 `git mv` 将 git 索引路径归一为全小写 `nomathexpectation/chatexchange`（见 §4 / §14.F）。

---

## 6. 事件系统迁移（核心）

NeoForge 事件无 Fabric 等价"事件总线"。迁移策略分两类：
- **A. Fabric API 提供了对应回调** → 直接用回调（首选）。
- **B. Fabric API 无对应回调** → 用 Mixin 注入（次选），并核对目标在 MC 源码中的确切签名。

> 约束：对需要核对 MC 源码的部分，本节只记录**目标类/语义**，不臆测具体方法签名，统一汇总到 §11 待查清单。

### 6.1 事件对照表

| NeoForge 事件 | 现用途 | Fabric 方案 | 说明 |
|---------------|--------|-------------|------|
| `FMLCommonSetupEvent` | 初始化日志 | `ModInitializer.onInitialize()` | 直接放入入口 |
| `ServerStartedEvent` | 启动 ExchangeServer | **Fabric API** `ServerLifecycleEvents.SERVER_STARTED` | 高可信 |
| `ServerStoppingEvent` | 停止 ExchangeServer | **Fabric API** `ServerLifecycleEvents.SERVER_STOPPING` | 高可信 |
| `RegisterCommandsEvent` | 注册 `/chatexchange` | **Fabric API** `CommandRegistrationCallback.EVENT` | 回调签名：`(dispatcher, registryAccess/buildContext, environment)`；用第 2 个参数替代原 `event.buildContext` |
| `ServerChatEvent` | 广播玩家聊天 | ✅ Mixin `ServerGamePacketListenerImpl#handleChat` HEAD cancellable；`mixinMode` 在 Fabric 上被忽略（Mixin 为唯一路径） | 见 §6.3 |
| `PlayerLoggedInEvent` | 广播加入 | ✅ Mixin `PlayerList#placeNewPlayer` RETURN | 已实现 |
| `PlayerLoggedOutEvent` | 广播离开 | ✅ Mixin `PlayerList#remove(ServerPlayer)` HEAD | 已实现 |
| `LivingDeathEvent`（仅 Player） | 广播死亡 | ✅ Mixin `ServerPlayer#die(DamageSource)` HEAD（**不挂 `LivingEntity.die`**，因 `ServerPlayer.die` 不调用 super） | 见 §11.1 |
| `AdvancementEarnEvent` | 广播进度 | ✅ Mixin `PlayerAdvancements#award` **RETURN**（复刻完成判定，**不挂 `broadcastSystemMessage` INVOKE**——其位于 lambda 内不可达） | 见 §11.1 |
| `ModConfigEvent` | 配置变更（原空实现） | 已删除（FCAP 注册后即生效，无需） | — |

### 6.2 高可信 Fabric API 回调（直接迁移）

```kotlin
object ExchangeHooks {
    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            ExchangeServer.startNewInstance(server)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            ExchangeServer.stopInstance()
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, buildContext, environment ->
            registerCommands(dispatcher, buildContext, environment)   // 保存 buildContext 为全局 registries + 注册 /chatexchange
        }
    }
}
```
> `CommandRegistrationCallback` 第 2 参数为 `CommandBuildContext`，第 3 参数为 `Commands.CommandSelection`（已实测）。
> `registerCommands` 保留原逻辑：`environment != Commands.CommandSelection.DEDICATED` 时不注册命令。

### 6.3 聊天事件：Mixin（已实现）
Fabric 无 `ServerChatEvent` 稳定等价，故聊天**唯一走 Mixin**（`ServerGamePacketListenerImpl#handleChat(ServerboundChatPacket)` HEAD + `cancel`），runServer 已验证。
- `mixinMode` 配置项**保留但在 Fabric 上被忽略**（聊天 Mixin 始终生效），仅作旧配置文件兼容；保留为兼容旧 `chatexchange-common.toml`（原默认值 `false` 已改为 `true`）。
- 26.2 实测：`ServerGamePacketListenerImpl#handleChat` 与 `ServerboundChatPacket#message()` 签名与原 NeoForge 一致，直接复用。

### 6.4 聊天消息改写逻辑
聊天 Mixin 取消原始聊天后，用 `commandBroadcastFormat`（默认 `["<", {"selector":"@s"}, "> "]`）重新广播并推送交换服务器。游戏内显示为 `<玩家> 消息`，与原版聊天格式一致；区别在于绕过了原版聊天格式化/签名链路（功能上无碍，runServer 已验证中英文正常）。涉及 API 均为 vanilla 26.2 可用：`Component.copy()`、`PlainTextContents`、`Component.literal`。

---

## 7. 配置系统迁移（最大重写量）

### 7.1 现状
`ChatExchangeConfig` 用 `ModConfigSpec.Builder` 声明每个配置项（含 `comment`/`translation`/`worldRestart`/`defineInRange`/`defineListAllowEmpty`/自定义校验 lambda），最后 `LOADING_CONTEXT.activeContainer.registerConfig(ModConfig.Type.COMMON, spec)`。访问通过 `xxx.get()`。

NeoForge 配置特性：
- 自动生成 TOML 文件；
- 内置 GUI（`ConfigurationScreen`）；
- 每项 `translation()` 提供本地化键（对应 `lang/zh_cn.json` 的 `chatexchange.config.*`）；
- `worldRestart()` 语义。

### 7.2 目标方案（已实现：ForgeConfigAPIPort）

- 依赖：`maven.modrinth:ohNO6lps:rSd3GiG8`（= ForgeConfigAPIPort 26.2.1 fabric，经 Modrinth maven）。
- FCAP 以**与 NeoForge 完全相同的包名** vendor 了 `net.neoforged.neoforge.common.ModConfigSpec` / `net.neoforged.fml.config.ModConfig`——原 `ChatExchangeConfig` 的 `ModConfigSpec.Builder` 配置代码**近乎逐行复用**（`comment`/`translation`/`worldRestart`/`defineInRange`/`defineListAllowEmpty`/自定义校验 lambda 全部保留）。
- 注册：`ConfigRegistry.INSTANCE.register(ChatExchange.MOD_ID, ModConfig.Type.COMMON, spec)`（替代 NeoForge 的 `LOADING_CONTEXT.activeContainer.registerConfig(...)`）。FCAP 在 Fabric 上注册后立即可读（`.get()` 即用）。
- 落盘：FCAP/NeoForge `ConfigTracker` 自动写 TOML 到 `config/chatexchange-common.toml`（与 NeoForge COMMON 配置路径一致，旧配置可继承）。
- **无配置 GUI**：FCAP 仅后端。原 NeoForge 客户端 GUI（`IConfigScreenFactory`/`ConfigurationScreen`）在 Fabric 无等价物，已移除。若后续需要 GUI，需另接 Cloth Config/YACL（与配置数据层分离）。
- `mixinMode` 默认值由 NeoForge 的 `false` 改为 `true`（Fabric 上 Mixin 为唯一聊天路径）；该项在 Fabric 被忽略，仅保留兼容旧配置文件。

### 7.3 客户端配置界面
已移除。原 `ChatExchangeConfigClient`（`IConfigScreenFactory`/`ConfigurationScreen`/`LOADING_CONTEXT.registerExtensionPoint`）在 Fabric 无对应；当前无 `client` entrypoint、无 Mod Menu 集成。配置经文件 `config/chatexchange-common.toml` 编辑。

### 7.4 实现形态
`ChatExchangeConfig` 保留为 Kotlin `object`，每个配置项为 `ModConfigSpec.ConfigValue<T>`（与 NeoForge 同构），业务侧继续用 `ChatExchangeConfig.xxx.get()` 访问，无需改动调用方：
```kotlin
object ChatExchangeConfig {
    private val builder = ModConfigSpec.Builder()
    val host: ModConfigSpec.ConfigValue<String> = builder.comment(...).translation(...).define("host", "0.0.0.0")
    // ... 其余项同构
    val spec: ModConfigSpec = builder.build()
    internal fun register() {
        ConfigRegistry.INSTANCE.register(ChatExchange.MOD_ID, ModConfig.Type.COMMON, spec)
    }
}
```

### 7.5 需迁移的配置项清单（逐项）
host、port、token、language、maxSafeReadBytesPerEvent、maxConnectionsPerAddress、mixinMode、ignoreBotRegex（含正则校验）、chat、joinLeave、death、advancement、broadcastTriggerPrefix（List<String>）、broadcastPrefix（JSON 校验）、commandBroadcastFormat（JSON 校验）、receiveMessageFormat（JSON 校验）。
- JSON 校验逻辑 `testJson` 依赖 `parseJsonToComponent`，迁移后该函数需能脱离 `registries` 工作（见 §8.2）。

---

## 8. 关键函数 / 数据结构迁移

> 以下函数/结构均已迁移实现并通过编译+runServer 验证。各条原标注的"待查 §11"已在实施期全部核对解决（见 §11 末尾结论与 §14.E）。

### 8.1 `parseJsonToComponent`（现位于 `Commands.kt`）
- 依赖全局 `registries: CommandBuildContext`（来自命令注册）。
- 用到：`ComponentArgument.textComponent(registries).parse(StringReader)`、`ComponentUtils.resolve(ResolutionContext, raw)`、`ResolutionContext.builder()`、`withSource(...withPermission(LevelBasedPermissionSet.GAMEMASTER))`、`withEntityOverride`、`Component.translatableEscape`。
- 迁移：保留函数；`registries` 改由 `CommandRegistrationCallback` 注入并存为全局。需核对（待查 §11）：
  - `ComponentUtils.resolve` / `ResolutionContext` 在 26.2 vanilla 是否存在及签名；
  - `LevelBasedPermissionSet.GAMEMASTER` 是否为 vanilla（疑似 1.21.6+ 新权限系统）；
  - `Component.translatableEscape` 是否 NeoForge 专有（若是，需替换为 `Component.literal` 兜底）。

### 8.2 `ExchangeServer` 内的 MC API 调用
构建伪 `CommandSourceStack` 时用到（待查 §11，均为 vanilla，需确认 26.2 签名）：
- `MinecraftServer` 的 `respawnData`（字段/访问器）、`findRespawnDimension()`、`serverVersion`、`serverModName`；
- `PlayerList.players`、`playerCount`、`maxPlayers`、`playerNamesArray`、`broadcastSystemMessage(Component, boolean)`；
- `CommandSourceStack` 构造器签名（参数顺序/数量较多，需核对）；
- `Vec3.atLowerCornerOf`、`Vec2.ZERO`。
> `respawnData` / `findRespawnDimension()` 疑似 NeoForge 添加或较新 vanilla API；若不存在，可改用主世界出生点（`server.overworld().sharedSpawnPos` 等）构造伪源，需在源码确认替代。

### 8.3 `ChatExchangeData`（SavedData）
- 现状：`SavedData` + `SavedDataType(...)` + `RecordCodecBuilder` + `UUIDUtil.CODEC_SET`，挂载方式 `dataStorage.computeIfAbsent(ChatExchangeData.TYPE)`。
- vanilla 26.2 实际：`SavedData` 无抽象方法（仅 dirty 跟踪）；用 record `SavedDataType<T>(Identifier id, Supplier<T> ctor, Codec<T> codec, DataFixTypes dataFixType)`（**4 参，非 `SavedData.Factory`**），`SavedDataStorage.computeIfAbsent(SavedDataType)` 单参，`MinecraftServer.getDataStorage()` 返回 `SavedDataStorage`。
- 迁移：`SavedDataType(...)` 补第 4 个 `DataFixTypes`（用 `SAVED_DATA_COMMAND_STORAGE` 作功能中性占位，已加注释）；`UUIDUtil.CODEC_SET` 为 vanilla 可用。逻辑平台无关，仅持久化 API 适配。

### 8.4 `CustomLanguage`（语言加载）
- 主体逻辑（继承 `Language`、重写 `getOrDefault/has/getVisualOrder/getComponent`、`Language.loadFromJson`、`MultiPackResourceManager` 读取各 namespace 的 `lang/<code>.json`）均为 **vanilla API**，可保留。
- **需替换**：`net.neoforged.fml.i18n.I18nManager.loadTranslations(lang)`（NeoForge 专有，用于加载 FML/i18n 平台翻译）。Fabric 无等价物；该调用对应的 `fmllang/`、`neolang/` 资源在 Fabric 上无意义（见 §9），可直接删除该行加载。
- `Language.inject(language)` 暂时切换全局语言再 `string` 再恢复的实现可保留（vanilla API）。
- **待查 §11**：`Language.loadFromJson(InputStream, BiConsumer, BiConsumer)` 重载、`Language.getComponent`、`getLanguageData` 是否为 vanilla 可重写方法（NeoForge 可能扩展过 `Language`）。

### 8.5 `ChatImageSupport`
- `ModList.get().isLoaded("chatimage")` → `FabricLoader.getInstance().isModLoaded("chatimage")`。
- 其余 `ChatImageSupportInternal` 不变。

---

## 9. 资源文件处理

| 路径 | 用途 | 处理 |
|------|------|------|
| `assets/chatexchange/lang/en_us.json`、`zh_cn.json` | 模组自身文案 + `chatexchange.config.*` 配置翻译键 | 保留。若不做 GUI，配置翻译键可留作未来使用 |
| `assets/chatexchange/mclang/zh_cn.json` | vanilla 字符串（供 ExchangeServer 按指定语言解析可翻译组件） | 保留（纯资源，平台无关） |
| `assets/chatexchange/fmllang/zh_cn.json` | FML/NeoForge 平台字符串 | **删除**（Fabric 无 FML） |
| `assets/chatexchange/neolang/zh_cn.json` | NeoForge 平台字符串 | **删除** |
| `src/main/templates/META-INF/neoforge.mods.toml` | NeoForge 元数据 | **删除** |
| `chatexchange.mixins.json` | Mixin 配置 | 修改（见 §10） |
| `assets/chatexchange/icon.png`（模板有，主项目无） | 模组图标 | 可选添加，并在 `fabric.mod.json` 引用 |

> 注意：`fmllang`/`neolang` 体积很大（数百行平台文案），删除可显著瘦身。删除后 `CustomLanguage.loadFrom` 中对应的两次 `loadFrom("/assets/.../fmllang|neolang/...")` 调用与 `I18nManager.loadTranslations` 一并移除。

---

## 10. Mixin 配置迁移

### 现状 `chatexchange.mixins.json`
```jsonc
{
  "required": true, "minVersion": "0.8",
  "package": "NoMathExpectation.chatExchange.neoForged.mixin",
  "compatibilityLevel": "JAVA_8",
  "refmap": "chatexchange.refmap.json",
  "mixins": [ "ServerGamePacketListenerImplMixin" ],
  "client": [], "server": [],
  "injectors": { "defaultRequire": 1 }
}
```

### 实际 `chatexchange.mixins.json`
```jsonc
{
  "required": true,
  "package": "nomathexpectation.chatexchange.mixin",
  "compatibilityLevel": "JAVA_25",
  "mixins": [
    "PlayerAdvancementsMixin",
    "PlayerListMixin",
    "ServerGamePacketListenerImplMixin",
    "ServerPlayerMixin"
  ],
  "injectors": { "defaultRequire": 1 }
}
```
变更点（相对原 NeoForge 版）：
- `package` 改为小写 `nomathexpectation.chatexchange.mixin`。
- `compatibilityLevel` → `JAVA_25`。
- **删除 `refmap` 字段**：Loom 1.17 在 `remapJar` 阶段将映射烘焙进 Mixin 字节码，production jar 无独立 refmap（`mixins.json` 无 `refmap` 字段属预期，运行期已验证）。
- 删除 `minVersion`/`client`/`server` 空数组。
- Mixin 类位于 `src/main/java/nomathexpectation/chatexchange/mixin/`（Java 源集），4 个：聊天/加入离开/死亡/进度。

### `ServerGamePacketListenerImplMixin.java`（聊天）
- `@Shadow public ServerPlayer player;`；引用经 fabric-language-kotlin 仍可 `ChatExchangeConfig.INSTANCE.getXxx().get()` 访问 Kotlin `object`。
- 顶层函数合成类随文件迁移：`ChatExchangeDataKt.getChatExchangeData`、`ChatExchangeConfigKt.startsWithBroadcastPrefix`/`removeBroadcastPrefix`、`CommandsKt.parseJsonToComponent`（原 `NeoForgeEventsKt`，现 `Commands.kt`）。
- `mixinMode` 检查已移除（Fabric 上 Mixin 为唯一路径，见 §6.3）。

---

## 11. Minecraft 源码待查清单（**已于实施期全部核对/解决**）

> 本节原为迁移前的核对清单。实施期已对照 26.2 vanilla 源码（`D:\...\Minecraft`）逐项核实并落地；以下保留原条目并标注实际结论。运行期经 `runServer` 验证（聊天/加入/离开/死亡/进度五类 Mixin 均正确触发）。

### 11.1 Mixin 注入目标
1. **聊天**：`net.minecraft.server.network.ServerGamePacketListenerImpl`
   - 方法 `handleChat(ServerboundChatPacket)`：确认存在、描述符、是否仍为单一入口（26.x 聊天链路可能变更）。
   - `net.minecraft.network.protocol.game.ServerboundChatPacket#message()`：确认消息文本访问器（可能改为 `message()` 返回 `Component`/`SignedMessage`，需核对）。
2. **玩家加入**：定位"玩家加入世界并通知"的 vanilla 入口（候选：`net.minecraft.server.players.PlayerList#placeNewPlayer(...)` 或 `ServerPlayer` 完成加入处）。确认可拿到玩家名/UUID 的时机。
3. **玩家离开**：定位断开连接入口（候选：`ServerGamePacketListenerImpl#onDisconnect(...)` 或 `PlayerList#remove(ServerPlayer)`）。
4. **玩家死亡**：**实测改用 `net.minecraft.server.level.ServerPlayer#die(DamageSource)`**。注意 `ServerPlayer.die` 完全覆写且**不调用 `super.die`**，因此挂 `LivingEntity.die`/`Player.die` 对服务端玩家**永不触发**（运行期已验证）。死亡文案取 `DamageSource#getLocalizedDeathMessage(LivingEntity)`。
5. **进度获得**：**实测改用 `PlayerAdvancements#award` RETURN**（不挂 `broadcastSystemMessage` INVOKE）。原因：`award` 内的 `broadcastSystemMessage` 调用位于 `display -> {}` **lambda** 内（合成方法），从 `award` 字节码扫描 INVOKE 会"0 targets"。改为 RETURN 后用 `cir.getReturnValueZ()`（有新授予进度）+ `getOrStartProgress(holder).isDone()` 复刻"刚完成"判定；取 `holder.value().display()` 的 `title` + `shouldAnnounceChat`。

### 11.2 配置/数据 API
6. `net.minecraft.world.level.saveddata.SavedData`：26.2 的工厂类（`SavedData.Factory`？）构造签名、`save` 写入方式。
7. `net.minecraft.world.level.storage.DimensionDataStorage`（或 `DataStorage`）：`computeIfAbsent(...)` 的重载与参数（Factory + name）。
8. `net.minecraft.core.UUIDUtil#CODEC_SET` 是否存在（用于 Set<UUID> 持久化）。

### 11.3 文本/命令 API
9. `net.minecraft.network.chat.ComponentUtils#resolve(ResolutionContext, Component)` 与 `net.minecraft.network.chat.ResolutionContext`（builder/`withSource`/`withEntityOverride`）是否存在及签名。
10. `net.minecraft.server.permissions.LevelBasedPermissionSet`（常量 `GAMEMASTER`）是否为 vanilla。
11. `net.minecraft.network.chat.Component#translatableEscape(...)` 是否 vanilla（疑似 NeoForge 扩展）。
12. `net.minecraft.commands.arguments.ComponentArgument#textComponent(CommandBuildContext)` 与 `com.mojang.brigadier.StringReader` 解析链。
13. `CommandBuildContext` 在 `CommandRegistrationCallback` 中的形参名/类型。

### 11.4 MinecraftServer 相关
14. `MinecraftServer` 的 `respawnData`（字段/Getter）、`findRespawnDimension()`：是否存在、是否 NeoForge 添加；若为 NeoForge 专有，需找 vanilla 替代（如 `overworld()` + `sharedSpawnPos`）。
15. `MinecraftServer#getServerVersion()` / `getServerModName()`：确认访问器名（代码用了属性语法 `serverVersion`/`serverModName`）。
16. `CommandSourceStack` 构造器完整参数列表（用于构造伪命令源）。
17. `PlayerList`：`players()`、`getPlayerCount()`、`getMaxPlayers()`、`getPlayerNamesArray()`（或等价）、`broadcastSystemMessage(Component, boolean)` 的确切签名。

### 11.5 Language 相关
18. `net.minecraft.locale.Language`：`loadFromJson(InputStream, BiConsumer<String,String>, BiConsumer<String,Component>)` 重载、`inject(Language)`、`getComponent(String)`、`getLanguageData()` 是否为可重写方法（NeoForge 可能放开过访问）。
19. `net.minecraft.server.packs.resources.MultiPackResourceManager` 与 `PackType.CLIENT_RESOURCES` 构造方式、`getResourceStack(Identifier)` / `namespaces`。

> **核对结论（§11.2–11.5）**：
> - `SavedData`：vanilla 26.2 用 `SavedDataType(id, ctor, codec, DataFixTypes)`（4 参，**非** `SavedData.Factory`），`computeIfAbsent(SavedDataType)` 单参；`UUIDUtil.CODEC_SET` 存在。
> - `ComponentUtils.resolve(ResolutionContext, Component)`、`ResolutionContext.builder/withSource/withEntityOverride/build`、`LevelBasedPermissionSet.GAMEMASTER`、`Component.translatableEscape`、`ComponentArgument.textComponent(CommandBuildContext)` 均为 vanilla 26.2 可用。
> - `MinecraftServer`：`getRespawnData().pos()`、`findRespawnDimension()`、`getServerVersion()`、`getServerModName()` 均为 vanilla；`CommandSourceStack` 9 参构造器签名与原代码一致。
> - `PlayerList`：`getPlayers()`（Kotlin `.players`）、`getPlayerCount()`（`.playerCount`）、`getMaxPlayers()`（`.maxPlayers`）、`getPlayerNamesArray()`（`.playerNamesArray`）、`broadcastSystemMessage(Component, boolean)` 均存在。
> - `Language`：vanilla **仅** 4 个抽象方法（`getOrDefault/has/isDefaultRightToLeft/getVisualOrder`）+ 2 参静态 `loadFromJson(InputStream, BiConsumer<String,String>)`；**无** `getComponent`/`getLanguageData`/3 参 `loadFromJson`（均 NeoForge 扩展，已移除）。`MultiPackResourceManager`/`PackType.CLIENT_RESOURCES`/`getResourceStack`/`getNamespaces` 均可用；`server.resourceManager as CloseableResourceManager` 取 `.listPacks()`。

---

## 12. 迁移执行顺序（已执行完成）

> 以下为实际执行顺序，均已落地；标注实施期调整。

1. **构建骨架**：复制 `FABRIC_TEMPLATE` 的 `settings/build/gradle.properties`，套用本项目依赖（含 ktor `include`、ChatImageCode compileOnly、FCAP via Modrinth），先把最小 `ModInitializer` 跑通。
2. **元数据 + Mixin 配置**：建 `fabric.mod.json`、改 `chatexchange.mixins.json`，移除 `templates/`。
3. **平台无关代码先行**：迁入 `ExchangeEvents.kt`、`ChatImageSupportInternal.kt`（仅调整包名/import）。
4. **配置系统**：用 **ForgeConfigAPIPort**（drop-in `ModConfigSpec`，复用原配置代码，注册改 `ConfigRegistry.INSTANCE.register`），`.get()` 业务调用无需改动。
5. **MC API 核对**：按 §11 清单在 26.2 源码逐项确认，更新 `ExchangeServer` / `ChatExchangeData` / `CustomLanguage` / `parseJsonToComponent`。
6. **事件迁移**：先接 Fabric API 回调（SERVER_STARTED/STOPPING、CommandRegistration）；再迁聊天 Mixin；最后补 join/leave/death/advancement Mixin。
7. **ChatImage 集成**：换 `FabricLoader.isModLoaded`，保留 CICode 解析。
8. **资源瘦身**：删除 `fmllang/`、`neolang/`，清理 `CustomLanguage` 对应加载代码。（**客户端 GUI 未做**——FCAP 无 GUI，如需另接。）
9. **联调验证**：`runServer` 烟雾测试（加入/聊天/死亡/进度/离开五类事件 ✅）；外部 TCP 客户端联调 ⬜ 待用户验证。

---

## 13. 风险与注意事项

- **版本对齐**：NeoForge 现状 26.1.2，Fabric 移植版锁定 26.2（已确认）。两侧 fabric-api/loom/fabric-language-kotlin/ForgeConfigAPIPort 均须匹配 26.2；26.x 处于快速迭代期，聊天/权限/SavedData/Component API 可能与文档示例有出入——务必以 §11 源码核实为准。
- **ktor 分发**：已决策用 Loom `include` 嵌套 jar。实测中若与其他打包 ktor 的模组发生类冲突，再回退评估 shadow+relocate（注意与 fabric-language-kotlin 的 Kotlin stdlib 重复打包问题）。
- **Kotlin 版本**：fabric-language-kotlin 绑定特定 Kotlin 版本，需与 `plugin.serialization`、代码使用的语法（如 `$$"""..."""` 多行字面量）兼容。
- **聊天 Mixin 稳定性**：26.x 若引入签名聊天/新消息链路，`handleChat` 入口或参数可能变化；`mixinMode` 在 Fabric 被忽略，聊天唯一走 Mixin。
- **配置依赖**：ForgeConfigAPIPort（硬依赖 `depends`）承担配置文件读写（vendor 的 `ModConfigSpec`/`autoconfig` 序列化器），**服务端必需**——缺失会导致配置 I/O 失败。配置无 GUI（FCAP 仅后端），经 `config/chatexchange-common.toml` 编辑。
- **`environment`**：无客户端 GUI，`fabric.mod.json` 的 `environment` 设为 `"*"`（核心逻辑服务端运行，集成服务端/客户端亦可加载），仅 `main` entrypoint。
- **数据迁移兼容**：原 NeoForge 存档中的 `chatexchange_data`（SavedData）文件格式与 Fabric 写出格式一致（同为 codec 序列化），玩家"关闭广播"状态可平滑继承。
- **包名重构连带**：Kotlin 顶层函数合成的 `*Kt` 类名、`object` 的 `INSTANCE`、Mixin 的 `@Shadow`/`remap` 等都随包名变化，需全局核对 import 与反射/字符串引用（已全量更新并归一化 git 索引为小写）。

---

## 14. 迁移进度与剩余工作（完成记录）

> 进度状态：✅ 已完成　⚠️ 部分完成/需进一步验证　⬜ 待办

### A. 构建与元数据 — ✅
- ✅ `settings.gradle.kts` / `gradle.properties` / `build.gradle.kts` 改造（Loom 1.17、26.2、`implementation`/`include`、**ForgeConfigAPIPort via Modrinth maven**）—— `gradlew build` 通过。
- ✅ `fabric.mod.json`（`main` entrypoint、`depends forgeconfigapiport`、`recommends chatimage`、`environment "*"`）。
- ✅ 删除 `src/main/templates/`、`assets/chatexchange/fmllang/`、`assets/chatexchange/neolang/`。

### B. 入口点 — ✅
- ✅ `nomathexpectation.chatexchange.ChatExchange`（`ModInitializer`）调用 `ChatExchangeConfig.register()` + `ExchangeHooks.register()`。
- ✅ `ExchangeHooks` 注册 `ServerLifecycleEvents.SERVER_STARTED/STOPPING`（启停 `ExchangeServer`）与 `CommandRegistrationCallback`（调 `registerCommands`）。

### C. 事件系统（§6）— ✅ 全部用 Mixin 实现，runServer 实测通过
- ✅ `RegisterCommandsEvent` → `CommandRegistrationCallback`（`Commands.kt`，保存 `buildContext` 为全局 `registries`）。
- ✅ `ServerChatEvent` → Mixin `ServerGamePacketListenerImpl#handleChat` HEAD cancellable；`mixinMode` 在 Fabric 上**被忽略**（Mixin 是唯一路径，见 C/§6.3）。
- ✅ `PlayerLoggedInEvent` → Mixin `PlayerList#placeNewPlayer` RETURN。
- ✅ `PlayerLoggedOutEvent` → Mixin `PlayerList#remove(ServerPlayer)` HEAD。
- ✅ `LivingDeathEvent` → Mixin `ServerPlayer#die(DamageSource)` HEAD（**不挂 `LivingEntity.die`**，因 `ServerPlayer.die` 不调用 super）。
- ✅ `AdvancementEarnEvent` → Mixin `PlayerAdvancements#award` **RETURN**（复刻完成判定，**不挂 `broadcastSystemMessage` INVOKE**，因其位于 lambda 内不可达）。
- ✅ 5 个 Mixin 类登记进 `chatexchange.mixins.json`。

### D. 配置系统（§7）— ✅ ForgeConfigAPIPort（**非 Cloth Config**）
- ✅ `ChatExchangeConfig` 复用 NeoForge `ModConfigSpec`（FCAP drop-in 同包名），注册改 `ConfigRegistry.INSTANCE.register(MOD_ID, COMMON, spec)`，`.get()` 访问形态不变。
- ⚠️ **无配置 GUI**：FCAP 仅后端，配置经文件 `config/chatexchange-common.toml` 编辑。原 NeoForge 客户端 GUI（`ConfigurationScreen`/`IConfigScreenFactory`）在 Fabric 无等价物，已移除；如需 GUI 需另接（Cloth Config/YACL）。
- ✅ 全部配置项迁移（含 JSON/正则校验、`testJson`）。

### E. 关键 MC API 适配（§8 / §11）— ✅ 已按 26.2 源码核对并实现
- ✅ `parseJsonToComponent`：`registries` 改可空；`ComponentUtils.resolve`/`ResolutionContext`/`LevelBasedPermissionSet.GAMEMASTER`/`Component.translatableEscape` 均为 vanilla 可用。
- ✅ `ExchangeServer`：`respawnData`/`findRespawnDimension()`/`serverVersion`/`serverModName`/`CommandSourceStack` 构造/`PlayerList` 方法/`broadcastSystemMessage(Component,boolean)` 均为 vanilla 26.2，直接复用。
- ✅ `ChatExchangeData`：vanilla 26.2 `SavedDataType` 需第 4 个 `DataFixTypes`（用 `SAVED_DATA_COMMAND_STORAGE` 占位），`computeIfAbsent(SavedDataType)` 单参。
- ✅ `CustomLanguage`：移除 `I18nManager`/`componentMap`/`getComponent`/`getLanguageData`，改 2 参 `loadFromJson`；`server.resourceManager as CloseableResourceManager` 取包列表。
- ✅ `ChatImageSupport`：`ModList` → `FabricLoader.isModLoaded`。

### F. 文件迁移与包名归一化（§4 / §10）— ✅
- ✅ 全部旧 `NoMathExpectation.chatExchange.neoForged.*` 迁至小写包 `nomathexpectation.chatexchange`（`NeoForgeEvents` 拆为 `Commands.kt` + 各 Mixin）。
- ✅ Java Mixin 全部置于 `src/main/java/nomathexpectation/chatexchange/mixin/`。
- ✅ `chatexchange.mixins.json`：`package` 小写、`compatibilityLevel JAVA_25`、删除 `refmap`、登记 5 个 Mixin。
- ✅ `ChatExchange.ID` → `ChatExchange.MOD_ID` 全量更新。
- ✅ **目录大小写归一化**：两步 `git mv` 将 git 索引路径归一为全小写（仅旧 `neoForged` 路径出现在删除/重命名源侧）。

### G. 联调验证 — ⚠️ 部分完成
- ✅ `runServer` 启动正常；**五类事件（加入/聊天/死亡/进度/离开）经实测全部正确推送**（`[CE-DEBUG/PUSH]` 日志确认）。
- ⬜ 外部 TCP 客户端连接、token 认证、连接数限制、语言切换、ChatImage 可选集成的端到端联调（用户侧验证）。

### H. 当前编译状态快照（迁移完成）
- ✅ `gradlew clean build` **BUILD SUCCESSFUL**。
- ✅ 全部 Kotlin 源文件迁移至小写包 `nomathexpectation.chatexchange`；Java Mixin 位于 `src/main/java/nomathexpectation/chatexchange/mixin/`（4 个：聊天/加入离开/死亡/进度）。
- ✅ 产物 `chatexchange-0.2.jar`：入口点 `nomathexpectation.chatexchange.ChatExchange`、Mixin 类、`fabric.mod.json`（depends `forgeconfigapiport`）、`chatexchange.mixins.json`、内嵌 ktor 三件套（`META-INF/jars/ktor-*-jvm-2.3.13.jar`）均正确。
- ✅ Mixin refmap：Loom 1.17 不再生成独立 `*.refmap.json`，而是在 `remapJar` 阶段将 intermediary 映射**直接烘焙进 Mixin 字节码**（production jar 中 mixins.json 无 `refmap` 字段属预期）。
- ⚠️ **待运行期验证**：Mixin 在运行时能否正确应用（尤其是 `PlayerAdvancements#award` 的 `broadcastSystemMessage` INVOKE 注入点与 `handleChat` 在 26.2 的实际签名），需 `runServer` 实测确认。

### I. 与原计划的偏差记录（实施期发现）
1. **配置系统：Cloth Config → ForgeConfigAPIPort**（见顶部"选型变更"）。cloth-config 26.2.155 的 autoconfig 在编译期不可访问，故改用 FCAP（drop-in ModConfigSpec，原配置代码近乎逐行复用）。配置 GUI 暂缺（FCAP 无 GUI），配置经文件编辑。
2. **Maven 源**：FCAP 官方 maven（`raw.githubusercontent.com/Fuzss/modresources`）Gradle 无法 GET，`jsdelivr` 同样失败；**改用 Modrinth maven**（`https://api.modrinth.com/maven`，坐标 `maven.modrinth:ohNO6lps:rSd3GiG8`，`exclusiveContent` + `includeGroup("maven.modrinth")`）。
3. **`SavedData`**：vanilla 26.2 `SavedDataType` 需第 4 个 `DataFixTypes` 参数（NeoForge 为 3 参）；选用 `DataFixTypes.SAVED_DATA_COMMAND_STORAGE` 作为功能中性的占位（已加注释）。
4. **`Language`**：vanilla 无 `getComponent`/`getLanguageData`/3 参 `loadFromJson`（均为 NeoForge 扩展）；`CustomLanguage` 移除 componentMap，改用 2 参 `loadFromJson(stream, biConsumer)`，并移除 `I18nManager`/fmllang/neolang。
5. **配置 I/O 时序**：`parseJsonToComponent` 的全局 `registries`（CommandBuildContext）改为可空；配置校验（`testJson`）发生在命令注册之前（registries 未就绪），此时回退为 `translatableEscape`，校验照常通过。
