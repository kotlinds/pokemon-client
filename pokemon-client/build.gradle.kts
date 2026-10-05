import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven)
}

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("pokemon-client")
        description.set("Kotlin Multiplatform client for Pokémon games: reads a game from its RAM and ROM into a typed model and plays it through typed, self-checking actions")
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


/**
 * Pokémon game client: reads a Pokémon game from its RAM (and ROM) and drives it through typed actions.
 *
 * Kotlin Multiplatform with the JVM target only for now: commonMain keeps the code free of java.* APIs so
 * more targets can be added when the library is published. Code that still needs the JVM (resource tables
 * generated from the decompilation, replaced by ROM reads later) lives in jvmMain.
 *
 * Hard rule: no emulator dependency here (no libretro, no JNA). The emulator is reached through the
 * ports defined in this module (see `ConsolePort`), implemented by pokemon-client-libretro or by the app.
 */
kotlin {
    jvmToolchain(21)
    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
                // ROM tests run only when POKEMON_ROM is set: make it an input so results with and without a ROM
                // aren't taken for each other from the build cache.
                inputs.property("pokemonRom", System.getenv("POKEMON_ROM").orEmpty())
                // Same for the Platinum ROM tests (PLATINUM_ROM, skipped when unset).
                inputs.property("platinumRom", System.getenv("PLATINUM_ROM").orEmpty())
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinds.rom)
            implementation(libs.kotlinds.narc)
            implementation(libs.kotlinds.compression)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
