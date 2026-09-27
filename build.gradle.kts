import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("dev.kikugie.loom-back-compat")
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
	`maven-publish`
}

version = "${property("mod.version")}+${sc.current.version}"
group = property("mod.group") as String
base.archivesName = property("mod.id") as String

// Java/Kotlin bytecode target per MC generation (matches what the loader requires).
// Compiled with the host JDK via --release cross-compilation; no toolchain provisioning needed.
val requiredJava: Int = when {
	sc.current.parsed >= "26.1" -> 25
	sc.current.parsed >= "1.20.5" -> 21
	else -> 17
}

// Loom adds the essential maven repositories (Fabric, Mojang, ...) automatically.
// Custom repositories below are only for third-party mod/lib dependencies.
repositories {
	exclusiveContent {
		forRepository {
			maven {
				name = "Modrinth"
				url = uri("https://api.modrinth.com/maven")
			}
		}
		filter {
			includeGroup("maven.modrinth")
		}
	}
	maven {
		name = "KituinMavenReleases"
		url = uri("https://maven.kituin.fun/releases")
	}
	maven {
		name = "NucleoidMaven"
		url = uri("https://maven.nucleoid.xyz")
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${sc.current.version}")
	// Obfuscated MC (<= 1.21.x): applies official Mojang mappings.
	// Non-obfuscated MC (26.x): no-op; loom-back-compat picks the right loom variant per node.
	loomx.applyMojangMappings()

	// modImplementation works on both loom variants (loom-back-compat aliases the
	// unobfuscated configurations), so all mod deps use it uniformly.
	modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:" + sc.properties["deps.fabric_api"] as String)

	// fabric-language-kotlin provides Kotlin stdlib + kotlinx (coroutines/serialization) at runtime.
	modImplementation("net.fabricmc:fabric-language-kotlin:${property("deps.fabric_kotlin")}")

	// TextPlaceholderAPI: Simplified Text Format + placeholders for chat formatting. jij-bundled.
	include(modImplementation(sc.properties["deps.placeholder_api"] as String)!!)

	// Server Translations API: per-player client-language resolution of command feedback. jij-bundled.
	// Translations load from data/chatexchange/lang/ (mirrored from assets/ at processResources).
	include(modImplementation(sc.properties["deps.server_translations"] as String)!!)

	// Config system: ForgeConfigAPIPort (per-version builds; API shape differs, see ChatExchangeConfig.kt).
	modImplementation(sc.properties["deps.fcap"] as String)

	// ChatImageCode: compile-only; provided at runtime by the optional chatimage mod.
	compileOnly(sc.properties["deps.chatimage_code"] as String)

	// ktor: pure-JVM libs, shipped as nested jars.
	include(implementation("io.ktor:ktor-io:2.3.13")!!)
	include(implementation("io.ktor:ktor-utils:2.3.13")!!)
	include(implementation("io.ktor:ktor-network:2.3.13")!!)
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.toVersion(requiredJava)
	targetCompatibility = JavaVersion.toVersion(requiredJava)
}

kotlin {
	compilerOptions {
		jvmTarget.set(JvmTarget.fromTarget(requiredJava.toString()))
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = requiredJava
}

tasks.processResources {
	val props = buildMap {
		put("version", project.version.toString())
		put("minecraft", sc.properties["mod.mc_compat"] as String)
		put("java", requiredJava.toString())
	}
	inputs.properties(props)

	filesMatching("fabric.mod.json") {
		expand(props)
	}

	filesMatching("*.mixins.json") {
		expand("java" to "JAVA_${requiredJava}")
	}

	// Mirror the lang files into data/chatexchange/lang/ so server-translations-api
	// picks them up as datapack translations (its autoload convention), while the
	// assets/ copy keeps serving the client-side (config screen) keys.
	// NOTE: node projects live in versions/<v>/, so resolve against the root project dir.
	from(rootProject.layout.projectDirectory.dir("src/main/resources/assets/chatexchange/lang")) {
		into("data/chatexchange/lang")
	}
}

tasks.register<Copy>("buildAndCollect") {
	group = "build"
	description = "Builds the mod jar (and sources) and copies them to rootProject/build/libs-collect/."

	from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
	into(rootProject.layout.buildDirectory.file("libs-collect/${project.version}"))
}

publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	repositories {
		maven {
			url = rootProject.projectDir.resolve("repo").toURI()
		}
	}
}
