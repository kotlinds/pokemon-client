package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PokegearCard
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tunes the Pokégear radio to [station] (opening the Pokégear and its radio when needed), checks the dial in RAM,
 * and closes the Pokégear when [close] (the radio keeps playing: the Poké Flute still wakes the Route 11 Snorlax).
 */
data class TuneRadio(val station: RadioStation, val close: Boolean = false) : GameAction {
    override val key get() = "tune_radio(${station.wire})"
}

/**
 * The Pokégear radio's action: `tune_radio` (its recipe: [FieldRecipes.tuneRadio]). Listed with the common actions
 * ([CommonActions.definitions]).
 */
object PokegearActions {

    private val assisted = setOf(ActionMode.ASSISTED)

    val tuneRadio = ActionDefinition(TuneRadio::class, object : ActionSpec<TuneRadio> {
        override val name = "tune_radio"
        override val description = "Tune the Pokégear radio to a station (opens the Pokégear and its radio from the field " +
            "or another Pokégear app, touches the preset button or drags the dial's cursor onto the channel, checks the " +
            "station in the game). close: true closes the Pokégear after (the music keeps playing). In Kanto with the " +
            "Expansion Card, station poke_flute plays the Poké Flute: then talk to a sleeping Pokémon to wake it."
        override val parameters = listOf(
            Parameter("station", ParameterType.STRING, "The station id.", values = RadioStation.entries.filter { it != RadioStation.COMMERCIALS }.map { it.wire }),
            Parameter("close", ParameterType.BOOLEAN, "Close the Pokégear once tuned (default false).", required = false),
        )
        override val modes = assisted
        override fun availability(state: GameState): Availability = availabilityOf(state)
        override fun parse(json: JsonObject): TuneRadio {
            val raw = json["station"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw ActionException(ActionError.InvalidParameter("station", "missing"))
            val station = RadioStation.parse(raw)?.takeIf { it != RadioStation.COMMERCIALS }
                ?: throw ActionException(ActionError.InvalidParameter("station", raw, RadioStation.entries.filter { it != RadioStation.COMMERCIALS }.map { it.wire }))
            return TuneRadio(station, json["close"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        override fun enumerate(state: GameState) = emptyList<TuneRadio>()
    })

    /** The definitions, in the order they are listed to agents. */
    val definitions: List<ActionDefinition<*>> get() = listOf(tuneRadio)

    // region Availability

    private fun availabilityOf(state: GameState): Availability {
        val screen = state.screen
        val radio = (screen as? Screen.Viewer)?.radio
        val onGear = screen is Screen.Viewer && screen.app in GEAR_VIEWERS ||
            (screen as? Screen.ListMenu)?.kind == MenuKind.PHONE_CONTACTS
        val walking = FieldControl.inControl(state)
        if (!onGear && !walking) return Availability.Hidden
        if (state.player?.pokegearCards?.contains(PokegearCard.RADIO) == false) {
            return Availability.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, "The Pokégear has no Radio Card yet", "the Goldenrod Radio Tower's quiz gives it")
        }
        val stations = radio?.channels?.flatMap { it.stations } ?: RadioStation.entries.filter { it != RadioStation.COMMERCIALS }
        return Availability.Available(mapOf("station" to stations.map { Choice(it.wire, it.wire) }), listed = radio != null)
    }

    // endregion

    private val GEAR_VIEWERS = setOf(ViewerApp.POKEGEAR_RADIO, ViewerApp.POKEGEAR_MAP)
}
