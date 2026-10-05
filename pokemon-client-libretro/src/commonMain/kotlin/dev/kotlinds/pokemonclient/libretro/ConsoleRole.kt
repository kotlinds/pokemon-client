package dev.kotlinds.pokemonclient.libretro

import kotlinx.io.files.Path

/** What a [LibretroConsole] is for. */
enum class ConsoleRole {
    /** The game: uses the data directory, loads and writes back the in-game save. */
    MAIN,

    /**
     * A second, throwaway instance of the core, next to the main one (music during pauses: it plays the paused game's
     * music). It loads its own copy of the core file ([LibretroCoreSpec.resolveShadow]), works in a temporary copy of
     * `system/` and of the in-game save (deleted when closed), and never writes the game's files.
     */
    SHADOW;

    internal companion object {
        /** A temporary directory with copies of `system/` and `saves/<rom>.sav` of [dataDirectory]. */
        fun throwawayDirectory(dataDirectory: Path, romBase: String): Path {
            val directory = Files.createTemporaryDirectory("ai-plays-pokemon-shadow")
            val system = Path(dataDirectory, "system")
            if (Files.isDirectory(system)) Files.copyDirectory(system, Path(directory, "system"))
            val save = Path(dataDirectory, "saves", "$romBase.sav")
            if (Files.exists(save)) Files.copy(save, Path(Files.createDirectories(Path(directory, "saves")), "$romBase.sav"))
            return directory
        }
    }
}
