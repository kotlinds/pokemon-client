package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.data.MachineId
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleOutcome
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.LearnQuestion
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource

/**
 * The recipes of the battle actions: `attack`, `run`, `keep_battling`, `switch`, `throw_ball`, `learn_move`, and the
 * battle half of `use_item` ([useItemInBattle]), with the steps the bag recipes share with them (the move lists of
 * PP items, [forgetRefusal]). A family of the chain of [RecipeBase], above [BasicRecipes].
 */
abstract class BattleRecipes internal constructor() : BasicRecipes() {

    /**
     * FIGHT, then the move (checked by id / name), then the target in doubles. The move is checked against the active
     * Pokémon's moves BEFORE anything is pressed ([BattleMoveChoice.refusal]: a move it doesn't know, without PP,
     * disabled, taunted, or another than the encored one leaves the menu untouched). Under Encore, or when no move can
     * be chosen (Struggle), the game skips the move list: FIGHT alone plays the turn, and that is the success.
     * Started from the move list already open (a previous attempt), the move is picked there. A move the list refuses
     * (Torment, Imprison, a Choice item: not selectable) backs out to the command menu with a typed error. On the
     * target screen, a move with a single possible choice (spread moves: "all targets") is confirmed without asking
     * for a target.
     */
    override fun attack(action: GameAction.Attack, context: PlanContext): ActionOutcome {
        val start = context.state()
        val battle = start.battle
        val actorRef = (start.screen as? Screen.BattleCommand)?.actor ?: battle?.actor ?: BattlerRef.PLAYER_LEFT
        val actor = battle?.battlers?.firstOrNull { it.ref == actorRef }
        if (actor != null) BattleMoveChoice.refusal(actor, action.move)?.let { return ActionOutcome.Failed(it) }
        val toList = if (start.screen is Screen.MoveSelect) Step.Done(start)
        else context.navigator.choose(Screen.BattleCommand::class, "FIGHT") { it.id == "option:fight" }
        return toList.then {
            val now = context.state()
            val moves = now.screen as? Screen.MoveSelect
            if (moves == null) {
                // Encore / Struggle: no move list, the turn is under way (or the next Pokémon's command menu is up).
                val skipped = actor?.let(BattleMoveChoice::skippedList)
                return@then when {
                    skipped != null -> ActionOutcome.Done(skipped)
                    // What the state can't tell left no move (Torment, Imprison, Gravity, Heal Block, a Choice item):
                    // the game skipped the list for Struggle, seen as the turn under way (its messages, animations) or
                    // the next Pokémon's command menu (doubles).
                    turnUnderWay(now.screen, actorRef, battle?.isDouble == true) -> ActionOutcome.Done(BattleMoveChoice.STRUGGLED_UNSEEN)
                    else -> ActionOutcome.Failed(ActionError.UnexpectedScreen("the move list", now.screen))
                }
            }
            val wanted = moves.entries.firstOrNull { entry ->
                val id = entry.id.removePrefix("move:").toIntOrNull() ?: return@firstOrNull false
                matchesRef(action.move.raw, "move", id, entry.label.substringBefore(" ("))
            } ?: return@then backOut(context, ActionError.InvalidParameter("move", action.move.raw, moves.entries.filter { it.id.startsWith("move:") && it.selectable }.map { "${it.id} = ${it.label.substringBefore(" (")}" }))
            if (!wanted.selectable) return@then backOut(
                context,
                ActionError.Unavailable(
                    UnavailableReason.MOVE_REFUSED,
                    "the game refuses ${wanted.label.substringBefore(" (")} this turn (Disable, Taunt, Torment, Imprison or a Choice item)",
                    "choose among " + moves.entries.filter { it.id.startsWith("move:") && it.selectable }.joinToString { "${it.id} ${it.label.substringBefore(" (")}" },
                ),
            )
            context.navigator.choose(Screen.MoveSelect::class, wanted.label) { it.id == wanted.id }.then { after ->
                val targets = after.screen as? Screen.TargetSelect
                if (targets == null) {
                    ActionOutcome.Done()
                } else {
                    val choices = targets.entries.filter { it.selectable && it.id != CANCEL }
                    val target = when {
                        // Spread moves: one "all targets" entry, whatever target was given.
                        choices.size == 1 && choices.single().id == ALL_TARGETS -> ALL_TARGETS
                        action.target != null -> action.target.wire
                        choices.size == 1 -> choices.single().id
                        else -> return@then backOut(context, ActionError.InvalidParameter("target", "none", choices.map { "${it.id} = ${it.label}" }))
                    }
                    when (val chosen = context.navigator.choose(Screen.TargetSelect::class, target) { it.id == target }) {
                        is Step.Done -> ActionOutcome.Done(if (target == ALL_TARGETS && action.target != null) "this move hits every target" else null)
                        is Step.Failed -> backOut(context, chosen.error)
                    }
                }
            }.let { outcome -> if (outcome is ActionOutcome.Failed && context.state().screen.let { it is Screen.MoveSelect || it is Screen.TargetSelect }) backOut(context, outcome.error) else outcome }
        }
    }

    /**
     * True when [screen], right after FIGHT for [actor], is the turn being played: the battle's messages and
     * animations, or in a [double] battle the command menu of the player's other Pokémon. Never the same actor's menu.
     */
    private fun turnUnderWay(screen: Screen, actor: BattlerRef, double: Boolean): Boolean =
        screen is Screen.Battle || (double && screen is Screen.BattleCommand && screen.actor != null && screen.actor != actor)

    /** Leaves the move / target screens (B) back to the command menu, then fails with [error]. */
    private fun backOut(context: PlanContext, error: ActionError): ActionOutcome {
        repeat(3) {
            val screen = context.navigator.settle().screen
            if (screen !is Screen.MoveSelect && screen !is Screen.TargetSelect) return ActionOutcome.Failed(error)
            context.navigator.press(Button.B, screen)
        }
        return ActionOutcome.Failed(error)
    }

    /**
     * Flees: RUN on the battle command menu, then follows the attempt to its end: the battle is over (got away), or
     * the game asks for a choice again ("Can't escape!": the foe had its turn, then the command menu, or the party
     * when the foe's attack made one of the player's Pokémon faint; or a trapping foe prevented it at once, checked
     * live against an Arena Trap Diglett). The outcome is read from the state (the battle still there or not), never
     * from the message. A failed escape is done (the command was carried out) but stops a chain
     * ([ChainStop.EscapeFailed]): the steps after a `run` were meant for after the battle.
     */
    override fun run(action: GameAction.Run, context: PlanContext): ActionOutcome {
        val foe = context.state().battle?.battlers?.firstOrNull { !it.ref.isPlayerSide }?.let { it.nickname ?: it.species.name }
        return context.navigator.choose(Screen.BattleCommand::class, "RUN") { it.id == "option:run" }.then {
            when (val escaped = escapeResult(context)) {
                null -> ActionOutcome.Failed(ActionError.Timeout("the escape attempt didn't end"))
                true -> ActionOutcome.Done("got away safely")
                false -> ActionOutcome.Done("couldn't escape: the battle goes on", stopsChain = ChainStop.EscapeFailed(foe))
            }
        }
    }

    /**
     * Follows an escape attempt: true once the battle is over, false when the battle asks for a choice again, null
     * when neither happened in [RUN_FRAMES]. Messages waiting for A are read; the fade out (whatever screen it
     * shows while the battle is freed) is only waited for.
     */
    private fun escapeResult(context: PlanContext): Boolean? {
        // Bounded in rounds too: each one runs frames, but a scripted game may not count them. Every other screen
        // (the fade out, whatever it shows while the battle is freed) is only waited through.
        val end = context.navigator.advanceUntil(maxPresses = RUN_FRAMES / 10, maxFrames = RUN_FRAMES, waitOn = { true }) { state ->
            state.battle == null || (state.screen is Screen.Selectable && state.screen.awaiting == Awaiting.INPUT)
        }
        val state = (end as? Step.Done)?.value ?: return null
        return state.battle == null
    }

    /**
     * "Switch Pokémon?" → KEEP BATTLING. Called while the messages before the question still scroll (EXP, level
     * up), it first reads them up to the question; when no question comes (the foe sent its next Pokémon at once,
     * the battle ended) it stops there and says so.
     */
    override fun keepBattling(action: GameAction.KeepBattling, context: PlanContext): ActionOutcome {
        fun switchPrompt(state: GameState) = (state.screen as? Screen.ListMenu)?.kind == MenuKind.BATTLE_SWITCH_OR_KEEP
        val reached = context.navigator.advanceUntil { state ->
            switchPrompt(state) || state.screen is Screen.Selectable || state.battle == null ||
                state.screen is Screen.Battle && state.screen.awaiting == Awaiting.INPUT
        }
        return reached.then { state ->
            when {
                switchPrompt(state) -> context.navigator.choose(Screen.ListMenu::class, "KEEP BATTLING") { it.id == "option:keep" }
                    .then { ActionOutcome.Done() }
                state.battle == null -> ActionOutcome.Done("no switch question: the battle is over")
                state.screen is Screen.BattleCommand -> ActionOutcome.Done("no switch question: the foe sent its next Pokémon")
                else -> ActionOutcome.Failed(ActionError.UnexpectedScreen("the switch-or-keep question", state.screen))
            }
        }
    }

    /**
     * Sends [GameAction.Switch.mon] in: from the command menu (POKéMON), from "Will you switch Pokémon?" (SWITCH),
     * or on the forced replacement grid after a K.O.; then SHIFT on the Pokémon.
     */
    override fun switch(action: GameAction.Switch, context: PlanContext): ActionOutcome {
        val state = context.navigator.settle()
        val toGrid = when {
            state.screen is Screen.PartyGrid -> Step.Done(state)
            state.screen is Screen.BattleCommand -> context.navigator.choose(Screen.BattleCommand::class, "POKéMON") { it.id == "option:pokemon" }
            (state.screen as? Screen.ListMenu)?.kind == MenuKind.BATTLE_SWITCH_OR_KEEP ->
                context.navigator.choose(Screen.ListMenu::class, "SWITCH") { it.id == "option:switch" }
            state.screen is Screen.YesNo && (state.screen as Screen.YesNo).entries.any { it.id == "option:next" } ->
                context.navigator.choose(Screen.YesNo::class, "USE NEXT POKéMON") { it.id == "option:next" }
            else -> Step.Failed(ActionError.UnexpectedScreen("the battle menu or the party", state.screen))
        }
        return toGrid.andThen {
            context.navigator.settle().let { grid ->
                if (grid.screen !is Screen.PartyGrid) return@andThen Step.Failed(ActionError.UnexpectedScreen("the party", grid.screen))
            }
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon to send in") { it.id == action.mon.toString() }
        }.andThen { after ->
            if (after.screen is Screen.ContextMenu) context.navigator.choose(Screen.ContextMenu::class, "SHIFT") { it.id == "option:shift" } else Step.Done(after)
        }.then { ActionOutcome.Done() }
    }

    /**
     * BAG → POKé BALLS → the ball → USE, then follows the throw to its typed [ThrowResult]: caught (then the Pokédex
     * entry, the nickname question answered with [GameAction.ThrowBall.nickname] (none: NO), and the PC transfer when
     * the party is full, up to the overworld), broke free (after how many shakes; then fled, for a roaming Pokémon
     * running on its turn), or missed. The result is read from the game (the shakes it computed when the ball landed,
     * and how it decided the battle ends: [BattleState.outcome]), never guessed from the screens that follow: a battle
     * that is over isn't a capture (Raikou broke free, then fled).
     */
    override fun throwBall(action: GameAction.ThrowBall, context: PlanContext): ActionOutcome {
        val before = context.state()
        // The ball by its id or its name in the game's data (the bag of the state, like `sell`), never by the label
        // the pocket shows (the game may run in another language): refused before opening anything.
        val balls = ActionConditions.ballsInBag(before)
        val stack = balls.firstOrNull { matchesRef(action.ball.raw, "item", it.item.id.value, it.item.name) }
            ?: return ActionOutcome.Failed(ActionError.InvalidParameter("ball", action.ball.raw, balls.map { "item:${it.item.id.value} (${it.item.name} x${it.quantity})" }))
        val ballId = "item:${stack.item.id.value}"
        val reached = context.navigator.choose(Screen.BattleCommand::class, "BAG") { it.id == "option:bag" }.andThen {
            context.navigator.choose(Screen.Bag::class, "POKé BALLS") { it.id == BALLS_POCKET }
        }.andThen { state ->
            val bag = state.screen as? Screen.Bag ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the balls pocket", state.screen))
            val ball = bag.entries.firstOrNull { it.id == ballId }
                ?: return@andThen Step.Failed(ActionError.NotOnScreen("$ballId (${stack.item.name})", "the balls pocket", balls.map { "item:${it.item.id.value} (${it.item.name})" }))
            context.navigator.choose(Screen.Bag::class, stack.item.name) { it.id == ball.id }
        }.andThen {
            // USE is confirmed here, not by the navigator: its settling would let the whole throw play unseen.
            when (val use = context.navigator.select(Screen.ContextMenu::class, "USE") { it.id == "option:use" }) {
                is Step.Failed -> Step.Failed(use.error)
                is Step.Done -> Step.Done(context.state())
            }
        }
        if (reached is Step.Failed) return ActionOutcome.Failed(reached.error)
        context.scope.tap(Button.A)
        val foe = before.battle?.battlers?.firstOrNull { !it.ref.isPlayerSide }
        // The throw, the shakes, then the next turn (it broke free) or the capture's messages and questions.
        var shakes: Int? = null
        var outcome: BattleOutcome? = null
        var end: GameState? = null
        for (poll in 0 until THROW_FRAMES / 2) {
            val state = context.state()
            state.battle?.ballShakes?.let { shakes = it }
            state.battle?.outcome?.let { outcome = it }
            val screen = state.screen
            val settled = screen.awaiting == Awaiting.INPUT
            if (settled && (screen is Screen.Selectable || screen is Screen.Overworld || screen is Screen.Keyboard)) {
                end = state
                break
            }
            // The Pokédex entry of a new species waits for A; battle messages go on by themselves.
            if (settled && (screen is Screen.PressToContinue || screen is Screen.Dialogue && screen.source != TextSource.BATTLE)) {
                context.scope.tap(Button.A)
                context.navigator.awaitChange(screen, maxFrames = 60)
            } else context.scope.step(2)
        }
        val last = end ?: return ActionOutcome.Failed(ActionError.Timeout("the throw didn't end"))
        val foeName = foe?.let { it.nickname ?: it.species.name } ?: "the Pokémon"
        val result = when {
            shakes == BattleState.CAUGHT_SHAKES || outcome == BattleOutcome.CAUGHT -> ThrowResult.Caught(foe?.species?.name ?: "the Pokémon", foe?.level)
            shakes != null && outcome == BattleOutcome.FOE_FLED -> ThrowResult.BrokeFreeThenFled(shakes!!, foeName)
            shakes != null -> ThrowResult.BrokeFree(shakes!!)
            else -> ThrowResult.Missed
        }
        if (result !is ThrowResult.Caught) return ActionOutcome.Done("$ballId: ${result.describe()}")
        // Caught: the nickname question (in battle), then the transfer to the PC when the party is full.
        val named = answerNickname(context, last, action.nickname)
        if (named is Step.Failed) return ActionOutcome.Failed(named.error)
        val after = context.navigator.advanceUntil(AFTER_CATCH_WAITS) { s ->
            s.battle == null && s.screen is Screen.Overworld || s.screen is Screen.Selectable || s.screen is Screen.Keyboard
        }
        val where = (after as? Step.Done)?.value?.let { state ->
            when {
                state.party.size > before.party.size -> "joined the party"
                (state.storage?.mons?.size ?: 0) > (before.storage?.mons?.size ?: 0) ->
                    state.storage?.mons?.firstOrNull { m -> before.storage?.mons?.none { it.id == m.id } == true }?.let { m ->
                        "sent to ${state.storage?.boxes?.firstOrNull { it.index == m.box }?.name ?: "BOX ${m.box + 1}"} (party full)"
                    }
                else -> null
            }
        }
        val nicknamed = action.nickname?.let { " as $it" } ?: ""
        return ActionOutcome.Done("$ballId: ${result.describe()}$nicknamed" + (where?.let { ", $it" } ?: ""))
    }

    /**
     * Answers "Give a nickname to the caught X?": YES and types [nickname] (this game's `enter_text`), or NO. Nothing
     * when no question shows.
     */
    private fun answerNickname(context: PlanContext, state: GameState, nickname: String?): Step<GameState> {
        val prompt = state.screen as? Screen.YesNo ?: return Step.Done(state)
        if (prompt.entries.none { it.id == "option:yes" } || prompt.entries.none { it.id == "option:no" }) return Step.Done(state)
        if (nickname == null) return context.navigator.choose(Screen.YesNo::class, "NO (no nickname)") { it.id == "option:no" }
        return context.navigator.choose(Screen.YesNo::class, "YES (nickname)") { it.id == "option:yes" }.andThen {
            context.navigator.advanceUntil(NICKNAME_WAITS) { it.screen is Screen.Keyboard }
        }.andThen {
            when (val typed = enterText(GameAction.EnterText(nickname), context)) {
                is ActionOutcome.Done -> Step.Done(context.state())
                is ActionOutcome.Failed -> Step.Failed(typed.error)
            }
        }
    }

    /**
     * After "X wants to learn Y" (in or after a battle, or when evolving): forgets [GameAction.LearnMove.forget], or
     * gives up learning the new move when it's null. The move to forget is checked before anything is pressed
     * ([forgetRefusal], like `teach`): an HM move or a move it doesn't know is refused on the question,
     * which stays on screen for the next call. Started from the list of moves to forget (left open by an earlier
     * call), it picks there, or cancels it to give up the new move.
     */
    override fun learnMove(action: GameAction.LearnMove, context: PlanContext): ActionOutcome {
        val outcome = learnMoveSteps(action, context)
        // Learning from the field (Rare Candy, TM): the bag / party menus the item was used from are still open.
        if (outcome is ActionOutcome.Done) closeFieldMenus(context)
        return outcome
    }

    /** Out of battle, closes the bag / party menus left open behind a finished prompt (never during an evolution). */
    private fun closeFieldMenus(context: PlanContext) {
        val state = context.navigator.settle()
        if (state.battle != null) return
        val menus = state.screen is Screen.Bag || state.screen is Screen.PartyGrid || state.screen is Screen.ContextMenu ||
            (state.screen as? Screen.Dialogue)?.source == TextSource.MENU
        if (menus) closeToOverworld(context)
    }

    /** [learnMove] up to the answer, the menus it was started from left as they are. */
    private fun learnMoveSteps(action: GameAction.LearnMove, context: PlanContext): ActionOutcome {
        // Messages may come first ("Which move should be forgotten?"): read them up to the question or the list.
        val reached = context.navigator.advanceUntil(LEARN_WAITS) { it.screen is Screen.Selectable }
        if (reached is Step.Failed) return ActionOutcome.Failed(reached.error)
        val state = (reached as Step.Done).value
        val forget = action.forget
        val prompt = state.screen as? Screen.YesNo
        // Who learns: the prompt says (its MoveOffer), or the list is about it.
        val learner = (prompt?.learning?.mon ?: (state.screen as? Screen.MoveSelect)?.mon)?.let { id -> state.party.firstOrNull { it.id == id } }
        if (forget != null && learner != null) forgetRefusal(context, learner, forget)?.let { return ActionOutcome.Failed(it) }
        val toList = when {
            // Giving up from the list: CANCEL (the cursor checked) leads to "give up on Y?".
            state.screen is Screen.MoveSelect && forget == null ->
                context.navigator.choose(Screen.MoveSelect::class, "CANCEL (don't learn it)") { it.id == "option:cancel" }
            state.screen is Screen.MoveSelect -> Step.Done(state)
            prompt != null && ActionConditions.forgetAnswer(prompt) != null ->
                if (forget == null) context.navigator.choose(Screen.YesNo::class, "KEEP OLD MOVES") { it.id == keepAnswer(prompt) }
                else context.navigator.choose(Screen.YesNo::class, "FORGET A MOVE") { it.id == ActionConditions.forgetAnswer(prompt) }
            else -> Step.Failed(ActionError.UnexpectedScreen("the question about the new move", state.screen))
        }
        if (forget == null) {
            // "Give up on learning Y?" → yes.
            return toList.andThen {
                context.navigator.advanceUntil(LEARN_WAITS) { s -> (s.screen as? Screen.YesNo)?.let(::giveUpAnswer) != null }
            }.andThen { s ->
                val answer = (s.screen as Screen.YesNo).let(::giveUpAnswer)
                context.navigator.choose(Screen.YesNo::class, "GIVE UP") { it.id == answer }
            }.then { ActionOutcome.Done("kept the old moves") }
        }
        return toList.andThen {
            context.navigator.advanceUntil(LEARN_WAITS) { it.screen is Screen.MoveSelect }
        }.andThen { listState ->
            val list = listState.screen as Screen.MoveSelect
            val entry = list.entries.firstOrNull { e ->
                val id = e.id.removePrefix("move:").toIntOrNull() ?: return@firstOrNull false
                e.id != "move:${list.newMove?.id?.value}" && matchesRef(forget.raw, "move", id, e.label.substringBefore(" ("))
            } ?: return@andThen Step.Failed(ActionError.InvalidParameter("forget", forget.raw, list.entries.filter { it.id.startsWith("move:") }.map { it.label }))
            // The only moves these lists refuse are HMs ("HM moves can't be forgotten now").
            if (!entry.selectable) return@andThen Step.Failed(ActionError.HmCannotForget(entry.label.substringBefore(" (")))
            context.navigator.choose(Screen.MoveSelect::class, entry.label) { it.id == entry.id }
        }.andThen {
            // In battle, "FORGET <move>" / CANCEL confirms the choice (it appears a few frames after the list).
            var confirm: GameState? = null
            for (frame in 0 until CONFIRM_FRAMES step 2) {
                val now = context.state()
                if (now.screen is Screen.ContextMenu) {
                    confirm = now
                    break
                }
                if (now.screen !is Screen.MoveSelect && now.screen.awaiting == Awaiting.INPUT) break
                context.scope.step(2)
            }
            if (confirm != null) context.navigator.choose(Screen.ContextMenu::class, "FORGET") { it.id == "option:forget" } else Step.Done(context.state())
        }.then { after ->
            if (after.screen is Screen.MoveSelect) ActionOutcome.Failed(ActionError.Timeout("still on the move list"))
            else ActionOutcome.Done("forgot ${forget.raw}")
        }
    }

    /**
     * Why teaching [mon] with [forget] would fail on the "forget a move" question, before any menu: four moves and no
     * [forget] ([ActionError.ForgetNeeded], listing the moves it can forget), a [forget] it doesn't know
     * ([ActionError.InvalidParameter]) or an HM move ([ActionError.HmCannotForget]: the game never lets one go). Null
     * when the teaching can go on (fewer than four moves: [forget] isn't needed and is ignored). A step of
     * `learn_move` and `teach`.
     */
    internal open fun forgetRefusal(context: PlanContext, mon: PartyMon, forget: MoveRef?): ActionError? {
        if (mon.moves.size < MAX_MOVES) return null
        // HM moves, by id from the game's machine table (never by name): the moves of HM01..HM08.
        val hms = context.game.data?.let { data -> MachineId.all.filter { it.isHm }.mapNotNull(data::machineMove).toSet() }.orEmpty()
        val forgettable = mon.moves.filter { it.move.id !in hms }
        if (forget == null) return ActionError.ForgetNeeded(mon.displayName, forgettable.map { "move:${it.move.id.value} ${it.move.name}" })
        val known = mon.moves.firstOrNull { matchesRef(forget.raw, "move", it.move.id.value, it.move.name) }
            ?: return ActionError.InvalidParameter("forget", forget.raw, forgettable.map { "move:${it.move.id.value} ${it.move.name}" })
        return if (known.move.id in hms) ActionError.HmCannotForget(known.move.name) else null
    }

    private fun keepAnswer(prompt: Screen.YesNo): String = if (prompt.entries.any { it.id == "option:keep" }) "option:keep" else "option:no"

    /** The entry giving up the new move: `option:give_up` in battle, YES on the field's "stop trying to teach?". */
    private fun giveUpAnswer(prompt: Screen.YesNo): String? = when {
        prompt.entries.any { it.id == "option:give_up" } -> "option:give_up"
        prompt.learning?.question == LearnQuestion.GIVE_UP && prompt.entries.any { it.id == "option:yes" } -> "option:yes"
        else -> null
    }

    /**
     * `use_item` in battle, in one action: BAG → the battle pocket holding the item (the battle bag sorts items its own
     * way: a Revive is in STATUS HEALERS, an Ether in HP/PP RESTORE) → the item → USE → the Pokémon → the move for a PP
     * restoring item. Every step goes through the navigator.
     *
     * The bag in RAM doesn't change until the battle ends, so the outcome is told by the screens: the bag closes and the
     * turn goes on (used), or the bag / party screen shows its own message and stays open ("It won't have any effect":
     * the recipe closes it back to the command menu and reports [UnavailableReason.NO_EFFECT]).
     *
     * The battle half of `use_item` ([useItem] plays it when a battle is on), not an action of its own: a step a game
     * may override when its battle bag differs, without rewriting the field half.
     */
    internal open fun useItemInBattle(action: GameAction.UseItem, context: PlanContext): ActionOutcome {
        if (action.batch.isNotEmpty()) {
            return ActionOutcome.Failed(ActionError.InvalidParameter("items", "${action.uses.size} items", listOf("one item per turn in battle")))
        }
        val start = context.navigator.settle()
        val command = start.screen as? Screen.BattleCommand
            ?: return ActionOutcome.Failed(ActionError.UnexpectedScreen("the battle command menu", start.screen))
        if (command.entries.none { it.id == ActionConditions.BATTLE_BAG }) {
            return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "This battle has no bag"))
        }
        val itemId = resolveItem(start, action.item)
            ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "There's no ${action.item.raw} in the bag"))
        var target = action.target
        val reached = context.navigator.choose(Screen.BattleCommand::class, "BAG") { it.id == ActionConditions.BATTLE_BAG }.andThen {
            context.navigator.settle().screen as? Screen.Bag ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the battle bag", context.state().screen))
            val bag = context.state().screen as Screen.Bag
            val pocket = bag.pocketContents.entries.firstOrNull { (_, items) -> itemId in items }?.key
                ?: return@andThen Step.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "${action.item.raw} can't be used in battle (it's in no battle pocket)"))
            if (pocket == BALLS_POCKET) return@andThen Step.Failed(ActionError.InvalidParameter("item", action.item.raw, listOf("use throw_ball for a Poké Ball")))
            context.navigator.choose(Screen.Bag::class, pocket) { it.id == pocket }
        }.andThen {
            context.navigator.choose(Screen.Bag::class, action.item.raw) { it.id == "item:${itemId.value}" }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen { after ->
            val grid = after.screen as? Screen.PartyGrid ?: return@andThen Step.Done(after)
            val candidates = grid.entries.filter { it.id.startsWith("mon:") && it.selectable }
            val mon = target?.toString() ?: candidates.singleOrNull()?.id
                ?: return@andThen Step.Failed(ActionError.InvalidParameter("target", "none", candidates.map { "${it.id} = ${it.label}" }))
            target = target ?: MonId.parse(mon)
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == mon }
        }.andThen { after ->
            if (!isMoveList(after.screen)) return@andThen Step.Done(after)
            chooseMove(context, after.screen as Screen.Selectable, action.move)
        }
        if (reached is Step.Failed) {
            backToCommand(context)
            return ActionOutcome.Failed(reached.error)
        }
        // The bag / party screen prints its message ("PP was restored.", "It won't have any effect.") and waits for A.
        // Then: used, the bag closes and the turn plays; refused, the item screens are back.
        var said: String? = null
        val read = context.navigator.advanceUntil(MAX_MESSAGES, onMessage = { state -> said = (state.screen as? Screen.Dialogue)?.text?.replace('\n', ' ') ?: said }) { state ->
            state.screen !is Screen.Dialogue
        }
        val end = (read as? Step.Done)?.value ?: return ActionOutcome.Failed(ActionError.Timeout("the bag didn't close after using ${action.item.raw}"))
        if (stillInBag(end.screen)) {
            backToCommand(context)
            return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${action.item.raw} had no effect" + (said?.let { ": $it" } ?: ""), "the turn wasn't used"))
        }
        return ActionOutcome.Done("used item:${itemId.value}" + (target?.let { " on $it" } ?: "") + (said?.let { ": $it" } ?: ""))
    }

    /**
     * "Restore which move?" lists: the field one (moves + QUIT) and the battle one (moves + CANCEL). A step of
     * `use_item` (both halves).
     */
    internal open fun isMoveList(screen: Screen): Boolean =
        screen is Screen.ListMenu && screen.entries.any { it.id.startsWith("move:") } && screen.entries.all { it.id.startsWith("move:") || it.id.startsWith("option:") || it.id.startsWith("slot:") }

    /**
     * Picks [move] on a "Restore which move?" list (typed error listing the moves when it's missing or unknown). A step
     * of `use_item` (both halves).
     */
    internal open fun chooseMove(context: PlanContext, list: Screen.Selectable, move: MoveRef?): Step<GameState> {
        val moves = list.entries.filter { it.id.startsWith("move:") }
        val entry = move?.let { ref ->
            moves.firstOrNull { e -> matchesRef(ref.raw, "move", e.id.removePrefix("move:").toIntOrNull() ?: -1, e.label.substringBefore(" (")) }
        } ?: return Step.Failed(ActionError.InvalidParameter("move", move?.raw ?: "none", moves.map { "${it.id} = ${it.label}" }))
        return context.navigator.choose(list::class, entry.label) { it.id == entry.id }
    }

    /** The item screens still open: the game refused the item (back to the bag, the item's USE menu or the party). */
    private fun stillInBag(screen: Screen): Boolean = when (screen) {
        is Screen.Bag -> true
        is Screen.PartyGrid -> screen.purpose == PartyPurpose.BATTLE_USE_ITEM
        is Screen.ContextMenu -> screen.entries.any { it.id == "option:use" }
        is Screen.ListMenu -> isMoveList(screen)
        else -> false
    }

    /** Leaves the bag / party screens (A on their messages, B elsewhere) until the command menu is back. */
    private fun backToCommand(context: PlanContext) {
        repeat(MAX_BACK_PRESSES) {
            val state = context.navigator.settle()
            when (state.screen) {
                is Screen.BattleCommand -> return
                is Screen.Dialogue -> if (state.screen.awaiting == Awaiting.INPUT) context.scope.tap(Button.A) else return
                is Screen.Bag, is Screen.PartyGrid, is Screen.ContextMenu, is Screen.ListMenu -> context.scope.tap(Button.B)
                else -> return
            }
            context.navigator.awaitChange(state.screen, maxFrames = 90)
        }
    }

    /** The item's id, from the bag (by id or name). */
    private fun resolveItem(state: GameState, item: ItemRef): ItemId? =
        state.bag.orEmpty().flatMap { it.items }.firstOrNull { matchesRef(item.raw, "item", it.item.id.value, it.item.name) }?.item?.id
            ?: item.raw.removePrefix("item:").toIntOrNull()?.let(::ItemId)

    private companion object {
        const val CANCEL = "option:cancel"
        const val ALL_TARGETS = "target:all"

        /** The escape plays out in a few seconds; a failed one adds the foe's turn (~20 s at most). */
        const val RUN_FRAMES = 1800

        const val BALLS_POCKET = "pocket:poke_balls"

        /** The throw, the shakes, "Gotcha!" and the Pokédex entry up to the nickname question (~1600 frames seen). */
        const val THROW_FRAMES = 2400
        const val AFTER_CATCH_WAITS = 40
        const val NICKNAME_WAITS = 20
        const val LEARN_WAITS = 40
        const val CONFIRM_FRAMES = 120

        const val MAX_BACK_PRESSES = 8

        /** Moves a Pokémon knows at most. */
        const val MAX_MOVES = 4
        const val MAX_MESSAGES = 6
    }
}

/** How a thrown Poké Ball ended. */
sealed interface ThrowResult {
    /** Caught: [species] at [level] (of the wild Pokémon). */
    data class Caught(val species: String, val level: Int?) : ThrowResult

    /** It broke free after [shakes] shakes (0..3). */
    data class BrokeFree(val shakes: Int) : ThrowResult

    /**
     * It broke free after [shakes] shakes, then [name] fled on its turn (a roaming Pokémon: Raikou, Entei, Latias...):
     * the battle is over, nothing was caught.
     */
    data class BrokeFreeThenFled(val shakes: Int, val name: String) : ThrowResult

    /** The ball didn't reach a catch roll (blocked, or the Pokémon can't be caught). */
    data object Missed : ThrowResult

    /** For the action's answer: "caught SPINARAK Lv12", "broke free after 2 shakes", "missed". */
    fun describe(): String = when (this) {
        is Caught -> "caught $species" + (level?.let { " Lv$it" } ?: "")
        is BrokeFree -> "broke free after $shakes shake" + if (shakes == 1) "" else "s"
        is BrokeFreeThenFled -> "broke free after $shakes shake" + (if (shakes == 1) "" else "s") + ", then the wild $name fled: the battle is over, nothing caught"
        Missed -> "missed"
    }
}
