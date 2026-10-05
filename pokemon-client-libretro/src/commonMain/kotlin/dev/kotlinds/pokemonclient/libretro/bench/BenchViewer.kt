package dev.kotlinds.pokemonclient.libretro.bench

import dev.kotlinds.pokemonclient.console.Frame

/**
 * Optional live window for the bench (`BENCH_WINDOW=1`): shows every emulated frame, paced at the DS's ~60 fps so a
 * human can watch what the actions do. Never plays sound.
 */
internal interface BenchViewer {
    /** Shows [frame], waiting so frames go by at the console's speed. */
    fun show(frame: Frame)

    /** Closes the window (the process can then exit). */
    fun close()
}

/**
 * The live window of this platform, or null when it has none: the bench then runs headless, which is all it needs
 * (its checks print text and write files; the window is only for a human to watch).
 *
 * A platform service rather than a common UI on purpose: the window is a debugging aid of a test tool, and a UI toolkit
 * such as Compose Multiplatform would become a dependency of this published library for it. The JVM opens a small
 * Swing frame; native targets are meant to run the bench headless (an actual returning null).
 */
internal expect fun openBenchViewer(title: String): BenchViewer?
