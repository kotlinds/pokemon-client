package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.BagItem
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.LearnQuestion
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ShopCurrency
import dev.kotlinds.pokemonclient.state.ShopItem
import dev.kotlinds.pokemonclient.state.StartMenuFeature
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.PcStorage

/**
 * The common predicates the actions' availability is built on (the screens each action starts from, read from the
 * state, and the valid parameter values offered there), and what the recipes check before acting.
 *
 * Helpers, not entry points: when an action can run is decided by one method per action in the game's recipes
 * (`<action>Availability`, e.g. [FieldRecipes.tuneRadioAvailability]), whose common default calls these; both the
 * listing and the execution read that method ([ActionDefinition.availability]), never these helpers directly.
 */
internal object ActionConditions {

    // region Battle

    /** The entry of the battle command menu that opens the bag (absent in battles without one). */
    const val BATTLE_BAG = "option:bag"

    /**
     * The balls pocket of the state's bag ([dev.kotlinds.pokemonclient.state.BagPocket.name], language independent):
     * what `throw_ball` lists and throws from.
     */
    const val BALLS_BAG_POCKET = "balls"

    /** The balls in the bag ([BALLS_BAG_POCKET]): the choices of `throw_ball`, and what its recipe throws. */
    fun ballsInBag(state: GameState): List<BagItem> = state.bag.orEmpty().firstOrNull { it.name == BALLS_BAG_POCKET }?.items.orEmpty()

    /** The screens `use_item` starts from in battle: the command menu, when it has a BAG. */
    fun canUseItemInBattle(state: GameState): Boolean =
        state.battle != null && (state.screen as? Screen.BattleCommand)?.entries?.any { it.id == BATTLE_BAG } == true

    /** The screens `switch` starts from. */
    fun canSwitch(state: GameState): Boolean = when (val s = state.screen) {
        is Screen.BattleCommand -> true
        is Screen.PartyGrid -> s.purpose == PartyPurpose.BATTLE_SWITCH || s.purpose == PartyPurpose.BATTLE_REPLACE_FAINTED
        is Screen.ListMenu -> s.kind == MenuKind.BATTLE_SWITCH_OR_KEEP
        is Screen.YesNo -> s.entries.any { it.id == "option:next" }
        else -> false
    }

    /** The screens `learn_move` starts from: the question about the new move, or the list of moves to forget. */
    fun isLearnPrompt(state: GameState): Boolean = when (val s = state.screen) {
        is Screen.YesNo -> forgetAnswer(s) != null
        is Screen.MoveSelect -> s.context != MoveContext.BATTLE
        else -> false
    }

    /**
     * The entry answering "forget a move" on a learn prompt: `option:forget` in battle, YES on the field's plain YES /
     * NO question ([LearnQuestion.FORGET_A_MOVE]). Null when it isn't such a prompt.
     */
    fun forgetAnswer(prompt: Screen.YesNo): String? = when {
        prompt.entries.any { it.id == "option:forget" } -> "option:forget"
        prompt.learning?.question == LearnQuestion.FORGET_A_MOVE && prompt.entries.any { it.id == "option:yes" } -> "option:yes"
        else -> null
    }

    // endregion

    // region Bag and party

    /**
     * Field actions need the player free to act: walking around, or already in a field menu (start menu, the party,
     * the bag and their menus). Not in the PC's menus: their actions (DEPOSIT, MARKING...) aren't the party's.
     */
    fun inField(state: GameState): Boolean = state.battle == null && when (val s = state.screen) {
        // X opens nothing before the bag is given (the start of a new game).
        is Screen.Overworld -> s.awaiting == Awaiting.INPUT && state.startMenu?.contains(StartMenuFeature.BAG) != false
        is Screen.ListMenu -> s.kind == MenuKind.START_MENU
        is Screen.PartyGrid -> true
        is Screen.Bag -> !s.inBattle
        is Screen.ContextMenu -> !isPcMenu(s)
        else -> false
    }

    /**
     * True for the menu opened on a PC box slot: it has entries only the PC offers (DEPOSIT, WITHDRAW, MARKING,
     * RELEASE, HELD ITEMS), or is the MOVE ITEMS menu (a single GIVE / TAKE then EXIT).
     */
    private fun isPcMenu(menu: Screen.ContextMenu): Boolean =
        menu.item == null && (menu.entries.any { it.id in PC_ONLY_ENTRIES } ||
            (menu.entries.size == 2 && menu.entries[0].id in setOf("option:give", "option:take")))

    private val PC_ONLY_ENTRIES = setOf("option:deposit", "option:withdraw", "option:marking", "option:release", "option:held_items")

    // endregion

    // region Moving

    /** True when moving actions can start: walking (or surfing) freely, with the maps known. */
    fun canWalk(state: GameState, hasWorld: Boolean): Boolean =
        hasWorld && state.field != null && FieldControl.inControl(state)

    // endregion

    // region Services: the Poké Mart

    /** Where a purchase (`buy`) or a sale (`sell`) can start from. */
    enum class ShopStage { OVERWORLD, CLERK_MENU, SHOP_LIST, QUANTITY }

    /** The stage of the shop the state is at, or null when no purchase can start from here. */
    fun shopStage(state: GameState): ShopStage? = when (val screen = state.screen) {
        is Screen.Shop -> ShopStage.SHOP_LIST
        is Screen.Quantity -> if (state.field != null && clerkFaced(state) != null) ShopStage.QUANTITY else null
        is Screen.ListMenu -> if (screen.kind == MenuKind.MULTICHOICE && screen.entries.size == CLERK_MENU_SIZE && clerkFaced(state) != null) ShopStage.CLERK_MENU else null
        else -> if (canWalk(state, hasWorld = true) && shopClerks(state).isNotEmpty()) ShopStage.OVERWORLD else null
    }

    /**
     * What one clerk sells ([clerk] null: the counter the player is at, its list on screen or the clerk faced), as
     * the game decides it ([FieldObject.catalog]: the clerk's own mart script and the badges, read before talking),
     * with the [currency] its prices are in (money for a catalog; the list on screen says).
     */
    data class ShopStock(val clerk: FieldObject?, val items: List<ShopItem>, val currency: ShopCurrency = ShopCurrency.MONEY)

    /**
     * What can be bought here, clerk by clerk: the list on screen when the shop is open, the clerk faced (their menu
     * is open), else every clerk of the map whose catalog is known, nearest first (a floor of a department store has
     * two: each sells its own list, never mixed).
     */
    fun shopStock(state: GameState): List<ShopStock> {
        (state.screen as? Screen.Shop)?.let { shop -> return listOf(ShopStock(null, shop.items, shop.currency)) }
        if (shopStage(state) != ShopStage.OVERWORLD) return listOfNotNull(clerkFaced(state)?.catalog?.let { ShopStock(null, it) })
        return shopClerks(state).mapNotNull { c -> c.catalog?.let { ShopStock(c, it) } }
    }

    /** Every clerk of this map, nearest first. */
    fun shopClerks(state: GameState): List<FieldObject> {
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

    /** The clerk's menu: BUY / SELL / SEE YA!. */
    private const val CLERK_MENU_SIZE = 3

    // endregion

    // region Menus

    /** The OPTIONS screen (`set_options`): a list whose entries are settings. */
    fun isOptionsScreen(state: GameState): Boolean = (state.screen as? Screen.ListMenu)?.entries?.firstOrNull()?.id?.startsWith("setting:") == true

    // endregion

    // region System and story

    /** The intro stages `continue_game` goes through (the new-game intro is not one: it starts another game). */
    private val BEFORE_THE_GAME = setOf(IntroStage.LOADING, IntroStage.INTRO_MOVIE, IntroStage.TITLE_SCREEN, IntroStage.MAIN_MENU)

    /** True on the screens `continue_game` starts from: the intro movie, the title screen, the main menu, loading. */
    fun beforeTheGame(state: GameState): Boolean {
        val screen = state.screen
        return state.field == null &&
            (screen is Screen.Intro && screen.stage in BEFORE_THE_GAME || screen is Screen.ListMenu && screen.kind == MenuKind.MAIN_MENU)
    }

    /** The screens `watch_hall_of_fame` starts from: the registration in the Hall of Fame, or the save after it. */
    fun hallOfFameOffered(state: GameState): Boolean = when (val screen = state.screen) {
        is Screen.Viewer -> screen.app == ViewerApp.HALL_OF_FAME_REGISTER
        is Screen.Animation -> screen.kind == AnimationKind.SAVING
        else -> false
    }

    // endregion
    // region Choices: the valid parameter values offered with an available action

    /** Old Rod, Good Rod, Super Rod (Gen 4 item ids): what `fish` offers. */
    val RODS = setOf(445, 446, 447)

    /** The party as choices: "mon:… = CYNDAQUIL Lv5". */
    fun monChoices(state: GameState): List<Choice> = state.party.map { Choice(it.id.toString(), "${it.displayName} Lv${it.level}") }

    /** Stored Pokémon as choices: "mon:… = HO-OH Lv45 (BOX 1)". */
    fun storedChoices(storage: PcStorage): List<Choice> = storage.boxes.flatMap { box ->
        box.mons.map { Choice(it.id.toString(), "${it.displayName}${it.level?.let { l -> " Lv$l" } ?: ""} (${box.name})") }
    }

    /** The people, items and signs of this map as `go_to` / `interact` targets. */
    fun targetChoices(state: GameState): List<Choice> = state.field?.objects.orEmpty()
        .filter { it.kind != FieldObjectKind.FOLLOWER }
        .map { Choice(MovePlans.objectTargetId(it), "${it.label} at ${it.x},${it.y}") }

    /** Items worth offering to `use_item` (medicine, berries, battle items; key items have their own action) and the targets. */
    fun itemChoices(state: GameState, inBattle: Boolean): Map<String, List<Choice>> {
        val pockets = if (inBattle) setOf("medicine", "berries", "battle_items") else setOf("medicine", "berries", "items", "battle_items")
        val items = state.bag.orEmpty().filter { it.name in pockets }.flatMap { it.items }.filter { it.quantity > 0 }
            .map { Choice("item:${it.item.id.value}", "${it.item.name} x${it.quantity}") }
        return mapOf("item" to items, "target" to monChoices(state))
    }

    /** Walking around but the start menu has no [feature] yet: say so instead of failing on the menu. */
    fun locked(state: GameState, feature: StartMenuFeature): Availability.Unavailable? {
        val walking = FieldControl.inControl(state)
        if (!walking || state.startMenu?.contains(feature) != false) return null
        val detail = if (StartMenuFeature.BAG !in state.startMenu) "The start menu doesn't open yet" else "The start menu has no ${feature.name.lowercase()} yet"
        return Availability.Unavailable(UnavailableReason.NOT_UNLOCKED_YET, detail, "the story unlocks it (Mom gives it at the start)")
    }

    // endregion
}
