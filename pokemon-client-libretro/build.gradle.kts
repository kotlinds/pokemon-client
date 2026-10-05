/*
 * pokemon-client-libretro: runs the Pokémon client on a libretro core. It provides the ConsolePort adapter over
 * libretro-kmp (LibretroConsole), the known cores (pinned downloads checked by SHA-256), the save file conversions
 * between cores, and a headless bench to try actions on a ROM without the app (e.g. when adding a game).
 *
 * pokemon-client itself never depends on an emulator: this module is the only bridge.
 */
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven)
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
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

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(projects.pokemonClient)
    api(libs.libretro.kmp)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

// The headless bench (see Bench.kt): runs commands and typed actions on a ROM, without the app, e.g.
// POKEMON_ROM=/path/rom.nds ./gradlew -q :pokemon-client-libretro:bench -PbenchArgs="<data dir>|<out dir>|load:x.state|step:1|state"
// (BENCH_WINDOW=1 shows the game live, muted).
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the headless bench on the ROM of POKEMON_ROM."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.kotlinds.pokemonclient.libretro.bench.BenchKt"
    args = providers.gradleProperty("benchArgs").map { it.split("|") }.getOrElse(emptyList())
}
