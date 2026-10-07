package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.PartyMon

/**
 * Who can learn a TM / HM, from the game's data ([SpeciesInfo.machines]): what the party screen of a TM shows as
 * ABLE / UNABLE / LEARNED, known before opening the bag (`teach` refuses an UNABLE Pokémon before pressing anything,
 * `lookup machine` and `buy` say who of the party can learn it).
 */
object MachineCompatibility {

    /** What [machine] would do for one Pokémon, like the game's party screen labels it. */
    enum class Fit { ABLE, LEARNED, UNABLE }

    /** [mon] and [machine], from the game's data ([of] below). */
    fun of(data: GameData, mon: PartyMon, machine: MachineId): Fit =
        of(mon.isEgg, mon.moves.map { it.move.id }, data.machineMove(machine), data.species(mon.species.id)?.machines?.contains(machine) == true)

    /**
     * The rule of the TM party screen (pokeheartgold party_context_menu.c, the ABLE / UNABLE / LEARNED labels): an Egg
     * can't learn, a Pokémon knowing the machine's [move] already has it, else its species decides ([compatible]: the
     * machine is in its personal data's TM / HM set). The one rule, shared by the screen's decoder (which reads the
     * Pokémon from RAM) and the actions (which read them from the state).
     */
    fun of(isEgg: Boolean, knownMoves: Collection<MoveId>, move: MoveId?, compatible: Boolean): Fit = when {
        isEgg -> Fit.UNABLE
        move != null && move in knownMoves -> Fit.LEARNED
        compatible -> Fit.ABLE
        else -> Fit.UNABLE
    }

    /** The party Pokémon able to learn [machine] ("mon:… NAME"; one knowing the move already says so). */
    fun partyCanLearn(data: GameData, party: List<PartyMon>, machine: MachineId): List<String> = party.mapNotNull { mon ->
        when (of(data, mon, machine)) {
            Fit.ABLE -> "${mon.id} ${mon.displayName}"
            Fit.LEARNED -> "${mon.id} ${mon.displayName} (already knows it)"
            Fit.UNABLE -> null
        }
    }
}
