package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.console.Frame
import kotlinx.io.files.Path
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import java.nio.file.Path as JavaPath

// JVM: the platform services declared in commonMain (PlatformServices.kt), with the JDK.

internal actual fun environmentVariable(name: String): String? = System.getenv(name)

internal actual fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

internal actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal actual fun gzip(data: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

internal actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

internal actual fun httpGet(url: String): ByteArray {
    val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() != 200) throw IOException("Failed to download $url (HTTP ${response.statusCode()})")
    return response.body()
}

internal actual fun unzipFirstEntry(zip: ByteArray): Pair<String, ByteArray>? = ZipInputStream(ByteArrayInputStream(zip)).use {
    val entry = it.nextEntry ?: return null
    entry.name to it.readBytes()
}

internal actual fun lastModifiedMillis(path: Path): Long? =
    path.java.takeIf { Files.exists(it) }?.let { Files.getLastModifiedTime(it).toMillis() }

internal actual fun setLastModifiedMillis(path: Path, millis: Long) {
    Files.setLastModifiedTime(path.java, FileTime.fromMillis(millis))
}

internal actual fun currentCorePlatform(): CorePlatform =
    CorePlatform.of(System.getProperty("os.name"), System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" })

internal actual fun ByteArray.rangeEquals(fromIndex: Int, toIndex: Int, other: ByteArray, otherFromIndex: Int, otherToIndex: Int): Boolean =
    java.util.Arrays.equals(this, fromIndex, toIndex, other, otherFromIndex, otherToIndex)

internal actual fun encodePng(frame: Frame): ByteArray {
    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

internal actual fun sleepMillis(millis: Long) = Thread.sleep(millis)

private val Path.java: JavaPath get() = JavaPath.of(toString())
