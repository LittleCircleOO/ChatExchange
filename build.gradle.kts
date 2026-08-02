import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base {
	archivesName = "chatexchange"
}

repositories {
	// Loom adds the essential maven repositories (Fabric, Mojang, ...) automatically.
	// Custom repositories below are only for third-party mod/lib dependencies.
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
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	// Loom 1.17 auto-applies Mojang official mappings for recent MC (matches our mojmap code),
	// and uses plain `implementation`/`api` rather than the legacy `modImplementation`/`modApi` configs.

	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
	implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")

	// ktor: ship alongside the mod via Loom `include` (nested jars).
	// Kotlin stdlib / kotlinx.coroutines / kotlinx.serialization are provided at runtime by fabric-language-kotlin.
	include(implementation("io.ktor:ktor-io:2.3.13")!!)
	include(implementation("io.ktor:ktor-utils:2.3.13")!!)
	include(implementation("io.ktor:ktor-network:2.3.13")!!)

	// Config system: ForgeConfigAPIPort (drop-in NeoForge ModConfigSpec/ModConfig for Fabric).
	// maven.modrinth: forge-config-api-port (id ohNO6lps) v26.2.1 fabric (version id rSd3GiG8).
	implementation("maven.modrinth:ohNO6lps:rSd3GiG8")

	// ChatImageCode: compile-only; provided at runtime by the optional ChatImage mod.
	compileOnly("io.github.kituin:ChatImageCode:0.12.1")

	// TextPlaceholderAPI: Simplified Text Format + placeholders for chat formatting. jij-bundled.
	include(implementation("eu.pb4:placeholder-api:3.1.0-beta.1+26.2")!!)
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	repositories {
		maven {
			url = project.projectDir.resolve("repo").toURI()
		}
	}
}
