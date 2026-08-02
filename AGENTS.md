# AGENTS.md

ChatExchange — a **server-side Fabric mod** (MC 26.2) that runs a TCP socket server (`ktor-network`) broadcasting server chat/join/leave/death/advancement events as JSON to external clients, and injects received messages back as system chat. Migrated from NeoForge; see `doc/MIGRATION_TO_FABRIC.md` for the full record.

## Build & run
- Build: `./gradlew build` (Windows: `.\gradlew.bat build`). Output: `build/libs/chatexchange-<ver>.jar` (production, intermediary-remapped). No separate test/lint/typecheck tasks — `build` is the single verification target.
- Smoke test a dev server: `./gradlew runServer`. Connect a client and watch `run/logs/latest.log`.
- **Java 25 host toolchain required.** There is no foojay auto-provisioning; the host JDK must be 25+ (CI uses `microsoft` JDK 25). `options.release = 25`; no explicit Gradle `toolchain {}` spec.

## Loom 1.17 quirks (do NOT follow older Fabric conventions)
- Use plain `implementation` / `api` for **all** deps including mods — `modImplementation` / `modApi` are **removed** (cause "Configuration not found").
- Do **not** add `mappings(loom.officialMojangMappings())` — mappings are auto-applied; explicit ones error with "Cannot use Mojang mappings in a non-obfuscated environment". Source is written against Mojang (mojmap) names.
- No standalone `*.refmap.json` is emitted; Loom bakes intermediary mappings into the Mixin bytecode at `remapJar`. A `mixins.json` without a `refmap` field is correct.
- Kotlin plugin version must be ≤ the Kotlin bundled by `fabric-language-kotlin` (currently 2.4.10).

## Dependencies
- `fabric-api`, `fabric-loader`, `fabric-language-kotlin` (provides Kotlin stdlib + kotlinx.coroutines/serialization at runtime — do not bundle these).
- **ktor** (`ktor-io`/`ktor-utils`/`ktor-network`) shipped via `include(implementation(...))` → nested jars under `META-INF/jars/`.
- **ForgeConfigAPIPort (FCAP)** is the config backend, pulled from **Modrinth maven** (`exclusiveContent` + `includeGroup("maven.modrinth")`), coordinate `maven.modrinth:ohNO6lps:rSd3GiG8`. The FCAP GitHub/raw-GitHub maven cannot be fetched by Gradle; do not switch back to it.
- `ChatImageCode` is `compileOnly` (provided by the optional `chatimage` mod at runtime).

## Mixin authoring rules (hard-won)
- Mixins live in `src/main/java/nomathexpectation/chatexchange/mixin/` (Java source set, **not** `src/main/kotlin`), declared in `src/main/resources/chatexchange.mixins.json`.
- The four Mixin classes (five injection points) and their targets:
  - Chat: `ServerGamePacketListenerImpl#handleChat(ServerboundChatPacket)` HEAD cancellable. On Fabric this is the **only** chat path — the `mixinMode` config exists only for file-compat and is **ignored**.
  - Join: `PlayerList#placeNewPlayer` RETURN; Leave: `PlayerList#remove(ServerPlayer)` HEAD.
  - Death: **`ServerPlayer#die(DamageSource)`** — `ServerPlayer.die` overrides `die` without calling `super.die`, so hooking `LivingEntity.die`/`Player.die` never fires for server players.
  - Advancement: `PlayerAdvancements#award` **RETURN**, re-derive completion via `cir.getReturnValueZ() && getOrStartProgress(holder).isDone()`. **Do not** `@At(INVOKE)` into `broadcastSystemMessage` — that call is inside a `display -> {}` lambda (synthetic method) and is unreachable from `award`'s bytecode ("Scanned 0 target(s)").
- Access Kotlin `object` members from Java Mixins via `ChatExchangeConfig.INSTANCE.getXxx().get()`; top-level functions via `<File>Kt` (e.g. `CommandsKt.parseJsonToComponent`, `ChatExchangeConfigKt.startsWithBroadcastPrefix`, `ChatExchangeDataKt.getChatExchangeData`).

## Config (FCAP, file-based, no GUI)
- `ChatExchangeConfig` is a Kotlin `object` using NeoForge `ModConfigSpec` (FCAP vendors `net.neoforged.neoforge.common.ModConfigSpec` / `net.neoforged.fml.config.ModConfig` at the same packages — original code reused near-verbatim). Register via `ConfigRegistry.INSTANCE.register(MOD_ID, ModConfig.Type.COMMON, spec)`.
- No in-game config GUI exists on Fabric. Values are read with `.get()`; defaults via `ConfigValue.getDefault()`. File: `config/chatexchange-common.toml`.

## Package & resources
- Source package is all-lowercase `nomathexpectation.chatexchange` (Mixins in `.mixin`). The old NeoForge `NoMathExpectation.chatExchange.neoForged` package is deleted.
- Localization: `assets/chatexchange/lang/{en_us,zh_cn}.json` (mod's own keys) and `mclang/` (bundled vanilla strings for the exchange server's language resolution) are kept. `fmllang/` and `neolang/` (FML/NeoForge platform strings) were deleted — do not restore them.
- Local references for MC internals: Minecraft 26.2 source at `D:\Users\LZY\Documents\GitHub\Minecraft` (branch `26.2.x`), NeoForge reference at `D:\Users\LZY\Documents\GitHub\NeoForge`.

## Windows / case-sensitive gotcha
- The dev filesystem is case-insensitive. Package/paths are lowercase; to rename a directory's case in git you must use a **two-step `git mv`** via a temp name (direct case-only rename is a no-op). After any package rename, verify `git ls-files --stage` holds lowercase before committing.

## Debug logging
- `ExchangeServer.kt` has `private const val DEBUG = false` that gates `[CE-DEBUG/PUSH]` raw-event + wire-JSON logging. Set to `true` and rebuild to trace outbound events.
