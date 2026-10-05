plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.maven) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

allprojects {
    group = "dev.kotlinds"
    // -SNAPSHOT between releases: consumed through mavenLocal (`./gradlew publishToMavenLocal`) while developing.
    version = "0.1.1-SNAPSHOT"
    project.ext.set("url", "https://github.com/kotlinds/pokemon-client")
    project.ext.set("license.name", "Apache 2.0")
    project.ext.set("license.url", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    project.ext.set("developer.id", "nathanfallet")
    project.ext.set("developer.name", "Nathan Fallet")
    project.ext.set("developer.email", "contact@nathanfallet.me")
    project.ext.set("developer.url", "https://www.nathanfallet.me")
    project.ext.set("scm.url", "https://github.com/kotlinds/pokemon-client.git")

    repositories {
        mavenCentral()
    }
}

dependencies {
    dokka(projects.pokemonClient)
    dokka(projects.pokemonClientLibretro)

    kover(projects.pokemonClient)
    kover(projects.pokemonClientLibretro)
}
