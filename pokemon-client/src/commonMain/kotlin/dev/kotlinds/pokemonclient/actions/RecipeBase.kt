package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen

/**
 * The root of every game's recipes: one method per action type, and [perform], the single dispatch from an action
 * to its method.
 *
 * The recipes of a game are one object ([dev.kotlinds.pokemonclient.PokemonGame.recipes]): a chain of classes, the
 * common recipes ([Recipes]) at the bottom, then a generation's ([dev.kotlinds.pokemonclient.games.gen4.Gen4Recipes]),
 * then a game's own. A game that carries out an action differently overrides that action's method; the action's
 * spec (name, parameters, availability, description: what agents see, [CommonActions]) stays the common one, so the
 * contract never varies per game.
 *
 * Safety by construction:
 * - [perform] is an exhaustive `when` over the sealed [GameAction], without `else`: a new action type doesn't
 *   compile until every game has a recipe for it;
 * - a recipe that carries out another action as one of its steps (talking to the nurse for `heal`, typing a
 *   nickname for `throw_ball`...) calls that action's method on the same object (`enterText(...)`, a virtual call),
 *   and the walking engine, which lives beside the chain, calls it on the game's recipes ([PlanContext.recipes]: a
 *   Repel used again on the way); so the game's own recipe is played there too, never the common one behind its
 *   back. This is also why the families of recipes are a chain of classes and never delegate (`by`): in a delegate,
 *   `this` is the delegate, and its nested calls would skip the game's overrides;
 * - the screens many recipes go through (the start menu, the party, the bag, the way back to the field...) are steps
 *   of the chain, `internal open` methods ([openStartMenuEntry], [openParty], [bagItem], [closeToOverworld], and the
 *   families' own), called the same virtual way by the recipes and by the walking engine ([PlanContext.recipes]): a
 *   game whose menu differs overrides that one step, and every recipe going through it plays the game's own way,
 *   none of them copied. There is one method per step, never a static copy of it beside the chain.
 *
 * Every member is `internal`: the recipes run only through [ActionRegistry.execute] (availability checked first) or
 * as a step of another recipe; they are not part of the library's API, and every game lives in this module. The
 * constructor is internal too: no recipes can be made outside it.
 *
 * Recipes are stateless (one instance is shared by every session and thread): what an action needs to remember
 * lives in its local variables or in its [PlanContext]. They are blocking (not `suspend`), like the
 * [dev.kotlinds.pokemonclient.runtime.ActionScope] they drive.
 */
abstract class RecipeBase internal constructor() {

    /** Carries out [action] with this game's recipe for its type. No availability check (see [ActionRegistry.execute]). */
    internal fun perform(action: GameAction, context: PlanContext): ActionOutcome = when (action) {
        is GameAction.Press -> press(action, context)
        is GameAction.Touch -> touch(action, context)
        is GameAction.Wait -> wait(action, context)
        is GameAction.Drag -> drag(action, context)
        is GameAction.AdvanceDialogue -> advanceDialogue(action, context)
        is GameAction.Choose -> choose(action, context)
        is GameAction.EnterText -> enterText(action, context)
        is GameAction.Attack -> attack(action, context)
        is GameAction.Switch -> switch(action, context)
        is GameAction.KeepBattling -> keepBattling(action, context)
        is GameAction.Run -> run(action, context)
        is GameAction.ThrowBall -> throwBall(action, context)
        is GameAction.LearnMove -> learnMove(action, context)
        is GameAction.UseItem -> useItem(action, context)
        is GameAction.Teach -> teach(action, context)
        is GameAction.ReorderParty -> reorderParty(action, context)
        is GameAction.GiveItem -> giveItem(action, context)
        is GameAction.TakeItem -> takeItem(action, context)
        is GameAction.Deposit -> deposit(action, context)
        is GameAction.Withdraw -> withdraw(action, context)
        is GameAction.Release -> release(action, context)
        is GameAction.Buy -> buy(action, context)
        is GameAction.SetQuantity -> setQuantity(action, context)
        is GameAction.Pc -> pc(action, context)
        is GameAction.SetOptions -> setOptions(action, context)
        is GameAction.Sell -> sell(action, context)
        is GameAction.UseKeyItem -> useKeyItem(action, context)
        is GameAction.RegisterItem -> registerItem(action, context)
        is GameAction.Fish -> fish(action, context)
        is GameAction.Fly -> fly(action, context)
        is GameAction.UseFieldMove -> useFieldMove(action, context)
        is GameAction.SaveGame -> saveGame(action, context)
        is GameAction.ChooseStarter -> chooseStarter(action, context)
        is GameAction.SoftReset -> softReset(action, context)
        is GameAction.ContinueGame -> continueGame(action, context)
        is GameAction.WatchHallOfFame -> watchHallOfFame(action, context)
        is GameAction.OpenMenu -> openMenu(action, context)
        is GameAction.Heal -> heal(action, context)
        is GameAction.GoTo -> goTo(action, context)
        is GameAction.Interact -> interact(action, context)
        is GameAction.FindEncounter -> findEncounter(action, context)
        is GameAction.Step -> step(action, context)
        is GameAction.Push -> push(action, context)
        is TuneRadio -> tuneRadio(action, context)
    }

    // region Basic: buttons, touch screen, text

    /** `press`. */
    internal abstract fun press(action: GameAction.Press, context: PlanContext): ActionOutcome

    /** `touch`. */
    internal abstract fun touch(action: GameAction.Touch, context: PlanContext): ActionOutcome

    /** `wait`. */
    internal abstract fun wait(action: GameAction.Wait, context: PlanContext): ActionOutcome

    /** `drag`. */
    internal abstract fun drag(action: GameAction.Drag, context: PlanContext): ActionOutcome

    /** `advance_dialogue`. */
    internal abstract fun advanceDialogue(action: GameAction.AdvanceDialogue, context: PlanContext): ActionOutcome

    /** `choose`. */
    internal abstract fun choose(action: GameAction.Choose, context: PlanContext): ActionOutcome

    /** `enter_text`. */
    internal abstract fun enterText(action: GameAction.EnterText, context: PlanContext): ActionOutcome

    // endregion

    // region Battle

    /** `attack`. */
    internal abstract fun attack(action: GameAction.Attack, context: PlanContext): ActionOutcome

    /** `switch`. */
    internal abstract fun switch(action: GameAction.Switch, context: PlanContext): ActionOutcome

    /** `keep_battling`. */
    internal abstract fun keepBattling(action: GameAction.KeepBattling, context: PlanContext): ActionOutcome

    /** `run`. */
    internal abstract fun run(action: GameAction.Run, context: PlanContext): ActionOutcome

    /** `throw_ball`. */
    internal abstract fun throwBall(action: GameAction.ThrowBall, context: PlanContext): ActionOutcome

    /** `learn_move`. */
    internal abstract fun learnMove(action: GameAction.LearnMove, context: PlanContext): ActionOutcome

    // endregion

    // region Bag and party

    /** `use_item`, in the field or in battle. */
    internal abstract fun useItem(action: GameAction.UseItem, context: PlanContext): ActionOutcome

    /** `teach`. */
    internal abstract fun teach(action: GameAction.Teach, context: PlanContext): ActionOutcome

    /** `reorder_party`. */
    internal abstract fun reorderParty(action: GameAction.ReorderParty, context: PlanContext): ActionOutcome

    /** `give_item`. */
    internal abstract fun giveItem(action: GameAction.GiveItem, context: PlanContext): ActionOutcome

    /** `take_item`. */
    internal abstract fun takeItem(action: GameAction.TakeItem, context: PlanContext): ActionOutcome

    /** `use_key_item`. */
    internal abstract fun useKeyItem(action: GameAction.UseKeyItem, context: PlanContext): ActionOutcome

    /** `register_item`. */
    internal abstract fun registerItem(action: GameAction.RegisterItem, context: PlanContext): ActionOutcome

    // endregion

    // region Moving

    /** `go_to`. */
    internal abstract fun goTo(action: GameAction.GoTo, context: PlanContext): ActionOutcome

    /** `interact`. */
    internal abstract fun interact(action: GameAction.Interact, context: PlanContext): ActionOutcome

    /** `step`. */
    internal abstract fun step(action: GameAction.Step, context: PlanContext): ActionOutcome

    /** `find_encounter`. */
    internal abstract fun findEncounter(action: GameAction.FindEncounter, context: PlanContext): ActionOutcome

    /** `push`. */
    internal abstract fun push(action: GameAction.Push, context: PlanContext): ActionOutcome

    // endregion

    // region Services: Pokémon Center, PC, Poké Mart

    /** `heal`. */
    internal abstract fun heal(action: GameAction.Heal, context: PlanContext): ActionOutcome

    /** `pc`. */
    internal abstract fun pc(action: GameAction.Pc, context: PlanContext): ActionOutcome

    /** `deposit`. */
    internal abstract fun deposit(action: GameAction.Deposit, context: PlanContext): ActionOutcome

    /** `withdraw`. */
    internal abstract fun withdraw(action: GameAction.Withdraw, context: PlanContext): ActionOutcome

    /** `release`. */
    internal abstract fun release(action: GameAction.Release, context: PlanContext): ActionOutcome

    /** `buy`. */
    internal abstract fun buy(action: GameAction.Buy, context: PlanContext): ActionOutcome

    /** `sell`. */
    internal abstract fun sell(action: GameAction.Sell, context: PlanContext): ActionOutcome

    /** `set_quantity`. */
    internal abstract fun setQuantity(action: GameAction.SetQuantity, context: PlanContext): ActionOutcome

    // endregion

    // region Field: field moves, menus, Pokégear

    /** `fly`. */
    internal abstract fun fly(action: GameAction.Fly, context: PlanContext): ActionOutcome

    /** `fish`. */
    internal abstract fun fish(action: GameAction.Fish, context: PlanContext): ActionOutcome

    /** `use_field_move`. */
    internal abstract fun useFieldMove(action: GameAction.UseFieldMove, context: PlanContext): ActionOutcome

    /** `save_game`. */
    internal abstract fun saveGame(action: GameAction.SaveGame, context: PlanContext): ActionOutcome

    /** `set_options`. */
    internal abstract fun setOptions(action: GameAction.SetOptions, context: PlanContext): ActionOutcome

    /** `open_menu`. */
    internal abstract fun openMenu(action: GameAction.OpenMenu, context: PlanContext): ActionOutcome

    /** `tune_radio`. */
    internal abstract fun tuneRadio(action: TuneRadio, context: PlanContext): ActionOutcome

    // endregion

    // region System and story

    /** `soft_reset`. */
    internal abstract fun softReset(action: GameAction.SoftReset, context: PlanContext): ActionOutcome

    /** `continue_game`. */
    internal abstract fun continueGame(action: GameAction.ContinueGame, context: PlanContext): ActionOutcome

    /** `choose_starter`. */
    internal abstract fun chooseStarter(action: GameAction.ChooseStarter, context: PlanContext): ActionOutcome

    /** `watch_hall_of_fame`. */
    internal abstract fun watchHallOfFame(action: GameAction.WatchHallOfFame, context: PlanContext): ActionOutcome

    // endregion

    // region Field menus: the steps every family goes through

    // How the start menu, the party and the bag are reached and left: the steps a game whose menus differ overrides
    // (a start menu read as a list instead of touched...), so every recipe going through them plays the game's own way
    // without being copied. Here, the lowest layer, because recipes of every family (and the walking engine, through
    // [PlanContext.recipes]) use them.

    /** Opens the start menu (X) from the overworld and picks [entryId], or does nothing if already there. */
    internal open fun openStartMenuEntry(context: PlanContext, entryId: String): Step<GameState> {
        var state = context.navigator.settle()
        if (state.screen is Screen.Overworld) {
            context.scope.tap(Button.X)
            context.navigator.awaitChange(state.screen)
            state = context.navigator.settle()
        }
        if ((state.screen as? Screen.ListMenu)?.kind != MenuKind.START_MENU) {
            return Step.Failed(ActionError.UnexpectedScreen("the start menu", state.screen))
        }
        return context.navigator.choose(Screen.ListMenu::class, entryId) { it.id == entryId }
    }

    /** The field party grid (from the overworld, or already open). */
    internal open fun openParty(context: PlanContext): Step<GameState> {
        val state = context.navigator.settle()
        if ((state.screen as? Screen.PartyGrid)?.purpose == PartyPurpose.FIELD) return Step.Done(state)
        return openStartMenuEntry(context, "option:pokemon")
    }

    /**
     * Opens the bag on the pocket holding [item] and turns pages until the item is on screen; returns its entry.
     */
    internal open fun bagItem(context: PlanContext, item: ItemRef): Step<Entry> {
        val owned = context.state().bag.orEmpty().flatMap { pocket -> pocket.items.map { pocket.name to it } }
            .firstOrNull { (_, stack) -> matchesRef(item.raw, "item", stack.item.id.value, stack.item.name) }
            ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "There's no ${item.raw} in the bag"))
        val (pocket, stack) = owned
        val opened = (context.state().screen as? Screen.Bag)?.let { Step.Done(context.state()) } ?: openStartMenuEntry(context, "option:bag")
        if (opened is Step.Failed) return opened
        var bag = context.navigator.settle().screen as? Screen.Bag ?: return Step.Failed(ActionError.UnexpectedScreen("the bag", context.state().screen))
        if (bag.pocket != pocket) {
            val tab = "pocket:$pocket"
            when (val switched = context.navigator.choose(Screen.Bag::class, pocket) { it.id == tab }) {
                is Step.Failed -> return switched
                is Step.Done -> bag = switched.value.screen as? Screen.Bag ?: return Step.Failed(ActionError.UnexpectedScreen("the bag", switched.value.screen))
            }
        }
        val itemId = "item:${stack.item.id.value}"
        repeat(bag.pages.coerceAtLeast(1)) {
            bag.entries.firstOrNull { it.id == itemId }?.let { return Step.Done(it) }
            // Not on this page: turn the page with the (touch) arrow, then look again.
            val next = bag.entries.firstOrNull { it.id == "page:next" }?.touch ?: return@repeat
            val before = bag
            context.scope.touch(next)
            context.navigator.awaitChange(before)
            bag = context.navigator.settle().screen as? Screen.Bag ?: return Step.Failed(ActionError.UnexpectedScreen("the bag", context.state().screen))
        }
        return Step.Failed(ActionError.NotOnScreen(stack.item.name, "the $pocket pocket", bag.entries.map { it.label }))
    }

    /** Presses B until the player can walk again (at most a few times), reading every message on the way. */
    internal open fun closeToOverworld(context: PlanContext, maxPresses: Int = MAX_CLOSE_PRESSES) {
        repeat(maxPresses) {
            val state = context.navigator.settle()
            when (state.screen) {
                is Screen.Overworld -> return
                // A question about learning a move (Rare Candy...) is the agent's to answer: B would give the move up.
                is Screen.YesNo, is Screen.MoveSelect -> if (state.battle == null && ActionConditions.isLearnPrompt(state)) return else context.scope.tap(Button.B)
                is Screen.Dialogue, is Screen.PressToContinue -> context.scope.tap(Button.A)
                // The Pokégear has no B: its Close button is touched.
                is Screen.Viewer -> state.screen.exit.touch?.let { context.scope.touch(it) } ?: context.scope.tap(state.screen.exit.button ?: Button.B)
                else -> context.scope.tap(Button.B)
            }
            context.navigator.awaitChange(state.screen, maxFrames = 60)
        }
    }

    // endregion

    private companion object {
        const val MAX_CLOSE_PRESSES = 8
    }
}
