package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.console.Frame
import kotlinx.io.files.Path

/*
 * What this module needs from the platform, beyond kotlinx-io's files: every other line of the module is common code,
 * so another target only has to provide these actuals.
 */

/** The value of the environment variable [name], or null when it isn't set. */
internal expect fun environmentVariable(name: String): String?

/** SHA-1 digest of [data]. */
internal expect fun sha1(data: ByteArray): ByteArray

/** SHA-256 digest of [data]. */
internal expect fun sha256(data: ByteArray): ByteArray

/** [data] compressed with gzip. */
internal expect fun gzip(data: ByteArray): ByteArray

/** [data] decompressed from gzip. */
internal expect fun gunzip(data: ByteArray): ByteArray

/** The body of an HTTP GET on [url], following redirects; throws when the answer isn't 200. */
internal expect fun httpGet(url: String): ByteArray

/** The first entry of the zip archive [zip], as its name and its content, or null when the archive is empty. */
internal expect fun unzipFirstEntry(zip: ByteArray): Pair<String, ByteArray>?

/** Last modification time of [path] in milliseconds since the epoch, or null when it doesn't exist. */
internal expect fun lastModifiedMillis(path: Path): Long?

/** Sets the last modification time of [path] (milliseconds since the epoch). */
internal expect fun setLastModifiedMillis(path: Path, millis: Long)

/** The operating system and architecture this process runs on, as the libretro buildbot names them. */
internal expect fun currentCorePlatform(): CorePlatform

/**
 * Whether `this[fromIndex until toIndex]` equals `other[otherFromIndex until otherToIndex]`. Save states are
 * megabytes long and compared while the game is paused, so platforms use their fastest comparison.
 */
internal expect fun ByteArray.rangeEquals(fromIndex: Int, toIndex: Int, other: ByteArray, otherFromIndex: Int, otherToIndex: Int): Boolean

/** [frame] encoded as a PNG image. */
internal expect fun encodePng(frame: Frame): ByteArray

/** Blocks the calling thread for [millis] milliseconds. */
internal expect fun sleepMillis(millis: Long)
