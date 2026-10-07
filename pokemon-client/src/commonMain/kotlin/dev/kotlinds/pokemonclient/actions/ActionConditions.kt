package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.LearnQuestion
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MoveContext
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp

/**
 * The screens each action starts from, read from the state: what the specs' availability ([CommonActions]) is built
 * on, and what the recipes check before acting.
 *
 * Static on purpose, never methods of the recipes ([RecipeBase]): an action's availability is the common contract,
 * the same for every game; a game's recipes may carry an action out differently, never decide when it can be done.
 */
internal object ActionConditions {

    // region Battle

    /** The entry of the battle command menu that opens the bag (absent in battles without one). */
    const val BATTLE_BAG = "option:bag"

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
}
