package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.libretro.sound.SavestateSoundSplicer
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * A libretro core we know how to run: where to download it and which options to give it.
 *
 * Adding another emulator (e.g. mGBA for GBA Pokémon games) is mostly adding an entry here.
 */
enum class LibretroCoreSpec(
    /** File name on the libretro buildbot, without the platform extension (e.g. "melonds_libretro"). */
    val buildbotName: String,
    /** Core options answered through RETRO_ENVIRONMENT_GET_VARIABLE; missing keys use the core's defaults. */
    val options: Map<String, String>,
    /**
     * SHA-256 of the exact core build we tested, per platform. The buildbot only serves "latest", which can
     * change at any time: a different build may behave differently or reject our save states, so we refuse
     * to run an unknown build instead of failing in subtle ways. Platforms without a pinned hash aren't checked.
     */
    private val pinnedSha256: Map<String, String> = emptyMap(),
    /** How the core stores the in-game save on disk (the app keeps `<rom>.sav` as the canonical file). */
    val saveFormat: SaveFormat = SaveFormat.RAW,
) {
    /**
     * melonDS 0.9.3 (libretro port). We don't use the newer "melonDS DS" core because its ARM JIT
     * installs signal handlers that conflict with the JVM's own, which crashes the process.
     * The interpreter is plenty fast: ~4x real time on an Apple Silicon Mac.
     */
    MELONDS(
        buildbotName = "melonds_libretro",
        options = mapOf(
            "melonds_console_mode" to "DS",
            "melonds_boot_directly" to "enabled", // skip the DS firmware menu (FreeBIOS can't show it anyway)
            "melonds_threaded_renderer" to "disabled", // frames must be complete when retro_run() returns
            "melonds_screen_layout" to "Top/Bottom", // 256x384: top screen above bottom screen
            "melonds_touch_mode" to "Touch", // the touch screen is driven by the libretro pointer device
            "melonds_language" to "English",
        ),
        pinnedSha256 = mapOf("apple/osx/arm64" to "1f0139c31bc5388222dcca8f70490aed2e290c31bf65ea4b03f4c362df585900"),
    ),

    /**
     * DeSmuME 0.9.12 (libretro port). Unlike melonDS 0.9.3 it can load an existing in-game save without BIOS /
     * firmware dumps: with a save present, HeartGold's main menu turns DS wireless on, which melonDS 0.9.3's
     * generated firmware can't do ("A communication error has occurred"). Interpreter only (its JIT would fight
     * the JVM's). Touch needs `desmume_pointer_mouse`; saves are `.dsv` files (see [SaveFormat.DESMUME]).
     */
    DESMUME(
        buildbotName = "desmume_libretro",
        options = mapOf(
            "desmume_cpu_mode" to "interpreter",
            "desmume_screens_layout" to "top/bottom",
            "desmume_screens_gap" to "0",
            "desmume_pointer_mouse" to "enabled",
            "desmume_pointer_type" to "touch",
            "desmume_firmware_language" to "English",
            "desmume_frameskip" to "0",
            "desmume_internal_resolution" to "256x192",
        ),
        pinnedSha256 = mapOf("apple/osx/arm64" to "33845ef6ffc0ca2fc5203e58a1f1dc608ba90e8e9b140de109d52b14ccec3c17"),
        saveFormat = SaveFormat.DESMUME,
    ),
    ;

    companion object {
        /**
         * Picks the core for a ROM, from its file extension; [preferred] (a config option) chooses among the cores
         * able to run it.
         */
        fun forRom(rom: Path, preferred: String? = null): LibretroCoreSpec = when (rom.name.substringAfterLast('.').lowercase()) {
            "nds" -> entries.firstOrNull { it.name.equals(preferred, ignoreCase = true) } ?: DESMUME
            else -> throw IllegalArgumentException("No emulator core configured for $rom")
        }
    }

    /** How this core's save states are read and spliced by music during pauses. */
    val soundSplicer: SavestateSoundSplicer
        get() = when (this) {
            MELONDS -> SavestateSoundSplicer.MELONDS
            DESMUME -> SavestateSoundSplicer.DESMUME
        }

    /**
     * A second copy of the core file in [directory] (`<name>_shadow.<ext>`), for a second, independent instance in
     * the same process ([ConsoleRole.SHADOW]): loading the same path twice gives the same library, so the same
     * emulator globals. Refreshed whenever it differs from the main file. The two images stay apart because
     * libretro-kmp (0.1.2+) opens every core with `RTLD_NOW | RTLD_LOCAL` on POSIX systems, and Windows keeps DLLs
     * loaded from different files apart anyway.
     */
    fun resolveShadow(directory: Path): Path {
        val main = resolve(directory)
        val platform = currentCorePlatform()
        val copy = Path(directory, "${buildbotName}_shadow.${platform.extension}")
        val mainBytes = Files.readBytes(main)
        if (!Files.exists(copy) || !sha256(Files.readBytes(copy)).contentEquals(sha256(mainBytes))) Files.writeAtomically(copy, mainBytes)
        return copy.also { verify(it, platform) }
    }

    /** Downloads the core from the libretro buildbot into [directory] if it isn't there yet. */
    fun resolve(directory: Path): Path {
        val platform = currentCorePlatform()
        val target = Path(directory, "$buildbotName.${platform.extension}")
        if (Files.exists(target)) return target.also { verify(it, platform) }

        Files.createDirectories(directory)
        val url = "https://buildbot.libretro.com/nightly/${platform.buildbotPath}/latest/$buildbotName.${platform.extension}.zip"
        println("Downloading libretro core from $url")
        // The zip contains a single file: the core library.
        val (_, core) = unzipFirstEntry(httpGet(url)) ?: throw IOException("Empty archive at $url")
        verify(core, target, platform)
        Files.writeAtomically(target, core)
        return target
    }

    /** Checks the core file against the pinned hash of its platform (see [pinnedSha256]). */
    private fun verify(file: Path, platform: CorePlatform) = verify(Files.readBytes(file), file, platform)

    /** Checks [bytes], the content of [file], against the pinned hash of its platform (see [pinnedSha256]). */
    private fun verify(bytes: ByteArray, file: Path, platform: CorePlatform) {
        val expected = pinnedSha256[platform.buildbotPath] ?: return
        val actual = sha256(bytes).joinToString("") { "%02x".fmt(it) }
        if (actual != expected) {
            throw IOException(
                "Unexpected $buildbotName build at $file (sha256 $actual, expected $expected). " +
                    "The buildbot's latest build changed: test it, then update the pinned hash in LibretroCoreSpec.",
            )
        }
    }
}
