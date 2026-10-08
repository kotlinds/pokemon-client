package dev.kotlinds.pokemonclient.libretro

/** The test resource at [path] (relative to `src/commonTest/resources`, e.g. `sound/x.state.gz`). */
internal expect fun readTestResource(path: String): ByteArray

/**
 * Skips the calling test (reported as skipped, not passed) unless [condition] holds: the ROM tests without their ROM
 * or their core.
 */
internal expect fun assumeTrue(condition: Boolean, message: String)
