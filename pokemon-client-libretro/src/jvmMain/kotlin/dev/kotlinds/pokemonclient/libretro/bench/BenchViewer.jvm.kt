package dev.kotlinds.pokemonclient.libretro.bench

import dev.kotlinds.pokemonclient.console.Frame
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** JVM: the bench's live window is a plain Swing frame. */
internal actual fun openBenchViewer(title: String): BenchViewer? = SwingBenchViewer(title)

/**
 * The bench's live window on the JVM (`BENCH_WINDOW=1`): shows every emulated frame, paced at the DS's ~60 fps so a
 * human can watch what the actions do. Never plays sound. Closed when the commands are done.
 */
private class SwingBenchViewer(title: String, private val scale: Int = 2) : BenchViewer {
    private var image: BufferedImage? = null
    private var lastFrameAt = System.nanoTime()

    private val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            image?.let { g.drawImage(it, 0, 0, it.width * scale, it.height * scale, null) }
        }
    }

    private lateinit var window: JFrame

    init {
        SwingUtilities.invokeAndWait {
            panel.preferredSize = Dimension(256 * scale, 384 * scale)
            window = JFrame(title).apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane.add(panel)
                pack()
                isVisible = true
            }
        }
    }

    override fun show(frame: Frame) {
        val next = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        next.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        image = next
        panel.repaint()
        val wait = FRAME_NANOS - (System.nanoTime() - lastFrameAt)
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        lastFrameAt = System.nanoTime()
    }

    override fun close() = SwingUtilities.invokeLater { window.dispose() }

    private companion object {
        const val FRAME_NANOS = 1_000_000_000L / 60
    }
}
