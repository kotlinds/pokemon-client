package dev.kotlinds.pokemonclient.libretro

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random

/** Small file helpers over kotlinx-io, shared by the module (saves, cores, the bench, fixtures). */
internal object Files {

    fun exists(path: Path): Boolean = SystemFileSystem.exists(path)

    fun isDirectory(path: Path): Boolean = SystemFileSystem.metadataOrNull(path)?.isDirectory == true

    fun size(path: Path): Long = SystemFileSystem.metadataOrNull(path)?.size ?: -1L

    /** Creates [path] and its parents if needed, and returns it. */
    fun createDirectories(path: Path): Path = path.also { SystemFileSystem.createDirectories(it) }

    fun readBytes(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    fun writeBytes(path: Path, bytes: ByteArray) = SystemFileSystem.sink(path).buffered().use { it.write(bytes) }

    /** Reads [size] bytes of [path] from [offset], without reading what comes before into memory. */
    fun readRange(path: Path, offset: Long, size: Int): ByteArray = SystemFileSystem.source(path).buffered().use {
        it.skip(offset)
        it.readByteArray(size)
    }

    /** Copies the file [from] to [to], replacing it, and returns [to]. */
    fun copy(from: Path, to: Path): Path = to.also {
        SystemFileSystem.source(from).buffered().use { source -> SystemFileSystem.sink(to).buffered().use { it.transferFrom(source) } }
    }

    /** Writes [bytes] next to [target] first, then moves them in place: a crash never leaves a half-written file. */
    fun writeAtomically(target: Path, bytes: ByteArray) {
        val temporary = Path(target.parent ?: Path("."), "${target.name}.part")
        writeBytes(temporary, bytes)
        SystemFileSystem.atomicMove(temporary, target)
    }

    /** Copies the directory [from] into [to], recursively. */
    fun copyDirectory(from: Path, to: Path) {
        createDirectories(to)
        SystemFileSystem.list(from).forEach { child ->
            val target = Path(to, child.name)
            if (isDirectory(child)) copyDirectory(child, target) else copy(child, target)
        }
    }

    /** Deletes [path], with everything inside when it is a directory. */
    fun deleteRecursively(path: Path) {
        if (!exists(path)) return
        if (isDirectory(path)) SystemFileSystem.list(path).forEach(::deleteRecursively)
        SystemFileSystem.delete(path)
    }

    /** A new, empty directory in the system's temporary directory, its name starting with [prefix]. */
    fun createTemporaryDirectory(prefix: String): Path {
        while (true) {
            val candidate = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong().toString(36)}")
            if (!exists(candidate)) return createDirectories(candidate)
        }
    }

    /** The absolute, normalized form of the existing [path]. */
    fun absolute(path: Path): Path = SystemFileSystem.resolve(path)
}
