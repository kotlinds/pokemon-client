package dev.kotlinds.pokemonclient

import org.junit.jupiter.api.Assumptions
import java.io.File
import java.util.zip.GZIPInputStream

// JVM: the test platform services declared in commonTest (TestPlatform.kt).

internal actual fun readTestResourceOrNull(path: String): ByteArray? =
    object {}.javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }

internal actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

internal actual fun environmentVariable(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

internal actual fun readFileOrNull(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

internal actual fun assumeTrue(condition: Boolean, message: String) = Assumptions.assumeTrue(condition, message)
