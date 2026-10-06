package dev.kotlinds.pokemonclient.libretro.bench

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.libretro.ConsoleRole
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.libretro.sound.PauseStart
import dev.kotlinds.pokemonclient.libretro.sound.ResyncDecision
import dev.kotlinds.pokemonclient.libretro.sound.ResyncRefusal
import dev.kotlinds.pokemonclient.libretro.sound.ResyncResult
import dev.kotlinds.pokemonclient.libretro.sound.SampleBuffer
import dev.kotlinds.pokemonclient.libretro.sound.ShadowEnd
import dev.kotlinds.pokemonclient.libretro.sound.ShadowRun
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverLayout
import dev.kotlinds.pokemonclient.libretro.sound.SoundDriverState
import dev.kotlinds.pokemonclient.libretro.sound.SoundFixtures
import dev.kotlinds.pokemonclient.libretro.sound.SoundResync
import dev.kotlinds.pokemonclient.libretro.Files
import dev.kotlinds.pokemonclient.libretro.fmt
import kotlinx.io.files.Path
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Collects the audio of a console while [target] is set (bench only). */
class AudioTap {
    /** Where samples go now (null: dropped). */
    var target: SampleBuffer? = null

    /** Drops the samples while set (priming frames). */
    var muted = false

    fun onAudio(samples: ShortArray, frames: Int) {
        if (!muted) target?.add(samples, frames * 2)
    }
}

/**
 * Bench checks of music during pauses, on the real implementation ([ShadowRun] on a [ConsoleRole.SHADOW] console,
 * [SoundResync] on the bench's console), headless and unpaced. Every command starts its pauses with the app's own rule
 * ([PauseStart], with the limits the command is given):
 * - `pausemusic:<name>[:<play s>,<pause s>,<resume s>[,<max song wait>]]` (default 5,5,10,0): plays, pauses (up to
 *   `max song wait` frames later while the game is about to change its song, [SoundResync.songChangePending], as the
 *   app does; the game runs on meanwhile, heard), the shadow plays, resumes
 *   with the resync, and writes `<name>_app.wav` (what the app plays), `<name>_today.wav` (silence during the pause,
 *   then the music jumps back) and `<name>_ideal.wav` (as if the game never paused) to the out dir; prints the guards'
 *   decision, the continuity at resume (correlation with the ideal continuation) and the main RAM differences
 *   with "today" after the resume;
 * - `pausemusicstats:<pauses>[:<pause frames>[:<max delay>[:<button>,<button>...]]]`: that many short pauses one after
 *   the other (the game runs 7 frames between two), and the share of each outcome and the mean continuity; with a max
 *   delay, a pause starts up to that many frames later when its first frame can't be resynced
 *   ([SoundResync.pauseRefusal]), as the app does; with buttons, the game presses them in turn, 16 frames each, while
 *   it runs (e.g. `UP,DOWN` walks back and forth). Every refusal is printed with the players at the pause and at the
 *   shadow's end.
 */
class PauseMusicCheck(
    private val main: LibretroConsole,
    private val mainTap: AudioTap,
    private val spec: LibretroCoreSpec,
    private val rom: Path,
    private val data: Path,
    private val out: Path,
) : AutoCloseable {
    private val shadowTap = AudioTap()
    private val shadowDelegate = lazy {
        val started = TimeSource.Monotonic.markNow()
        LibretroConsole(spec, rom, data, onVideo = {}, onAudio = shadowTap::onAudio, role = ConsoleRole.SHADOW).also {
            println("  shadow ${it.coreName} ready in ${started.elapsedNow().inWholeMilliseconds} ms")
        }
    }
    private val shadow: LibretroConsole by shadowDelegate

    /** Closes the shadow (deletes its throwaway directory). */
    override fun close() {
        if (shadowDelegate.isInitialized()) shadow.close()
    }
    // Lazy: the bench also drives ROMs whose sound driver isn't known (Platinum); only the pausemusic commands need it.
    private val layout by lazy { SoundDriverLayout.forRom(rom) ?: error("music during pauses doesn't support this ROM") }
    private val resync by lazy { SoundResync(layout, spec.soundSplicer) }
    private val rate get() = main.sampleRate.roundToInt()

    private fun seconds(s: Double) = (s * main.fps).roundToInt()

    fun check(arg: String) {
        val name = arg.substringBefore(':')
        val params = arg.substringAfter(':', "5,5,10").split(',').map { it.toDouble() }
        val (play, pause, resume) = params
        val maxSongWait = params.getOrElse(3) { 0.0 }.toInt()
        val played = SampleBuffer().also { mainTap.target = it }
        main.step(seconds(play))
        // As the app does, the song-change wait only (no delay for frames a pause can't be resynced from).
        val pauseStart = PauseStart(resync, maxSongChangeWait = maxSongWait, maxPauseDelay = 0)
        startPause(pauseStart) { main.step(1) }
        if (maxSongWait > 0) println("  pause put off by ${pauseStart.songChangeWait} frames for a song change")
        mainTap.target = null

        // Pause: the main console stays frozen at P; the shadow plays from P.
        var t: TimeMark = TimeSource.Monotonic.markNow()
        val paused = main.saveState()
        val tSave = ms(t)
        val pausedRam = mainRam()
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        val during = SampleBuffer().also { shadowTap.target = it }
        t = TimeSource.Monotonic.markNow()
        check(run.begin(paused)) { "shadow rejected the paused state" }
        val tLoad = ms(t)

        t = TimeSource.Monotonic.markNow()
        repeat(seconds(pause)) { run.step() }
        val msPerFrame = ms(t) / seconds(pause)
        t = TimeSource.Monotonic.markNow()
        val end = run.end(settleFrames = ShadowRun.SETTLE_FRAMES) // as the app does
        val tEnd = ms(t)
        shadowTap.target = null
        println("  P: ${describe(paused)}")
        println("  shadow end: ${(end as? ShadowEnd.Safe)?.let { "frame ${it.frames}, " + describe(it.state) } ?: end}")

        // The ideal reference: the shadow simply goes on from where it stopped (as if the game had never paused).
        val ideal = SampleBuffer().also { shadowTap.target = it }
        if (end is ShadowEnd.Safe) shadow.step(seconds(resume))
        shadowTap.target = null

        // Resume: the real resync on the main console.
        t = TimeSource.Monotonic.markNow()
        val result = resync.resume(main, paused, end)
        val tResync = ms(t)
        println("  result: $result")
        println("  timings: main save ${tSave}ms, shadow load ${tLoad}ms, shadow ${"%.2f".fmt(msPerFrame)} ms/frame, safe end ${tEnd}ms, resync ${tResync}ms")
        check(result is ResyncResult.Resynced || mainRam().contentEquals(pausedRam)) { "main RAM changed although nothing was loaded" }
        if (result is ResyncResult.Resynced) check(mainRam().contentEquals(pausedRam)) { "main RAM changed by the resync" }

        val resumed = SampleBuffer().also { mainTap.target = it }
        main.step(seconds(resume))
        mainTap.target = null
        val mainAfter = mainRam()

        // "Today" reference, on the shadow: the game resumes from P.
        val today = SampleBuffer().also { shadowTap.target = it }
        shadow.loadState(paused)
        shadow.step(seconds(resume))
        val todayRam = shadowRam()
        shadowTap.target = null

        writeWav("${name}_app", played, during, resumed)
        writeWav("${name}_today", played, SampleBuffer().also { it.addSilence(during.size) }, today)
        writeWav("${name}_ideal", played, during, ideal)
        if (end is ShadowEnd.Safe) {
            val lag = rate / 100 // 10 ms
            println("  continuity at resume (corr with ideal, per 0.5 s): " + (0 until 4).joinToString(" ") { "%+.3f".fmt(correlation(resumed, ideal, it * rate / 2, rate / 2)) })
            println("  same, best lag within 10 ms:                       " + (0 until 4).joinToString(" ") { "%+.3f".fmt(correlation(resumed, ideal, it * rate / 2, rate / 2, lag)) })
            println("  today, best lag within 10 ms:                      " + (0 until 4).joinToString(" ") { "%+.3f".fmt(correlation(today, ideal, it * rate / 2, rate / 2, lag)) })
            println("  spectral similarity with ideal, first 2 s: resumed ${"%.3f".fmt(spectral(resumed, ideal, 0, 2 * rate))}, today ${"%.3f".fmt(spectral(today, ideal, 0, 2 * rate))}")
            println("  loudness (rms) resumed / ideal, first 0.5 s: ${"%.2f".fmt(rms(resumed, rate / 2) / rms(ideal, rate / 2))}")
            println("  jump at resume |shadow last - main first| ${jump(during, resumed)} (today ${jump(during, today)}; median step ${medianStep(during)})")
        }
        println("  main RAM after ${resume}s vs today: ${ramDiff(mainAfter, todayRam, paused)}")
    }

    /**
     * `pausemusicfixtures:<prefix>[:<frames>]`: test fixtures (see [SoundFixtures]) of the current state (`<prefix>_p`)
     * and of the shadow's safe end after that many frames (`<prefix>_s`), printing their players and what the guards
     * decide for them; then steps the game until a frame where the
     * sound thread runs (`<prefix>_busy`) and one where a DMA is mid-burst if the core is melonDS (`<prefix>_dma`).
     */
    fun fixtures(arg: String) {
        val prefix = arg.substringBefore(':')
        val frames = arg.substringAfter(':', "120").toInt()
        val paused = main.saveState()
        SoundFixtures.write(Path(out, "${prefix}_p.state.gz"), spec.soundSplicer, layout, paused)
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        check(run.begin(paused))
        repeat(frames) { run.step() }
        val end = run.end() as? ShadowEnd.Safe ?: error("no safe frame")
        SoundFixtures.write(Path(out, "${prefix}_s.state.gz"), spec.soundSplicer, layout, end.state)
        println("  ${prefix}_p: ${players(paused)} | ${prefix}_s (frame ${end.frames}): ${players(end.state)}")
        println("  guards: ${(resync.splice(paused, end.state) as? ResyncDecision.Refused)?.reason ?: "pass"}")
        for (i in 0 until 600) {
            val state = main.saveState()
            if (SoundDriverState.read(spec.soundSplicer.locate(state), layout).soundThreadRunning) {
                SoundFixtures.write(Path(out, "${prefix}_busy.state.gz"), spec.soundSplicer, layout, state)
                break
            }
            main.step(1)
        }
        if (spec == LibretroCoreSpec.MELONDS) for (i in 0 until 600) {
            val state = main.saveState()
            if (spec.soundSplicer.primingState(state) != null) {
                SoundFixtures.write(Path(out, "${prefix}_dma.state.gz"), spec.soundSplicer, layout, state)
                break
            }
            main.step(1)
        }
        println("  fixtures written to $out")
    }

    /** `pausemusicdriver`: the sound driver of the current frame (players, sound thread, commands in flight). */
    fun driver() = println("  driver: ${describe(main.saveState())}")

    /** `pausemusicload:<state file>`: the shadow loads a state file (priming if needed) and runs 60 frames. */
    fun load(arg: String) {
        val bytes = Files.readBytes(Path(out, arg))
        println("  priming needed: ${spec.soundSplicer.primingState(bytes) != null}")
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        println("  begin=${run.begin(bytes)}")
        repeat(60) { run.step() }
        println("  ran ${run.frames} frames")
    }

    fun stats(arg: String) {
        val parts = arg.split(':')
        val pauses = parts[0].toInt()
        val pauseFrames = parts.getOrNull(1)?.toInt() ?: 60
        val maxDelay = parts.getOrNull(2)?.toInt() ?: 0
        // Buttons pressed in turn, 16 frames each, while the game runs between two pauses (e.g. UP,DOWN walks).
        val buttons = parts.getOrNull(3)?.split(',')?.map { name ->
            if (name.trim().uppercase() == "NONE") emptySet() else setOf(Button.valueOf(name.trim().uppercase()))
        }.orEmpty()
        var ran = 0
        fun runMain(frames: Int) = repeat(frames) {
            main.step(1, if (buttons.isEmpty()) InputFrame.NONE else InputFrame(buttons[(ran / 16) % buttons.size]))
            ran++
        }
        val delays = IntArray(maxDelay + 1)
        val outcomes = HashMap<String, Int>()
        val continuity = mutableListOf<Double>()
        val continuityLag = mutableListOf<Double>()
        val spectralSimilarity = mutableListOf<Double>()
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        // As the app does: the pause starts up to maxDelay frames later when this frame can't be resynced.
        val pauseStart = PauseStart(resync, maxSongChangeWait = 0, maxPauseDelay = maxDelay)
        repeat(pauses) { n ->
            runMain(7)
            val paused = startPause(pauseStart) { runMain(1) }
            delays[pauseStart.pauseDelay]++
            run.begin(paused)
            repeat(pauseFrames) { run.step() }
            val end = run.end(settleFrames = ShadowRun.SETTLE_FRAMES) // as the app does
            val ideal = SampleBuffer().also { shadowTap.target = it }
            shadow.step(30)
            shadowTap.target = null
            val result = resync.resume(main, paused, end)
            val key = when (result) {
                is ResyncResult.Resynced -> "resynced"
                is ResyncResult.Refused -> result.reason::class.simpleName!!
                is ResyncResult.Aborted -> "ABORTED ${result.why}"
            }
            outcomes[key] = (outcomes[key] ?: 0) + 1
            if (result is ResyncResult.Refused) {
                println("  #$n refused: ${result.reason.message} | P: ${describe(paused)} ${players(paused)}" +
                    ((end as? ShadowEnd.Safe)?.let { " | end (+${it.frames - pauseFrames}): ${describe(it.state)} ${players(it.state)}" } ?: " | end: $end"))
            }
            if (result is ResyncResult.Resynced && end is ShadowEnd.Safe) {
                val after = SampleBuffer().also { mainTap.target = it }
                runMain(30)
                mainTap.target = null
                continuity += correlation(after, ideal, 0, rate / 4)
                continuityLag += correlation(after, ideal, 0, rate / 4, rate / 100)
                spectralSimilarity += spectral(after, ideal, 0, 30 * rate / 60)
            }
        }
        if (maxDelay > 0) println("  pause delayed by 0..$maxDelay frames: ${delays.joinToString()}")
        println("  outcomes over $pauses pauses of $pauseFrames frames: ${outcomes.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }}")
        if (continuity.isNotEmpty()) {
            println("  continuity (corr with ideal, first 0.25 s): mean ${"%.3f".fmt(continuity.average())}, min ${"%.3f".fmt(continuity.min())}")
            println("  continuity (best lag within 10 ms): mean ${"%.3f".fmt(continuityLag.average())}, min ${"%.3f".fmt(continuityLag.min())}, " +
                "median ${"%.3f".fmt(continuityLag.sorted()[continuityLag.size / 2])}")
            println("  spectral similarity with ideal (first 0.5 s): mean ${"%.3f".fmt(spectralSimilarity.filter { !it.isNaN() }.average())}")
        }
    }

    /**
     * `pausemusicintro:<button>,<button>...[:<before>,<after>,<every>,<pause frames>[,<save every>[,<keep input>[,<settle frames>[,<measure menu>]]]]]` (default 60,900,3,60,0,0,0,0): battle
     * starts. Presses the buttons given in turn, 16 frames each (`NONE`: nothing; e.g. LEFT,RIGHT walks back and forth in
     * tall grass, UP,A walks to a trainer and talks) until a sequence player
     * gets paused (HeartGold pauses the field music when the battle music starts: the encounter, frame E), then
     * replays the same inputs from the start and, from E - before to E + after, every `every` frames, pauses like the
     * app (up to [PauseStart.MAX_PAUSE_DELAY_FRAMES] frames later when the frame is refused by [SoundResync.pauseRefusal]), plays the shadow for
     * `pause frames`, resumes with the real resync and prints the outcome, why, the screen, and the continuity. Inputs
     * stop at E (the battle intro runs by itself) unless `keep input` is 1. With `pause frames` 0, only the pause guards
     * of every frame (no shadow). `here`: no inputs, E is the current frame. `settle frames`: see [ShadowRun.end]. `measure menu` 1: after
     * each resync where sounds ended during the pause, how many frames sooner the battle command menu comes than resuming
     * from the paused state. With `save every` > 0, saves `intro_<offset>.state` every that many
     * frames. The main console goes back to the frame it paused at after each test.
     */
    fun intro(arg: String, screen: () -> String) {
        val parts = arg.split(':')
        // `here`: no walking, the scan starts at the current frame (E = 0), e.g. from a state just before a battle.
        val here = parts[0] == "here"
        val dirs = if (here) emptyList() else parts[0].split(',').map { name ->
            if (name.trim().uppercase() == "NONE") emptySet() else setOf(dev.kotlinds.pokemonclient.console.Button.valueOf(name.trim().uppercase()))
        }
        val params = (parts.getOrNull(1)?.split(',')?.map(String::toInt) ?: emptyList())
        val before = params.getOrElse(0) { 60 }
        val after = params.getOrElse(1) { 900 }
        val every = params.getOrElse(2) { 3 }
        val pauseFrames = params.getOrElse(3) { 60 }
        val saveEvery = params.getOrElse(4) { 0 }
        val keepInput = params.getOrElse(5) { 0 } == 1
        val settleFrames = params.getOrElse(6) { 0 }
        val measureMenu = params.getOrElse(7) { 0 } == 1
        val sooner = mutableListOf<Int>()
        var waitedFrames = 0
        val start = main.saveState()
        fun input(i: Int, encounter: Int) =
            if (dirs.isEmpty() || (i >= encounter && !keepInput)) dev.kotlinds.pokemonclient.console.InputFrame.NONE
            else dev.kotlinds.pokemonclient.console.InputFrame(dirs[(i / 16) % dirs.size])
        fun hasPausedPlayer(state: ByteArray) = runCatching { SoundDriverState.read(spec.soundSplicer.locate(state), layout).players.any { it.isPaused } }.getOrDefault(false)

        var encounter = if (here) 0 else -1
        if (!here) for (i in 0 until 20_000) {
            if (hasPausedPlayer(main.saveState())) { encounter = i; break }
            main.step(1, input(i, Int.MAX_VALUE))
        }
        check(encounter >= 0) { "no encounter" }
        if (here) println("  scanning from the current frame")
        println("  encounter (a player paused) at frame $encounter: ${screen()}")
        check(main.loadState(start))
        val from = maxOf(0, encounter - before)
        for (i in 0 until from) main.step(1, input(i, encounter))

        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        val pauseStart = PauseStart(resync, maxSongChangeWait = 0)
        val outcomes = HashMap<String, Int>()
        val continuity = mutableListOf<Double>()
        val spectralSimilarity = mutableListOf<Double>()
        for (f in from..encounter + after) {
            val offset = f - encounter
            if ((f - from) % every == 0) {
                val atFrame = main.saveState()
                if (saveEvery > 0 && offset % saveEvery == 0) Files.writeBytes(Path(out, "intro_$offset.state"), atFrame)
                val raw = resync.pauseRefusal(atFrame)
                if (pauseFrames == 0) {
                    // Pause guards only, no shadow: which frames a pause can't start at.
                    val key = raw?.let { it::class.simpleName!! } ?: "ok"
                    outcomes[key] = (outcomes[key] ?: 0) + 1
                    if (raw != null && raw != ResyncRefusal.SoundThreadRunningAtPause) {
                        println("  %+5d %-40s %s | %s".fmt(offset, screen().take(40), raw.message, commandQueue(atFrame)))
                    }
                    main.step(1, input(f, encounter))
                    continue
                }
                // The input of the frame each frame put off emulates, as if the pause had been asked then.
                val paused = startPause(pauseStart, atFrame) { putOff -> main.step(1, input(f + putOff - 1, encounter)) }
                val delay = pauseStart.pauseDelay
                val ram = mainRam()
                val where = screen()
                check(run.begin(paused))
                repeat(pauseFrames) { run.step() }
                val end = run.end(settleFrames = settleFrames)
                val waited = ((end as? ShadowEnd.Safe)?.frames ?: pauseFrames) - pauseFrames
                waitedFrames += waited
                val ideal = SampleBuffer().also { shadowTap.target = it }
                if (end is ShadowEnd.Safe) shadow.step(30)
                shadowTap.target = null
                val result = resync.resume(main, paused, end)
                val key = when (result) {
                    is ResyncResult.Resynced -> "resynced"
                    is ResyncResult.Refused -> result.reason::class.simpleName!!
                    is ResyncResult.Aborted -> "ABORTED"
                }
                outcomes[key] = (outcomes[key] ?: 0) + 1
                var detail = ""
                if (result is ResyncResult.Resynced && end is ShadowEnd.Safe) {
                    check(mainRam().contentEquals(ram)) { "main RAM changed by the resync" }
                    val resumed = SampleBuffer().also { mainTap.target = it }
                    main.step(30)
                    mainTap.target = null
                    val c = correlation(resumed, ideal, 0, rate / 4, rate / 100)
                    val sp = spectral(resumed, ideal, 0, rate / 2)
                    if (!c.isNaN()) continuity += c
                    if (!sp.isNaN()) spectralSimilarity += sp
                    detail = "corr %+.3f spectral %.3f".fmt(c, sp)
                    if (result.endedDuringPause > 0) detail += " (ended during the pause: ${result.endedDuringPause})"
                    if (measureMenu && result.endedDuringPause > 0) {
                        // How much sooner the resumed game reaches the command menu than the same game resumed from P.
                        fun framesToMenu(): Int { var n = 0; while (n < 1500 && !screen().trimStart().startsWith("BattleCommand")) { main.step(1); n++ }; return n }
                        val resynced = 30 + framesToMenu()
                        check(main.loadState(paused))
                        val today = framesToMenu()
                        sooner += today - resynced
                        detail += " menu ${today - resynced} frames sooner"
                    }
                } else if (result is ResyncResult.Refused) {
                    detail = result.reason.message
                    val endState = (end as? ShadowEnd.Safe)?.state
                    detail += " | P: " + players(paused) + (endState?.let { " | end: " + players(it) } ?: "")
                    if (result.reason is ResyncRefusal.UnreadableCommands) detail += " | " + commandQueue(paused)
                }
                println("  %+5d %-40s raw %-26s delay %d -> %-22s %s".fmt(offset, where.take(40), raw?.let { it::class.simpleName } ?: "ok", delay, key, (if (waited > 0) "(end +$waited frames) " else "") + detail))
                check(main.loadState(atFrame))
            }
            main.step(1, input(f, encounter))
        }
        println("  outcomes: ${outcomes.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" }}" + if (settleFrames > 0) "; shadow ends past the pause: $waitedFrames frames in all" else "")
        if (sooner.isNotEmpty()) println("  command menu reached sooner after resyncs with sounds ended during the pause: ${sooner.sorted()} frames")
        if (continuity.isNotEmpty()) println("  continuity (best lag 10 ms, first 0.25 s): mean %.3f min %.3f; spectral (0.5 s) mean %.3f".fmt(continuity.average(), continuity.min(), spectralSimilarity.average()))
    }

    /**
     * `pausemusicmenu:<button>,<button>...:<pause s>,<pause s>...[:<keep input>[:<awaiting input>]]`: from walking like `pausemusicintro`
     * (the same buttons, 16 frames each) to the battle, the first frame the battle command menu awaits input
     * (with `awaiting input` 1: the first frame its cursor shows; saved as `menu_first.state`); then, for each pause length and with the shadow's end settle frames 0 and
     * [ShadowRun.SETTLE_FRAMES], pauses there like the app (up to [PauseStart.MAX_PAUSE_DELAY_FRAMES] frames later if refused, [PauseStart]), resumes with the real
     * resync and prints the outcome, the players, and the continuity with the uninterrupted music.
     */
    fun menu(arg: String, screen: () -> String) {
        val parts = arg.split(':')
        val dirs = parts[0].split(',').map { name ->
            if (name.trim().uppercase() == "NONE") emptySet() else setOf(dev.kotlinds.pokemonclient.console.Button.valueOf(name.trim().uppercase()))
        }
        val pauses = parts[1].split(',').map(String::toDouble)
        val keepInput = parts.getOrNull(2) == "1"
        val awaitingInput = parts.getOrNull(3) == "1" // the menu's cursor shown (it takes input), not just drawn
        fun hasPausedPlayer(state: ByteArray) = runCatching { SoundDriverState.read(spec.soundSplicer.locate(state), layout).players.any { it.isPaused } }.getOrDefault(false)
        var encounter = -1
        var i = 0
        while (i < 30_000) {
            val inBattle = encounter >= 0
            if (!inBattle && hasPausedPlayer(main.saveState())) encounter = i
            if (inBattle && screen().trimStart().startsWith("BattleCommand") && (!awaitingInput || "cursor=Hidden" !in screen())) break
            val input = if (encounter >= 0 && !keepInput) dev.kotlinds.pokemonclient.console.InputFrame.NONE
                else dev.kotlinds.pokemonclient.console.InputFrame(dirs[(i / 16) % dirs.size])
            main.step(1, input)
            i++
        }
        check(encounter >= 0) { "no battle" }
        val first = main.saveState()
        Files.writeBytes(Path(out, "menu_first.state"), first)
        println("  encounter at frame $encounter, command menu at frame $i (+${i - encounter}): ${screen()}")
        println("  P: ${describe(first)} | ${players(first)}")
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        val pauseStart = PauseStart(resync, maxSongChangeWait = 0)
        for (settle in listOf(0, ShadowRun.SETTLE_FRAMES)) for (seconds in pauses) {
            check(main.loadState(first))
            val paused = startPause(pauseStart, first) { main.step(1) }
            val delay = pauseStart.pauseDelay
            val ram = mainRam()
            check(run.begin(paused))
            repeat(seconds(seconds)) { run.step() }
            val end = run.end(settleFrames = settle)
            val ideal = SampleBuffer().also { shadowTap.target = it }
            if (end is ShadowEnd.Safe) shadow.step(30)
            shadowTap.target = null
            val result = resync.resume(main, paused, end)
            var detail = ""
            if (result is ResyncResult.Resynced && end is ShadowEnd.Safe) {
                check(mainRam().contentEquals(ram)) { "main RAM changed by the resync" }
                val resumed = SampleBuffer().also { mainTap.target = it }
                main.step(30)
                mainTap.target = null
                detail = "corr %+.3f spectral %.3f, ended during the pause %d".fmt(correlation(resumed, ideal, 0, rate / 4, rate / 100), spectral(resumed, ideal, 0, rate / 2), result.endedDuringPause)
            }
            val waited = ((end as? ShadowEnd.Safe)?.frames ?: 0) - seconds(seconds)
            println("  settle %2d pause %4.1fs delay %d end +%d -> %s %s | end: %s".fmt(settle, seconds, delay, waited,
                when (result) { is ResyncResult.Resynced -> "resynced"; is ResyncResult.Refused -> result.reason.message; is ResyncResult.Aborted -> "ABORTED ${result.why}" },
                detail, (end as? ShadowEnd.Safe)?.let { players(it.state) } ?: end.toString()))
        }
    }

    /**
     * `pausemusicscan:<button>,<button>...:<frames>[,<every>,<pause frames>,<max delay>,<max song wait>,<save refused>]`
     * (default 300,3,60,3,0,0): from the current frame, the game runs `frames` frames pressing the buttons in turn, 16
     * frames each (`NONE`: nothing; one button: held all along, e.g. `LEFT` walks west across a map edge). Every `every`
     * frames it pauses like the app: up to `max song wait` frames later while the game is about to change its song
     * ([SoundResync.songChangePending]), then up to `max delay` frames later when [SoundResync.pauseRefusal] refuses the
     * frame; the shadow plays `pause frames` and ends like the app ([ShadowRun.SETTLE_FRAMES]), the real resync
     * resumes, and the outcome is printed with [where] (map, screen), the players at the pause and at the shadow's end,
     * and the continuity. With `save refused` 1, the frame of each refused pause is saved as `scan_<offset>.state`. The
     * main console goes back to the frame it paused at after each test.
     */
    fun scan(arg: String, where: () -> String) {
        val parts = arg.split(':')
        val dirs = parts[0].split(',').map { name ->
            if (name.trim().uppercase() == "NONE") emptySet() else setOf(Button.valueOf(name.trim().uppercase()))
        }
        val params = parts.getOrNull(1)?.split(',')?.map(String::toInt).orEmpty()
        val frames = params.getOrElse(0) { 300 }
        val every = params.getOrElse(1) { 3 }
        val pauseFrames = params.getOrElse(2) { 60 }
        val maxDelay = params.getOrElse(3) { 3 }
        val maxSongWait = params.getOrElse(4) { 0 }
        val saveRefused = params.getOrElse(5) { 0 } == 1
        fun input(i: Int) = InputFrame(dirs[(i / 16) % dirs.size])
        val run = ShadowRun(shadow, resync, silence = { shadowTap.muted = it })
        val pauseStart = PauseStart(resync, maxSongChangeWait = maxSongWait, maxPauseDelay = maxDelay)
        val outcomes = LinkedHashMap<String, Int>()
        val continuity = mutableListOf<Double>()
        for (f in 0 until frames) {
            if (f % every == 0) {
                val atFrame = main.saveState()
                val raw = resync.pauseRefusal(atFrame)
                // The input of the frame each frame put off emulates (frame f first: see startPause).
                val paused = startPause(pauseStart, atFrame) { putOff -> main.step(1, input(f + putOff - 1)) }
                val delay = pauseStart.pauseDelay
                val songWait = pauseStart.songChangeWait
                val ram = mainRam()
                val place = where()
                check(run.begin(paused))
                repeat(pauseFrames) { run.step() }
                val end = run.end(settleFrames = ShadowRun.SETTLE_FRAMES)
                val ideal = SampleBuffer().also { shadowTap.target = it }
                if (end is ShadowEnd.Safe) shadow.step(30)
                shadowTap.target = null
                val result = resync.resume(main, paused, end)
                val key = when (result) {
                    is ResyncResult.Resynced -> "resynced"
                    is ResyncResult.Refused -> result.reason::class.simpleName!!
                    is ResyncResult.Aborted -> "ABORTED"
                }
                outcomes[key] = (outcomes[key] ?: 0) + 1
                val endState = (end as? ShadowEnd.Safe)?.state
                var detail = "P: ${players(paused)}" + (endState?.let { " | end (+${(end as ShadowEnd.Safe).frames - pauseFrames}): ${players(it)}" } ?: " | end: $end")
                if (result is ResyncResult.Resynced && end is ShadowEnd.Safe) {
                    check(mainRam().contentEquals(ram)) { "main RAM changed by the resync" }
                    val resumed = SampleBuffer().also { mainTap.target = it }
                    main.step(30)
                    mainTap.target = null
                    val c = correlation(resumed, ideal, 0, rate / 4, rate / 100)
                    if (!c.isNaN()) continuity += c
                    detail = "corr %+.3f | ".fmt(c) + detail
                } else if (result is ResyncResult.Refused) {
                    detail = result.reason.message + " | " + detail
                    if (saveRefused) Files.writeBytes(Path(out, "scan_$f.state"), paused)
                }
                val song = if (resync.songChangePending(atFrame)) "song change pending" else ""
                println("  %4d %-46s raw %-24s %-19s delay %d+%3d -> %-20s %s".fmt(f, place.take(46), raw?.let { it::class.simpleName } ?: "ok", song, delay, songWait, key, detail))
                check(main.loadState(atFrame))
            }
            main.step(1, input(f))
        }
        println("  outcomes: ${outcomes.entries.joinToString { "${it.key} ${it.value}" }}")
        if (continuity.isNotEmpty()) println("  continuity (best lag 10 ms, first 0.25 s): mean %.3f min %.3f".fmt(continuity.average(), continuity.min()))
    }

    /** The players of [state], short: index, flags (A active, P paused), tracks, bank. */
    private fun players(state: ByteArray): String = runCatching {
        SoundDriverState.read(spec.soundSplicer.locate(state), layout).players.joinToString(" ") { p ->
            "#${p.index}${if (p.isPaused) "P" else "A"}/${p.tracks.size}t/bank %08X".fmt(p.bank)
        }
    }.getOrElse { "?" }

    /** The ARM9's command bookkeeping of [state], raw (why [SoundDriverState.commandsInFlight] may be unreadable). */
    private fun commandQueue(state: ByteArray): String = runCatching {
        val s = spec.soundSplicer.locate(state)
        val shared = s.arm7(layout.sharedWorkPointer)
        val a = layout.arm9Commands
        val finished = s.main(shared)
        val tag = s.main(shared + a.currentTag)
        val write = s.main(shared + a.waitingWrite)
        val queue = (0 until a.waitingSlots).map { s.main(shared + a.waitingQueue + it * 4) }
        val lists = queue.map { head ->
            val ids = mutableListOf<String>()
            var c = head
            var n = 0
            while (c != 0 && n++ < 300) {
                if (!s.inMainRam(c)) { ids += "bad %08X".fmt(c); break }
                ids += "%02X".fmt(s.main(c + 4)); c = s.main(c)
            }
            ids.joinToString(",")
        }
        // The words around the statics block, to see the other statics (free list, reserved list...).
        val block = (-0x60 until 0x10 step 4).joinToString(" ") { "%08X".fmt(s.main(shared + it)) }
        "shared %08X finished %d tag %d write %d queue %s lists %s block %s".fmt(shared, finished, tag, write, queue.joinToString(",") { "%08X".fmt(it) }, lists, block)
    }.getOrElse { "? ${it.message}" }

    private fun describe(state: ByteArray): String = runCatching {
        val s = SoundDriverState.read(spec.soundSplicer.locate(state), layout)
        "players ${s.players.map { p -> "#${p.index} flags ${p.flags} ${p.tracks.size} tracks" }}, sound thread running ${s.soundThreadRunning}, " +
            "queued ${s.queuedCommandLists}, in flight ${s.commandsInFlight?.map { "0x%02X".fmt(it) }}, locked ${s.lockedChannels}, " +
            "game music ${s.gameMusic}" + (layout.gameMusic?.let { m ->
                val st = spec.soundSplicer.locate(state)
                " (game music state ${st.main(m.state)}, fade ${st.main(m.fadeTimer) and 0xFFFF}, after ${st.main(m.afterFadeTimer) and 0xFFFF}, queued ${st.main(m.queuedSong) and 0xFFFF}, " +
                    "player flags ${(0 until 16).joinToString("") { "%x".fmt(st.bytes[st.arm7Wram.offset + layout.work + 0x540 + it * 0x24 - SoundDriverLayout.ARM7_WRAM].toInt() and 0xF) }})"
            } ?: "")
    }.getOrElse { "unreadable: ${it.message}" }

    private fun mainRam() = ByteArray(main.memorySize(MemoryRegion.MAIN_RAM)).also { main.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }
    private fun shadowRam() = ByteArray(shadow.memorySize(MemoryRegion.MAIN_RAM)).also { shadow.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }

    /**
     * Differing bytes: in the SND shared work (written by the ARM7), in HeartGold US's known sound / clock buffers
     * (NNS driver-info copies of the ARM7 work at 0x021DD460, 2 x 0x11E0; `sRTCWork` at 0x021D1048, the real-time
     * clock), and the rest as merged ranges.
     */
    private fun ramDiff(a: ByteArray, b: ByteArray, paused: ByteArray): String {
        val state = spec.soundSplicer.locate(paused)
        val shared = state.arm7(layout.sharedWorkPointer) - SoundDriverLayout.MAIN_RAM
        var inShared = 0
        var inMirrors = 0
        var inClock = 0
        val ranges = mutableListOf<IntArray>()
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            if (i - shared in 0 until 0x280) { inShared++; continue }
            if (layout == SoundDriverLayout.HEARTGOLD_US && i in 0x1DD460 until 0x1DF820) { inMirrors++; continue }
            if (layout == SoundDriverLayout.HEARTGOLD_US && i in 0x1D1048 until 0x1D10A0) { inClock++; continue }
            val last = ranges.lastOrNull()
            if (last != null && i - last[1] <= 16) last[1] = i else ranges += intArrayOf(i, i)
        }
        val other = ranges.sumOf { it[1] - it[0] + 1 }
        return "$inShared bytes in SNDSharedWork, $inMirrors in NNS driver-info copies, $inClock in the RTC work, " +
            "${ranges.size} other ranges (~$other bytes): " + ranges.take(12).joinToString { "%08X-%08X".fmt(it[0] + SoundDriverLayout.MAIN_RAM, it[1] + SoundDriverLayout.MAIN_RAM) }
    }

    /**
     * Correlation of [x] (shifted by up to [maxLag] samples either way, best one) with [y], over [length] samples from
     * [from]: the sequencer ticks of the resumed game keep the paused game's timer phase, so notes may start up to one
     * tick (~5 ms) apart from the ideal continuation, which a plain correlation punishes although it isn't audible.
     */
    private fun correlation(x: SampleBuffer, y: SampleBuffer, from: Int, length: Int, maxLag: Int = 0): Double =
        (-(maxLag / 4) * 4..maxLag step 4).maxOf { lag -> if (from + lag < 0) -2.0 else correlationAt(x, y, from, length, lag).takeUnless { it.isNaN() } ?: -2.0 }
            .takeIf { it > -2.0 } ?: Double.NaN

    private fun correlationAt(x: SampleBuffer, y: SampleBuffer, from: Int, length: Int, lag: Int): Double {
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        val end = minOf(from + length, x.frames - maxOf(lag, 0), y.frames)
        for (i in from until end) {
            val a = (x.data[2 * (i + lag)] + x.data[2 * (i + lag) + 1]) / 2.0
            val b = (y.data[2 * i] + y.data[2 * i + 1]) / 2.0
            xy += a * b; xx += a * a; yy += b * b
        }
        return if (xx == 0.0 || yy == 0.0) Double.NaN else xy / sqrt(xx * yy)
    }

    /**
     * Spectral similarity of [x] and [y] over [length] samples from [from]: the mean, over 2048-sample windows, of the
     * correlation of their magnitude spectra (phase-blind, closer to what is heard than the waveform correlation).
     */
    private fun spectral(x: SampleBuffer, y: SampleBuffer, from: Int, length: Int): Double {
        val n = 2048
        val values = (from until minOf(from + length, x.frames, y.frames) - n step n).map { start ->
            val a = magnitudes(x, start, n)
            val b = magnitudes(y, start, n)
            val ma = a.average(); val mb = b.average()
            var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (i in a.indices) { ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb) }
            if (aa == 0.0 || bb == 0.0) Double.NaN else ab / sqrt(aa * bb)
        }.filter { !it.isNaN() }
        return if (values.isEmpty()) Double.NaN else values.average()
    }

    /** Magnitude spectrum (Hann window, radix-2 FFT) of [n] mono samples of [x] from [start]. */
    private fun magnitudes(x: SampleBuffer, start: Int, n: Int): DoubleArray {
        val re = DoubleArray(n) { i -> (x.data[2 * (start + i)] + x.data[2 * (start + i) + 1]) / 2.0 * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / n)) }
        val im = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var size = 2
        while (size <= n) {
            val angle = -2 * Math.PI / size
            for (k in 0 until n step size) for (m in 0 until size / 2) {
                val wr = kotlin.math.cos(angle * m); val wi = kotlin.math.sin(angle * m)
                val tr = re[k + m + size / 2] * wr - im[k + m + size / 2] * wi
                val ti = re[k + m + size / 2] * wi + im[k + m + size / 2] * wr
                re[k + m + size / 2] = re[k + m] - tr; im[k + m + size / 2] = im[k + m] - ti
                re[k + m] += tr; im[k + m] += ti
            }
            size *= 2
        }
        return DoubleArray(n / 2) { kotlin.math.ln(1 + sqrt(re[it] * re[it] + im[it] * im[it])) }
    }

    private fun rms(x: SampleBuffer, length: Int): Double {
        val n = minOf(length * 2, x.size)
        return sqrt((0 until n).sumOf { x.data[it].toDouble() * x.data[it] } / maxOf(n, 1))
    }

    private fun jump(before: SampleBuffer, after: SampleBuffer): Int =
        if (before.size < 2 || after.size < 2) 0
        else (abs(before.data[before.size - 2] - after.data[0]) + abs(before.data[before.size - 1] - after.data[1])) / 2

    private fun medianStep(x: SampleBuffer): Int {
        val n = minOf(x.frames - 1, rate)
        if (n <= 0) return 0
        val from = x.frames - 1 - n
        return (from until from + n).map { abs(x.data[2 * it + 2] - x.data[2 * it]) }.sorted()[n / 2]
    }

    /** Writes [parts] one after the other as `<name>.wav` in the out dir. */
    private fun writeWav(name: String, vararg parts: SampleBuffer) {
        val all = SampleBuffer().also { list -> parts.forEach(list::addAll) }
        Files.writeBytes(Path(out, "$name.wav"), all.toWav(rate))
        println("  wrote $name.wav (${"%.1f".fmt(all.frames.toDouble() / rate)} s)")
    }

    /**
     * Pauses like the app: from the main console at [from] (its current state), each frame [pauseStart] puts the pause
     * off is emulated by [stepFrame] (given the frames put off so far, this one included); returns the state the pause
     * starts at. [pauseStart]'s counters then tell how long it was put off.
     *
     * [from] is the state before frame `f` is emulated (the loops take it, test, then step frame `f` with its input):
     * the first frame put off is frame `f` itself, so the n-th one (putOff = n) emulates frame `f + putOff − 1`, with
     * that frame's input, as if the pause had been asked then. Like the app ([dev.kotlinds.pokemonclient.libretro.sound.PauseStart]
     * in its `ShadowAudio`), where a pause put off lets the game emulate the very frame it was about to, with the input
     * held then.
     */
    private fun startPause(pauseStart: PauseStart, from: ByteArray = main.saveState(), stepFrame: (putOff: Int) -> Unit): ByteArray {
        pauseStart.reset()
        var paused = from
        while (pauseStart.putOff(paused) != null) {
            stepFrame(pauseStart.songChangeWait + pauseStart.pauseDelay)
            paused = main.saveState()
        }
        return paused
    }

    private fun ms(start: TimeMark) = start.elapsedNow().inWholeMicroseconds / 1000.0
}
