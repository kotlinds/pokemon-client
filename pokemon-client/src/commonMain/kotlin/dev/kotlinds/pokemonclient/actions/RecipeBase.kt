package dev.kotlinds.pokemonclient.actions

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
 *   nickname for `throw_ball`...) goes through the same object ([PlanContext.run] → [PlanContext.recipes], the
 *   game's recipes → [perform], a virtual call), so the game's own recipe is played there too, never the common one
 *   behind its back. This is also why the families of recipes are a chain of classes and never delegate (`by`): in
 *   a delegate, `this` is the delegate, and its nested calls would skip the game's overrides.
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
}
