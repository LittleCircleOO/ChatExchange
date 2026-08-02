# ChatExchange 格式化层迁移计划：自实现 JSON 组件 → TextPlaceholderAPI

> 目标：用 [TextPlaceholderAPI](https://github.com/Patbox/TextPlaceholderAPI)（TPAPI）的 Simplified Text Format（STF）+ 占位符/局部变量，替换当前由 `parseJsonToComponent`（`ComponentArgument` + `ComponentUtils.resolve`）驱动的两条"格式化文本并打印到聊天框"管线。
> 平台不变：仍为 Fabric / MC 26.2 / Java 25 / Loom 1.17。
> 实现范式参照 [StyledChat](https://github.com/Patbox/StyledChat) 的 `ChatStyle`（解析一次为 `TextNode` 模板，渲染期注入局部变量）。

### 已确认的选型决策（已锁定）
- **库**：TextPlaceholderAPI（mod id `placeholder-api`，`environment: *`，仅硬依赖 `fabricloader`+`minecraft`，README 明确标注 jij-able）。
- **版本**：`3.1.0-beta.1+26.2`（仓库 `26.2` 分支，mapping `26.2-pre-1`，与本模组 MC 26.2 + Java 25 兼容）。
- **分发**：Loom `include(implementation(...))` 嵌套 jar（与现有 ktor 三件套同法），`fabric.mod.json` 的 `depends` 增加 `placeholder-api`。
- **Maven 源**：Nucleoid maven `https://maven.nucleoid.xyz`（坐标 `eu.pb4:placeholder-api:3.1.0-beta.1+26.2`）；Modrinth maven（slug `placeholder-api` / id `eXts2L7r`）为备选。
- **License**：LGPLv3（与本模组分发兼容）。
- **范式**：参照 StyledChat `ChatStyle.PARSER` —— 解析一次为 `TextNode`，渲染期通过 `DYN_KEY`（`Function<String, Component>`）注入 `${...}` 局部变量。

> ⚠️ **修订（见 §12）**：§1.2 / §6.3 中"`@bc` 前缀路径取消 vanilla 并自行格式化"的设计存在**签名失同步**与**占位符状态错乱**两个缺陷，已在 **§12** 中以"观察者模式 + 移除前缀"修订取代。除被 §12 明确取代的内容外，§1–§11 其余（TPAPI 依赖、`Formatting`、命令/接收路径格式化、校验器等）保持有效。

---

## 0. 背景与目标

ChatExchange 有两条"把文本格式化为 `Component` 并送入聊天框"的路径，二者当前都走自实现的 `parseJsonToComponent`（基于 vanilla `ComponentArgument` 解析 JSON 文本组件 + `ComponentUtils.resolve` 解析选择器/可翻译）：

1. **广播格式**（`@bc` 前缀 与 `/chatexchange send` 共用配置 `commandBroadcastFormat`）
2. **接收格式**（外部 TCP 消息展示，配置 `receiveMessageFormat`）

这两条路径存在以下痛点，迁移后可一并解决：

| 痛点 | 现状 | 迁移后 |
|------|------|--------|
| 配置语法为 JSON 文本组件 | `["<", {"selector": "@s"}, "> "]` 等数组结构，冗长 | STF 字符串 `"<${player}> ${message}"`，接近自然书写 |
| 外来发送者靠手写树遍历替换 | `ExchangeServer.kt` `replaceName()` 搜索字面 `$name` 并递归替换 | `${name}` 局部变量，渲染期注入，无需遍历 |
| `registries` 全局可空 + 时序坑 | `Commands.kt:21` 的 `CommandBuildContext?` 在命令注册前未就绪，校验回退 `translatableEscape`（见 MIGRATION §I.5） | `ParserContext.Key.HOLDER_LOOKUP`，`ServerPlaceholderContext.of(...).asParserContext()` 自动注入；校验改为纯语法 |
| 选择器 `@s` 在控制台执行为空 | `commandBroadcastFormat` 用 `{"selector":"@s"}`，`/chatexchange send` 由控制台执行时 `@s` 无实体 → 名字缺失 | `${player}` 局部变量恒为 `source.displayName`，控制台/玩家皆可用 |
| 能力封闭 | 仅 JSON 组件 + vanilla 选择器 | 额外获得 STF 标签（`<red>`/`<lang:>`/`<hover:>`…）、`%player:*%`/`%server:*%` 占位符、markdown、legacy 颜色等 |

---

## 1. 现状：两条格式化管线（迁移基线）

### 1.1 共用解析函数 `parseJsonToComponent`
`Commands.kt:24-40`。依赖全局 `registries: CommandBuildContext?`（`:21`，在 `registerCommands` 的 `:43` 赋值）。逻辑：用 `ComponentArgument.textComponent(ctx).parse(...)` 解析 JSON 文本组件，再 `ComponentUtils.resolve(ResolutionContext..., raw)`。

### 1.2 管线 A：广播格式（前缀 `@bc` + 命令 `/chatexchange send`，共用 `commandBroadcastFormat`）
- 配置：`ChatExchangeConfig.kt:79-83`，默认 `["<", {"selector": "@s"}, "> "]`，校验走 `testJson`。
- **前缀路径**：`ServerGamePacketListenerImplMixin.java:51-60`。取 `commandBroadcastFormat.get()`，经 `CommandsKt.parseJsonToComponent(format.get(), player.createCommandSourceStack(), null)`，`.copy().append(newString)`，`broadcastSystemMessage(component, false)`。
- **命令路径**：`Commands.kt:51-78`（子命令为 `send`，非 `broadcast`）。`commandBroadcastFormat.get().parseJsonToComponent(context.source)`，`.copy().append(message)`，`broadcastSystemMessage(component, false)`，并向外部发 `MessageEvent`。
- 差异仅来源：前缀路径 `source` = `player.createCommandSourceStack()`（恒为玩家）；命令路径 `source` = `context.source`（可能控制台）→ `@s` 解析为空。

### 1.3 管线 B：接收格式（外部消息，`receiveMessageFormat`）
- 配置：`ChatExchangeConfig.kt:84-88`，默认 `["<", "$name", "> "]`，校验走 `testJson`。
- 实现：`ExchangeServer.kt:138-202` 的 `receiveRoutine`。构造合成 `CommandSourceStack`（`:153-163`，仅用于 `parseJsonToComponent`），`receiveMessageFormat.get().parseJsonToComponent(source)`（`:180-185`），经 `replaceName()`（`:165-177`，递归把 `PlainTextContents == "$name"` 的节点替换为 `event.from`），`.append(event.content)`（`:186`），`playerList.players.forEach { sendSystemMessage }`（`:189-191`）。

### 1.4 校验器 `testJson`
`ChatExchangeConfig.kt:90-93`，调用 `parseJsonToComponent()`（无参，registries 可能未就绪 → 回退 `translatableEscape`）。被 `commandBroadcastFormat` / `receiveMessageFormat` / `broadcastPrefix`（`:74-78`）共用。

> 注：`broadcastPrefix`（`:74`）虽定义并校验，但**全代码无 `.get()` 读取**（实际触发前缀用的是 `broadcastTriggerPrefix` 列表 + `startsWithBroadcastPrefix`/`removeBroadcastPrefix`）。属死配置，见 §7.3。

---

## 2. TextPlaceholderAPI 关键 API（迁移所需）

| TPAPI 元素 | 作用 | 迁移用途 |
|------------|------|---------|
| `NodeParser.builder()...build()`（`ParserBuilder.java`） | 组装解析器链 | 构建本模组统一 `PARSER` |
| `.simplifiedTextFormat()` / `.quickText()` | STF 标签 `<red>`/`<lang:>`/`<hover:>` 等 | 替代 JSON 文本组件 |
| `.serverPlaceholders()` | `%player:*%` / `%server:*%`（`Placeholders.SERVER_PLACEHOLDER_PARSER`） | 广播路径额外能力（接收路径无玩家，不可用） |
| `.placeholders(TagLikeParser.PLACEHOLDER_USER, DYN_KEY)` | `${...}` 局部变量（`PLACEHOLDER_USER = Format.of("${","}","")`，见 `TagLikeParser.java`） | `${player}` / `${name}` / `${message}` |
| `.staticPreParsing()`（`StaticPreParser.java`） | 把静态子树预转为 Component，模板化 | 与 StyledChat 一致，便于按模板缓存复用 |
| `ServerPlaceholderContext.of(player/source/server)`（`PlaceholderContext.java`） | 携带 player/level/HOLDER_LOOKUP | 渲染上下文来源 |
| `.asParserContext()` | 转为 `ParserContext`，含 `Key.HOLDER_LOOKUP`（`HolderLookup.Provider`） | `<lang:>`/`<item:>` 解析所需注册表 |
| `ParserContext.with(key, fn)` | 注入 `Function<String, Component>` 供 `${...}` 查表 | 注入局部变量 Map |
| `NodeParser.parseNode(String) → TextNode` + `TextNode.toComponent(ctx)` | 解析为模板 / 渲染为 `Component` | 校验 + 渲染 |
| `DynamicTextNode.key(String)` | 创建 `ParserContext.Key<Function<String, Component>>` | `DYN_KEY` |

**参照实现**：StyledChat `ChatStyle.java` 的 `PARSER` 常量、`DYN_KEY`，以及 `getChat(...)` / `getDisplayName(...)`：
```
node.toComponent(ServerPlaceholderContext.of(player).asParserContext()
        .with(DYN_KEY, Map.of("player", player.getDisplayName(), "message", message)::get));
```

---

## 3. 构建配置变更

### 3.1 `build.gradle.kts`
- `repositories` 增加Patbox/Nucleoid maven（现有 `exclusiveContent` 仅限 `maven.modrinth` 组，互不冲突）：
  ```kotlin
  maven {
      name = "NucleoidMaven"
      url = uri("https://maven.nucleoid.xyz")
  }
  ```
- `dependencies` 增加（与 ktor 同型，`include` 嵌套）：
  ```kotlin
  // TextPlaceholderAPI: STF + placeholders, jij-bundled.
  include(implementation("eu.pb4:placeholder-api:3.1.0-beta.1+26.2")!!)
  ```
- 遵循 AGENTS.md：用 `implementation`/`include`，**不要** `modImplementation`/`modApi`。

### 3.2 `fabric.mod.json`
- `depends` 增加：
  ```json
  "placeholder-api": ">=3.1.0-beta.1"
  ```
- （可选）`recommends` 无需改动；`environment` 维持 `"*"`。

---

## 4. 新增格式化核心 `Formatting.kt`（提议草案）

新建 `src/main/kotlin/nomathexpectation/chatexchange/Formatting.kt`，集中承载 TPAPI 解析器、校验与两条管线的渲染。对外提供 `@JvmStatic` 方法，供 Java Mixin 直接 `Formatting.formatBroadcast(...)` 调用（遵循 AGENTS.md 的 Java↔Kotlin 互操作约定）。

```kotlin
package nomathexpectation.chatexchange

import eu.pb4.placeholders.api.ParserContext
import eu.pb4.placeholders.api.ServerPlaceholderContext
import eu.pb4.placeholders.api.node.DynamicTextNode
import eu.pb4.placeholders.api.parsers.NodeParser
import eu.pb4.placeholders.api.parsers.TagLikeParser
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer

object Formatting {
    /** ${...} 局部变量查表键（参照 StyledChat DYN_KEY）。 */
    val DYN_KEY: ParserContext.Key<Function<String, Component>> = DynamicTextNode.key("chatexchange")

    private val PARSER: NodeParser = NodeParser.builder()
        .simplifiedTextFormat()                                      // <red>..</red>, <lang:..>, ...
        .quickText()
        .serverPlaceholders()                                        // %player:*% / %server:*%（广播路径有效）
        .placeholders(TagLikeParser.PLACEHOLDER_USER, DYN_KEY)       // ${player} / ${name} / ${message}
        .staticPreParsing()
        .build()

    /** 纯语法校验（不触发 HOLDER_LOOKUP），替代 testJson。 */
    @JvmStatic
    fun validate(input: String): Boolean = runCatching {
        PARSER.parseNode(input); true
    }.getOrDefault(false)

    /** 管线 A：玩家/命令广播。局部变量 ${player}=source 显示名, ${message}=原始消息。 */
    @JvmStatic
    fun formatBroadcast(format: String, source: CommandSourceStack, message: String): Component {
        val vars = mapOf(
            "player" to source.displayName,
            "message" to Component.literal(message),   // 字面节点，不二次解析（见 §8.1）
        )
        val ctx = ServerPlaceholderContext.of(source).asParserContext()
            .with(DYN_KEY) { key -> vars[key] ?: Component.empty() }
        return PARSER.parseNode(format).toComponent(ctx)
    }

    /** 管线 B：外部接收。局部变量 ${name}=event.from, ${message}=原始消息。无玩家实体。 */
    @JvmStatic
    fun formatReceive(format: String, server: MinecraftServer, fromName: String, message: String): Component {
        val vars = mapOf(
            "name" to Component.literal(fromName),
            "message" to Component.literal(message),
        )
        val ctx = ServerPlaceholderContext.of(server).asParserContext()
            .with(DYN_KEY) { key -> vars[key] ?: Component.empty() }
        return PARSER.parseNode(format).toComponent(ctx)
    }
}
```

**优化提示（可选）**：当前草案每次 `parseNode(format)`（格式串很短、聊天频次低，开销可忽略，且与现状 `parseJsonToComponent(format.get())` 每次调用一致）。若需"解析一次复用"，可缓存 `TextNode` 并在 format 字符串变化时失效（参照 StyledChat `ChatStyle` 在配置加载时一次性 `parseText`）。

---

## 5. 占位符语义映射（旧 → 新）

| 旧（JSON 文本组件） | 新（STF） | 说明 |
|---------------------|-----------|------|
| `["<", {"selector": "@s"}, "> "]` | `"<${player}> ${message}"` | `${player}`=`source.displayName`；前缀路径恒为玩家，命令路径可为控制台（修复 `@s` 空名） |
| `["<", "$name", "> "]` | `"<${name}> ${message}"` | 外来发送者；`$name` 字面 token → `${name}` 局部变量 |
| `{"selector": "@s"}`（仅取名字） | `${player}` 或 `%player:display_name%` | STF 无原生 vanilla 选择器；若需 `@a`/`@p` 等需自注册占位符（见 §8.4） |
| `{"translate":"key","with":[...]}` | `<lang:key:'arg1':'arg2'>` | STF 原生支持；需 `HOLDER_LOOKUP`（由 `asParserContext()` 注入） |
| `{"color":"red","text":"x"}` | `<red>x</red>` 或 `<c:#ff0000>x` | STF 风格 |

---

## 6. 三处调用点改造

### 6.1 `ChatExchangeConfig.kt`（默认值 + 校验器）
- 默认值迁移：
  - `commandBroadcastFormat`（`:81`）：`"""["<", {"selector": "@s"}, "> "]"""` → `"""<${player}> ${message}"""`
  - `receiveMessageFormat`（`:86`）：`$$"""["<", "$name", "> "]"""` → `"""<${name}> ${message}"""`
- 校验器：`:90-93` 的 `testJson` → 改为调用 `Formatting.validate(it as? String)`。
  - **附带修复 MIGRATION §I.5**：`validate` 为纯语法解析（`parseNode` 不触达 `HOLDER_LOOKUP`），不再依赖 `registries` 时序。

### 6.2 `Commands.kt`（`/chatexchange send`）
- `:57-68` 的 `commandBroadcastFormat.get().parseJsonToComponent(context.source)` →
  `Formatting.formatBroadcast(format, context.source, message)`，结果 `.copy()` 后即可（`${message}` 已内含消息体，**不再** `.append(message)`）。
- 失败回退（`:60-67`）改为：捕获异常 → 警告 + 用 `format.getDefault()` 再调 `formatBroadcast`。

### 6.3 `ServerGamePacketListenerImplMixin.java`（`@bc` 前缀）

> ⚠️ **本节已由 §12 取代**：取消 vanilla 并格式化的设计会导致签名失同步与占位符错乱。聊天路径改为观察者模式（不 cancel、不格式化、仅外发），且 `@bc` 前缀机制整体移除（显式广播改由 `/bc` 命令承担）。以下内容仅作历史记录。

- `:51-60`：
  ```java
  var format = ChatExchangeConfig.INSTANCE.getCommandBroadcastFormat();
  Component component;
  try {
      component = Formatting.formatBroadcast(format.get(), player.createCommandSourceStack(), newString);
  } catch (Exception e) {
      chatExchange$LOGGER.warn("Failed to format broadcast. Using default.", e);
      component = Formatting.formatBroadcast(format.getDefault(), player.createCommandSourceStack(), newString);
  }
  player.level().getServer().getPlayerList().broadcastSystemMessage(component, false);
  ```
  注意：`${message}` 已含 `newString`，删去原 `.append(newString)`。
- 导入 `nomathexpectation.chatexchange.Formatting`。

### 6.4 `ExchangeServer.kt`（接收路径）
- 删除 `:153-163` 的合成 `CommandSourceStack`（仅旧 `parseJsonToComponent` 需要，TPAPI 路径不需要）。
- 删除 `:165-177` 的 `replaceName()`（被 `${name}` 取代）。
- `:179-186` 改为：
  ```kotlin
  val formatted = kotlin.runCatching {
      Formatting.formatReceive(ChatExchangeConfig.receiveMessageFormat.get(), minecraftServer, event.from, event.content)
  }.getOrElse {
      logger.warn("Failed to format received message. Using default.", it)
      Formatting.formatReceive(ChatExchangeConfig.receiveMessageFormat.default, minecraftServer, event.from, event.content)
  }
  ```
- `:188-191` 的日志 + `sendSystemMessage` 不变。

### 6.5 接收路径占位符精确定义（发送人 / 消息）

接收路径（`formatReceive`）**只定义两个局部变量**，二者均经 `${...}`（`TagLikeParser.PLACEHOLDER_USER` → `DYN_KEY`）解析，均以**不二次解析的 `Component`** 注入：

| 占位符 | 数据来源 | 注入值（Kotlin） | 是否二次解析 | 缺省时 |
|--------|---------|------------------|-------------|--------|
| `${name}` | `MessageEvent.from`（外来发送者标识，如 IRC 昵称 / 桥接机器人名） | `Component.literal(event.from)` | 否 | `Component.empty()` |
| `${message}` | `MessageEvent.content`（外来消息正文，**原始**，未做 CICode 解析） | `Component.literal(event.content)` | 否 | `Component.empty()` |

> 注：CICode 解析（`tryParseCICodeFileToData`）仅作用于**外发** payload（`ExchangeServer.sendEvent`），接收路径显示的始终是原始 `event.content`——本迁移不改此点。

**逐项说明：**

- **发送人 = `${name}`（非 `%player:*%`、非 `%server:*%`）**：接收路径无 `ServerPlayer` 实体，`ServerPlaceholderContext.of(server)` 不带玩家，故 `%player:*%` 无法解析发送人。发送人只能以局部变量 `${name}` 表达——这正是 StyledChat 用 `${player}`/`${message}`（而非服务端占位符）承载"渲染期才确定的值"的同构做法。
  - 命名取 `${name}`，与旧 `$name` 一致，迁移心智成本最低；若更看重语义可改 `${sender}`（仅改 `vars` 的 key 与默认值，零结构性影响）。
  - 值为 `Component.literal`，可被周围 STF 样式包裹：`<aqua>${name}</aqua>` 生效；而 `event.from` 内部即便含 `<red>`/`${...}` 也不会被解释（安全）。
- **消息 = `${message}`（取代现状 `.append(event.content)`）**：改用 `${message}` 后，用户可在格式串中任意排布消息位置（`"[外] ${name}: ${message}"`、`">> ${message}"`），不再固定末尾追加。
  - **代价**：若格式串省略 `${message}`，消息正文将不显示（与 StyledChat `${message}` 语义一致）。默认值已包含 `${message}`，仅需在更新日志中提示。
- **未知占位符**（如 `${foo}`）→ `vars[key] ?: Component.empty()`，渲染为空，不报错。

**Wiring（`formatReceive` 内）：**
```kotlin
val vars = mapOf(
    "name" to Component.literal(fromName),      // = event.from
    "message" to Component.literal(message),    // = event.content
)
val ctx = ServerPlaceholderContext.of(server).asParserContext()
    .with(DYN_KEY) { key -> vars[key] ?: Component.empty() }
return PARSER.parseNode(format).toComponent(ctx)
```

**默认值与示例：**
- 默认 `receiveMessageFormat = "<${name}> ${message}"` → `<DiscordUser> hello`
- `<gray>[外]</gray> <aqua>${name}</aqua>: ${message}` → 带样式的发送人
- `>> ${message}` → 故意省略发送人

---

## 7. 配置迁移与破坏性变更

### 7.1 格式项语法变更（破坏性）
两个格式项从"JSON 文本组件字符串"变为"STF 字符串"。**已存在的 `config/chatexchange-common.toml` 中的旧值需用户手动迁移**（或删除让默认值重生成）。建议在 README/更新日志中给出对照表（§5）。

### 7.2 校验器语义
`testJson`（"能否解析为 JSON 组件"）→ `Formatting.validate`（"能否解析为 STF 模板"）。两者均返回布尔，配置加载行为一致；仅接受的语法不同。

### 7.3 死配置 `broadcastPrefix`（建议清理）
`broadcastPrefix`（`:74-78`）定义并经 `testJson` 校验，但**全代码无读取**。若删除 `parseJsonToComponent`，`testJson` 不复存在，`broadcastPrefix` 的校验亦需处理。建议：
- **首选**：删除 `broadcastPrefix` 配置项及其两条 lang 键（`assets/chatexchange/lang/{en_us,zh_cn}.json:20`）。
- **次选**：保留但改用 `Formatting.validate`（其默认 `"[]"` 作为 STF 即字面文本 `[]`，可解析，无害）。

### 7.4 其他配置项
`broadcastTriggerPrefix`（触发前缀列表）、`ignoreBotRegex` 等与格式化无关，**不动**。

---

## 8. 安全与正确性考量

### 8.1 消息体不二次解析（保留现有安全语义）
- 现状：三条路径均 `.append(原始字符串)`（前缀 `newString`、命令 `message`、接收 `event.content`）——纯文本，不解析，外部/玩家输入无法注入格式。
- 迁移后：`message` 以 `Component.literal(...)` 经 `DYN_KEY` 函数返回，TPAPI 将其包为 `DirectComponentNode` 直接嵌入，**不重新解析**其中的 `${...}`/`%...%`/`<...>`。安全语义不变。
- **注意**：不要把消息体放进 format 字符串再 `parseNode`，那会丢失此防护。

### 8.2 `HOLDER_LOOKUP` 与可翻译/物品标签
- `<lang:>`/`<item:>` 等需注册表。`ServerPlaceholderContext.of(source/server).asParserContext()` 自动写入 `Key.HOLDER_LOOKUP`（= `registryAccess()`）。
- 接收路径用 `of(MinecraftServer)` 即可带上 `registryAccess()`，无需真实实体。

### 8.3 控制台执行 `/chatexchange send`
- `${player}` = `source.displayName`（控制台为 "Server"），恒非空 → 修复了现状 `@s` 在控制台为空的问题。

### 8.4 选择器语义缺失（已知取舍）
- STF 无原生 vanilla 选择器（`@s`/`@a`/`@p`）。当前两处默认格式仅用 `@s`（取名字），由 `${player}` 等价替代，无影响。
- 若后续确需 `@a`/`@p` 等，通过 `Placeholders.registerServer(Identifier, handler)` 自注册 `%chatexchange:*%` 占位符（超出本次范围）。

### 8.5 CICode 集成不受影响
`ChatImageSupport` 的 CICode 解析（`tryParseCICodeFileToData`）仅作用于**外发** payload（`ExchangeServer.sendEvent` 的 URL 解析），与游戏内显示无关（显示始终追加原始字符串）。本次迁移不改外发逻辑，`${message}` 承载原始 `content`，行为一致。

---

## 9. 删除项清单
- `ExchangeServer.kt:165-177` `replaceName()`。
- `ExchangeServer.kt:153-163` 接收路径的合成 `CommandSourceStack`（迁移后无引用）。
- `Commands.kt:24-40` `parseJsonToComponent`（全量被 `Formatting` 取代；确认无其他引用后删除）。
- `Commands.kt:21,43` 全局 `registries: CommandBuildContext?` 及其赋值（`registerCommands` 的 `buildContext` 形参可保留以免改回调签名，或一并移除）。
- `ChatExchangeConfig.kt:90-93` `testJson`（被 `Formatting.validate` 取代）。
- （建议）`ChatExchangeConfig.kt:74-78` 死配置 `broadcastPrefix` 及其 lang 键。

---

## 10. 验证清单
- [ ] `gradlew build` 通过（确认 `include(implementation("eu.pb4:placeholder-api:..."))` 解析与 remap 正常，产物 `META-INF/jars/` 含 placeholder-api）。
- [ ] `runServer` 启动正常；Mixin 仍正确应用（本改动不触碰任何现有 Mixin 注入点，仅改 Mixin 体内调用）。
- [ ] `/chatexchange send <msg>`（玩家执行）：显示 `<玩家名> <msg>`；外发 `MessageEvent` 正确。
- [ ] `/chatexchange send <msg>`（控制台执行）：显示 `<Server> <msg>`（验证 `@s` 空名修复）。
- [ ] `@bc <msg>` 前缀广播：显示 `<玩家名> <msg>`；外发 `MessageEvent` 正确。
- [ ] 外部客户端发送 `MessageEvent`：显示 `<外来名> <消息>`；`replaceName` 已删除仍正确。
- [ ] 在 `commandBroadcastFormat` 中放入 `<lang:multiplayer.player.joined:'${player}'>`，验证 `HOLDER_LOOKUP` 注入生效（可翻译标签渲染）。
- [ ] 在 `receiveMessageFormat` 中放入恶意 `${player}`/`%server:*%`，验证外来消息体不触发解析（安全）。
- [ ] 配置校验：填入非法 STF（如未闭合 `<red>x`）→ `validate` 返回 false，配置回退默认。

---

## 11. 风险与回退
- **风险 1：TPAPI 26.2 分支为 `beta`/`pre` 映射**。若运行期与 MC 26.2 正式版存在细微 API/映射差异，需 `runServer` 实测（§10）；该库被 StyledChat 等主流模组使用，稳定性可期。
- **风险 2：配置破坏性变更**。旧 TOML 中 `commandBroadcastFormat`/`receiveMessageFormat` 为 JSON 数组，升级后若未迁移将解析为字面文本（不报错但显示异常）。需在更新日志显著提示。
- **风险 3：嵌套 jar 冲突**。若服务器同时装有其他 `include` 了 TPAPI 的模组，Loader 会去重取一；`depends placeholder-api` 保证最低版本，避免不兼容。
- **回退**：本次改动集中于 `Formatting.kt`（新增）+ 4 处调用点 + 配置默认值/校验器。回退即还原 `parseJsonToComponent`、`registries`、`replaceName`、`testJson` 及各默认值，无结构性地基改动。

---

## 12. 修订（观察者模式 + 移除前缀）：签名与占位符双修复

> 本节**修订并取代** §1.2 / §6.3 关于"`@bc` 前缀路径取消 vanilla 并自行格式化"的设计。该设计存在两个缺陷（详见 [`FIX_HANDLECHAT_LASTSEEN_DESYNC.md`](./FIX_HANDLECHAT_LASTSEEN_DESYNC.md)），本节一并修订。§1–§11 中除被本节明确取代的内容外，其余（TPAPI 依赖、`Formatting`、命令/接收路径格式化、校验器等）保持有效。

### 12.1 背景：原聊天路径设计的两个缺陷

1. **聊天签名（last-seen）失同步**：§6.3 的 `ci.cancel()` 跳过 vanilla `handleChat` 内的 last-seen-messages 状态机 → 客户端/服务端签名确认集合错位 → `Failed to validate message acknowledgements ... previously ignored message at index N`，玩家被踢/消息被拒。
2. **`%chatexchange:isbroadcast%` 状态错乱**：广播判定 = `(broadcastme) OR (@bc 前缀)`，但占位符上下文拿不到消息文本，只能反映 `(broadcastme)` → 非广播者发 `@bc` 时占位符=FALSE 而消息实际被广播。

**共同根因：`@bc` 前缀** —— 它既是 guard 的第二条件，也是占位符准确性的唯一缺口。移除它可同时根治两个缺陷。

### 12.2 修订方案（五点）

① **聊天路径改观察者模式**：`ServerGamePacketListenerImplMixin` 去掉 `cancellable` 与 `ci.cancel()`，只做"读消息 → `sendEvent` 外发"副作用；验证/签名/游戏内显示交回 vanilla。**→ 修复缺陷 1。**

② **移除前缀解析**：删除 `@bc` 触发逻辑（`startsWithBroadcastPrefix`/`removeBroadcastPrefix`、`broadcastTriggerPrefix` 配置）。转发条件收敛为 `chat && ¬ignored`（即 `broadcastme`）。**→ 修复缺陷 2（广播判定与占位符恒等）。**

③ **显式广播命令**：保留 `/chatexchange send <msg>`，新增顶层 `/bc <msg>`（redirect，等价）；二者走系统消息，`commandBroadcastFormat` 仍生效，不受签名约束。

④ **模式切换命令**：保留 `/chatexchange broadcastme <bool>`，新增顶层 `/bcme <true|false>`（redirect，等价）。

⑤ **新增占位符 `%chatexchange:isbroadcast%`**：`Placeholders.registerServer`，返回 `chat && ¬ignored`（= broadcastme 状态）。移除前缀后，该值与实际广播判定**完全一致**，无错乱。

⑥ **（由 ①② 衍生）去除普通玩家聊天的格式化**：聊天 Mixin 不再 `formatBroadcast`/`broadcastSystemMessage`；`commandBroadcastFormat` 此后仅 `/chatexchange send` 与 `/bc` 使用；玩家聊天游戏内显示归 vanilla/StyledChat。

### 12.3 为何两项缺陷同时消失

| 缺陷 | 根因 | 修订后 |
|------|------|--------|
| 签名失同步 | `ci.cancel()` 跳过 last-seen 状态机 | ① 不 cancel → vanilla `handleChat` 完整运行 |
| 占位符状态错乱 | `@bc` 前缀（占位符看不到消息文本） | ② 移除前缀 → 广播判定 = `broadcastme` = 占位符值 |

> 关键洞察：前缀既是 guard 的第二条件，也是占位符准确性的唯一缺口。移除前缀后，"是否广播"与"`%chatexchange:isbroadcast%`"由同一个布尔量决定，二者恒等。

### 12.4 改动清单（代码与配置）

**`ServerGamePacketListenerImplMixin`**：
- 注解去 `cancellable = true`；删 `ci.cancel()`、`commandBroadcastFormat`/`Formatting.formatBroadcast`/`broadcastSystemMessage` 整段；删前缀分支。
- 保留 HEAD 处 `sendEvent(new MessageEvent(playerName, string))`（全文，无前缀可剥）；guard 收敛为 `if (!chat || data.isIgnoredPlayer(player.getUUID())) return;`。

**`Commands.kt`**：
- 新增顶层 `Commands.literal("bc")` redirect 到 `send` 子命令；`Commands.literal("bcme")` redirect 到 `broadcastme <bool>` 子命令。
- `/chatexchange`（无参）描述去掉对 `broadcastTriggerPrefix` 的引用（原 `%s` 拼接）。

**`ChatExchangeConfig.kt`**：
- 删除 `broadcastTriggerPrefix` 配置项及 `startsWithBroadcastPrefix`/`removeBroadcastPrefix` 顶层函数。
- 删除 lang 键 `chatexchange.config.broadcastTriggerPrefix`（`en_us`/`zh_cn`）。

**`Formatting.kt` / 占位符**：
- 新增占位符注册（init 期）：`Placeholders.registerServer(Identifier("chatexchange","isbroadcast")) { ctx, _ -> if (ChatExchangeConfig.chat.get() && !getChatExchangeData(ctx.server()).isIgnoredPlayer(ctx.serverPlayer().uuid)) Component.literal("TRUE") else Component.empty() }`。
- `formatBroadcast` 保留（命令路径用）；聊天路径不再调用。

**行为变更**（详见 [`FIX_HANDLECHAT_LASTSEEN_DESYNC.md`](./FIX_HANDLECHAT_LASTSEEN_DESYNC.md) §6）：
- 玩家聊天游戏内显示归 vanilla/StyledChat；`@bc` 弃用（迁移到 `/bc`）；`broadcastme` 仅控外发，不再接管游戏内显示。

### 12.5 验证补充（在 §10 基础上）
- [ ] `broadcastme true ↔ false` 反复切换 + 多次发言：**全程无** last-seen 报错（此前必现）。
- [ ] `%chatexchange:isbroadcast%`（经 StyledChat）：广播者=TRUE、非广播者=FALSE，且与实际外发**完全一致**（无前缀干扰）。
- [ ] `/bc <msg>`、`/bcme <bool>` 与 `/chatexchange send`、`/chatexchange broadcastme` 行为等价。
- [ ] `@bc <msg>` 前缀不再触发任何外发（已废弃，仅作普通聊天经 vanilla 显示）。
- [ ] StyledChat 在场时玩家聊天由其装饰，切换 `broadcastme` 无报错、无显示冲突。
