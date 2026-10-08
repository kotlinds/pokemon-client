package dev.kotlinds.pokemonclient.libretro

import org.junit.jupiter.api.Assumptions

/** JVM: test resources are on the test classpath. */
internal actual fun readTestResource(path: String): ByteArray =
    object {}.javaClass.getResourceAsStream("/$path")?.use { it.readBytes() } ?: error("missing test resource $path")

internal actual fun assumeTrue(condition: Boolean, message: String) = Assumptions.assumeTrue(condition, message)
