package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs as S
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.HallOfFameStage
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.ViewerExit
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A
import dev.kotlinds.pokemonclient.games.hgss.HgssTextAddresses as T

/**
 * The applications with nothing to choose, as [Screen.Viewer]s, from the running sub-application (the app names of
 * [HgssAddresses.APP_BY_OVERLAY] / [HgssVersion.appByInit]): the Pokédex (overlay 18), the trainer card (50), a
 * Pokémon's summary (`PokemonSummary_Init`, viewing only: the forget-a-move summary is [HgssPostBattleScreens]'),
 * the Pokégear's map and radio (overlay 100, `PokegearAppData.app`), the Hall of Fame (63 register, 64 view).
 *
 * Ways out, verified live: B leaves the Pokédex, the trainer card and the summary (back to the menu they were opened
 * from); the Pokégear has no B: its Close button on the app bar ([GEAR_CLOSE]) closes it.
 */
internal object HgssViewerScreens : HgssScreenDecoder {

    /** The Pokégear app bar's Close button (the same as the phone's `option:cancel`). */
    val GEAR_CLOSE = TouchPoint(230, 176)

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.mode != GameMode.APP || state.fading) return null
        val awaiting = if (state.awaitingInput) Awaiting.INPUT else Awaiting.ANIMATION
        val back = ViewerExit(button = Button.B)
        return when (state.modeDetail) {
            "pokedex" -> Screen.Viewer(ViewerApp.POKEDEX, back, awaiting)
            "trainer_card" -> Screen.Viewer(ViewerApp.TRAINER_CARD, back, awaiting)
            "pokemon_summary" -> Screen.Viewer(ViewerApp.SUMMARY, back, awaiting)
            "pokegear" -> gearViewer(mem, awaiting)
            "hall_of_fame_register" -> hallOfFame(mem, state, awaiting)
            "hall_of_fame" -> Screen.Viewer(ViewerApp.HALL_OF_FAME, back, awaiting)
            else -> null
        }
    }

    /**
     * The Pokégear's map or radio (the phone is [HgssPhoneScreens]'), from `PokegearAppData.app`, with the app bar's
     * buttons ([Screen.Viewer.apps]) and, for the radio, its dial and programme ([HgssRadio]). The radio waits for
     * input only once its own input loop runs (not while it slides in).
     */
    private fun gearViewer(mem: HgssMemory, awaiting: Awaiting): Screen.Viewer? {
        val fs = mem.ptr(mem.version.fieldSystemPtr) ?: return null
        val gear = mem.ptr(fs + A.FS_SUB0)?.let { mem.ptr(it + A.FSS0_SUB_APP) } ?: return null
        if (mem.fn(gear + S.OM_INIT) != T.FN_POKEGEAR_INIT) return null
        val data = mem.ptr(gear + S.OM_DATA) ?: return null
        val app = when (mem.u8(data + T.GEAR_APP)) {
            T.GEAR_APP_MAP -> ViewerApp.POKEGEAR_MAP
            T.GEAR_APP_RADIO -> ViewerApp.POKEGEAR_RADIO
            else -> return null
        }
        val apps = HgssPhoneScreens.appBar(mem, data)?.buttons?.map { it.entry }.orEmpty()
        val exit = ViewerExit(touch = GEAR_CLOSE)
        if (app != ViewerApp.POKEGEAR_RADIO) return Screen.Viewer(app, exit, awaiting, apps = apps)
        val (radioApp, ready) = HgssRadio.radioApp(mem, gear, data) ?: return Screen.Viewer(app, exit, awaiting, apps = apps)
        val radio = HgssRadio.decode(mem, radioApp, inAppBar = mem.u8(data + T.GEAR_CURSOR_IN_BAR) == 1)
        return Screen.Viewer(app, exit, if (ready) awaiting else Awaiting.ANIMATION, radio = radio, apps = apps)
    }

    /**
     * The registration in the Hall of Fame ([HgssGameClear.registration]): the team being registered is the party
     * (the game copies it, eggs left out), its [HallOfFameStage] says which member is presented, and it waits for
     * input only once the whole team is shown (the reader's awaiting).
     */
    private fun hallOfFame(mem: HgssMemory, state: HgssState, awaiting: Awaiting): Screen.Viewer {
        val app = mem.ptr(mem.version.fieldSystemPtr)?.let { mem.ptr(it + A.FS_SUB0) }?.let { mem.ptr(it + A.FSS0_SUB_APP) }
        val registration = app?.let { mem.ptr(it + S.OM_DATA) }?.let { HgssGameClear.registration(mem, it) }
        val names = state.party.filter { !it.isEgg }.map { it.nickname ?: it.speciesName }
        val stage = when {
            registration == null -> null
            registration.presenting ->
                HallOfFameStage.Presenting(registration.monIndex + 1, registration.count, names.getOrElse(registration.monIndex) { "?" })
            registration.leaving -> HallOfFameStage.Leaving
            registration.wholeTeam -> HallOfFameStage.WholeTeam
            else -> null
        }
        val progress = when (stage) {
            is HallOfFameStage.Presenting -> "presenting ${stage.index}/${stage.count}: ${stage.name}"
            HallOfFameStage.WholeTeam -> "the whole team is shown" + if (awaiting == Awaiting.INPUT) ": A goes on (the game then saves)" else ""
            HallOfFameStage.Leaving -> "leaving: the game saves next"
            null -> null
        }
        return Screen.Viewer(
            ViewerApp.HALL_OF_FAME_REGISTER, ViewerExit(button = Button.A), awaiting, team(state) + listOfNotNull(progress), hallOfFame = stage,
        )
    }

    private fun team(state: HgssState): List<String> =
        state.party.filter { !it.isEgg }.map { mon -> (mon.nickname ?: mon.speciesName) + " Lv" + mon.level }
}
