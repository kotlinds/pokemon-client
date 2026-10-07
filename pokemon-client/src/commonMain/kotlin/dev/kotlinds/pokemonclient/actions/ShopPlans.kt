package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.data.MachineCompatibility
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ShopCurrency
import dev.kotlinds.pokemonclient.state.ShopGoods
import dev.kotlinds.pokemonclient.state.ShopItem
import dev.kotlinds.pokemonclient.state.kind

/**
 * Recipes of buying at a Poké Mart: get to the shop list (talk to the clerk and pick BUY — the first entry of the
 * clerk's menu whatever the language — unless already there), then for each purchase pick the item by its id, set
 * the quantity one press at a time (read back after each press), confirm; finally leave with B (it picks SEE YA!).
 * The result is checked on the bag and the money, never on the clerk's words.
 */
internal object ShopPlans {

    /** Where a purchase can start from. */
    enum class Stage { OVERWORLD, CLERK_MENU, SHOP_LIST, QUANTITY }

    /** The stage of the shop the state is at, or null when no purchase can start from here. */
    fun stage(state: GameState): Stage? = when (val screen = state.screen) {
        is Screen.Shop -> Stage.SHOP_LIST
        is Screen.Quantity -> if (state.field != null && clerkFaced(state) != null) Stage.QUANTITY else null
        is Screen.ListMenu -> if (screen.kind == MenuKind.MULTICHOICE && screen.entries.size == CLERK_MENU_SIZE && clerkFaced(state) != null) Stage.CLERK_MENU else null
        else -> if (MovePlans.canWalk(state, hasWorld = true) && clerk(state) != null) Stage.OVERWORLD else null
    }

    /**
     * What one clerk sells ([clerk] null: the counter the player is at, its list on screen or the clerk faced), as
     * the game decides it ([FieldObject.catalog]: the clerk's own mart script and the badges, read before talking),
     * with the [currency] its prices are in (money for a catalog; the list on screen says).
     */
    data class Stock(val clerk: FieldObject?, val items: List<ShopItem>, val currency: ShopCurrency = ShopCurrency.MONEY)

    /**
     * What can be bought here, clerk by clerk: the list on screen when the shop is open, the clerk faced (their menu
     * is open), else every clerk of the map whose catalog is known, nearest first (a floor of a department store has
     * two: each sells its own list, never mixed).
     */
    fun stock(state: GameState): List<Stock> {
        (state.screen as? Screen.Shop)?.let { shop -> return listOf(Stock(null, shop.items, shop.currency)) }
        if (stage(state) != Stage.OVERWORLD) return listOfNotNull(clerkFaced(state)?.catalog?.let { Stock(null, it) })
        return clerks(state).mapNotNull { c -> c.catalog?.let { Stock(c, it) } }
    }

    /** True when [items] has the item [ref] names (`item:<id>` or its name). */
    private fun sells(items: List<ShopItem>, ref: ItemRef) = items.any { matchesRef(ref.raw, "item", it.item.id.value, it.item.name) }

    /**
     * The clerk to buy [purchases] from, walking in from the field: the nearest one whose catalog has them all; else
     * the nearest whose catalog isn't known (talking shows it). Null when the player is at a counter already (the
     * shop list or the clerk's menu: that clerk). A known set of clerks none of whom sells them all is refused
     * before moving, with what each one sells.
     */
    internal fun clerkFor(state: GameState, purchases: List<Purchase>): Step<FieldObject?> {
        if (stage(state) != Stage.OVERWORLD) return Step.Done(null)
        val all = clerks(state)
        all.firstOrNull { c -> c.catalog?.let { items -> purchases.all { sells(items, it.item) } } == true }?.let { return Step.Done(it) }
        all.firstOrNull { it.catalog == null }?.let { return Step.Done(it) }
        if (all.isEmpty()) return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
        val missing = purchases.firstOrNull { p -> all.none { c -> sells(c.catalog.orEmpty(), p.item) } } ?: purchases.first()
        val allowed = all.flatMap { c -> c.catalog.orEmpty().map { "item:${it.item.id.value} (${it.item.name}${it.price?.let { p -> ", ₽$p" } ?: ""}, ${c.id})" } }
        // Every line sold, but by different clerks: one buy per clerk.
        val split = purchases.all { p -> all.any { c -> sells(c.catalog.orEmpty(), p.item) } }
        return Step.Failed(
            if (split) ActionError.Unavailable(UnavailableReason.NO_STOCK, "No single clerk here sells all of ${purchases.joinToString { it.item.raw }}: " +
                describeStocks(all.mapNotNull { c -> c.catalog?.let { Stock(c, it) } }).removePrefix("nothing bought; "), "buy from each clerk in its own buy")
            else ActionError.InvalidParameter("item", missing.item.raw, allowed),
        )
    }

    val buy = ActionPlan<GameAction.Buy> { action, context ->
        val start = context.state()
        if (action.purchases.isEmpty()) return@ActionPlan listCatalog(context, start)
        val seller = clerkFor(start, action.purchases)
        if (seller is Step.Failed) return@ActionPlan ActionOutcome.Failed(seller.error)
        val opened = openShop(context, (seller as Step.Done).value)
        if (opened is Step.Failed) {
            PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
            return@ActionPlan ActionOutcome.Failed(opened.error)
        }
        val bought = mutableListOf<String>()
        var failure: ActionError? = null
        // What the shop's prices are paid with, and how much of it is left (read on the list: athlete points aren't
        // in the player's state).
        var currency = ShopCurrency.MONEY
        var left: Long? = null
        for (purchase in action.purchases) {
            val before = context.state()
            val shop = before.screen as? Screen.Shop
            shop?.let { currency = it.currency }
            var itemId = 0
            val step = buyOne(context, purchase) { itemId = it }
            if (step is Step.Failed) {
                failure = step.error
                break
            }
            val after = context.state()
            val afterShop = after.screen as? Screen.Shop
            val paid = (balance(before) ?: 0) - (balance(after) ?: 0)
            left = balance(after)
            if (shop?.oneOfEach == true) {
                // One of each, and not always into the bag (apricorns go to the Apricorn Box, Data Cards nowhere):
                // the game marks the line sold out when the purchase is made (shop_menu.c:955-961).
                val line = afterShop?.items?.firstOrNull { it.item.id.value == itemId }
                if (line?.soldOut != true) {
                    failure = ActionError.Timeout("item:$itemId wasn't marked as bought by the shop")
                    break
                }
                bought += "1 ${line.item.name} (${currency.format(paid)})"
                continue
            }
            val gained = count(after, itemId) - count(before, itemId)
            if (gained < purchase.quantity) {
                failure = ActionError.Timeout("the bag got $gained ${purchase.item.raw} instead of ${purchase.quantity}")
                break
            }
            bought += "$gained ${itemName(after, itemId)} (${currency.format(paid)})" + bonus(before, after, itemId).joinToString("") { " + bonus: $it" }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        val remaining = if (currency == ShopCurrency.MONEY) context.state().player?.money else left
        val summary = "bought ${bought.joinToString()}" + (remaining?.let { ", ${currency.format(it)} left" } ?: "")
        when {
            failure == null -> ActionOutcome.Done(summary)
            bought.isEmpty() -> ActionOutcome.Failed(failure)
            // Some lines were bought: say so, with why the rest wasn't.
            else -> ActionOutcome.Done("$summary; stopped: ${failure.code} ${failure.message}")
        }
    }

    /**
     * `buy` without an item: nothing is bought, the answer lists what is sold, clerk by clerk ([stock]): the catalogs
     * the game data knows (nothing moves); a clerk whose catalog isn't known is talked to and its shop list read on
     * screen (BUY, read, leave). At a counter already: that clerk's list.
     */
    private fun listCatalog(context: PlanContext, start: GameState): ActionOutcome {
        val atCounter = stage(start) != Stage.OVERWORLD
        (start.screen as? Screen.Shop)?.takeIf { it.goods != ShopGoods.ITEMS }?.let { return ActionOutcome.Failed(notItems(it)) }
        val known = stock(start).filter { it.items.isNotEmpty() }
        val unknown = if (atCounter) emptyList() else clerks(start).filter { it.catalog == null }
        val canLearn = partyCanLearn(context, start)
        if (known.isNotEmpty() && unknown.isEmpty()) return ActionOutcome.Done(describeStocks(known, canLearn))
        val read = mutableListOf<Stock>()
        for (clerk in if (atCounter) listOf(null) else unknown) {
            val opened = openShop(context, clerk)
            val shop = (opened as? Step.Done)?.value?.screen as? Screen.Shop
            PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
            if (opened is Step.Failed) return ActionOutcome.Failed(opened.error)
            if (shop != null && shop.goods != ShopGoods.ITEMS) return ActionOutcome.Failed(notItems(shop))
            if (shop != null && shop.items.isNotEmpty()) read += Stock(clerk, shop.items, shop.currency)
        }
        val all = known + read
        return if (all.isEmpty()) ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_STOCK, "The shop list shows nothing to buy"))
        else ActionOutcome.Done(describeStocks(all, canLearn))
    }

    /**
     * For a TM / HM on sale, who of the party can learn it ([MachineCompatibility]; "nobody" said too), so a machine
     * nobody can use isn't bought blind. Pokédex knowledge (a species' TMs): nothing below that level
     * ([ActionSettings.pokedex]) or without the game's data.
     */
    private fun partyCanLearn(context: PlanContext, state: GameState): (dev.kotlinds.pokemonclient.state.ItemId) -> List<String>? {
        val data = context.game.data?.takeIf { context.settings.pokedex } ?: return { null }
        return { item -> data.machineOf(item)?.let { MachineCompatibility.partyCanLearn(data, state.party, it).ifEmpty { listOf("nobody") } } }
    }

    /**
     * "nothing bought; sold here: item:4 (Poké Ball, ₽200), item:17 (Potion, ₽300)" for one clerk; with several,
     * each clerk's own list: "nothing bought; person:3 sells: item:17 (Potion, ₽300); person:5 sells: item:4 (...)".
     * A TM says who of the party can learn it when [canLearn] tells ("item:340 (TM13, ₽3000, party can learn: ...)").
     */
    internal fun describeStocks(stocks: List<Stock>, canLearn: (dev.kotlinds.pokemonclient.state.ItemId) -> List<String>? = { null }): String {
        fun items(stock: Stock) = stock.items.joinToString {
            "item:${it.item.id.value} (${it.item.name}${it.price?.let { p -> ", " + stock.currency.format(p.toLong()) } ?: ""}" +
                (if (it.soldOut) ", sold out" else "") +
                (canLearn(it.item.id)?.let { who -> ", party can learn: ${who.joinToString()}" } ?: "") + ")"
        }
        val single = stocks.singleOrNull()
        return if (single != null) "nothing bought; sold here: " + items(single)
        else "nothing bought; " + stocks.joinToString("; ") { "${it.clerk?.id ?: "this clerk"} sells: " + items(it) }
    }

    /**
     * What the bag got besides the [bought] item during a purchase: the clerk's gift (a Premier Ball for 10 Poké
     * Balls), as "1 Premier Ball (item:12)".
     */
    internal fun bonus(before: GameState, after: GameState, bought: Int): List<String> {
        val old = before.bag.orEmpty().flatMap { it.items }.groupBy { it.item.id.value }.mapValues { (_, s) -> s.sumOf { it.quantity } }
        return after.bag.orEmpty().flatMap { it.items }.groupBy { it.item.id.value }
            .mapNotNull { (id, stacks) ->
                val gained = stacks.sumOf { it.quantity } - (old[id] ?: 0)
                if (id == bought || gained <= 0) null else "$gained ${stacks.first().item.name} (item:$id)"
            }
    }

    /**
     * Sells [GameAction.Sell.quantity] of an item: talk to the clerk, SELL (the second entry of the clerk's menu
     * whatever the language), pick the item in the bag the game opens, set the number (read back after each press),
     * A, YES to the price; then leave. Checked on the bag and the money.
     */
    val sell = ActionPlan<GameAction.Sell> { action, context ->
        val start = context.state()
        val stack = start.bag.orEmpty().flatMap { it.items }.firstOrNull { matchesRef(action.item.raw, "item", it.item.id.value, it.item.name) }
            ?: return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "There's no ${action.item.raw} in the bag"))
        if (action.quantity > stack.quantity) {
            return@ActionPlan ActionOutcome.Failed(ActionError.InvalidParameter("quantity", action.quantity.toString(), listOf("1..${stack.quantity}")))
        }
        val price = context.game.data?.item(stack.item.id)?.price
        if (price == 0) return@ActionPlan ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${stack.item.name} can't be sold (no price: key items, ...)"))
        val itemId = stack.item.id.value
        val sold = openSellBag(context).andThen {
            PartyBagPlans.bagItem(context, action.item)
        }.andThen { entry ->
            context.navigator.choose(Screen.Bag::class, entry.label) { it.id == entry.id }
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Quantity || it.screen is Screen.YesNo }
        }.andThen { asked ->
            // A single item skips the number: the price question comes at once.
            if (asked.screen is Screen.YesNo) return@andThen Step.Done(asked)
            setQuantity(context, action.quantity).andThen { quantity ->
                context.scope.tap(Button.A)
                context.navigator.awaitChange(quantity.screen)
                context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.YesNo }
            }
        }.andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (sell)") { it.id == "option:yes" }
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Bag }
        }
        PartyBagPlans.closeToOverworld(context, maxPresses = CLOSE_PRESSES)
        if (sold is Step.Failed) return@ActionPlan ActionOutcome.Failed(sold.error)
        val after = context.state()
        val gone = count(start, itemId) - count(after, itemId)
        val earned = (after.player?.money ?: 0) - (start.player?.money ?: 0)
        if (gone != action.quantity) return@ActionPlan ActionOutcome.Failed(ActionError.Timeout("the bag lost $gone ${stack.item.name} instead of ${action.quantity}"))
        ActionOutcome.Done("sold $gone ${stack.item.name} for ₽$earned" + (after.player?.money?.let { ", ₽$it now" } ?: ""))
    }

    /** Talks to the clerk (unless the clerk's menu is open) and picks SELL, up to the bag the game opens for selling. */
    private fun openSellBag(context: PlanContext): Step<GameState> {
        var state = context.navigator.settle()
        if (state.screen is Screen.Bag) return Step.Done(state)
        if (stage(state) == Stage.OVERWORLD) {
            val clerk = clerk(state) ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
            when (val talk = context.run(GameAction.Interact(clerk.id))) {
                is ActionOutcome.Failed -> return Step.Failed(talk.error)
                is ActionOutcome.Done -> Unit
            }
            val menu = context.navigator.advanceUntil(SHOP_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }
            if (menu is Step.Failed) return menu
            state = (menu as Step.Done).value
        }
        if (stage(state) != Stage.CLERK_MENU) return Step.Failed(ActionError.UnexpectedScreen("the clerk's menu", state.screen))
        return context.navigator.choose(Screen.ListMenu::class, "SELL") { it.id == "option:$CLERK_SELL" }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Bag }
        }
    }

    /** Sets the number of a quantity screen, then confirms it with A when asked. */
    val setQuantity = ActionPlan<GameAction.SetQuantity> { action, context ->
        setQuantity(context, action.value).then { state ->
            if (!action.confirm) return@then ActionOutcome.Done("quantity ${action.value}")
            context.scope.tap(Button.A)
            context.navigator.awaitChange(state.screen)
            ActionOutcome.Done("quantity ${action.value} confirmed")
        }
    }

    /** From wherever [stage] says, to the shop list; from the field, talking to [chosen] (else the nearest clerk). */
    private fun openShop(context: PlanContext, chosen: FieldObject? = null): Step<GameState> {
        var state = context.navigator.settle()
        if (stage(state) == Stage.QUANTITY) {
            // B on the quantity goes back to the list.
            context.scope.tap(Button.B)
            context.navigator.awaitChange(state.screen)
            state = context.navigator.settle()
        }
        if (stage(state) == Stage.OVERWORLD) {
            val clerk = chosen ?: clerk(state) ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
            when (val talk = context.run(GameAction.Interact(clerk.id))) {
                is ActionOutcome.Failed -> return Step.Failed(talk.error)
                is ActionOutcome.Done -> Unit
            }
            val menu = context.navigator.advanceUntil(SHOP_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }
            if (menu is Step.Failed) return menu
            state = (menu as Step.Done).value
        }
        if ((state.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE) {
            val picked = context.navigator.choose(Screen.ListMenu::class, "BUY") { it.id == "option:$CLERK_BUY" }
            if (picked is Step.Failed) return picked
        }
        return context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Shop }
    }

    /** One line, from the shop list back to the shop list. */
    private fun buyOne(context: PlanContext, purchase: Purchase, onItem: (Int) -> Unit): Step<GameState> {
        val state = context.navigator.settle()
        val shop = state.screen as? Screen.Shop ?: return Step.Failed(ActionError.UnexpectedScreen("the shop list", state.screen))
        if (shop.goods != ShopGoods.ITEMS) return Step.Failed(notItems(shop))
        // The item by its id or its name in the game's data ([Screen.Shop.items]), never by the label shown.
        val sold = shop.items.firstOrNull { matchesRef(purchase.item.raw, "item", it.item.id.value, it.item.name) }
        val entry = sold?.let { s -> shop.entries.firstOrNull { it.id == "item:${s.item.id.value}" } }
            ?: return Step.Failed(ActionError.InvalidParameter("item", purchase.item.raw, shop.items.map { "item:${it.item.id.value} (${it.item.name}${it.price?.let { p -> ", " + shop.currency.format(p.toLong()) } ?: ""})" }))
        if (sold.soldOut) return Step.Failed(ActionError.Unavailable(UnavailableReason.NO_STOCK, "${sold.item.name} is sold out"))
        // One of each: no quantity is asked (one line = one).
        if (shop.oneOfEach && purchase.quantity != 1) return Step.Failed(ActionError.InvalidParameter("quantity", purchase.quantity.toString(), listOf("1")))
        val price = sold.price
        if (!entry.selectable || (price != null && price.toLong() * purchase.quantity > shop.balance)) {
            return Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_ENOUGH_MONEY,
                "${purchase.quantity} × ${sold.item.name} cost ${shop.currency.format((price ?: 0).toLong() * purchase.quantity)}, you have ${shop.currency.format(shop.balance)}"))
        }
        onItem(sold.item.id.value)
        val chosen = context.navigator.choose(Screen.Shop::class, entry.label) { it.id == entry.id }
        val asked = if (shop.oneOfEach) {
            // Straight to the confirmation (shop_menu.c:752): the price question, YES.
            chosen.andThen { context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.Shop } }
        } else {
            chosen.andThen {
                setQuantity(context, purchase.quantity)
            }.andThen { quantity ->
                // The quantity was just read back: A confirms it.
                context.scope.tap(Button.A)
                context.navigator.awaitChange(quantity.screen)
                context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.Shop }
            }
        }
        return asked.andThen { question ->
            if (question.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (buy)") { it.id == "option:yes" } else Step.Done(question)
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Shop }
        }
    }

    /** The refusal of a list that sells seals or decorations ([ShopGoods]): `buy` only buys items. */
    private fun notItems(shop: Screen.Shop): ActionError = ActionError.Unavailable(UnavailableReason.GOODS_NOT_ITEMS,
        "This counter sells ${shop.goods.name.lowercase()}, not items: buy only buys items", "press B to leave the list")

    /** How much the player has of what the shop on screen charges (its balance), else the money; null when unknown. */
    private fun balance(state: GameState): Long? = (state.screen as? Screen.Shop)?.balance ?: state.player?.money

    /**
     * Brings the quantity shown to [quantity]: RIGHT / LEFT (±10) while far from it, then UP / DOWN (±1), reading the
     * value back after every press. A press that doesn't move the value as expected makes it fall back to ±1 (the
     * quantity screens of other menus may not take ±10).
     */
    private fun setQuantity(context: PlanContext, quantity: Int): Step<GameState> {
        var tens = true
        repeat(MAX_QUANTITY_PRESSES) {
            val state = context.navigator.settle()
            val screen = state.screen as? Screen.Quantity ?: return Step.Failed(ActionError.UnexpectedScreen("the quantity", state.screen))
            if (quantity !in screen.min..screen.max) {
                return Step.Failed(ActionError.Unavailable(UnavailableReason.NOT_ENOUGH_MONEY, "The quantity can go from ${screen.min} to ${screen.max}, not $quantity"))
            }
            if (screen.value == quantity) return Step.Done(state)
            val diff = quantity - screen.value
            val (button, expected) = when {
                tens && diff >= TEN -> Button.RIGHT to screen.value + TEN
                tens && diff <= -TEN -> Button.LEFT to screen.value - TEN
                diff > 0 -> Button.UP to screen.value + 1
                else -> Button.DOWN to screen.value - 1
            }
            context.scope.tap(button)
            context.navigator.awaitChange(screen, maxFrames = QUANTITY_CHANGE_FRAMES)
            val now = (context.navigator.settle().screen as? Screen.Quantity)?.value
            if ((button == Button.RIGHT || button == Button.LEFT) && now != expected) tens = false
        }
        return Step.Failed(ActionError.Timeout("the quantity never reached $quantity"))
    }

    /** The clerk of this map (the nearest one). */
    private fun clerk(state: GameState): FieldObject? = clerks(state).firstOrNull()

    /** Every clerk of this map, nearest first. */
    private fun clerks(state: GameState): List<FieldObject> {
        val field = state.field ?: return emptyList()
        return field.objects.filter { it.role == PersonRole.CLERK }.sortedBy { kotlin.math.abs(it.x - field.x) + kotlin.math.abs(it.y - field.y) }
    }

    /** The clerk the player faces (next to them, or across the counter: two tiles ahead). */
    private fun clerkFaced(state: GameState): FieldObject? {
        val field = state.field ?: return null
        val facing = field.facing ?: return null
        return field.objects.firstOrNull { o ->
            o.role == PersonRole.CLERK && (1..2).any { d -> o.x == field.x + facing.dx * d && o.y == field.y + facing.dy * d }
        }
    }

    private fun count(state: GameState, itemId: Int) =
        state.bag.orEmpty().flatMap { it.items }.filter { it.item.id.value == itemId }.sumOf { it.quantity }

    private fun itemName(state: GameState, itemId: Int) =
        state.bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == itemId }?.item?.name ?: "item:$itemId"

    /** BUY / SELL / SEE YA!: BUY is always first. */
    private const val CLERK_BUY = 0
    private const val CLERK_SELL = 1
    private const val CLERK_MENU_SIZE = 3
    private const val SHOP_WAITS = 40
    private const val MAX_QUANTITY_PRESSES = 60
    private const val QUANTITY_CHANGE_FRAMES = 20
    private const val TEN = 10
    private const val CLOSE_PRESSES = 12
}
