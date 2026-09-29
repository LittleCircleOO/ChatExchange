# AGENTS.md

ChatExchange — a **server-side Fabric mod** that runs a TCP socket server (`ktor-network`) broadcasting server chat/join/leave/death/advancement events as JSON to external clients, and injects received messages back as system chat. Migrated from NeoForge; see `doc/MIGRATION_TO_FABRIC.md` for the full record.

## Build & run (stonecutter multi-version)
- Version nodes: **1.20.1, 1.21.1, 26.1.2, 26.2, 26.3** (declared in `settings.gradle.kts`; active = `stonecutter.gradle.kts`). Per-node deps live in `stonecutter.properties.toml`.
- Build ALL five: `.\gradlew.bat build :1.20.1:build :1.21.1:build :26.1.2:build :26.2:build :26.3:build`. Plain `build` only builds the **active** version at the root. Jars land in `versions/<v>/build/libs/`. No test/lint tasks — `build` is the verification target.
- **Java 25 host toolchain required** (no foojay). All versions cross-compile from JDK 25 via `--release` 17/21/25 per generation; no toolchain spec.
- Gradle wrapper 9.8. Loom comes from `dev.kikugie.loom-back-compat` (`loomx.loom_version = 1.18-SNAPSHOT`): nodes ≥26 use the non-obf loom, ≤1.21.x use `fabric-loom-remap` + `loomx.applyMojangMappings()`. `modImplementation` works uniformly on both.
- Kotlin plugin (2.4.10) must be ≤ the Kotlin bundled by `fabric-language-kotlin` (1.14.1+kotlin.2.4.20).

### Stonecutter source rules (critical)
- The **active node compiles raw `src/main` unpreprocessed** — the shared source must stay valid for the ACTIVE version (26.2). Therefore every conditional's branch that is *inactive for 26.2* (all `else` branches, `>= 26.3` if-parts) must be written pre-wrapped: `/*code *///?}`. Branches active for 26.2 stay raw. Docs: stonecutter.kikugie.dev/wiki/v2 (supports `<`, `elif`, `&&`/`||`).
- Java files cannot nest block comments — keep `//?` conditionals in Java single-level (no nested if-in-else). Kotlin nesting is fine (block comments nest).
- Whole-file overrides: a file at `versions/<v>/src/main/...` **replaces** the shared one for that node. Used for `ChatExchangeData.kt` (SavedData model differs too much: 26.x `SavedDataType`+codec / 1.20.2+ `SavedData.Factory` / 1.20.1 load-create functions).
- Loom bakes intermediary mappings into mixin bytecode at `remapJar` (no refmap file, all versions). A `mixins.json` without `refmap` is correct.

### Per-version API differences encoded in the source
- `PlayerList#placeNewPlayer`: 2 args pre-1.20.2, 3 args (CommonListenerCookie) since.
- `PlayerAdvancements#award`: `Advancement` pre-1.21, `AdvancementHolder` since. `Advancement.getDisplay()` → `display()` (1.20.2, returns Optional). `DisplayInfo.getTitle()/shouldAnnounceChat()` → `title()/announceToChat()` (26.3 record).
- ID class: `ResourceLocation` ctor (1.20.1) → `fromNamespaceAndPath` (1.21) → `Identifier` (26.x).
- FCAP 3 package eras: v5 `ConfigRegistry` + neoforge (26.x) / `fabric.api.neoforge.v4.NeoForgeConfigRegistry` + neoforge (1.20.2–1.21.x) / `api.config.v2.ForgeConfigRegistry` + forge alias (1.20.1, aliased `ForgeConfigSpec as ModConfigSpec`).
- placeholder-api: 3.x builder + `serverPlaceholders()` + `ServerPlaceholderContext` (26.x) / 2.4.x builder + `globalPlaceholders()` + `PlaceholderContext.of(source)` (1.20.2–1.21.x) / **2.1.4 has no builder** — on 1.20.1 `Formatting` merges the parser chain by hand following StyledChat's 2.1.4 approach (`TextParserV1.DEFAULT` + `Placeholders.DEFAULT_PLACEHOLDER_PARSER` + `PatternPlaceholderParser`→`DynamicNodeCompat`, the latter being a versioned file under `versions/1.20.1/`). `${...}` and `%player:*%` work on 1.20.1; only Simplified Text Format tags (`<red>` etc.) are unavailable there. `Placeholders.registerServer` is 3.x-only (2.x uses `register`).

## Dependencies
- `fabric-api`, `fabric-loader`, `fabric-language-kotlin` (provides Kotlin stdlib + kotlinx.coroutines/serialization at runtime — do not bundle these).
- **ktor** (`ktor-io`/`ktor-utils`/`ktor-network`) shipped via `include(implementation(...))` → nested jars under `META-INF/jars/`.
- **ForgeConfigAPIPort (FCAP)** is the config backend, pulled from **Modrinth maven** (`exclusiveContent` + `includeGroup("maven.modrinth")`); per-MC version ids in `stonecutter.properties.toml`. The FCAP GitHub/raw-GitHub maven cannot be fetched by Gradle; do not switch back to it.
- `ChatImageCode` is `compileOnly` (provided by the optional `chatimage` mod at runtime; one version serves all MC versions).

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
- Player-facing command feedback is sent as plain translatable components; server-translations-api (jij-bundled, lang mirrored from `assets/chatexchange/lang/` into `data/chatexchange/lang/` at `processResources`) resolves them per player's client language. The `language` config value only governs external-forwarding localization.
- External-forwarding translation sources, merged in `CustomLanguage.languageOf` (later wins): bundled `mclang/<locale>.json` → server packs' `assets/<ns>/lang/<locale>.json` (via a forced `CLIENT_RESOURCES` view — vanilla never reads datapack assets itself) → **admin overrides in `config/chatexchange_resourcepacks/`** (zip or folder, no `pack.mcmeta` needed; dir + README created at init). Snapshot taken at `SERVER_STARTED`; restart to apply.

## Package & resources
- Source package is all-lowercase `nomathexpectation.chatexchange` (Mixins in `.mixin`). The old NeoForge `NoMathExpectation.chatExchange.neoForged` package is deleted.
- Localization: `assets/chatexchange/lang/{en_us,zh_cn}.json` (mod's own keys) and `mclang/` (bundled vanilla strings for the exchange server's language resolution) are kept. `fmllang/` and `neolang/` (FML/NeoForge platform strings) were deleted — do not restore them.

## Inbound image rendering (`nomathexpectation.chatexchange.image`)
- Replaces the stopped ChatImage front-end; zero client-side mod required. Inbound CICodes (`[[CICode,url=http(s)://...|base64://...]]`, regex vendored in `CICode.kt`) are replaced by an interactive placeholder in `${message}` (`ImagePool.buildMessage` → `Formatting.formatReceive`, which takes the message as a `Component`).
- Placeholder text / action-button texts are **config values holding translation keys** (`chatexchange.image.*`); server-translations-api resolves them per player language during component codec encode, including inside hover events and the dialog embedded in the click event. Non-key config values fall back to literal text.
- Interaction: 26.x — click opens a `MultiActionDialog` (`ImageDialog`, `ClickEvent.ShowDialog(Holder.direct(...))`) with large ASCII art + preview/map-art buttons; 1.20.1/1.21.1 — click runs `/chatexchange image menu` which privately sends clickable buttons (`ImageMenu`). `/chatexchange image preview` spawns a **virtual** item frame + fake map id (`VirtualMapDisplay`, base `0x40000000`, no `map_*.dat`, auto-despawn); `/chatexchange image map` lazily creates real map art (`MapArt`, hash→mapId dedup in `ImagePool.hashToMapId`, fake dimension `chatexchange:generated` locks content, 26.3-only `drop(stack, false, Prediction.SERVER_ONLY)`).
- Version forks (all single-condition): `>= 26.1` dialog block, `>= 1.21` `MapId`/`MAP_ID` vs NBT `map` tag, `>= 26.3` drop `Prediction`, HoverEvent record vs `HoverEvent.Action.SHOW_TEXT` (old). Map palette (61 vanilla `MapColor` constants × brightness 135/180/220/255, packed `base<<2|brightness`) and Floyd-Steinberg dithering are version-independent; `MapColors.kt` references vanilla constants instead of vendoring a table.
- Outbound ChatImage compat (`ChatImageSupport*.kt`, file:///→base64) is intentionally kept for servers that still run the old mod; `recommends: chatimage` was removed from `fabric.mod.json`.

## Windows / case-sensitive gotcha
- The dev filesystem is case-insensitive. Package/paths are lowercase; to rename a directory's case in git you must use a **two-step `git mv`** via a temp name (direct case-only rename is a no-op). After any package rename, verify `git ls-files --stage` holds lowercase before committing.

## Debug logging
- `ExchangeServer.kt` has `private const val DEBUG = false` that gates `[CE-DEBUG/PUSH]` raw-event + wire-JSON logging. Set to `true` and rebuild to trace outbound events.
