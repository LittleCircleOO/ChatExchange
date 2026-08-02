# 修复指南:`handleChat` 取消导致的聊天签名(last-seen)失同步

> 适用:ChatExchange(Fabric / MC 26.2)。
> 缺陷:玩家在 `broadcastme` 状态间切换、或正常发言后,服务端报
> `Failed to validate message acknowledgements from <player>: Last seen update acknowledged unknown or previously ignored message at index N`,随后玩家被踢/消息被拒。
> 严重性:**功能性缺陷 + 破坏 vanilla 聊天签名状态机**,必修复。

---

## 1. 缺陷概述与报错特征

报错原文:

```
Failed to validate message acknowledgements from <player>:
Last seen update acknowledged unknown or previously ignored message at index 18
```

- `Failed to validate message acknowledgements` → vanilla **`LastSeenMessagesValidator`**(每连接一个的签名确认状态机)在校验该玩家本次包携带的 `LastSeenMessages.Update` 时失败。
- `acknowledged unknown or previously ignored message at index N` → 客户端 last-seen 集合里确认了一个**服务端 validator 不存在、或已滑出窗口被标记 ignored 的签名**。

即:**客户端"我见过这些签名"集合 ↔ 服务端"你应能确认的签名"集合失同步。**

---

## 2. 根因

`ServerGamePacketListenerImplMixin.java:30-61` 对 `handleChat(ServerboundChatPacket)` 做了 **`@Inject(... cancellable = true)` + `ci.cancel()`**(`:45`)。

vanilla `handleChat` 内部不止"广播聊天",还负责**推进/校验发送方的 last-seen-messages 状态机**(签名链、"last seen" 跟踪器)。本模组用 `ci.cancel()` 把整个方法掐断后:

1. 玩家在 `broadcastme true`(`chat` 默认开 → 默认每条都命中)期间发出的包,其 last-seen-update **从未被服务端处理** → 服务端 validator 停在旧状态。
2. 客户端**不知道包被取消**,继续按它实际收到的消息维护自己的 last-seen 集合并持续前进。
3. 两端逐步失同步。
4. 一旦该玩家某条包不再被取消(切到 `broadcastme false`),vanilla 验证真正运行 → 拿着(服务端视角下)过时/不存在的签名比对 → 报错。

**这是 1.19.1+ "不要 `cancel` 玩家聊天包处理"的经典陷阱。**

> 附:该报错本身也**确证了 `handleChat` Mixin 在 26.2 运行期确实生效**(否则不会发生 cancel → 不会失同步),间接排除了 MIGRATION §H 关于该 Mixin 未应用的担忧。

---

## 3. 影响范围(仅一个 Mixin)

排查其余 Mixin,确认**只有聊天 Mixin 取消了 vanilla**;其余均为**非取消观察者**,不受本缺陷影响,无需改动:

| Mixin | 注入点 | 是否 cancel | 行为 |
|-------|--------|-----------|------|
| `ServerGamePacketListenerImplMixin`(聊天) | `handleChat` HEAD | **是(缺陷)** | 取消 + 系统消息重发 |
| `PlayerListMixin`(加入/离开) | `placeNewPlayer` RETURN / `remove` HEAD | 否 | 仅 `sendEvent`,vanilla 照常显示 |
| `ServerPlayerMixin`(死亡) | `die` HEAD | 否 | 仅 `sendEvent` |
| `PlayerAdvancementsMixin`(进度) | `award` RETURN | 否 | 仅 `sendEvent` |

**结论:修复完全局部化于 `ServerGamePacketListenerImplMixin`。**

---

## 4. 修复原则:观察,而非拦截

核心一句话:**不要 `cancel` `handleChat`;模组只做"读取消息 → 外发 `sendEvent`"的副作用,把验证、签名状态机、游戏内显示全部交回 vanilla。**

- 外发 `MessageEvent`(模组的核心价值)是**非阻塞副作用**(`sendEvent` 内部 `launch` 协程),在 HEAD 读取 `packet.message()` 后触发即可,**无需 cancel**。
- vanilla `handleChat` 照常运行 → 签名验证、last-seen 推进、玩家聊天广播**全部保留** → 失同步不复存在。
- 游戏内显示由 vanilla(或 StyledChat 等装饰模组)负责,与本模组解耦 → 顺带消除此前与 StyledChat 的显示冲突。

---

## 5. 具体改动:`ServerGamePacketListenerImplMixin`

### 5.1 改前(缺陷形态,节选)

```java
@Inject(
        method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
        at = @At("HEAD"),
        cancellable = true   // ← 允许取消
)
private void chatExchange$onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
    var data = ChatExchangeDataKt.getChatExchangeData(player.level().getServer());
    var string = packet.message();
    if ((!ChatExchangeConfig.INSTANCE.getChat().get() || data.isIgnoredPlayer(player.getUUID()))
            && !ChatExchangeConfigKt.startsWithBroadcastPrefix(string)) {
        return;
    }

    ci.cancel();   // ← 破坏 last-seen 状态机

    var newString = ChatExchangeConfigKt.removeBroadcastPrefix(string);
    var playerName = ExchangeServer.Companion.componentToString(player.getName());
    ExchangeServer.Companion.sendEvent(new MessageEvent(playerName, newString));

    // ↓ 以下"格式化 + 系统消息重发"随 cancel 一并移除
    var format = ChatExchangeConfig.INSTANCE.getCommandBroadcastFormat();
    var source = player.createCommandSourceStack();
    Component component;
    try {
        component = Formatting.formatBroadcast(format.get(), source, newString);
    } catch (Exception e) { ... }
    player.level().getServer().getPlayerList().broadcastSystemMessage(component, false);
}
```

### 5.2 改后(观察者形态)

```java
@Inject(
        method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
        at = @At("HEAD")
        // 不再 cancellable —— 永不 cancel,vanilla 验证/签名/显示全部保留
)
private void chatExchange$onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
    var data = ChatExchangeDataKt.getChatExchangeData(player.level().getServer());
    var string = packet.message();
    if ((!ChatExchangeConfig.INSTANCE.getChat().get() || data.isIgnoredPlayer(player.getUUID()))
            && !ChatExchangeConfigKt.startsWithBroadcastPrefix(string)) {
        return;   // 不转发外发;vanilla 照常
    }

    var newString = ChatExchangeConfigKt.removeBroadcastPrefix(string);
    var playerName = ExchangeServer.Companion.componentToString(player.getName());
    ExchangeServer.Companion.sendEvent(new MessageEvent(playerName, newString));

    // 到此为止:不 cancel、不格式化、不 broadcastSystemMessage。
    // 游戏内显示由 vanilla(或 StyledChat)负责;外发由上方 sendEvent 负责。
}
```

**改动清单:**
1. 注解去掉 `cancellable = true`。
2. 删除 `ci.cancel();`。
3. 删除"取 `commandBroadcastFormat` → `Formatting.formatBroadcast` → `broadcastSystemMessage`"整段。
4. **保留**广播判定条件与 `sendEvent(MessageEvent(...))` —— 这是外发功能,且为非阻塞副作用,放在 HEAD 安全。
5. 清理随之不再使用的 import(`Formatting`、`Component`、`ChatExchangeConfig.commandBroadcastFormat` 等)。

---

## 6. 行为变化(必须显式记录)

| 维度 | 改前 | 改后 |
|------|------|------|
| 签名状态机 | 被 cancel 跳过 → 失同步报错 | vanilla 完整运行 → 正常 |
| 玩家聊天的游戏内显示 | ChatExchange 系统消息(`commandBroadcastFormat`) | **vanilla 玩家聊天**(有 StyledChat 则由其装饰) |
| `commandBroadcastFormat` 作用范围 | 聊天路径 + `/chatexchange send` | **仅 `/chatexchange send`**(聊天路径不再用它) |
| `broadcastme` 的语义 | 同时控制"外发"与"游戏内是否被接管显示" | **只控制外发**;游戏内显示恒由 vanilla 负责 |
| `@bc` 前缀(游戏内) | 被剥前缀后以系统消息显示 | vanilla 显示**含前缀的原文**(玩家打了 `@bc x` 就看到 `@bc x`) |
| `@bc` 前缀(外发) | 剥前缀 | **不变**(仍剥前缀) |
| 与 StyledChat 兼容 | 冲突(双方争聊天显示) | **自动兼容**(vanilla 聊天自然流经 StyledChat) |

> 说明:`@bc` 前缀在游戏内"显示原文"是**不可避免**的——签名机制下**无法修改已签名消息的内容**而不破坏签名。若需"游戏内也剥前缀",应改用命令触发(见 §8),而非继续用聊天前缀。

---

## 7. 可选增强:暴露广播状态给装饰模组

修复后,游戏内聊天显示权归 vanilla/StyledChat。若希望 StyledChat 对"正在广播的玩家"做差异化样式(如加 `[BC]` 标记),可**可选地**注册一个占位符(本轮非必需):

```kotlin
// 仅反映玩家广播状态(= broadcastme),无法反映 @bc 逐消息 override
Placeholders.registerServer(Identifier("chatexchange", "isbroadcast")) { ctx, _ ->
    val player = ctx.serverPlayer()
    val broadcasting = player != null
        && ChatExchangeConfig.chat.get()
        && !getChatExchangeData(ctx.server()).isIgnoredPlayer(player.uuid)
    if (broadcasting) Component.literal("TRUE") else Component.empty()
}
```

StyledChat 配置侧用 `%chatexchange:isbroadcast%`(配合 TPAPI 条件标签)即可。注意其语义是"该玩家是否广播者",非"本条消息是否被广播"。

---

## 8. 反模式(切勿尝试)

1. **不要 `ci.cancel()` `handleChat`** —— 本缺陷的根因。任何形式的整方法取消都会跳过 last-seen 状态机。
2. **不要尝试"修改已签名消息内容"(如把 `@bc x` 改成 `x` 再显示)** —— 会破坏玩家签名;vanilla 不允许服务端伪造玩家签名。剥前缀只能用于**外发 payload**(字符串),不能用于游戏内显示。
3. **不要把外发消息以"玩家签名聊天"形式重发** —— 服务端无法伪造玩家签名;只能用系统消息(命令路径正是如此)。
4. **不要为"保留 ChatExchange 游戏内格式"而重新引入 cancel** —— 这与"签名完整"二选一,不可兼得。需要统一观感时,改用 StyledChat 配置达成。

---

## 9. 验证清单

构建:`.\gradlew.bat build`(JDK 25)。运行 `runServer`,逐项验证:

- [ ] `broadcastme true` → 正常发言 → **无** "validate message acknowledgements" 报错;外部 TCP 客户端**收到**该消息;游戏内为 vanilla 玩家聊天。
- [ ] `broadcastme false` → 正常发言 → **无**报错;外部客户端**不收**;游戏内 vanilla。
- [ ] **回归重点**:在 true/false 间反复切换并多次发言 → 全程**无** last-seen 报错(此前必然复现)。
- [ ] `@bc hello` → 外部收到 `hello`(剥前缀);游戏内显示 `@bc hello`(原文);无报错。
- [ ] 安装 StyledChat → 玩家聊天由 StyledChat 装饰;`@bc`/`broadcastme` 切换**无报错**、无显示冲突。
- [ ] `/chatexchange send <msg>` → 仍走 `commandBroadcastFormat` 系统消息(本路径未改)。
- [ ] 外部接收消息 → 仍按 `receiveMessageFormat` 显示(本路径未改)。

---

## 10. 用户侧迁移说明(更新日志要点)

本次为**行为变更**,需在更新日志显著提示:

- **修复**:切换 `broadcastme` / 正常发言后不再触发"聊天消息验证失败"(签名失同步)。
- **变更**:玩家聊天的**游戏内显示不再由 ChatExchange 格式化**(`commandBroadcastFormat` 仅对 `/chatexchange send` 生效);改由 vanilla/聊天装饰模组(如 StyledChat)负责。如需自定义游戏内聊天样式,请在 StyledChat 等模组中配置。
- **变更**:`@bc` 前缀在**游戏内显示原文**(含前缀),仅外发时剥前缀。若需"游戏内也剥前缀",请改用 `/chatexchange send`。

---

## 附:为什么"不 cancel"能同时解决签名与 StyledChat 冲突

cancel `handleChat` 同时是两个问题的源头:
- **签名失同步**:cancel 跳过 last-seen 状态机 → 本缺陷。
- **StyledChat 显示冲突**:cancel 后用系统消息重发,与 StyledChat 争抢"玩家聊天的游戏内显示权"。

改为"只观察、不拦截"后:vanilla 聊天管道完整运行(签名正常),游戏内显示自然由 vanilla/StyledChat 接管(冲突消失),ChatExchange 退守其本职——**外部传输**。这正是"职责分离"的最简实现,且无需任何 StyledChat 在场检测或条件分支。
