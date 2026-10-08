package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.OVERWORLD
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.bag
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.field
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.item
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.mon
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.move
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ShopItem
import dev.kotlinds.pokemonclient.state.TextSpeed
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The shared steps of the recipes ([RecipeBase.openStartMenuEntry], [RecipeBase.bagItem], [RecipeBase.closeToOverworld],
 * [BattleRecipes.forgetRefusal], [ServiceRecipes.openStorage]...) are methods of the chain: a game overriding one of
 * them ([dev.kotlinds.pokemonclient.PokemonGame.recipes]) has it played by every recipe going through it, and by the
 * walking engine ([RecipeBase.perform], on the context's game), without copying any recipe; a game without the override keeps the common
 * step. The guard against static copies of a step beside the chain (they would skip the game's own).
 */
class RecipeStepsTest {

    /** What a game's own step was asked, in order; [STEP_ERROR] is its answer, so the recipe stops right after it. */
    private val asked = mutableListOf<String>()

    private fun stopped(outcome: ActionOutcome) = assertEquals(STEP_ERROR, assertIs<ActionOutcome.Failed>(outcome).error, outcome.toString())

    /** A game whose start menu is its own: each entry asked is recorded, nothing is pressed. */
    private fun ownStartMenu() = object : Recipes() {
        override fun openStartMenuEntry(context: PlanContext, entryId: String): Step<GameState> {
            asked += entryId
            return Step.Failed(STEP_ERROR)
        }
    }

    @Test
    fun aGamesOwnStartMenuIsPlayedByEveryRecipeOpeningIt() {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1), mon(2)), bag = listOf(BagPocket("items", listOf(item(17, "Potion")))))
        ui.game.recipes = ownStartMenu()
        val recipes = ui.game.recipes
        stopped(RecipeBase.perform(GameAction.SetOptions(textSpeed = TextSpeed.FAST), ui.context()))
        stopped(RecipeBase.perform(GameAction.OpenMenu("option:pokedex"), ui.context()))
        // Through the party ([RecipeBase.openParty]) and the bag ([RecipeBase.bagItem]): their common steps open the
        // start menu the game's own way.
        stopped(RecipeBase.perform(GameAction.ReorderParty(MonId(2, 1), 1), ui.context()))
        stopped(RecipeBase.perform(GameAction.TakeItem(MonId(1, 1)), ui.context()))
        stopped(RecipeBase.perform(GameAction.GiveItem(MonId(1, 1), ItemRef("Potion")), ui.context()))
        // The Pokégear's radio, opened from the field.
        stopped(RecipeBase.perform(TuneRadio(RadioStation.POKE_FLUTE), ui.context()))
        // Through the registry (every host), `save_game` too.
        stopped(ActionRegistry.of().execute(GameAction.SaveGame, ui.game.scope(), ui.game))
        assertEquals(listOf("option:options", "option:pokedex", "option:pokemon", "option:pokemon", "option:bag", "option:pokegear", "option:save"), asked)
        assertTrue(ui.game.presses.isEmpty(), "the game's own start menu pressed nothing: ${ui.game.presses}")

        // Another game (the common recipes): the common start menu, X then the entry.
        val other = ScriptedUi(OVERWORLD, party = listOf(mon(1)))
        other.onA = { screen, id -> if (ScriptedUi.isStart(screen) && id == "option:bag") bag("items", emptyList()) else screen }
        assertIs<ActionOutcome.Done>(RecipeBase.perform(GameAction.OpenMenu("option:bag"), other.context()))
        assertEquals(7, asked.size)
        assertEquals(Button.X, other.game.presses.first())
    }

    @Test
    fun aGamesOwnBagIsPlayedByEveryRecipeOpeningIt() {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), bag = listOf(BagPocket("items", listOf(item(17, "Potion", 3)))))
        ui.game.recipes = object : Recipes() {
            override fun bagItem(context: PlanContext, item: ItemRef): Step<Entry> {
                asked += item.raw
                return Step.Failed(STEP_ERROR)
            }
        }
        val recipes = ui.game.recipes
        stopped(RecipeBase.perform(GameAction.GiveItem(MonId(1, 1), ItemRef("give")), ui.context()))
        stopped(RecipeBase.perform(GameAction.UseItem(ItemRef("use"), MonId(1, 1)), ui.context()))
        stopped(RecipeBase.perform(GameAction.Teach(ItemRef("teach"), MonId(1, 1)), ui.context()))
        stopped(RecipeBase.perform(GameAction.UseKeyItem(ItemRef("key")), ui.context()))
        stopped(RecipeBase.perform(GameAction.RegisterItem(ItemRef("register")), ui.context()))
        // The selling bag, opened by the clerk, is searched the game's own way too.
        val selling = ScriptedUi(bag("items", listOf(Entry("item:17", "Potion"))), bag = listOf(BagPocket("items", listOf(item(17, "Potion", 3)))))
        selling.game.recipes = recipes
        stopped(RecipeBase.perform(GameAction.Sell(ItemRef("Potion"), 1), selling.context()))
        assertEquals(listOf("give", "use", "teach", "key", "register", "Potion"), asked)

        // Another game: the common bag (start menu → BAG), the item found on its pocket.
        val other = ScriptedUi(OVERWORLD, bag = listOf(BagPocket("items", listOf(item(17, "Potion")))))
        other.onA = { screen, id -> if (ScriptedUi.isStart(screen) && id == "option:bag") bag("items", listOf(Entry("item:17", "Potion"))) else screen }
        val common = BagStepRecipes().also { other.game.recipes = it }
        assertEquals("item:17", assertIs<Step.Done<Entry>>(common.bagItemOf(other.context(), ItemRef("Potion"))).value.id)
        assertEquals(6, asked.size)
    }

    @Test
    fun aGamesOwnKeyItemUseIsPlayedByTheWalkingEngine() {
        val ui = ScriptedUi(OVERWORLD, bag = listOf(BagPocket("key_items", listOf(item(450, "Bicycle")))))
        ui.field = field(1, 1, Direction.NORTH, movement = MovementMode.BIKE)
        ui.game.bicycleItem = 450
        ui.game.recipes = object : Recipes() {
            override fun activateKeyItem(context: PlanContext, item: ItemRef): Step<String> {
                asked += item.raw
                return Step.Failed(STEP_ERROR)
            }
        }
        // Getting off the bicycle before the ice ([BikeRide]): the engine uses the bicycle through the game's recipes.
        assertTrue(BikeRide.getOff(ui.context()).startsWith("couldn't get off the Bicycle"))
        assertEquals(listOf("item:450"), asked)
    }

    @Test
    fun aGamesOwnWayBackToTheFieldIsPlayedByTheRecipesLeavingMenus() {
        val closed = mutableListOf<Int>()
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), bag = listOf(BagPocket("items", listOf(item(17, "Potion")))))
        ui.game.recipes = object : Recipes() {
            override fun closeToOverworld(context: PlanContext, maxPresses: Int) {
                closed += maxPresses
                super.closeToOverworld(context, maxPresses)
            }
        }
        // An item that isn't in the bag: refused, and whatever was opened closed the game's own way.
        val failed = assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.UseItem(ItemRef("Ether"), MonId(1, 1)), ui.context()))
        assertEquals(UnavailableReason.UNKNOWN_ITEM, assertIs<ActionError.Unavailable>(failed.error).reason)
        // The PC's session leaves the game's own way after a failed boot (no PC here), with its own number of presses.
        assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.Deposit(MonId(1, 1)), ScriptedUi(OVERWORLD, party = listOf(mon(1), mon(2))).also { it.game.recipes = ui.game.recipes }.context()))
        assertEquals(listOf(8, 16), closed)

        // Another game: the common way back, never the other game's.
        val other = ScriptedUi(OVERWORLD, party = listOf(mon(1)))
        assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.UseItem(ItemRef("Ether"), MonId(1, 1)), other.context()))
        assertEquals(listOf(8, 16), closed)
    }

    /** A Pokémon knowing four moves, for the "forget a move" checks. */
    private val learner: PartyMon = mon(1, moves = listOf(move(33, "Tackle"), move(45, "Growl"), move(15, "Cut"), move(85, "Thunderbolt")))

    @Test
    fun aGamesOwnForgetCheckIsPlayedByTeachAndLearnMove() {
        val ui = ScriptedUi(OVERWORLD, party = listOf(learner), bag = listOf(BagPocket("tms_hms", listOf(item(328, "TM01")))))
        ui.game.recipes = object : Recipes() {
            override fun forgetRefusal(context: PlanContext, mon: PartyMon, forget: MoveRef?): ActionError? {
                asked += forget?.raw ?: "none"
                return STEP_ERROR
            }
        }
        stopped(RecipeBase.perform(GameAction.Teach(ItemRef("TM01"), MonId(1, 1), MoveRef("Tackle")), ui.context()))
        val list = Screen.MoveSelect(
            MoveContext.FORGET_IN_BATTLE, MonId(1, 1), Named(MoveId(53), "Flamethrower"),
            listOf(Entry("move:33", "Tackle"), Entry("move:45", "Growl"), Entry("move:15", "Cut", selectable = false), Entry("move:85", "Thunderbolt"), Entry("move:53", "Flamethrower"), Entry("option:cancel", "CANCEL")),
            Cursor.At(0), Topology.vertical(6), CancelBehavior.CLOSES,
        )
        val learning = ScriptedUi(list, party = listOf(learner))
        learning.game.recipes = ui.game.recipes
        stopped(RecipeBase.perform(GameAction.LearnMove(MoveRef("Growl")), learning.context()))
        assertEquals(listOf("Tackle", "Growl"), asked)
        assertTrue(ui.game.presses.isEmpty() && learning.game.presses.isEmpty())

        // Another game: the common check (an HM move is never forgotten), not the other game's.
        val other = ScriptedUi(list, party = listOf(learner))
        assertIs<ActionError.HmCannotForget>(assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.LearnMove(MoveRef("Cut")), other.context())).error)
        assertEquals(2, asked.size)
    }

    @Test
    fun aGamesOwnCountersArePlayedByTheServices() {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1), mon(2)))
        ui.game.recipes = object : Recipes() {
            override fun openStorage(context: PlanContext): Step<GameState> {
                asked += "storage"
                return Step.Failed(STEP_ERROR)
            }

            override fun openShop(context: PlanContext, chosen: FieldObject?): Step<GameState> {
                asked += "shop"
                return Step.Failed(STEP_ERROR)
            }

            override fun openSellBag(context: PlanContext): Step<GameState> {
                asked += "selling bag"
                return Step.Failed(STEP_ERROR)
            }
        }
        val recipes = ui.game.recipes
        stopped(RecipeBase.perform(GameAction.Deposit(MonId(2, 1)), ui.context()))
        stopped(RecipeBase.perform(GameAction.Withdraw(MonId(7, 1)), ui.context()))
        stopped(RecipeBase.perform(GameAction.Pc(listOf(PcOperation.Deposit(MonId(2, 1)))), ui.context()))
        stopped(RecipeBase.perform(GameAction.Release(MonId(2, 1), confirm = true), ui.context()))
        val shopList = Screen.Shop(3000, listOf(ShopItem(Named(ItemId(17), "Potion"), 300)), listOf(Entry("item:17", "Potion ₽300"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
        val shop = ScriptedUi(shopList, party = listOf(mon(1)), bag = listOf(BagPocket("items", listOf(item(17, "Potion", 3)))))
        shop.game.recipes = recipes
        stopped(RecipeBase.perform(GameAction.Buy(listOf(Purchase(ItemRef("Potion"), 1))), shop.context()))
        stopped(RecipeBase.perform(GameAction.Sell(ItemRef("Potion"), 1), shop.context()))
        assertEquals(listOf("storage", "storage", "storage", "storage", "shop", "selling bag"), asked)

        // Another game: the common counter (the shop list on screen is bought from), not the other game's.
        val other = ScriptedUi(shopList, party = listOf(mon(1)))
        val failed = assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.Buy(listOf(Purchase(ItemRef("Ultra Ball"), 1))), other.context()))
        assertIs<ActionError.InvalidParameter>(failed.error, "the common shop step reached the list and refused an item it doesn't sell")
        assertEquals(6, asked.size)
    }

    private companion object {
        /** The answer of a game's own step in these tests: the recipe stops on it. */
        val STEP_ERROR = ActionError.Timeout("the game's own step")
    }
}
