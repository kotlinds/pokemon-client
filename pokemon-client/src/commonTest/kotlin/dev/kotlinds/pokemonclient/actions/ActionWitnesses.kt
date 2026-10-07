package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.RadioStation
import dev.kotlinds.pokemonclient.world.FieldMoveKind

/**
 * One instance of every action type ([GameAction] is sealed: [RecipeBase.perform] can't compile without a recipe
 * for each, but a spec is found by type at run time). Complete by construction: [witnessOf] is an exhaustive `when`
 * over [GameAction] (no `else`), so a new action type doesn't compile until it has its own entry here, with its
 * witness; [ActionRegistryTest.everyActionTypeHasExactlyOneSpec] then requires a spec for it, and
 * [AvailabilityTest.theListingAndTheExecutionAgreeForEveryActionAndState] runs it.
 */
internal enum class Witness(val action: GameAction) {
    PRESS(GameAction.Press(Button.A)),
    TOUCH(GameAction.Touch(TouchPoint(1, 1))),
    WAIT(GameAction.Wait(1)),
    DRAG(GameAction.Drag(TouchPoint(1, 1), TouchPoint(2, 2))),
    ADVANCE_DIALOGUE(GameAction.AdvanceDialogue),
    CHOOSE(GameAction.Choose("option:yes")),
    ENTER_TEXT(GameAction.EnterText("ABC")),
    ATTACK(GameAction.Attack(MoveRef("move:33"))),
    SWITCH(GameAction.Switch(MonId(1, 2))),
    KEEP_BATTLING(GameAction.KeepBattling),
    RUN(GameAction.Run),
    THROW_BALL(GameAction.ThrowBall(ItemRef("item:4"))),
    LEARN_MOVE(GameAction.LearnMove(null)),
    USE_ITEM(GameAction.UseItem(ItemRef("item:17"))),
    TEACH(GameAction.Teach(ItemRef("item:328"), MonId(1, 2))),
    REORDER_PARTY(GameAction.ReorderParty(MonId(1, 2), 1)),
    GIVE_ITEM(GameAction.GiveItem(MonId(1, 2), ItemRef("item:17"))),
    TAKE_ITEM(GameAction.TakeItem(MonId(1, 2))),
    USE_KEY_ITEM(GameAction.UseKeyItem(ItemRef("item:450"))),
    REGISTER_ITEM(GameAction.RegisterItem(ItemRef("item:450"))),
    GO_TO(GameAction.GoTo(1, 1, null)),
    INTERACT(GameAction.Interact("person:0")),
    STEP(GameAction.Step(Direction.NORTH)),
    FIND_ENCOUNTER(GameAction.FindEncounter),
    PUSH(GameAction.Push("person:0")),
    HEAL(GameAction.Heal),
    PC(GameAction.Pc(emptyList())),
    DEPOSIT(GameAction.Deposit(MonId(1, 2))),
    WITHDRAW(GameAction.Withdraw(MonId(1, 2))),
    RELEASE(GameAction.Release(MonId(1, 2), confirm = true)),
    BUY(GameAction.Buy(emptyList())),
    SELL(GameAction.Sell(ItemRef("item:17"), 1)),
    SET_QUANTITY(GameAction.SetQuantity(1)),
    FLY(GameAction.Fly("New Bark Town")),
    FISH(GameAction.Fish(ItemRef("item:445"))),
    USE_FIELD_MOVE(GameAction.UseFieldMove(FieldMoveKind.CUT)),
    SAVE_GAME(GameAction.SaveGame),
    SET_OPTIONS(GameAction.SetOptions()),
    OPEN_MENU(GameAction.OpenMenu("option:bag")),
    TUNE_RADIO(TuneRadio(RadioStation.entries.first())),
    SOFT_RESET(GameAction.SoftReset),
    CONTINUE_GAME(GameAction.ContinueGame),
    CHOOSE_STARTER(GameAction.ChooseStarter("starter:0")),
    WATCH_HALL_OF_FAME(GameAction.WatchHallOfFame);
}

/** The [Witness] of [action]'s type. */
internal fun witnessOf(action: GameAction): Witness = when (action) {
    is GameAction.Press -> Witness.PRESS
    is GameAction.Touch -> Witness.TOUCH
    is GameAction.Wait -> Witness.WAIT
    is GameAction.Drag -> Witness.DRAG
    GameAction.AdvanceDialogue -> Witness.ADVANCE_DIALOGUE
    is GameAction.Choose -> Witness.CHOOSE
    is GameAction.EnterText -> Witness.ENTER_TEXT
    is GameAction.Attack -> Witness.ATTACK
    is GameAction.Switch -> Witness.SWITCH
    GameAction.KeepBattling -> Witness.KEEP_BATTLING
    GameAction.Run -> Witness.RUN
    is GameAction.ThrowBall -> Witness.THROW_BALL
    is GameAction.LearnMove -> Witness.LEARN_MOVE
    is GameAction.UseItem -> Witness.USE_ITEM
    is GameAction.Teach -> Witness.TEACH
    is GameAction.ReorderParty -> Witness.REORDER_PARTY
    is GameAction.GiveItem -> Witness.GIVE_ITEM
    is GameAction.TakeItem -> Witness.TAKE_ITEM
    is GameAction.UseKeyItem -> Witness.USE_KEY_ITEM
    is GameAction.RegisterItem -> Witness.REGISTER_ITEM
    is GameAction.GoTo -> Witness.GO_TO
    is GameAction.Interact -> Witness.INTERACT
    is GameAction.Step -> Witness.STEP
    GameAction.FindEncounter -> Witness.FIND_ENCOUNTER
    is GameAction.Push -> Witness.PUSH
    GameAction.Heal -> Witness.HEAL
    is GameAction.Pc -> Witness.PC
    is GameAction.Deposit -> Witness.DEPOSIT
    is GameAction.Withdraw -> Witness.WITHDRAW
    is GameAction.Release -> Witness.RELEASE
    is GameAction.Buy -> Witness.BUY
    is GameAction.Sell -> Witness.SELL
    is GameAction.SetQuantity -> Witness.SET_QUANTITY
    is GameAction.Fly -> Witness.FLY
    is GameAction.Fish -> Witness.FISH
    is GameAction.UseFieldMove -> Witness.USE_FIELD_MOVE
    GameAction.SaveGame -> Witness.SAVE_GAME
    is GameAction.SetOptions -> Witness.SET_OPTIONS
    is GameAction.OpenMenu -> Witness.OPEN_MENU
    is TuneRadio -> Witness.TUNE_RADIO
    GameAction.SoftReset -> Witness.SOFT_RESET
    GameAction.ContinueGame -> Witness.CONTINUE_GAME
    is GameAction.ChooseStarter -> Witness.CHOOSE_STARTER
    GameAction.WatchHallOfFame -> Witness.WATCH_HALL_OF_FAME
}
