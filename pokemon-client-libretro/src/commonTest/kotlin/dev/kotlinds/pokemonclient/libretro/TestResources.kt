package dev.kotlinds.pokemonclient.libretro

/** The test resource at [path] (relative to `src/commonTest/resources`, e.g. `sound/x.state.gz`). */
internal expect fun readTestResource(path: String): ByteArray
