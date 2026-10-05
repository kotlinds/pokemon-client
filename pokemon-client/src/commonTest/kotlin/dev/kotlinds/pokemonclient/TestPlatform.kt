package dev.kotlinds.pokemonclient

/*
 * What the tests need from the platform: everything else in commonTest is common code, so another target only has to
 * provide these actuals.
 */

/** The test resource at [path] (relative to `src/commonTest/resources`, e.g. `hgss/d1.ram.sparse.gz`), or null. */
internal expect fun readTestResourceOrNull(path: String): ByteArray?

/** The test resource at [path]; fails when it is missing. */
internal fun readTestResource(path: String): ByteArray = readTestResourceOrNull(path) ?: error("missing test resource $path")

/** [data] decompressed from gzip (the sparse RAM fixtures). */
internal expect fun gunzip(data: ByteArray): ByteArray

/** The value of the environment variable [name] (`POKEMON_ROM`, `PLATINUM_ROM`), or null when unset or blank. */
internal expect fun environmentVariable(name: String): String?

/** The content of the file at [path], or null when there is no such file (the ROMs of the ROM tests). */
internal expect fun readFileOrNull(path: String): ByteArray?

/**
 * Skips the calling test (reported as skipped, not passed) unless [condition] holds: the ROM tests without their ROM.
 */
internal expect fun assumeTrue(condition: Boolean, message: String)
