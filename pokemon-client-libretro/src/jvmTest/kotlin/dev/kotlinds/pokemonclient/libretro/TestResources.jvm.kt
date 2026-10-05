package dev.kotlinds.pokemonclient.libretro

/** JVM: test resources are on the test classpath. */
internal actual fun readTestResource(path: String): ByteArray =
    object {}.javaClass.getResourceAsStream("/$path")?.use { it.readBytes() } ?: error("missing test resource $path")
