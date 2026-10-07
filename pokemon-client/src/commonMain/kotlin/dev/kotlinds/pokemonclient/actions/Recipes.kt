package dev.kotlinds.pokemonclient.actions

/**
 * The common recipes: how every action is carried out in terms of screens ("open the bag, select the pocket, select
 * the item, USE..."), written once for every game. They never press blindly: every choice goes through the
 * [Navigator], which reads the cursor, moves one tap at a time and confirms only on the target.
 *
 * What a game plays ([dev.kotlinds.pokemonclient.PokemonGame.recipes], [COMMON] by default); a generation or a game
 * whose screens really differ extends it and overrides what differs
 * ([dev.kotlinds.pokemonclient.games.gen4.Gen4Recipes], then the game's own). The availability of an action is not a
 * recipe: it is the common contract ([ActionSpec.availability]), the same for every game.
 *
 * Each method carries out its action with the existing recipe value of its family for now (`BasicPlans`,
 * `ShopPlans`...).
 */
open class Recipes internal constructor() : RecipeBase() {

    // region Basic: buttons, touch screen, text

    override fun press(action: GameAction.Press, context: PlanContext) = BasicPlans.press.run(action, context)
    override fun touch(action: GameAction.Touch, context: PlanContext) = BasicPlans.touch.run(action, context)
    override fun wait(action: GameAction.Wait, context: PlanContext) = BasicPlans.wait.run(action, context)
    override fun drag(action: GameAction.Drag, context: PlanContext) = MoreActions.dragPlan.run(action, context)
    override fun advanceDialogue(action: GameAction.AdvanceDialogue, context: PlanContext) = BasicPlans.advanceDialogue.run(action, context)
    override fun choose(action: GameAction.Choose, context: PlanContext) = BasicPlans.choose.run(action, context)
    override fun enterText(action: GameAction.EnterText, context: PlanContext) = TextPlans.enterText.run(action, context)

    // endregion

    // region Battle

    override fun attack(action: GameAction.Attack, context: PlanContext) = BasicPlans.attack.run(action, context)
    override fun switch(action: GameAction.Switch, context: PlanContext) = BattlePlans.switch.run(action, context)
    override fun keepBattling(action: GameAction.KeepBattling, context: PlanContext) = BasicPlans.keepBattling.run(action, context)
    override fun run(action: GameAction.Run, context: PlanContext) = BasicPlans.run.run(action, context)
    override fun throwBall(action: GameAction.ThrowBall, context: PlanContext) = BattlePlans.throwBall.run(action, context)
    override fun learnMove(action: GameAction.LearnMove, context: PlanContext) = BattlePlans.learnMove.run(action, context)

    // endregion

    // region Bag and party

    override fun useItem(action: GameAction.UseItem, context: PlanContext) = PartyBagPlans.useItem.run(action, context)
    override fun teach(action: GameAction.Teach, context: PlanContext) = PartyBagPlans.teach.run(action, context)
    override fun reorderParty(action: GameAction.ReorderParty, context: PlanContext) = PartyBagPlans.reorderParty.run(action, context)
    override fun giveItem(action: GameAction.GiveItem, context: PlanContext) = PartyBagPlans.giveItem.run(action, context)
    override fun takeItem(action: GameAction.TakeItem, context: PlanContext) = PartyBagPlans.takeItem.run(action, context)
    override fun useKeyItem(action: GameAction.UseKeyItem, context: PlanContext) = PartyBagPlans.useKeyItem.run(action, context)
    override fun registerItem(action: GameAction.RegisterItem, context: PlanContext) = PartyBagPlans.registerItem.run(action, context)

    // endregion

    // region Moving

    override fun goTo(action: GameAction.GoTo, context: PlanContext) = MovePlans.goTo.run(action, context)
    override fun interact(action: GameAction.Interact, context: PlanContext) = MovePlans.interact.run(action, context)
    override fun step(action: GameAction.Step, context: PlanContext) = MovePlans.step.run(action, context)
    override fun findEncounter(action: GameAction.FindEncounter, context: PlanContext) = MovePlans.findEncounter.run(action, context)
    override fun push(action: GameAction.Push, context: PlanContext) = PushPlans.push(action, context)

    // endregion

    // region Services: Pokémon Center, PC, Poké Mart

    override fun heal(action: GameAction.Heal, context: PlanContext) = FieldPlans.heal.run(action, context)
    override fun pc(action: GameAction.Pc, context: PlanContext) = PcPlans.pc.run(action, context)
    override fun deposit(action: GameAction.Deposit, context: PlanContext) = PcPlans.deposit.run(action, context)
    override fun withdraw(action: GameAction.Withdraw, context: PlanContext) = PcPlans.withdraw.run(action, context)
    override fun release(action: GameAction.Release, context: PlanContext) = PcPlans.release.run(action, context)
    override fun buy(action: GameAction.Buy, context: PlanContext) = ShopPlans.buy.run(action, context)
    override fun sell(action: GameAction.Sell, context: PlanContext) = ShopPlans.sell.run(action, context)
    override fun setQuantity(action: GameAction.SetQuantity, context: PlanContext) = ShopPlans.setQuantity.run(action, context)

    // endregion

    // region Field: field moves, menus, Pokégear

    override fun fly(action: GameAction.Fly, context: PlanContext) = FieldPlans.fly.run(action, context)
    override fun fish(action: GameAction.Fish, context: PlanContext) = FieldPlans.fish.run(action, context)
    override fun useFieldMove(action: GameAction.UseFieldMove, context: PlanContext) = FieldPlans.useFieldMove.run(action, context)
    override fun saveGame(action: GameAction.SaveGame, context: PlanContext) = FieldPlans.saveGame.run(action, context)
    override fun setOptions(action: GameAction.SetOptions, context: PlanContext) = OptionsPlans.setOptions.run(action, context)
    override fun openMenu(action: GameAction.OpenMenu, context: PlanContext) = MoreActions.openMenuPlan.run(action, context)
    override fun tuneRadio(action: TuneRadio, context: PlanContext) = PokegearActions.tunePlan.run(action, context)

    // endregion

    // region System and story

    override fun softReset(action: GameAction.SoftReset, context: PlanContext) = SystemPlans.softReset.run(action, context)
    override fun continueGame(action: GameAction.ContinueGame, context: PlanContext) = SystemPlans.continueGame.run(action, context)
    override fun chooseStarter(action: GameAction.ChooseStarter, context: PlanContext) = StarterPlans.chooseStarter.run(action, context)
    override fun watchHallOfFame(action: GameAction.WatchHallOfFame, context: PlanContext) = HallOfFamePlans.watchHallOfFame.run(action, context)

    // endregion

    companion object {
        /** The common recipes alone: what a game plays when nothing of it differs (the default of [dev.kotlinds.pokemonclient.PokemonGame.recipes]). */
        internal val COMMON: Recipes = Recipes()
    }
}
