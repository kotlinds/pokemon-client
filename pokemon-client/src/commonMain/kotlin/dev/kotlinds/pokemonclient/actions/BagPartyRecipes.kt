package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.data.MachineCompatibility
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.kind

/**
 * The recipes of the party and bag actions out of battle (start menu → POKéMON / BAG → ...): `reorder_party`,
 * `take_item`, `give_item`, `use_item` (its field half; the battle half is [BattleRecipes.useItemInBattle]), `teach`,
 * `use_key_item` and `register_item`. A family of the chain of [RecipeBase], above [BattleRecipes]. Every step goes
 * through the navigator (cursor read, one tap at a time, confirm only on the target), and entries are found by their
 * ids; the start menu, the party and the bag are reached through the shared steps of [RecipeBase]
 * ([openStartMenuEntry], [openParty], [bagItem], [closeToOverworld]), which a game may override.
 */
abstract class BagPartyRecipes internal constructor() : BattleRecipes() {

    // region Availability: when each action of this family can run (read by the listing and the execution alike)

    /** `reorder_party`: in the field, with two Pokémon or more. */
    protected open fun reorderPartyAvailability(state: GameState): Availability =
        if (ActionConditions.inField(state) && state.party.size > 1) Availability.Available(mapOf("pokemon" to ActionConditions.monChoices(state))) else Availability.Hidden

    /** `take_item`: in the field, when a Pokémon holds an item. */
    protected open fun takeItemAvailability(state: GameState): Availability {
        val holders = state.party.filter { it.heldItem != null }
        return when {
            !ActionConditions.inField(state) -> Availability.Hidden
            holders.isEmpty() -> Availability.Unavailable(UnavailableReason.NO_STOCK, "No Pokémon holds an item")
            else -> Availability.Available(mapOf("pokemon" to holders.map { Choice(it.id.toString(), "${it.displayName} (${it.heldItem?.name})") }))
        }
    }

    /** `give_item`: in the field. */
    protected open fun giveItemAvailability(state: GameState): Availability =
        if (ActionConditions.inField(state)) Availability.Available(mapOf("pokemon" to ActionConditions.monChoices(state))) else Availability.Hidden

    /** `use_item`: in the field, or from a battle's command menu that has a BAG. */
    protected open fun useItemAvailability(state: GameState): Availability = when {
        state.battle != null -> if (ActionConditions.canUseItemInBattle(state)) Availability.Available(ActionConditions.itemChoices(state, inBattle = true)) else Availability.Hidden
        ActionConditions.inField(state) -> Availability.Available(ActionConditions.itemChoices(state, inBattle = false))
        else -> Availability.Hidden
    }

    /** `teach`: in the field, with a machine in the bag. */
    protected open fun teachAvailability(state: GameState): Availability {
        val machines = state.bag.orEmpty().firstOrNull { it.name == "tms_hms" }?.items.orEmpty()
        return if (!ActionConditions.inField(state) || machines.isEmpty()) Availability.Hidden
        else Availability.Available(mapOf("item" to machines.map { Choice("item:${it.item.id.value}", it.item.name) }, "pokemon" to ActionConditions.monChoices(state)))
    }

    /** `use_key_item`: in the field, with a key item in the bag. */
    protected open fun useKeyItemAvailability(state: GameState): Availability {
        val keys = state.bag.orEmpty().firstOrNull { it.name == "key_items" }?.items.orEmpty()
        return if (!ActionConditions.inField(state) || keys.isEmpty()) Availability.Hidden
        else Availability.Available(mapOf("item" to keys.map { Choice("item:${it.item.id.value}", it.item.name) }))
    }

    /** `register_item`: in the field, with a key item in the bag. */
    protected open fun registerItemAvailability(state: GameState): Availability {
        val keys = state.bag.orEmpty().firstOrNull { it.name == "key_items" }?.items.orEmpty()
        return if (!ActionConditions.inField(state) || keys.isEmpty()) Availability.Hidden
        else Availability.Available(mapOf("item" to keys.map { Choice("item:${it.item.id.value}", it.item.name + if (state.registeredItems.firstOrNull() == it.item.id) " (on Y)" else "") }))
    }

    // endregion

    /**
     * Moves [GameAction.ReorderParty.mon] to a position: party → mon → SWITCH → the mon at that position. With an
     * [GameAction.ReorderParty.order], every position in turn in the same party session (see [reorderWhole]).
     */
    override fun reorderParty(action: GameAction.ReorderParty, context: PlanContext): ActionOutcome {
        if (action.order.isNotEmpty()) return reorderWhole(action.order, context)
        val state = context.state()
        val target = state.party.getOrNull(action.position - 1)
            ?: return ActionOutcome.Failed(ActionError.InvalidParameter("position", action.position.toString(), (1..state.party.size).map(Int::toString)))
        if (target.id == action.mon) return ActionOutcome.Done("already at position ${action.position}")
        return openParty(context).andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon to move") { it.id == action.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "SWITCH") { it.id == "option:switch" }
        }.andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the position") { it.id == target.id.toString() }
        }.then {
            closeToOverworld(context)
            val moved = context.state().party.getOrNull(action.position - 1)?.id
            if (moved == action.mon) ActionOutcome.Done() else ActionOutcome.Failed(ActionError.Timeout("the party order didn't change as expected"))
        }
    }

    /**
     * Puts the party in [order] (the Pokémon left out keep the remaining places): in one party session, for each position
     * whose Pokémon isn't the wanted one, mon → SWITCH → the Pokémon at that position. Refused before anything is pressed
     * when an id isn't in the party; checked on the final order.
     */
    private fun reorderWhole(order: List<MonId>, context: PlanContext): ActionOutcome {
        val party = context.state().party
        order.firstOrNull { id -> party.none { it.id == id } }?.let { missing ->
            return ActionOutcome.Failed(ActionError.InvalidParameter("order", missing.toString(), party.map { "${it.id} = ${it.displayName}" }))
        }
        if (party.take(order.size).map { it.id } == order) return ActionOutcome.Done("already in that order")
        var step: Step<GameState> = openParty(context)
        for ((position, wanted) in order.withIndex()) {
            step = step.andThen { now ->
                val current = now.party.getOrNull(position)
                if (current == null || current.id == wanted) return@andThen Step.Done(now)
                context.navigator.choose(Screen.PartyGrid::class, "the Pokémon to move") { it.id == wanted.toString() }.andThen {
                    context.navigator.choose(Screen.ContextMenu::class, "SWITCH") { it.id == "option:switch" }
                }.andThen {
                    context.navigator.choose(Screen.PartyGrid::class, "position ${position + 1}") { it.id == current.id.toString() }
                }.andThen {
                    // The two Pokémon slide to their new places: the party order changes at the end of the animation.
                    val swapped = context.scope.stepUntil(SWAP_FRAMES) { memory -> context.game.state(memory).party.getOrNull(position)?.id == wanted }
                    if (swapped) Step.Done(context.navigator.settle())
                    else Step.Failed(ActionError.Timeout("$wanted didn't come to position ${position + 1}"))
                }
            }
        }
        return step.then {
            closeToOverworld(context)
            val now = context.state().party.take(order.size).map { it.id }
            if (now == order) ActionOutcome.Done() else ActionOutcome.Failed(ActionError.Timeout("the party order is ${now.joinToString()} instead of ${order.joinToString()}"))
        }
    }

    /**
     * Takes the item held by a Pokémon: party → mon → ITEM → TAKE. For Mail (MAIL → TAKE), the game then asks "Send the
     * removed Mail to your PC?": YES keeps the written message in the PC's mailbox (NO would erase it).
     */
    override fun takeItem(action: GameAction.TakeItem, context: PlanContext): ActionOutcome {
        return openParty(context).andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == action.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "ITEM") { it.id == "option:item" || it.id == "option:mail" }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "TAKE") { it.id == "option:take" }
        }.andThen { after ->
            if (after.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (send the Mail to the PC)") { it.id == "option:yes" } else Step.Done(after)
        }.then {
            closeToOverworld(context)
            val held = context.state().party.firstOrNull { it.id == action.mon }?.heldItem
            if (held == null) ActionOutcome.Done() else ActionOutcome.Failed(ActionError.Timeout("${held.name} is still held"))
        }
    }

    /**
     * Gives an item from the bag: bag → item → GIVE → the Pokémon (answers yes to swapping a held item). Mail is refused
     * before anything is pressed: giving it opens the mail editor, where the game wants a written message (an empty one
     * is refused) and that editor isn't decoded.
     */
    override fun giveItem(action: GameAction.GiveItem, context: PlanContext): ActionOutcome {
        val mail = context.state().bag.orEmpty().firstOrNull { it.name == MAIL_POCKET }?.items.orEmpty()
            .firstOrNull { matchesRef(action.item.raw, "item", it.item.id.value, it.item.name) }
        if (mail != null) {
            return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.MAIL_NEEDS_WRITING,
                "Giving ${mail.item.name} opens the mail editor to write a message, which isn't supported", "give another item"))
        }
        return bagItem(context, action.item).andThen { itemEntry ->
            context.navigator.choose(Screen.Bag::class, itemEntry.label) { it.id == itemEntry.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "GIVE") { it.id == "option:give" }
        }.andThen {
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == action.mon.toString() }
        }.andThen { after ->
            if (after.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES") { it.id == "option:yes" } else Step.Done(after)
        }.then {
            closeToOverworld(context)
            val held = context.state().party.firstOrNull { it.id == action.mon }?.heldItem
            if (held != null && matchesRef(action.item.raw, "item", held.id.value, held.name)) ActionOutcome.Done()
            else ActionOutcome.Failed(ActionError.Timeout("the Pokémon holds ${held?.name ?: "nothing"} instead of ${action.item.raw}"))
        }
    }

    /**
     * Uses one or several items ([GameAction.UseItem.uses]) in one bag session: bag → item → USE → the Pokémon when it
     * asks for one → the move for a PP restoring item ("Restore which move?"), then A through the effect's message (it
     * would otherwise close on its own after a few seconds) and on to the next item, the bag still open. Each use is
     * checked on the item's quantity (consumed = it worked; "It won't have any effect" keeps it). In battle, the
     * battle half ([BattleRecipes.useItemInBattle]) is played instead.
     */
    override fun useItem(action: GameAction.UseItem, context: PlanContext): ActionOutcome {
        // The battle half of this same recipe, not another action: the game's own step (a game may override it alone);
        // a game overriding `use_item` replaces both halves.
        if (context.state().battle != null) return useItemInBattle(action, context)
        val done = mutableListOf<String>()
        for ((index, use) in action.uses.withIndex()) {
            when (val outcome = useOneItem(use, context)) {
                is ActionOutcome.Done -> done += use.key + (outcome.detail?.let { ": $it" } ?: "")
                is ActionOutcome.Failed -> {
                    closeToOverworld(context)
                    val error = if (action.uses.size == 1) outcome.error else ActionError.BatchStepFailed(index, use.key, done, outcome.error)
                    return ActionOutcome.Failed(error)
                }
            }
        }
        closeToOverworld(context)
        return ActionOutcome.Done(done.joinToString("; "))
    }

    /** One field item use, from the overworld or from the bag left open by the previous use. */
    private fun useOneItem(use: ItemUse, context: PlanContext): ActionOutcome {
        val countBefore = quantity(context.state(), use.item)
        val reached = bagItem(context, use.item).andThen { itemEntry ->
            context.navigator.choose(Screen.Bag::class, itemEntry.label) { it.id == itemEntry.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen { after ->
            val grid = after.screen as? Screen.PartyGrid ?: return@andThen Step.Done(after)
            val mon = use.target
                ?: return@andThen Step.Failed(ActionError.InvalidParameter("target", "none", grid.entries.filter { it.id.startsWith("mon:") && it.selectable }.map { it.id }))
            context.navigator.choose(Screen.PartyGrid::class, "the Pokémon") { it.id == mon.toString() }
        }.andThen { after ->
            // Ether, PP Up...: "Restore which move?".
            if (!isMoveList(after.screen)) return@andThen Step.Done(after)
            chooseMove(context, after.screen as Screen.Selectable, use.move)
        }
        if (reached is Step.Failed) return ActionOutcome.Failed(reached.error)
        // The effect: A through its messages, until the bag (or the field) is back, or the party grid when the game
        // refused the item. Never a burst of blind presses: only messages are answered. A whole-party item (Sacred
        // Ash: one HP bar and one message per fainted Pokémon) takes longer, but never more than [EFFECT_MAX_FRAMES]:
        // the agent's call must answer in time.
        val effectStart = context.scope.frame
        context.navigator.advanceUntil(EFFECT_PRESSES, maxFrames = EFFECT_MAX_FRAMES, settleFrames = EFFECT_SETTLE_FRAMES, waitOn = { it is Screen.Animation }) { state ->
            state.screen !is Screen.Dialogue && state.screen !is Screen.PressToContinue && state.screen !is Screen.Animation
        }
        if (context.scope.frame - effectStart > EFFECT_MAX_FRAMES) {
            return ActionOutcome.Failed(ActionError.Timeout("${use.item.raw}'s effect still runs after ${EFFECT_MAX_FRAMES / 60} s: call get_state, then go on"))
        }
        val countAfter = quantity(context.state(), use.item)
        return when {
            countAfter < countBefore -> ActionOutcome.Done("${countBefore - countAfter} used, $countAfter left")
            else -> ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${use.item.raw} had no effect", "the item stays in the bag"))
        }
    }

    /** How many of [item] the bag holds. */
    private fun quantity(state: GameState, item: ItemRef): Int = state.bag.orEmpty().flatMap { it.items }
        .filter { matchesRef(item.raw, "item", it.item.id.value, it.item.name) }.sumOf { it.quantity }

    /**
     * Teaches a TM / HM from the bag: bag → the machine → USE → "Teach X?" YES → the Pokémon; when it already knows
     * four moves, answers "forget a move?" and forgets [GameAction.Teach.forget] (refused when null). A Pokémon that
     * can't learn it (UNABLE on the game's party screen) or knows it already is refused before the bag opens, naming
     * who of the party can ([MachineCompatibility]). The result is checked on the Pokémon's moves.
     */
    override fun teach(action: GameAction.Teach, context: PlanContext): ActionOutcome {
        val start = context.state()
        val mon = start.party.firstOrNull { it.id == action.mon }
            ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "${action.mon} isn't in the party"))
        machineRefusal(context, start, mon, action.item)?.let { return ActionOutcome.Failed(it) }
        // Four moves: the move to forget is checked before any menu opens (the game would ask "Should a move be
        // forgotten?" and the walk through the menus can't answer it).
        forgetRefusal(context, mon, action.forget)?.let { return ActionOutcome.Failed(it) }
        val result = bagItem(context, action.item).andThen { machine ->
            context.navigator.choose(Screen.Bag::class, machine.label) { it.id == machine.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen {
            context.navigator.advanceUntil(TEACH_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.PartyGrid }
        }.andThen { state ->
            if (state.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (teach)") { it.id == "option:yes" } else Step.Done(state)
        }.andThen {
            context.navigator.advanceUntil(TEACH_WAITS) { it.screen is Screen.PartyGrid }
        }.andThen {
            context.navigator.choose(Screen.PartyGrid::class, mon.displayName) { it.id == action.mon.toString() }
        }.andThen {
            // Four moves already: "Should a move be forgotten?" then the summary list.
            context.navigator.advanceUntil(TEACH_WAITS) { s -> s.screen is Screen.YesNo || s.screen is Screen.MoveSelect || s.screen is Screen.Bag || s.screen is Screen.PartyGrid || s.screen is Screen.Overworld }
        }.andThen { state ->
            if (state.screen !is Screen.YesNo && state.screen !is Screen.MoveSelect) return@andThen Step.Done(state)
            // Checked before the menus ([forgetRefusal]); only a party read wrongly could get here without it.
            val forget = action.forget ?: return@andThen Step.Failed(ActionError.ForgetNeeded(mon.displayName, mon.moves.map { "move:${it.move.id.value} ${it.move.name}" }))
            if (state.screen is Screen.YesNo) {
                val yes = context.navigator.choose(Screen.YesNo::class, "YES (forget a move)") { it.id == "option:yes" }
                if (yes is Step.Failed) return@andThen yes
            }
            when (val forgot = learnMove(GameAction.LearnMove(forget), context)) {
                is ActionOutcome.Failed -> Step.Failed(forgot.error)
                is ActionOutcome.Done -> Step.Done(context.state())
            }
        }
        closeToOverworld(context, maxPresses = 12)
        return result.then {
            val machineMove = context.state().party.firstOrNull { it.id == action.mon }?.moves.orEmpty()
            if (machineMove.size != mon.moves.size || machineMove.map { it.move.id } != mon.moves.map { it.move.id }) ActionOutcome.Done("${mon.displayName} learned it")
            else ActionOutcome.Failed(ActionError.Timeout("${mon.displayName}'s moves didn't change"))
        }
    }

    /**
     * Why [mon] can't be taught the machine [item] names (from the bag), before any menu: UNABLE (its species can't
     * learn it, or an Egg) or LEARNED (it knows the move), as the game's party screen would show it. The error names
     * who of the party can learn it. Null when it can, or when the game's data or the item isn't known (the screens
     * decide then).
     */
    private fun machineRefusal(context: PlanContext, state: GameState, mon: PartyMon, item: ItemRef): ActionError? {
        val data = context.game.data ?: return null
        val itemId = state.bag.orEmpty().flatMap { it.items }.firstOrNull { matchesRef(item.raw, "item", it.item.id.value, it.item.name) }?.item?.id ?: return null
        val machine = data.machineOf(itemId) ?: return null
        val move = data.machineMove(machine)?.let { data.move(it)?.name } ?: machine.label
        val others = MachineCompatibility.partyCanLearn(data, state.party, machine).ifEmpty { listOf("nobody") }
        return when (MachineCompatibility.of(data, mon, machine)) {
            MachineCompatibility.Fit.ABLE -> null
            MachineCompatibility.Fit.LEARNED -> ActionError.Unavailable(UnavailableReason.ALREADY_KNOWN, "${mon.displayName} already knows $move (${machine.label})", "party can learn it: ${others.joinToString()}")
            MachineCompatibility.Fit.UNABLE -> ActionError.Unavailable(UnavailableReason.CANNOT_LEARN, "${mon.displayName} can't learn $move (${machine.label})", "party can learn it: ${others.joinToString()}")
        }
    }

    /**
     * Uses a key item (Bicycle, Itemfinder, a rod...): see [activateKeyItem]. The Bicycle is checked on the player's
     * movement: where the map forbids cycling (indoors...) or while surfing it's refused without pressing anything,
     * and when the game still says no ("There's a time and place for everything!", mud, tall grass...) the movement
     * hasn't changed: the message is closed and the refusal is a typed error, never Done.
     */
    override fun useKeyItem(action: GameAction.UseKeyItem, context: PlanContext): ActionOutcome {
        val before = context.navigator.settle()
        val bicycle = isBicycle(context, before, action.item)
        val field = before.field
        if (bicycle && field != null && field.movement != MovementMode.BIKE) {
            val why = when {
                field.bikeAllowed == false -> "cycling isn't allowed on ${field.mapName}"
                field.movement == MovementMode.SURF -> "the player is surfing"
                else -> null
            }
            if (why != null) return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.CANNOT_USE_HERE, "${action.item.raw} can't be used here: $why", "walk, or ride outdoors"))
        }
        return activateKeyItem(context, action.item).then { how ->
            var state = context.navigator.settle()
            if (bicycle && field != null) {
                // Mounting or dismounting changes the movement; the game's refusal is only a message.
                var polls = 0
                while (state.field?.movement == field.movement && state.screen !is Screen.Dialogue && polls++ < BIKE_WAITS) {
                    context.scope.step(BIKE_POLL_FRAMES)
                    state = context.navigator.settle()
                }
                if (state.field?.movement == field.movement) {
                    closeToOverworld(context)
                    return@then ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.CANNOT_USE_HERE, "the game refused ${action.item.raw} here (used $how)", "walk, or ride where cycling is allowed"))
                }
            }
            ActionOutcome.Done("used $how, now: ${state.screen.kind}" + (state.field?.let { ", ${it.movement.name.lowercase()}" } ?: ""))
        }
    }

    /** True when [item] is the game's bicycle ([dev.kotlinds.pokemonclient.PokemonGame.bicycleItem]). */
    private fun isBicycle(context: PlanContext, state: GameState, item: ItemRef): Boolean {
        val id = context.game.bicycleItem ?: return false
        val name = state.bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == id }?.item?.name ?: ""
        return matchesRef(item.raw, "item", id, name)
    }

    /**
     * Starts using a key item and returns as soon as the game reacts (without waiting for what follows: a rod's
     * cast must be watched frame by frame). With Y when it's the item registered there, else bag → the item → USE.
     * The value says which way it went.
     */
    override fun activateKeyItem(context: PlanContext, item: ItemRef): Step<String> {
        // An item without USE (a key used by interacting) is refused before the bag opens: it would only show its
        // other entries (NOTES: "No USE on context_menu (entries: , , , MOVE, CANCEL)", the menu left open).
        noUseFromBag(context, item)?.let { return Step.Failed(it) }
        quickUse(context, item)?.let { return Step.Done(it) }
        val used = bagItem(context, item).andThen { entry ->
            context.navigator.choose(Screen.Bag::class, entry.label) { it.id == entry.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "USE") { it.id == "option:use" }
        }.andThen { Step.Done("from the bag") }
        // Whatever was opened on the way (the bag, the item's menu) is closed when the use didn't start.
        if (used is Step.Failed) closeToOverworld(context)
        return used
    }

    /**
     * The refusal of [item] when the game's item data says the bag offers no USE for it
     * ([dev.kotlinds.pokemonclient.data.ItemInfo.usableFromBag]), or null (usable, or not known: the game has no data).
     */
    private fun noUseFromBag(context: PlanContext, item: ItemRef): ActionError? {
        val stack = context.state().bag.orEmpty().flatMap { it.items }
            .firstOrNull { matchesRef(item.raw, "item", it.item.id.value, it.item.name) } ?: return null
        if (context.game.data?.item(stack.item.id)?.usableFromBag != false) return null
        return ActionError.Unavailable(UnavailableReason.NOT_USABLE_FROM_BAG, "${stack.item.name} has no USE in the bag",
            "it works by itself when you interact with what it is for (a locked door, a strange tree): go_to it and interact")
    }

    /** Bag → the key item → REGISTER. The first free slot gets it: the first one is Y. */
    override fun registerItem(action: GameAction.RegisterItem, context: PlanContext): ActionOutcome {
        val before = context.state().registeredItems
        return bagItem(context, action.item).andThen { item ->
            context.navigator.choose(Screen.Bag::class, item.label) { it.id == item.id }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "REGISTER") { it.id == "option:register" }
        }.then {
            closeToOverworld(context)
            val after = context.state().registeredItems
            val slot = after.indexOfFirst { id -> id != null && matchesRef(action.item.raw, "item", id.value, context.state().itemName(id.value)) }
            when {
                slot == 0 -> ActionOutcome.Done("registered on Y")
                slot == 1 -> ActionOutcome.Done("registered on the second touch button (Y keeps ${before.firstOrNull()?.let { context.state().itemName(it.value) }})")
                else -> ActionOutcome.Failed(ActionError.Timeout("the item isn't registered"))
            }
        }
    }

    /**
     * Uses [item] without the bag when it's registered and the player stands in the field: Y for the first registered
     * item, the game's touch button for the second one ([dev.kotlinds.pokemonclient.PokemonGame.registeredItemTouch]).
     * Returns how ("with Y"...), or null (nothing pressed) otherwise.
     */
    private fun quickUse(context: PlanContext, item: ItemRef): String? {
        val state = context.navigator.settle()
        if (state.screen !is Screen.Overworld || state.screen.awaiting != Awaiting.INPUT) return null
        val slot = state.registeredItems.indexOfFirst { id -> id != null && matchesRef(item.raw, "item", id.value, state.itemName(id.value)) }
        val how = when (slot) {
            0 -> "with Y".also { context.scope.tap(Button.Y) }
            1 -> "with its touch button".also { context.scope.touch(context.game.registeredItemTouch(1) ?: return null) }
            else -> return null
        }
        context.navigator.awaitChange(state.screen)
        return how
    }

    private fun GameState.itemName(id: Int) = bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == id }?.item?.name ?: ""

    private companion object {
        /** Frames the party screen's swap animation may take before the new order shows in the party. */
        const val SWAP_FRAMES = 300

        /** Enough for Sacred Ash on six fainted Pokémon (an HP bar and a message each). */
        const val EFFECT_PRESSES = 30

        /** About 20 s: with the session's settling, the agent's call stays well under its client's timeout. */
        const val EFFECT_MAX_FRAMES = 1200
        const val EFFECT_SETTLE_FRAMES = 240

        /** Polls of [BIKE_POLL_FRAMES] frames to wait for the bicycle's effect (the mount, or the refusal message). */
        const val BIKE_WAITS = 15
        const val BIKE_POLL_FRAMES = 4

        const val TEACH_WAITS = 40

        /** The bag pocket of the Mail items (pocket ids are the same in every language). */
        const val MAIL_POCKET = "mail"
    }
}
