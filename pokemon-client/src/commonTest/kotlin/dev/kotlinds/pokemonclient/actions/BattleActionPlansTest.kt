package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleOutcome
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.state.VolatileStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Battle recipes on a scripted game: item use in battle (pocket found from the bag's contents, outcome told by the
 * screens), attack checks made before any press, spread moves' single target, SHIFT confirmed after choosing a
 * Pokémon on the battle party grid, HM moves that can't be forgotten, and `switch` refused while trapped.
 */
class BattleActionPlansTest {

    private val thunderbolt = KnownMove(Named(MoveId(85), "Thunderbolt"), 15, 15, "Electric")
    private val icyWind = KnownMove(Named(MoveId(196), "Icy Wind"), 15, 15, "Ice")
    private val cut = KnownMove(Named(MoveId(15), "Cut"), 30, 30, "Normal")

    private fun mon(n: Int, hp: Int = 20) = PartyMon(
        id = MonId(n.toLong(), 1), slot = n - 1, species = Named(SpeciesId(n), "MON$n"), nickname = null, level = 10,
        hp = hp, maxHp = 20, status = null, types = emptyList(), heldItem = null, ability = null, moves = listOf(thunderbolt, icyWind),
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    private fun battler(ref: BattlerRef, moves: List<KnownMove> = emptyList(), volatile: Set<VolatileStatus> = emptySet()) = BattlerState(
        ref, if (ref.isPlayerSide) MonId(1, 1) else null, Named(SpeciesId(1), "MON"), null, 10, 20, 20, null, volatile, emptyMap(), listOf("Normal"), moves,
    )

    private fun battle(volatile: Set<VolatileStatus> = emptySet(), double: Boolean = false) = BattleState(
        BattleKind.WILD, double, BattlerRef.PLAYER_LEFT,
        listOf(battler(BattlerRef.PLAYER_LEFT, listOf(thunderbolt, icyWind), volatile), battler(BattlerRef.FOE_LEFT)),
        emptyList(), listOf(MonId(1, 1), MonId(2, 1)), null,
    )

    private val command = Screen.BattleCommand(
        BattlerRef.PLAYER_LEFT,
        listOf(Entry("option:fight", "FIGHT"), Entry("option:bag", "BAG"), Entry("option:run", "RUN"), Entry("option:pokemon", "POKéMON")),
        Cursor.At(0), Topology.grid(4, 2),
    )

    private val ether = BagItem(Named(ItemId(38), "Ether"), 1)

    /** A scripted battle: [onA] answers A on (screen, highlighted id); B goes back to the command menu. */
    private class Ui(val battle: () -> BattleState?, val party: List<PartyMon>, val bag: List<BagPocket>, start: Screen) {
        var onA: (Screen, String?) -> Screen = { s, _ -> s }
        var onB: (Screen) -> Screen = { it }
        val game = FakeGame(start, state = { GameState(0, it, null, party, bag, battle(), null) })

        init {
            game.onPress = { button, screen ->
                val selectable = screen as? Screen.Selectable
                val at = (selectable?.cursor as? Cursor.At)?.index
                when (button) {
                    Button.A -> onA(screen, at?.let { selectable.entries[it].id })
                    Button.B -> onB(screen)
                    else -> {
                        val next = at?.let { selectable.topology.next(it, button) }
                        if (selectable == null || next == null) screen else withCursor(selectable, next)
                    }
                }
            }
        }
    }

    private fun battleBag(pocket: String, items: List<String>) = Screen.Bag(
        pocket, listOf("HP/PP RESTORE", "STATUS HEALERS", "POKé BALLS", "BATTLE ITEMS"), 0, 1, true,
        items.map { Entry(it, it) } + Entry("option:cancel", "CANCEL"), Cursor.At(0), Topology.vertical(items.size + 1),
        pocketContents = mapOf("pocket:hp_pp_restore" to listOf(ItemId(38)), "pocket:status_healers" to listOf(ItemId(28)), "pocket:poke_balls" to emptyList(), "pocket:battle_items" to emptyList()),
    )

    private val bagMenu = battleBag("HP/PP RESTORE", listOf("pocket:hp_pp_restore", "pocket:status_healers", "pocket:poke_balls", "pocket:battle_items"))

    private fun battleGrid(party: List<PartyMon>) = Screen.PartyGrid(
        PartyPurpose.BATTLE_USE_ITEM, party.map { Entry(it.id.toString(), it.displayName) } + Entry("option:cancel", "CANCEL"),
        Cursor.At(0), Topology.vertical(party.size + 1), CancelBehavior.CLOSES,
    )

    private val restoreWhichMove = Screen.ListMenu(
        MenuKind.OTHER, listOf(Entry("move:85", "Thunderbolt (Electric, 3/15 PP)"), Entry("move:196", "Icy Wind (Ice, 15/15 PP)"), Entry("option:cancel", "CANCEL")),
        Cursor.At(0), Topology.vertical(3),
    )

    @Test
    fun useItemInBattleOpensThePocketHoldingTheItemAndPicksTheMove() {
        val party = listOf(mon(1), mon(2))
        val chosen = mutableListOf<String>()
        val ui = Ui({ battle() }, party, listOf(BagPocket("medicine", listOf(ether))), command)
        ui.onA = { screen, id ->
            id?.let { chosen += it }
            when {
                screen is Screen.BattleCommand && id == "option:bag" -> bagMenu
                screen is Screen.Bag && id == "pocket:hp_pp_restore" -> battleBag("HP/PP RESTORE", listOf("item:38"))
                screen is Screen.Bag && id == "item:38" -> Screen.ContextMenu(null, listOf(Entry("option:use", "USE"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.ContextMenu && id == "option:use" -> battleGrid(party)
                screen is Screen.PartyGrid -> restoreWhichMove
                screen is Screen.ListMenu -> Screen.Dialogue(TextSource.BATTLE, null, "PP was restored.", Awaiting.INPUT)
                // The bag closes: the turn plays.
                screen is Screen.Dialogue -> Screen.Battle(Awaiting.ANIMATION)
                else -> screen
            }
        }
        val outcome = PartyBagPlans.useItem.run(GameAction.UseItem(ItemRef("Ether"), MonId(2, 1), MoveRef("Icy Wind")), ui.game.context())
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(listOf("option:bag", "pocket:hp_pp_restore", "item:38", "option:use", "mon:00000002.00000001", "move:196"), chosen)
    }

    @Test
    fun useItemInBattleWithoutEffectGoesBackToTheCommandMenu() {
        val party = listOf(mon(1))
        val ui = Ui({ battle() }, party, listOf(BagPocket("medicine", listOf(ether))), command)
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand && id == "option:bag" -> bagMenu
                screen is Screen.Bag && id == "pocket:hp_pp_restore" -> battleBag("HP/PP RESTORE", listOf("item:38"))
                screen is Screen.Bag && id == "item:38" -> Screen.ContextMenu(null, listOf(Entry("option:use", "USE"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.ContextMenu -> battleGrid(party)
                screen is Screen.PartyGrid -> restoreWhichMove
                screen is Screen.ListMenu -> Screen.Dialogue(TextSource.BATTLE, null, "It won't have any effect.", Awaiting.INPUT)
                // Refused: back to the party screen.
                screen is Screen.Dialogue -> battleGrid(party)
                else -> screen
            }
        }
        ui.onB = { command }
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.useItem.run(GameAction.UseItem(ItemRef("item:38"), MonId(1, 1), MoveRef("move:85")), ui.game.context()))
        assertEquals(UnavailableReason.NO_EFFECT, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertIs<Screen.BattleCommand>(ui.game.screen)
    }

    @Test
    fun useItemListsTheMovesWhenAPpItemHasNoMove() {
        val party = listOf(mon(1))
        val ui = Ui({ battle() }, party, listOf(BagPocket("medicine", listOf(ether))), command)
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand -> bagMenu
                screen is Screen.Bag && id == "pocket:hp_pp_restore" -> battleBag("HP/PP RESTORE", listOf("item:38"))
                screen is Screen.Bag -> Screen.ContextMenu(null, listOf(Entry("option:use", "USE"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.ContextMenu -> battleGrid(party)
                screen is Screen.PartyGrid -> restoreWhichMove
                else -> screen
            }
        }
        ui.onB = { command }
        val failed = assertIs<ActionOutcome.Failed>(PartyBagPlans.useItem.run(GameAction.UseItem(ItemRef("Ether"), MonId(1, 1)), ui.game.context()))
        val error = assertIs<ActionError.InvalidParameter>(failed.error)
        assertEquals("move", error.parameter)
        assertTrue(error.allowed.any { "move:85" in it })
        assertIs<Screen.BattleCommand>(ui.game.screen, "the item screens are closed again")
    }

    @Test
    fun useItemParsesAListOfUses() {
        val json = Json.parseToJsonElement("""{"type":"use_item","items":[{"item":"Potion","target":"mon:00000001.00000001"},{"item":"Max Ether","target":"mon:00000002.00000001","move":"Sacred Fire"}]}""").jsonObject
        val action = assertIs<GameAction.UseItem>(ActionRegistry.of().parse(json, ActionMode.ASSISTED).getOrThrow())
        assertEquals(2, action.uses.size)
        assertEquals(MoveRef("Sacred Fire"), action.uses[1].move)
        assertEquals(MonId(2, 1), action.uses[1].target)
        val single = Json.parseToJsonElement("""{"type":"use_item","item":"Max Ether","target":"mon:00000001.00000001","move":"move:221"}""").jsonObject
        assertEquals(MoveRef("move:221"), assertIs<GameAction.UseItem>(ActionRegistry.of().parse(single, ActionMode.ASSISTED).getOrThrow()).move)
    }

    @Test
    fun attackWithAMoveTheMonDoesntKnowFailsBeforeAnyPress() {
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), command)
        val failed = assertIs<ActionOutcome.Failed>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Ice Shard")), ui.game.context()))
        assertIs<ActionError.InvalidParameter>(failed.error)
        assertTrue(ui.game.presses.isEmpty(), "the move screen wasn't opened")
    }

    @Test
    fun spreadMovesConfirmTheirOnlyTargetWithoutBeingGivenOne() {
        val ui = Ui({ battle(double = true) }, listOf(mon(1)), emptyList(), command)
        var confirmed: String? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand -> Screen.MoveSelect(
                    MoveContext.BATTLE, MonId(1, 1), null,
                    listOf(Entry("move:85", "Thunderbolt (Electric, 15/15 PP)"), Entry("move:196", "Icy Wind (Ice, 15/15 PP)"), Entry("option:cancel", "CANCEL")),
                    Cursor.At(0), Topology.vertical(3), CancelBehavior.CLOSES,
                )
                screen is Screen.MoveSelect -> Screen.TargetSelect(listOf(Entry("target:all", "MAREEP + MARILL"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
                screen is Screen.TargetSelect -> { confirmed = id; Screen.Battle(Awaiting.ANIMATION) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Icy Wind")), ui.game.context()))
        assertEquals("target:all", confirmed)
    }

    @Test
    fun choosingAPokemonOnTheReplacementGridAlsoConfirmsShift() {
        val party = listOf(mon(1, hp = 0), mon(2))
        val grid = Screen.PartyGrid(
            PartyPurpose.BATTLE_REPLACE_FAINTED,
            listOf(Entry(party[0].id.toString(), "MON1 FAINTED", selectable = false), Entry(party[1].id.toString(), "MON2"), Entry("option:cancel", "CANCEL", selectable = false)),
            Cursor.At(1), Topology.vertical(3), CancelBehavior.NONE,
        )
        val ui = Ui({ battle() }, party, emptyList(), grid)
        var shifted = false
        ui.onA = { screen, id ->
            when {
                screen is Screen.PartyGrid -> Screen.ContextMenu(MonId(2, 1), listOf(Entry("option:shift", "SHIFT"), Entry("option:summary", "SUMMARY"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(3))
                screen is Screen.ContextMenu && id == "option:shift" -> { shifted = true; Screen.Battle(Awaiting.ANIMATION) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(BasicPlans.choose.run(GameAction.Choose(party[1].id.toString()), ui.game.context()))
        assertTrue(shifted)
    }

    @Test
    fun forgettingAnHmIsATypedError() {
        val list = Screen.MoveSelect(
            MoveContext.FORGET_IN_BATTLE, MonId(1, 1), Named(MoveId(53), "Flamethrower"),
            listOf(
                Entry("move:15", "Cut (Normal, 30/30 PP)", selectable = false), Entry("move:85", "Thunderbolt (Electric, 15/15 PP)"),
                Entry("move:53", "Flamethrower (Fire) (new: don't learn it)"), Entry("option:cancel", "CANCEL"),
            ),
            Cursor.At(1), Topology.vertical(4), CancelBehavior.CLOSES,
        )
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), list)
        val failed = assertIs<ActionOutcome.Failed>(BattlePlans.learnMove.run(GameAction.LearnMove(MoveRef("Cut")), ui.game.context()))
        val error = assertIs<ActionError.HmCannotForget>(failed.error)
        assertEquals("HM_CANNOT_FORGET", error.code)
        assertEquals("Cut", error.move)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun switchIsUnavailableWhileTrapped() {
        val party = listOf(mon(1), mon(2))
        val trapped = GameState(0, command, null, party, emptyList(), battle(setOf(VolatileStatus.Trapped)), null)
        val availability = CommonActions.switch.spec.availability(trapped)
        assertEquals(UnavailableReason.TRAPPED, assertIs<Availability.Unavailable>(availability).reason)
        val free = GameState(0, command, null, party, emptyList(), battle(), null)
        assertIs<Availability.Available>(CommonActions.switch.spec.availability(free))
    }

    // region Encore, Disable, Struggle

    private val waterGun = KnownMove(Named(MoveId(55), "Water Gun"), 20, 25, "Water")

    private fun battleWith(vararg volatile: VolatileStatus, moves: List<KnownMove> = listOf(thunderbolt, icyWind), double: Boolean = false) = BattleState(
        BattleKind.WILD, double, BattlerRef.PLAYER_LEFT,
        listOf(battler(BattlerRef.PLAYER_LEFT, moves, volatile.toSet()), battler(BattlerRef.FOE_LEFT)),
        emptyList(), listOf(MonId(1, 1), MonId(2, 1)), null,
    )

    private fun moveList(vararg entries: Entry) = Screen.MoveSelect(
        MoveContext.BATTLE, MonId(1, 1), null, entries.toList() + Entry("option:cancel", "CANCEL"),
        Cursor.At(0), Topology.vertical(entries.size + 1), CancelBehavior.CLOSES,
    )

    @Test
    fun underEncoreFightAlonePlaysTheEncoredMoveAndIsASuccess() {
        // Race: attack under Encore answered UNEXPECTED_SCREEN with performed:[] while the turn was played (x5).
        val encored = VolatileStatus.Encored(Named(MoveId(85), "Thunderbolt"), 3)
        val ui = Ui({ battleWith(encored) }, listOf(mon(1)), emptyList(), command)
        // The game skips the move list: FIGHT starts the turn.
        ui.onA = { screen, id -> if (screen is Screen.BattleCommand && id == "option:fight") Screen.Battle(Awaiting.ANIMATION) else screen }
        val done = assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Thunderbolt")), ui.game.context()))
        assertTrue(done.detail.orEmpty().contains("Encore"), done.detail)
        assertEquals(listOf(Button.A), ui.game.presses)
    }

    @Test
    fun underEncoreInADoubleBattleTheNextPokemonsMenuAfterFightIsASuccess() {
        val encored = VolatileStatus.Encored(Named(MoveId(55), "Water Gun"), 2)
        val ui = Ui({ battleWith(encored, moves = listOf(waterGun, icyWind), double = true) }, listOf(mon(1)), emptyList(), command)
        ui.onA = { screen, id -> if (screen is Screen.BattleCommand && id == "option:fight") command.copy(actor = BattlerRef.PLAYER_RIGHT) else screen }
        assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("move:55"), BattlerRef.FOE_RIGHT), ui.game.context()))
    }

    @Test
    fun underEncoreAnotherMoveIsRefusedBeforeAnyPress() {
        val encored = VolatileStatus.Encored(Named(MoveId(85), "Thunderbolt"), 3)
        val ui = Ui({ battleWith(encored) }, listOf(mon(1)), emptyList(), command)
        val failed = assertIs<ActionOutcome.Failed>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Icy Wind")), ui.game.context()))
        assertEquals(UnavailableReason.ENCORED, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(ui.game.presses.isEmpty())
        val choices = assertIs<Availability.Available>(CommonActions.attack.spec.availability(GameState(0, command, null, listOf(mon(1)), emptyList(), battleWith(encored), null)))
        assertEquals(listOf("move:85"), choices.choices["move"]!!.map { it.value })
    }

    @Test
    fun withoutEncoreTheMoveListIsStillRequiredAfterFight() {
        // FIGHT leading anywhere else than the move list (not under Encore) is an error naming the screen's kind.
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), command)
        ui.onA = { screen, id -> if (screen is Screen.BattleCommand && id == "option:fight") command.copy(actor = BattlerRef.PLAYER_RIGHT) else screen }
        val failed = assertIs<ActionOutcome.Failed>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Thunderbolt")), ui.game.context()))
        val error = assertIs<ActionError.UnexpectedScreen>(failed.error)
        assertEquals("Expected the move list, but the screen is battle_command", error.message)
    }

    @Test
    fun whenWhatTheStateCantTellLeavesNoMoveFightPlaysStruggle() {
        // Review impl13 M5: Torment, Imprison, Gravity, Heal Block or a Choice item leave no move (StruggleCheck): the game
        // skips the move list (battle_controller_player.c:377) and the turn is played; not an UNEXPECTED_SCREEN.
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), command)
        ui.onA = { screen, id -> if (screen is Screen.BattleCommand && id == "option:fight") Screen.Battle(Awaiting.ANIMATION) else screen }
        val done = assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Thunderbolt")), ui.game.context()))
        assertEquals(BattleMoveChoice.STRUGGLED_UNSEEN, done.detail)
        assertEquals(listOf(Button.A), ui.game.presses)
        // In a double battle, the other Pokémon's command menu right after FIGHT is the turn going on too.
        val double = Ui({ battle(double = true) }, listOf(mon(1)), emptyList(), command)
        double.onA = { screen, id -> if (screen is Screen.BattleCommand && id == "option:fight") command.copy(actor = BattlerRef.PLAYER_RIGHT) else screen }
        assertEquals(BattleMoveChoice.STRUGGLED_UNSEEN, assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Thunderbolt")), double.game.context())).detail)
    }

    @Test
    fun tauntBlocksTheMovesWithoutBasePowerLikeTheGame() {
        // Review impl13 B10: StruggleCheck (overlay_12_0224E4FC.c:1983) blocks `moveData.power == 0` under Taunt.
        val taunted = setOf(VolatileStatus.Taunted)
        fun refused(move: KnownMove) = BattleMoveChoice.refusal(battler(BattlerRef.PLAYER_LEFT, listOf(move, thunderbolt), taunted), MoveRef("move:${move.move.id.value}"))
        val noPower = KnownMove(Named(MoveId(1000), "Powerless"), 10, 10, "Normal", power = 0, category = dev.kotlinds.pokemonclient.data.MoveCategory.PHYSICAL)
        assertEquals(UnavailableReason.TAUNTED, assertIs<ActionError.Unavailable>(refused(noPower)).reason)
        val withPower = KnownMove(Named(MoveId(1001), "Powered"), 10, 10, "Normal", power = 40, category = dev.kotlinds.pokemonclient.data.MoveCategory.STATUS)
        assertEquals(null, refused(withPower))
        // The power unknown: the category tells (a status move refused, a damaging one allowed).
        val status = KnownMove(Named(MoveId(1002), "Growl"), 10, 10, "Normal", category = dev.kotlinds.pokemonclient.data.MoveCategory.STATUS)
        assertEquals(UnavailableReason.TAUNTED, assertIs<ActionError.Unavailable>(refused(status)).reason)
        assertEquals(null, refused(thunderbolt.copy(category = dev.kotlinds.pokemonclient.data.MoveCategory.SPECIAL)))
        // Not taunted: nothing refused.
        assertEquals(null, BattleMoveChoice.refusal(battler(BattlerRef.PLAYER_LEFT, listOf(noPower, thunderbolt), emptySet()), MoveRef("move:1000")))
    }

    @Test
    fun aDisabledMoveIsRefusedBeforeAnyPressAndAnotherOneIsUsed() {
        val disabled = VolatileStatus.Disabled(Named(MoveId(85), "Thunderbolt"), 3)
        val ui = Ui({ battleWith(disabled) }, listOf(mon(1)), emptyList(), command)
        val failed = assertIs<ActionOutcome.Failed>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Thunderbolt")), ui.game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.DISABLED, error.reason)
        assertEquals("Thunderbolt is disabled (3 more turns). use another move", error.message)
        assertTrue(ui.game.presses.isEmpty())
        var used: String? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand -> moveList(Entry("move:85", "Thunderbolt (Electric, 15/15 PP)", selectable = false), Entry("move:196", "Icy Wind (Ice, 15/15 PP)"))
                screen is Screen.MoveSelect -> { used = id; Screen.Battle(Awaiting.ANIMATION) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Icy Wind")), ui.game.context()))
        assertEquals("move:196", used)
        val choices = assertIs<Availability.Available>(CommonActions.attack.spec.availability(GameState(0, command, null, listOf(mon(1)), emptyList(), battleWith(disabled), null)))
        assertEquals(listOf("move:196"), choices.choices["move"]!!.map { it.value })
    }

    @Test
    fun aMoveTheListRefusesBacksOutToTheCommandMenu() {
        // Codex: the list was left open after "NOT_SELECTABLE: Water Gun can't be chosen for Water Gun".
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), command)
        ui.onA = { screen, _ ->
            if (screen is Screen.BattleCommand) moveList(Entry("move:85", "Thunderbolt (Electric, 15/15 PP)", selectable = false), Entry("move:196", "Icy Wind (Ice, 15/15 PP)")) else screen
        }
        ui.onB = { command }
        val failed = assertIs<ActionOutcome.Failed>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Thunderbolt")), ui.game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.MOVE_REFUSED, error.reason)
        assertTrue(error.hint.orEmpty().contains("move:196"), error.hint)
        assertIs<Screen.BattleCommand>(ui.game.screen, "the move list was closed")
    }

    @Test
    fun attackGoesOnFromTheMoveListLeftOpen() {
        val list = moveList(Entry("move:85", "Thunderbolt (Electric, 15/15 PP)"), Entry("move:196", "Icy Wind (Ice, 15/15 PP)"))
        val ui = Ui({ battle() }, listOf(mon(1)), emptyList(), list)
        var used: String? = null
        ui.onA = { screen, id -> if (screen is Screen.MoveSelect) { used = id; Screen.Battle(Awaiting.ANIMATION) } else screen }
        assertIs<Availability.Available>(CommonActions.attack.spec.availability(GameState(0, list, null, listOf(mon(1)), emptyList(), battle(), null)))
        assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Icy Wind")), ui.game.context()))
        assertEquals("move:196", used)
    }

    @Test
    fun withNoMoveToChooseStruggleIsPlayedByFightAlone() {
        val empty = listOf(thunderbolt.copy(pp = 0), icyWind.copy(pp = 0))
        val ui = Ui({ battleWith(moves = empty) }, listOf(mon(1)), emptyList(), command)
        val state = GameState(0, command, null, listOf(mon(1)), emptyList(), battleWith(moves = empty), null)
        val choices = assertIs<Availability.Available>(CommonActions.attack.spec.availability(state))
        assertEquals(listOf("move:165"), choices.choices["move"]!!.map { it.value })
        val refused = assertIs<ActionOutcome.Failed>(BasicPlans.attack.run(GameAction.Attack(MoveRef("Thunderbolt")), ui.game.context()))
        assertEquals(UnavailableReason.NO_PP, assertIs<ActionError.Unavailable>(refused.error).reason)
        assertTrue(ui.game.presses.isEmpty())
        ui.onA = { screen, id -> if (screen is Screen.BattleCommand && id == "option:fight") Screen.Battle(Awaiting.ANIMATION) else screen }
        val done = assertIs<ActionOutcome.Done>(BasicPlans.attack.run(GameAction.Attack(MoveRef("move:165")), ui.game.context()))
        assertTrue(done.detail.orEmpty().contains("Struggle"), done.detail)
    }

    // endregion

    // region throw_ball

    /**
     * A throw: BAG → POKé BALLS → Poké Ball → USE; the ball lands [landing] frames after A: [shakes] (null: not read)
     * and [outcome] are set, and the battle goes on to [after] (null battle: it ended).
     */
    private fun throwUi(
        shakes: Int?, outcome: BattleOutcome?, after: Screen, battleAfter: Boolean,
        balls: List<BagItem> = listOf(BagItem(Named(ItemId(4), "Poké Ball"), 5)),
        pocket: Screen.Bag = battleBag("POKé BALLS", listOf("item:4")),
    ): Ui {
        var current: BattleState? = battle()
        val ui = Ui({ current }, listOf(mon(1)), listOf(BagPocket("balls", balls)), command)
        var thrownAt: Long? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.BattleCommand && id == "option:bag" -> bagMenu
                screen is Screen.Bag && id == "pocket:poke_balls" -> pocket
                screen is Screen.Bag && id != null && id.startsWith("item:") -> { thrown = id; Screen.ContextMenu(null, listOf(Entry("option:use", "USE"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2)) }
                screen is Screen.ContextMenu && id == "option:use" -> { thrownAt = ui.game.console.frame; Screen.Battle(Awaiting.ANIMATION) }
                screen is Screen.YesNo -> { current = null; Screen.Overworld(null, Awaiting.INPUT) }
                else -> screen
            }
        }
        ui.game.onFrame = { frame, screen ->
            val t = thrownAt
            when {
                t == null -> screen
                frame - t == 100L -> { current = current?.copy(ballShakes = shakes, outcome = outcome); screen }
                frame - t == 300L -> { if (!battleAfter) current = null; after }
                else -> screen
            }
        }
        return ui
    }

    /** The ball chosen in the pocket by the last throw ([throwUi]). */
    private var thrown: String? = null

    /**
     * The game in French: the pocket shows "SUPER BALL     ×3" (another name, another layout than "Great Ball x3"):
     * the ball is found by its id or by the game's own name in the bag's data, never in the label; a ball the bag
     * doesn't have is refused before anything is pressed, with the bag's balls by id and name.
     */
    @Test
    fun theBallIsChosenByTheBagsDataNotByItsLabel() {
        val balls = listOf(BagItem(Named(ItemId(4), "POKé BALL"), 5), BagItem(Named(ItemId(3), "SUPER BALL"), 3))
        val pocket = Screen.Bag(
            "POKé BALLS", listOf("SOINS", "STATUT", "POKé BALLS", "COMBAT"), 0, 1, true,
            listOf(Entry("item:4", "POKé BALL       ×5"), Entry("item:3", "SUPER BALL      ×3"), Entry("option:cancel", "RETOUR")), Cursor.At(0), Topology.vertical(3),
        )
        for (asked in listOf("item:3", "super ball")) {
            thrown = null
            val ui = throwUi(1, null, command, battleAfter = true, balls = balls, pocket = pocket)
            val done = BattlePlans.throwBall.run(GameAction.ThrowBall(ItemRef(asked)), ui.game.context()).let { assertIs<ActionOutcome.Done>(it, it.toString()) }
            assertEquals("item:3: broke free after 1 shake", done.detail, asked)
            assertEquals("item:3", thrown, asked)
        }
        // The English name isn't this game's: refused at once, listing the bag's balls from the data.
        val ui = throwUi(1, null, command, battleAfter = true, balls = balls, pocket = pocket)
        val failed = assertIs<ActionOutcome.Failed>(BattlePlans.throwBall.run(GameAction.ThrowBall(ItemRef("Great Ball")), ui.game.context()))
        assertEquals(listOf("item:4 (POKé BALL x5)", "item:3 (SUPER BALL x3)"), assertIs<ActionError.InvalidParameter>(failed.error).allowed)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun aNormalCaptureIsACapture() {
        val nickname = Screen.YesNo("Give a nickname?", listOf(Entry("option:yes", "YES"), Entry("option:no", "NO")), Cursor.At(0), Topology.vertical(2))
        val ui = throwUi(BattleState.CAUGHT_SHAKES, BattleOutcome.CAUGHT, nickname, battleAfter = true)
        val done = BattlePlans.throwBall.run(GameAction.ThrowBall(ItemRef("item:4")), ui.game.context()).let { assertIs<ActionOutcome.Done>(it, it.toString()) }
        assertTrue(done.detail.orEmpty().startsWith("item:4: caught MON"), done.detail)
    }

    @Test
    fun aCaptureThatEndsTheBattleIsACaptureEvenWithoutTheShakes() {
        val ui = throwUi(null, BattleOutcome.CAUGHT, Screen.Overworld(null, Awaiting.INPUT), battleAfter = false)
        val done = BattlePlans.throwBall.run(GameAction.ThrowBall(ItemRef("item:4")), ui.game.context()).let { assertIs<ActionOutcome.Done>(it, it.toString()) }
        assertTrue(done.detail.orEmpty().startsWith("item:4: caught MON"), done.detail)
    }

    @Test
    fun aRoamerFleeingAfterBreakingFreeIsNotACapture() {
        // Race (Codex, Raikou): "caught RAIKOU Lv40" with "Aargh! Almost had it!" / "The wild RAIKOU fled!".
        val ui = throwUi(2, BattleOutcome.FOE_FLED, Screen.Overworld(null, Awaiting.INPUT), battleAfter = false)
        val done = BattlePlans.throwBall.run(GameAction.ThrowBall(ItemRef("item:4")), ui.game.context()).let { assertIs<ActionOutcome.Done>(it, it.toString()) }
        assertEquals("item:4: broke free after 2 shakes, then the wild MON fled: the battle is over, nothing caught", done.detail)
    }

    @Test
    fun aBallBrokenFreeLeavesTheBattleGoingOn() {
        val ui = throwUi(1, null, command, battleAfter = true)
        val done = BattlePlans.throwBall.run(GameAction.ThrowBall(ItemRef("item:4")), ui.game.context()).let { assertIs<ActionOutcome.Done>(it, it.toString()) }
        assertEquals("item:4: broke free after 1 shake", done.detail)
    }

    // endregion

    // region learn_move

    private val cutMove = KnownMove(Named(MoveId(15), "Cut"), 30, 30, "Normal")
    private val hmData = StubGameData(machines = mapOf(dev.kotlinds.pokemonclient.data.MachineId(93) to MoveId(15)))
    private fun learner() = mon(1).copy(moves = listOf(cutMove, thunderbolt, icyWind, KnownMove(Named(MoveId(33), "Tackle"), 35, 35, "Normal")))

    private val forgetPrompt = Screen.YesNo(
        null, listOf(Entry("option:forget", "FORGET A MOVE"), Entry("option:keep", "KEEP OLD MOVES")), Cursor.At(0), Topology.vertical(2),
        learning = dev.kotlinds.pokemonclient.state.MoveOffer(MonId(1, 1), "MON1", Named(MoveId(53), "Flamethrower")),
    )

    @Test
    fun learnMoveRefusesAnHmToForgetOnTheQuestionBeforeAnyPress() {
        // Race (Codex): learn_move with an HM left the list of moves to forget open.
        val ui = Ui({ battle() }, listOf(learner()), emptyList(), forgetPrompt)
        ui.game.data = hmData
        val failed = assertIs<ActionOutcome.Failed>(BattlePlans.learnMove.run(GameAction.LearnMove(MoveRef("Cut")), ui.game.context()))
        assertEquals(ActionError.HmCannotForget("Cut"), failed.error)
        assertTrue(ui.game.presses.isEmpty())
        assertEquals(forgetPrompt, ui.game.screen, "the question stays for the next call")
    }

    @Test
    fun learnMoveForgetsAnotherMoveFromTheSameQuestion() {
        val ui = Ui({ battle() }, listOf(learner()), emptyList(), forgetPrompt)
        ui.game.data = hmData
        val list = Screen.MoveSelect(
            MoveContext.FORGET_IN_BATTLE, MonId(1, 1), Named(MoveId(53), "Flamethrower"),
            listOf(
                Entry("move:15", "Cut (Normal, 30/30 PP)", selectable = false), Entry("move:85", "Thunderbolt (Electric, 15/15 PP)"),
                Entry("move:196", "Icy Wind (Ice, 15/15 PP)"), Entry("move:33", "Tackle (Normal, 35/35 PP)"),
                Entry("move:53", "Flamethrower (Fire) (new: don't learn it)"), Entry("option:cancel", "CANCEL"),
            ),
            Cursor.At(1), Topology.vertical(6), CancelBehavior.CLOSES,
        )
        var forgot: String? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.YesNo -> list
                screen is Screen.MoveSelect -> { forgot = id; Screen.Battle(Awaiting.ANIMATION) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(BattlePlans.learnMove.run(GameAction.LearnMove(MoveRef("Tackle")), ui.game.context()))
        assertEquals("move:33", forgot)
    }

    @Test
    fun learnMoveWithoutForgetFromTheListLeftOpenGivesUpTheNewMove() {
        val list = Screen.MoveSelect(
            MoveContext.FORGET_IN_BATTLE, MonId(1, 1), Named(MoveId(53), "Flamethrower"),
            listOf(Entry("move:15", "Cut (Normal, 30/30 PP)", selectable = false), Entry("move:85", "Thunderbolt (Electric, 15/15 PP)"), Entry("option:cancel", "CANCEL")),
            Cursor.At(1), Topology.vertical(3), CancelBehavior.CLOSES,
        )
        val giveUp = Screen.YesNo(null, listOf(Entry("option:give_up", "GIVE UP"), Entry("option:keep", "DON'T GIVE UP")), Cursor.At(0), Topology.vertical(2))
        val ui = Ui({ battle() }, listOf(learner()), emptyList(), list)
        var gaveUp = false
        ui.onA = { screen, id ->
            when {
                screen is Screen.MoveSelect && id == "option:cancel" -> giveUp
                screen is Screen.YesNo && id == "option:give_up" -> { gaveUp = true; Screen.Battle(Awaiting.ANIMATION) }
                else -> screen
            }
        }
        assertIs<ActionOutcome.Done>(BattlePlans.learnMove.run(GameAction.LearnMove(null), ui.game.context()))
        assertTrue(gaveUp)
    }

    // endregion

    private companion object {
        fun withCursor(screen: Screen.Selectable, index: Int): Screen = when (screen) {
            is Screen.ListMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.PartyGrid -> screen.copy(cursor = Cursor.At(index))
            is Screen.ContextMenu -> screen.copy(cursor = Cursor.At(index))
            is Screen.Bag -> screen.copy(cursor = Cursor.At(index))
            is Screen.BattleCommand -> screen.copy(cursor = Cursor.At(index))
            is Screen.MoveSelect -> screen.copy(cursor = Cursor.At(index))
            is Screen.TargetSelect -> screen.copy(cursor = Cursor.At(index))
            is Screen.YesNo -> screen.copy(cursor = Cursor.At(index))
            else -> screen
        }
    }
}
