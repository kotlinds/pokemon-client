package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.OVERWORLD
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.dialogue
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.field
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.item
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.menu
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.mon
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.world
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.yesNo
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.BagPocket
import dev.kotlinds.pokemonclient.state.BoxMon
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PcBoxContents
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.PcStorage
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Pokémon Center and Poké Mart recipes on scripted screens (all checked live at the Indigo Plateau and in
 * Ecruteak): `heal` at the nurse, `buy` from the shop list, and one `pc` session (deposit, then withdraw) checked on
 * the party and the boxes. The player already stands in front of the counter / the PC.
 */
class ServicesRecipesTest {

    // region heal

    @Test
    fun healTalksToTheNurseAnswersYesAndChecksTheParty() {
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.NURSE)
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1, hp = 5), mon(2, hp = 0)), world = world(3, 3))
        ui.field = field(1, 1, Direction.NORTH, listOf(nurse))
        ui.onA = { screen, id ->
            when {
                screen is Screen.Overworld -> dialogue("Welcome to the Pokémon Center.", TextSource.FIELD)
                screen is Screen.Dialogue && screen.text.startsWith("Welcome") -> yesNo("Would you like to rest your Pokémon?")
                screen is Screen.YesNo && id == "option:yes" -> {
                    ui.party = ui.party.map { it.copy(hp = it.maxHp) }
                    dialogue("We've restored your Pokémon to full health.", TextSource.FIELD)
                }
                screen is Screen.Dialogue -> OVERWORLD
                else -> screen
            }
        }
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.heal(GameAction.Heal, ui.context()))
        assertEquals("party healed", done.detail)
        assertTrue(ui.party.all { it.hp == it.maxHp })
    }

    /** A game's recipes whose own `interact` records who it talked to, then presses A where the player stands. */
    private fun ownInteract(talked: MutableList<String>) = object : Recipes() {
        override fun interact(action: GameAction.Interact, context: PlanContext): ActionOutcome {
            talked += action.target
            val before = context.state().screen
            context.scope.tap(Button.A)
            context.navigator.awaitChange(before)
            return ActionOutcome.Done("the game's own interact")
        }
    }

    /**
     * A game's own `interact` ([dev.kotlinds.pokemonclient.PokemonGame.recipes], a subclass overriding it) is played
     * by the recipes that talk to someone as one of their steps (`heal` to the nurse, `buy` to the clerk: a virtual
     * call of [RecipeBase.interact]), not only when the agent calls `interact` itself; without it, the common one (the other heal
     * and buy tests). The guard against delegating recipes (`by`), whose nested calls would skip the override.
     */
    @Test
    fun aGamesOwnInteractIsPlayedByHealAndBuy() {
        val talked = mutableListOf<String>()
        val ownInteract = ownInteract(talked)
        // heal: the nurse (the same scripted counter as above).
        val nurse = FieldObject("person:0", "nurse", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.NURSE)
        val center = ScriptedUi(OVERWORLD, party = listOf(mon(1, hp = 5)), world = world(3, 3))
        center.field = field(1, 1, Direction.NORTH, listOf(nurse))
        center.onA = { screen, id ->
            when {
                screen is Screen.Overworld -> dialogue("Welcome to the Pokémon Center.", TextSource.FIELD)
                screen is Screen.Dialogue && screen.text.startsWith("Welcome") -> yesNo("Would you like to rest your Pokémon?")
                screen is Screen.YesNo && id == "option:yes" -> {
                    center.party = center.party.map { it.copy(hp = it.maxHp) }
                    dialogue("We've restored your Pokémon to full health.", TextSource.FIELD)
                }
                screen is Screen.Dialogue -> OVERWORLD
                else -> screen
            }
        }
        center.game.recipes = ownInteract
        assertEquals("party healed", assertIs<ActionOutcome.Done>(center.game.recipes.heal(GameAction.Heal, center.context())).detail)
        assertEquals(listOf("person:0"), talked)
        // buy: a clerk whose catalog isn't known (talked to, the list read on screen).
        val clerk = FieldObject("person:4", "shop clerk", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.CLERK)
        val list = Screen.Shop(3000, listOf(shopItem(17, "Potion", 300)), listOf(Entry("item:17", "Potion ₽300"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
        val mart = ScriptedUi(OVERWORLD, party = listOf(mon(1)), world = world(3, 3))
        mart.field = field(1, 1, Direction.NORTH, listOf(clerk))
        mart.onA = { screen, id ->
            when {
                screen is Screen.Overworld -> Screen.ListMenu(MenuKind.MULTICHOICE, (0..2).map { Entry("option:$it", "") }, Cursor.At(0), Topology.vertical(3))
                screen is Screen.ListMenu && id == "option:0" -> list
                else -> screen
            }
        }
        mart.game.recipes = ownInteract
        // Through the registry, like every host.
        val registry = ActionRegistry.of()
        assertIs<ActionOutcome.Done>(registry.execute(GameAction.Buy(emptyList()), mart.game.scope(), mart.game))
        assertEquals(listOf("person:0", "person:4"), talked)
        // The same counter in a game without override: the common interact, never the other game's.
        center.party = listOf(mon(1, hp = 5))
        center.game.recipes = Recipes.COMMON
        assertEquals("party healed", assertIs<ActionOutcome.Done>(center.game.recipes.heal(GameAction.Heal, center.context())).detail)
        assertEquals(listOf("person:0", "person:4"), talked)
    }

    @Test
    fun healWithoutANurseIsRefusedAtOnce() {
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1, hp = 5)), world = world(3, 3))
        ui.field = field(1, 1, Direction.NORTH)
        val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.heal(GameAction.Heal, ui.context()))
        assertEquals(UnavailableReason.WRONG_SCREEN, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(ui.game.presses.isEmpty())
    }

    // endregion

    // region buy

    /** The shop list (Ultra Ball ₽1200); a quantity screen moved by UP / DOWN (±1) and RIGHT / LEFT (±10). */
    private fun shopUi(money: Long): ScriptedUi {
        val list = Screen.Shop(money, listOf(shopItem(2, "Ultra Ball", 1200)), listOf(Entry("item:2", "Ultra Ball ₽1200"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
        val ui = ScriptedUi(list, party = listOf(mon(1)), bag = listOf(BagPocket("balls", listOf(item(2, "Ultra Ball", 1)))))
        ui.money = money
        var quantity = 0
        ui.onDpad = { button, screen ->
            (screen as? Screen.Quantity)?.let {
                val value = when (button) {
                    Button.UP -> it.value + 1
                    Button.DOWN -> it.value - 1
                    Button.RIGHT -> it.value + 10
                    Button.LEFT -> it.value - 10
                    else -> it.value
                }.coerceIn(it.min, it.max)
                it.copy(value = value)
            }
        }
        ui.onA = { screen, id ->
            when {
                screen is Screen.Shop && id == "item:2" -> Screen.Quantity(1, 1, 99)
                screen is Screen.Quantity -> { quantity = screen.value; yesNo("$quantity Ultra Ball(s)? That will be ₽${quantity * 1200}. OK?") }
                screen is Screen.YesNo && id == "option:yes" -> {
                    ui.money -= quantity * 1200L
                    ui.bag = listOf(BagPocket("balls", listOf(item(2, "Ultra Ball", 1 + quantity))))
                    dialogue("Here you are! Thank you!", TextSource.FIELD)
                }
                screen is Screen.Dialogue -> list.copy(balance = ui.money)
                else -> screen
            }
        }
        return ui
    }

    private fun shopItem(id: Int, name: String, price: Int?) =
        dev.kotlinds.pokemonclient.state.ShopItem(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.ItemId(id), name), price)

    /**
     * The game in French: the list's labels ("HYPER BALL   1 200 ₽") are only shown; what is sold, its id and its
     * price come from [Screen.Shop.items] (the game's data). The list, the choice by id and by the game's own name,
     * and the money check all use them.
     */
    @Test
    fun aShopListInAnotherLanguageGivesTheIdsAndPricesOfTheGamesData() {
        val list = Screen.Shop(
            2000, listOf(shopItem(2, "HYPER BALL", 1200), shopItem(17, "POTION", 300)),
            listOf(Entry("item:2", "HYPER BALL   1 200 ₽"), Entry("item:17", "POTION   300 ₽"), Entry("option:cancel", "ANNULER")), Cursor.At(0), Topology.vertical(3),
        )
        fun shop() = ScriptedUi(list, party = listOf(mon(1))).also { it.money = 2000 }
        val ui = shop()
        val listed = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), ui.context()))
        assertEquals("nothing bought; sold here: item:2 (HYPER BALL, ₽1200), item:17 (POTION, ₽300)", listed.detail)
        val choices = ActionRegistry.of().available(ui.game.state(list), ActionMode.ASSISTED).single { it.name == "buy" }.choices.getValue("item")
        assertEquals(listOf("item:2" to "HYPER BALL ₽1200", "item:17" to "POTION ₽300"), choices.map { it.value to it.label })
        // The price of the data, not of the label: 2 × 1200 > 2000, refused by id and by the game's name alike.
        for (asked in listOf("item:2", "hyper ball")) {
            val at = shop()
            val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef(asked), 2))), at.context()), asked)
            assertEquals(UnavailableReason.NOT_ENOUGH_MONEY, assertIs<ActionError.Unavailable>(failed.error).reason, asked)
            assertTrue(Button.A !in at.game.presses, asked)
        }
        // The English name isn't this game's: refused with the ids and the game's names.
        val unknown = assertIs<ActionOutcome.Failed>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef("Ultra Ball"), 1))), shop().context()))
        assertEquals(listOf("item:2 (HYPER BALL, ₽1200)", "item:17 (POTION, ₽300)"), assertIs<ActionError.InvalidParameter>(unknown.error).allowed)
    }

    /**
     * The Pokéathlon Dome's daily shop (HeartGold / SoulSilver mart type 3, shop_menu.c): athlete points (2500), its own
     * prices, no quantity asked (the price question comes at once), one of each line (a Red Apricorn already bought
     * today), and what is bought may not go to the bag (apricorns go to the Apricorn Box): checked on the line the
     * game marks sold out and on the points paid.
     */
    private fun pokeathlonUi(): ScriptedUi {
        fun list(points: Long, candyBought: Boolean) = Screen.Shop(
            points,
            listOf(shopItem(485, "Red Apricorn", 200).copy(soldOut = true), shopItem(81, "Moon Stone", 3000), shopItem(50, "Rare Candy", 2000).copy(soldOut = candyBought)),
            listOf(Entry("item:485", "Red Apricorn 200", selectable = false), Entry("item:81", "Moon Stone 3000", selectable = false),
                Entry("item:50", "Rare Candy 2000", selectable = !candyBought), Entry("option:cancel", "CANCEL")),
            Cursor.At(0), Topology.vertical(4),
            currency = dev.kotlinds.pokemonclient.state.ShopCurrency.ATHLETE_POINTS, oneOfEach = true,
        )
        val ui = ScriptedUi(list(2500, candyBought = false), party = listOf(mon(1)))
        ui.money = 72178
        ui.onA = { screen, id ->
            when {
                screen is Screen.Shop && id == "item:50" -> yesNo("Rare Candy? That will be 2000 athlete points. OK?")
                screen is Screen.YesNo && id == "option:yes" -> dialogue("Here you are! Thank you!", TextSource.FIELD)
                screen is Screen.Dialogue -> list(500, candyBought = true)
                else -> screen
            }
        }
        return ui
    }

    @Test
    fun aPokeathlonShopIsPaidInAthletePointsOneOfEach() {
        val ui = pokeathlonUi()
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef("item:50"), 1))), ui.context()))
        assertEquals("bought 1 Rare Candy (2000 athlete points), 500 athlete points left", done.detail)
        assertTrue(ui.game.presses.none { it == Button.UP || it == Button.RIGHT }, "no quantity to set")
        assertEquals(72178, ui.money, "the money isn't touched")
    }

    @Test
    fun aPokeathlonShopRefusesWhatIsSoldOutTooDearOrMoreThanOne() {
        val listed = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), pokeathlonUi().context()))
        assertEquals(
            "nothing bought; sold here: item:485 (Red Apricorn, 200 athlete points, sold out), item:81 (Moon Stone, 3000 athlete points), item:50 (Rare Candy, 2000 athlete points)",
            listed.detail,
        )
        val ui = pokeathlonUi()
        val choices = ActionRegistry.of().available(ui.game.state(ui.game.screen), ActionMode.ASSISTED).single { it.name == "buy" }.choices.getValue("item")
        assertEquals(listOf("item:81" to "Moon Stone 3000 athlete points", "item:50" to "Rare Candy 2000 athlete points"), choices.map { it.value to it.label })
        // Each refused before anything is chosen (a fresh counter each time: a refusal leaves the list).
        fun refused(item: String, quantity: Int): ActionError {
            val at = pokeathlonUi()
            val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef(item), quantity))), at.context()), item)
            assertTrue(Button.A !in at.game.presses, item)
            return failed.error
        }
        assertEquals(UnavailableReason.NO_STOCK, assertIs<ActionError.Unavailable>(refused("item:485", 1)).reason)
        // 3000 points while the player has 2500 (and ₽72178: the money doesn't count here).
        val dear = assertIs<ActionError.Unavailable>(refused("item:81", 1))
        assertEquals(UnavailableReason.NOT_ENOUGH_MONEY, dear.reason)
        assertEquals("1 × Moon Stone cost 3000 athlete points, you have 2500 athlete points", dear.detail)
        assertEquals(listOf("1"), assertIs<ActionError.InvalidParameter>(refused("item:50", 2)).allowed)
    }

    /** A seal counter (mart type 2): seals aren't items, `buy` refuses them with a typed error, nothing pressed. */
    @Test
    fun aSealCounterIsRefusedByBuy() {
        val seals = Screen.Shop(
            3000, emptyList(), listOf(Entry("seal:1", "seal 1"), Entry("seal:7", "seal 7"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(3),
            goods = dev.kotlinds.pokemonclient.state.ShopGoods.SEALS,
        )
        for (action in listOf(GameAction.Buy(emptyList()), GameAction.Buy(listOf(Purchase(ItemRef("item:1"), 1))))) {
            val ui = ScriptedUi(seals, party = listOf(mon(1)))
            val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.buy(action, ui.context()), action.toString())
            assertEquals(UnavailableReason.GOODS_NOT_ITEMS, assertIs<ActionError.Unavailable>(failed.error).reason)
            assertTrue(Button.A !in ui.game.presses)
        }
    }

    @Test
    fun buySetsTheQuantityConfirmsAndChecksTheBagAndTheMoney() {
        val ui = shopUi(money = 20000)
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef("Ultra Ball"), 12))), ui.context()))
        assertEquals("bought 12 Ultra Ball (₽14400), ₽5600 left", done.detail)
        // ±10 first, then ±1: RIGHT, UP (1 → 11 → 12).
        assertEquals(listOf(Button.RIGHT, Button.UP), ui.game.presses.filter { it == Button.RIGHT || it == Button.UP })
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun aPurchaseTheMoneyCantPayIsRefusedBeforeChoosingAnything() {
        val ui = shopUi(money = 2000)
        val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef("item:2"), 2))), ui.context()))
        assertEquals(UnavailableReason.NOT_ENOUGH_MONEY, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(Button.A !in ui.game.presses)
        assertEquals(2000, ui.money)
    }

    @Test
    fun buyWithoutAnItemListsTheShopListAndBuysNothing() {
        val ui = shopUi(money = 20000)
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), ui.context()))
        assertEquals("nothing bought; sold here: item:2 (Ultra Ball, ₽1200)", done.detail)
        assertTrue(Button.A !in ui.game.presses)
        assertEquals(20000, ui.money)
    }

    @Test
    fun buyWithoutAnItemGivesTheClerksCatalogWithoutMoving() {
        val clerk = FieldObject(
            "person:0", "shop clerk", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.CLERK,
            catalog = listOf(dev.kotlinds.pokemonclient.state.ShopItem(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.ItemId(4), "Poké Ball"), 200)),
        )
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), world = world(3, 3))
        ui.field = field(1, 2, Direction.NORTH, listOf(clerk))
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), ui.context()))
        assertEquals("nothing bought; sold here: item:4 (Poké Ball, ₽200)", done.detail)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun aTmOnSaleSaysWhoOfThePartyCanLearnItAtThePokedexLevelOnly() {
        // Map randomizer run: False Swipe bought for ₽2000 while nobody in the party could learn it.
        val tm = dev.kotlinds.pokemonclient.state.ShopItem(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.ItemId(381), "TM54"), 2000)
        val clerk = FieldObject("person:0", "shop clerk", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.CLERK, catalog = listOf(tm))
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1), mon(2)), world = world(3, 3))
        ui.field = field(1, 2, Direction.NORTH, listOf(clerk))
        val tm54 = dev.kotlinds.pokemonclient.data.MachineId(54)
        fun species(n: Int, machines: Set<dev.kotlinds.pokemonclient.data.MachineId>) = dev.kotlinds.pokemonclient.data.SpeciesInfo(
            dev.kotlinds.pokemonclient.state.SpeciesId(n), "MON$n", emptyList(), dev.kotlinds.pokemonclient.data.BaseStats(1, 1, 1, 1, 1, 1),
            emptyList(), 45, 64, null, emptyList(), machines,
        )
        ui.game.data = StubGameData(
            speciesInfo = listOf(species(1, emptySet()), species(2, setOf(tm54))).associateBy { it.id },
            machineItems = mapOf(dev.kotlinds.pokemonclient.state.ItemId(381) to tm54),
        )
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), ui.context()))
        assertEquals("nothing bought; sold here: item:381 (TM54, ₽2000, party can learn: ${MonId(2, 1)} MON2)", done.detail)
        // Below the Pokédex level: only what the shop shows.
        val plain = PlanContext(ui.game.scope(), ui.game, settings = ActionSettings(pokedex = false))
        assertEquals("nothing bought; sold here: item:381 (TM54, ₽2000)", assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), plain)).detail)
        // Nobody: said so.
        ui.game.data = StubGameData(speciesInfo = listOf(species(1, emptySet()), species(2, emptySet())).associateBy { it.id }, machineItems = mapOf(dev.kotlinds.pokemonclient.state.ItemId(381) to tm54))
        assertEquals("nothing bought; sold here: item:381 (TM54, ₽2000, party can learn: nobody)", assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), ui.context())).detail)
    }

    /**
     * A department store floor with two clerks (NOTES, map randomizer run: `buy` used the nearest clerk and listed one
     * catalog mixing both): the medicine counter next to the player, the ball counter further.
     */
    private fun twoClerks(): ScriptedUi {
        fun item(id: Int, name: String, price: Int) = dev.kotlinds.pokemonclient.state.ShopItem(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.ItemId(id), name), price)
        val medicine = FieldObject("person:3", "shop clerk", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.CLERK,
            catalog = listOf(item(17, "Potion", 300), item(27, "Full Heal", 600)))
        val balls = FieldObject("person:5", "shop clerk", FieldObjectKind.PERSON, 6, 0, Direction.SOUTH, role = PersonRole.CLERK,
            catalog = listOf(item(4, "Poké Ball", 200), item(17, "Potion", 300)))
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), world = world(8, 3))
        ui.field = field(1, 1, Direction.NORTH, listOf(medicine, balls))
        return ui
    }

    @Test
    fun buyWithoutAnItemListsEachClerksOwnStock() {
        val ui = twoClerks()
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), ui.context()))
        assertEquals(
            "nothing bought; person:3 sells: item:17 (Potion, ₽300), item:27 (Full Heal, ₽600); person:5 sells: item:4 (Poké Ball, ₽200), item:17 (Potion, ₽300)",
            done.detail,
        )
        assertTrue(ui.game.presses.isEmpty())
        // The action list says who sells what, never one list mixing the counters without saying so.
        val choices = ActionRegistry.of().available(ui.game.state(ui.game.screen), ActionMode.ASSISTED).single { it.name == "buy" }.choices.getValue("item")
        assertEquals(listOf("Potion ₽300 (person:3, person:5)", "Full Heal ₽600 (person:3)", "Poké Ball ₽200 (person:5)"), choices.map { it.label })
    }

    @Test
    fun buyGoesToTheClerkWhoSellsTheItem() {
        val ui = twoClerks()
        val state = ui.game.state(ui.game.screen)
        assertEquals("person:5", (Recipes.COMMON.clerkFor(state, listOf(Purchase(ItemRef("item:4"), 10))) as Step.Done).value?.id)
        assertEquals("person:3", (Recipes.COMMON.clerkFor(state, listOf(Purchase(ItemRef("Potion"), 1))) as Step.Done).value?.id, "the nearest of those who sell it")
        // Nobody sells it: refused before moving, with each clerk's items.
        val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef("item:2"), 1))), ui.context()))
        val error = assertIs<ActionError.InvalidParameter>(failed.error)
        assertTrue("item:4 (Poké Ball, ₽200, person:5)" in error.allowed, error.allowed.toString())
        // Sold, but by two different clerks: one buy each.
        val split = assertIs<ActionOutcome.Failed>(Recipes.COMMON.buy(GameAction.Buy(listOf(Purchase(ItemRef("item:4"), 1), Purchase(ItemRef("item:27"), 1))), ui.context()))
        assertEquals(UnavailableReason.NO_STOCK, assertIs<ActionError.Unavailable>(split.error).reason)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun buyWithoutAnItemReadsTheShopListWhenTheCatalogIsUnknown() {
        val clerk = FieldObject("person:0", "shop clerk", FieldObjectKind.PERSON, 1, 0, Direction.SOUTH, role = PersonRole.CLERK)
        val list = Screen.Shop(3000, listOf(shopItem(17, "Potion", 300)), listOf(Entry("item:17", "Potion ₽300"), Entry("option:cancel", "CANCEL")), Cursor.At(0), Topology.vertical(2))
        val ui = ScriptedUi(OVERWORLD, party = listOf(mon(1)), world = world(3, 3))
        ui.field = field(1, 1, Direction.NORTH, listOf(clerk))
        ui.onA = { screen, id ->
            when {
                // The clerk's BUY / SELL / SEE YA! menu (ids by position, whatever the language).
                screen is Screen.Overworld -> Screen.ListMenu(MenuKind.MULTICHOICE, (0..2).map { Entry("option:$it", "") }, Cursor.At(0), Topology.vertical(3))
                screen is Screen.ListMenu && id == "option:0" -> list
                else -> screen
            }
        }
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.buy(GameAction.Buy(emptyList()), ui.context()))
        assertEquals("nothing bought; sold here: item:17 (Potion, ₽300)", done.detail)
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    // endregion

    // region pc

    private val box1 = "BOX 1"

    /**
     * The PC in front of the player (Pc tile north): top menu → storage menu (DEPOSIT, WITHDRAW, MOVE, MOVE ITEMS,
     * SEE YA!) → boxes. B on a box asks "Continue Box operations?" (NO leaves); B on the menus goes back.
     */
    private fun pcUi(party: List<PartyMon>, stored: List<BoxMon>, opened: MutableList<PcMode> = mutableListOf()): ScriptedUi {
        val ui = ScriptedUi(OVERWORLD, party = party, world = world(3, 3, mapOf((1 to 0) to TileInfo(true, TileKind.Pc))))
        ui.field = field(1, 1, Direction.NORTH)
        fun storage(mons: List<BoxMon>) = PcStorage(0, listOf(PcBoxContents(0, box1, mons, 30), PcBoxContents(1, "BOX 2", emptyList(), 30)))
        ui.storage = storage(stored)
        fun multichoice(vararg ids: String) = Screen.ListMenu(MenuKind.MULTICHOICE, ids.map { Entry(it, it) }, Cursor.At(0), Topology.vertical(ids.size), CancelBehavior.CONFIRMS_LAST)
        val top = multichoice("option:0", "option:1", "option:2")
        val storageMenu = multichoice("option:0", "option:1", "option:2", "option:3", "option:4")
        fun box(mode: PcMode): Screen.PcBox {
            val mons = if (mode == PcMode.DEPOSIT) ui.party.map { it.id } else ui.storage!!.boxes[0].mons.map { it.id }
            val entries = mons.map { Entry(it.toString(), it.toString()) } + Entry("option:next_box", "▶", touch = dev.kotlinds.pokemonclient.console.TouchPoint(200, 20))
            return Screen.PcBox(0, box1, entries, Cursor.At(0), Topology.vertical(entries.size), mode = mode)
        }
        var mode = PcMode.DEPOSIT
        var picked: MonId? = null
        ui.onA = { screen, id ->
            when {
                screen is Screen.Overworld -> dialogue("ACE booted up the PC.", TextSource.FIELD)
                screen is Screen.Dialogue && screen.text.contains("booted") -> top
                screen is Screen.ListMenu && screen.entries.size == 3 && id == "option:0" -> dialogue("Accessed the Pokémon Storage System.", TextSource.FIELD)
                screen is Screen.Dialogue && screen.text.contains("Storage") -> storageMenu
                screen is Screen.ListMenu && screen.entries.size == 5 && id == "option:0" -> box(PcMode.DEPOSIT.also { mode = it; opened += it })
                screen is Screen.ListMenu && screen.entries.size == 5 && id == "option:1" -> box(PcMode.WITHDRAW.also { mode = it; opened += it })
                screen is Screen.PcBox && id != null && id.startsWith("mon:") -> {
                    picked = MonId(id.substringAfter(':').substringBefore('.').toLong(16), id.substringAfter('.').toLong(16))
                    if (mode == PcMode.DEPOSIT) menu("option:deposit", "option:summary", "option:marking", "option:release", "option:cancel")
                    else menu("option:withdraw", "option:summary", "option:marking", "option:release", "option:cancel")
                }
                screen is Screen.ContextMenu && id == "option:deposit" -> Screen.ListMenu(
                    MenuKind.OTHER, listOf(Entry("box:0", box1), Entry("box:1", "BOX 2"), Entry("option:deposit", "DEPOSIT"), Entry("option:cancel", "CANCEL")),
                    Cursor.At(0), Topology.vertical(4),
                )
                screen is Screen.ListMenu && screen.kind == MenuKind.OTHER && id == "option:deposit" -> {
                    val mon = ui.party.single { it.id == picked }
                    ui.party = ui.party - mon
                    ui.storage = storage(ui.storage!!.boxes[0].mons + BoxMon(mon.id, 0, ui.storage!!.boxes[0].mons.size, mon.species, null, mon.level, null, false))
                    box(PcMode.DEPOSIT)
                }
                screen is Screen.ContextMenu && id == "option:withdraw" -> {
                    val stored = ui.storage!!.find(picked!!)!!
                    ui.storage = storage(ui.storage!!.boxes[0].mons - stored)
                    ui.party = ui.party + mon(stored.id.personality.toInt())
                    box(PcMode.WITHDRAW)
                }
                screen is Screen.YesNo && id == "option:no" -> storageMenu
                screen is Screen.YesNo && id == "option:yes" -> box(mode)
                else -> screen
            }
        }
        ui.onB = { screen ->
            when {
                screen is Screen.PcBox -> yesNo("Continue Box operations?")
                screen is Screen.ListMenu && screen.entries.size == 5 -> top
                else -> OVERWORLD
            }
        }
        return ui
    }

    @Test
    fun onePcSessionDepositsThenWithdrawsAndChecksBoth() {
        val stored = BoxMon(MonId(7, 1), 0, 0, dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.SpeciesId(7), "MON7"), null, 20, null, false)
        val opened = mutableListOf<PcMode>()
        val ui = pcUi(listOf(mon(1), mon(2)), listOf(stored), opened)
        val ops = listOf(PcOperation.Deposit(MonId(2, 1)), PcOperation.Withdraw(MonId(7, 1)))
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.pc(GameAction.Pc(ops), ui.context()))
        assertEquals("deposited MON2 in BOX 1 (2/30); withdrew MON7 (party: 2/6)", done.detail)
        assertEquals(listOf(MonId(1, 1), MonId(7, 1)), ui.party.map { it.id })
        assertIs<Screen.Overworld>(ui.game.screen)
        // Two modes: the box screen is left once for the storage menu, the PC stays on.
        assertEquals(listOf(PcMode.DEPOSIT, PcMode.WITHDRAW), opened)
    }

    /** The same for the PC: a game's own `interact` boots it ([ServiceRecipes.openStorage] talks to it as a step of the session). */
    @Test
    fun aGamesOwnInteractIsPlayedByThePc() {
        val talked = mutableListOf<String>()
        val ui = pcUi(listOf(mon(1), mon(2)), emptyList())
        ui.game.recipes = ownInteract(talked)
        val done = assertIs<ActionOutcome.Done>(ActionRegistry.of().execute(GameAction.Deposit(MonId(2, 1)), ui.game.scope(), ui.game))
        assertEquals(listOf(MovePlans.PC), talked)
        assertTrue("deposited MON2" in done.detail.orEmpty(), done.detail)
        assertEquals(listOf(MonId(1, 1)), ui.party.map { it.id })
    }

    @Test
    fun severalOperationsOfOneModeStayOnTheBoxScreen() {
        // NOTES (Nathan, watching Claude): the box closed and reopened between every operation.
        val opened = mutableListOf<PcMode>()
        val ui = pcUi(listOf(mon(1), mon(2), mon(3)), emptyList(), opened)
        val ops = listOf(PcOperation.Deposit(MonId(2, 1)), PcOperation.Deposit(MonId(3, 1)))
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.pc(GameAction.Pc(ops), ui.context()))
        assertEquals("deposited MON2 in BOX 1 (1/30); deposited MON3 in BOX 1 (2/30)", done.detail)
        assertEquals(listOf(MonId(1, 1)), ui.party.map { it.id })
        assertEquals(listOf(PcMode.DEPOSIT), opened)
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun leavingTheBoxThatFailsAfterTheOperationsIsTold() {
        // Review impl13 B3: the way out of the box is checked once after the operations; its failure (a question with
        // a lasting answer it won't confirm) is in the answer, the deposit done before it too.
        val ui = pcUi(listOf(mon(1), mon(2)), emptyList())
        val leave = ui.onB
        ui.onB = { screen ->
            if (screen is Screen.PcBox) Screen.YesNo("Release it?", listOf(Entry("option:yes", "YES", dangerous = true), Entry("option:no", "NO")), Cursor.At(1), Topology.vertical(2))
            else leave(screen)
        }
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.deposit(GameAction.Deposit(MonId(2, 1)), ui.context()))
        assertTrue(done.detail.orEmpty().startsWith("deposited MON2 in BOX 1 (1/30); then leaving the box failed: UNEXPECTED_SCREEN"), done.detail)
        // Left the usual way: nothing more said (aSingleDepositStillOpensTheBoxOnceAndSwitchesThePcOff).
    }

    @Test
    fun aSingleDepositStillOpensTheBoxOnceAndSwitchesThePcOff() {
        val opened = mutableListOf<PcMode>()
        val ui = pcUi(listOf(mon(1), mon(2)), emptyList(), opened)
        val done = assertIs<ActionOutcome.Done>(Recipes.COMMON.deposit(GameAction.Deposit(MonId(2, 1)), ui.context()))
        assertEquals("deposited MON2 in BOX 1 (1/30)", done.detail)
        assertEquals(listOf(PcMode.DEPOSIT), opened)
        assertIs<Screen.Overworld>(ui.game.screen)
    }

    @Test
    fun impossibleSessionsAreRefusedBeforeThePcIsTouched() {
        val stored = BoxMon(MonId(7, 1), 0, 0, dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.SpeciesId(7), "MON7"), null, 20, null, false)
        // The last Pokémon can't be deposited.
        val alone = pcUi(listOf(mon(1)), listOf(stored))
        val last = assertIs<ActionOutcome.Failed>(Recipes.COMMON.pc(GameAction.Pc(listOf(PcOperation.Deposit(MonId(1, 1)))), alone.context()))
        assertEquals(UnavailableReason.LAST_POKEMON, assertIs<ActionError.Unavailable>(last.error).reason)
        assertTrue(alone.game.presses.isEmpty())
        // A full party can't withdraw (unless a deposit comes first in the same session).
        val full = pcUi((1..6).map { mon(it) }, listOf(stored))
        val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.pc(GameAction.Pc(listOf(PcOperation.Withdraw(MonId(7, 1)))), full.context()))
        assertEquals(UnavailableReason.PARTY_FULL, assertIs<ActionError.Unavailable>(failed.error).reason)
        assertTrue(full.game.presses.isEmpty())
    }

    // endregion
}
