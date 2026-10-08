/*
 * pokemon-client-libretro: runs the Pokémon client on a libretro core. It provides the ConsolePort adapter over
 * libretro-kmp (LibretroConsole), the known cores (pinned downloads checked by SHA-256), the save file conversions
 * between cores, and a headless bench to try actions on a ROM without the app (e.g. when adding a game).
 *
 * pokemon-client itself never depends on an emulator: this module is the only bridge.
 */
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven)
}

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("pokemon-client-libretro")
        description.set("Runs pokemon-client on a libretro core (DeSmuME, melonDS) through libretro-kmp, with a headless bench")
        url.set(project.ext.get("url")?.toString())
        licenses {
            license {
                name.set(project.ext.get("license.name")?.toString())
                url.set(project.ext.get("license.url")?.toString())
            }
        }
        developers {
            developer {
                id.set(project.ext.get("developer.id")?.toString())
                name.set(project.ext.get("developer.name")?.toString())
                email.set(project.ext.get("developer.email")?.toString())
                url.set(project.ext.get("developer.url")?.toString())
            }
        }
        scm {
            url.set(project.ext.get("scm.url")?.toString())
        }
    }
}

/*
 * Kotlin Multiplatform, with the JVM target only for now: the code lives in commonMain, and jvmMain only holds the
 * `actual`s of the platform services declared in commonMain (files metadata, hashes, gzip, HTTP, PNG, the bench's
 * window), so another target only needs its own actuals.
 */
kotlin {
    jvmToolchain(21)
    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
                // The ROM test (GettingStartedTest) runs only when POKEMON_ROM is set and LIBRETRO_CORES holds the core:
                // make them inputs so results with and without them aren't taken for each other from the build cache.
                inputs.property("pokemonRom", System.getenv("POKEMON_ROM").orEmpty())
                inputs.property("libretroCores", System.getenv("LIBRETRO_CORES").orEmpty())
                // GettingStartedTest checks that the guide shows its code (its `// doc:` blocks, comments included).
                inputs.file(rootProject.file("docs/getting-started.md"))
                inputs.file("src/commonTest/kotlin/dev/kotlinds/pokemonclient/libretro/GettingStartedTest.kt")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.pokemonClient)
            api(libs.libretro.kmp)
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// The headless bench (see Bench.kt, started by BenchMain.kt on the JVM): runs commands and typed actions on a ROM,
// without the app, e.g.
// POKEMON_ROM=/path/rom.nds ./gradlew -q :pokemon-client-libretro:bench -PbenchArgs="<data dir>|<out dir>|load:x.state|step:1|state"
// (BENCH_WINDOW=1 shows the game live, muted).
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the headless bench on the ROM of POKEMON_ROM."
    val main = kotlin.jvm().compilations["main"]
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass = "dev.kotlinds.pokemonclient.libretro.bench.BenchMainKt"
    args = providers.gradleProperty("benchArgs").map { it.split("|") }.getOrElse(emptyList())
}
