package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.actions.ActionConditions.ShopStage
import dev.kotlinds.pokemonclient.actions.ActionConditions.ShopStock
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.data.ItemPocket
import dev.kotlinds.pokemonclient.data.MachineCompatibility
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ItemId
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.PcMode
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ShopCurrency
import dev.kotlinds.pokemonclient.state.ShopGoods
import dev.kotlinds.pokemonclient.state.ShopItem

/**
 * The recipes of the services of a town: the Pokémon Center's nurse (`heal`), the PC (`pc`, `deposit`, `withdraw`,
 * `release`) and the Poké Mart (`buy`, `sell`, `set_quantity`). A family of the chain of [RecipeBase], above
 * [MoveRecipes]. Whoever serves is talked to with the game's own `interact` (a virtual call: a game's override is
 * played there too); the counters are reached through steps a game may override ([openStorage], [openShop],
 * [openSellBag]). Where each action can start from is the availability methods below (built on [ActionConditions]).
 */
abstract class ServiceRecipes internal constructor() : MoveRecipes() {

    // region Availability: when each action of this family can run (read by the listing and the execution alike)

    /** `heal`: walking freely in a Pokémon Center (a nurse on the map). */
    protected open fun healAvailability(state: GameState): Availability = when {
        !ActionConditions.canWalk(state, hasWorld = true) -> Availability.Hidden
        state.field?.objects?.any { it.role == PersonRole.NURSE } != true -> Availability.Hidden
        else -> Availability.Available()
    }

    /** `deposit`: walking freely where a PC may be, with two Pokémon or more. */
    protected open fun depositAvailability(state: GameState): Availability =
        if (ActionConditions.canWalk(state, hasWorld = true) && state.field?.hasPc != false && state.party.size > 1) Availability.Available(mapOf("pokemon" to ActionConditions.monChoices(state)))
        else Availability.Hidden

    /** `withdraw`: walking freely where a PC may be, with room in the party. */
    protected open fun withdrawAvailability(state: GameState): Availability = when {
        !ActionConditions.canWalk(state, hasWorld = true) || state.field?.hasPc == false -> Availability.Hidden
        state.party.size >= 6 -> Availability.Unavailable(UnavailableReason.PARTY_FULL, "The party is full", "deposit one first, or swap them in one `pc` session")
        else -> Availability.Available(state.storage?.let { mapOf("pokemon" to ActionConditions.storedChoices(it)) } ?: emptyMap())
    }

    /** `pc`: walking freely where a PC may be. */
    protected open fun pcAvailability(state: GameState): Availability {
        if (!ActionConditions.canWalk(state, hasWorld = true) || state.field?.hasPc == false) return Availability.Hidden
        return Availability.Available(buildMap {
            put("party", ActionConditions.monChoices(state))
            state.storage?.let { put("stored", ActionConditions.storedChoices(it)) }
        })
    }

    /** `release`: walking freely where a PC may be; accepted, never offered (dangerous). */
    protected open fun releaseAvailability(state: GameState): Availability {
        if (!ActionConditions.canWalk(state, hasWorld = true) || state.field?.hasPc == false) return Availability.Hidden
        return Availability.Available(mapOf("pokemon" to (state.party.map { Choice(it.id.toString(), "${it.displayName} Lv${it.level} (party)") } +
            state.storage?.boxes.orEmpty().flatMap { box -> box.mons.map { Choice(it.id.toString(), "${it.displayName} (${box.name})") } })), listed = false)
    }

    /** `buy`: at a Poké Mart ([ActionConditions.shopStage]), what each clerk sells. */
    protected open fun buyAvailability(state: GameState): Availability {
        if (ActionConditions.shopStage(state) == null) return Availability.Hidden
        // Clerk by clerk (never one list mixing two counters): an item sold by several says by whom.
        val stock = ActionConditions.shopStock(state)
        // A line sold out is shown by the list but can't be bought: not offered.
        val sellers = stock.flatMap { s -> s.items.filter { !it.soldOut }.map { Triple(it, s.clerk, s.currency) } }.groupBy({ it.first.item.id }, { it })
        return Availability.Available(if (sellers.isEmpty()) emptyMap() else mapOf("item" to sellers.values.map { lines ->
            val (item, _, currency) = lines.first()
            val by = lines.mapNotNull { it.second?.id }.takeIf { stock.size > 1 && it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")") ?: ""
            Choice("item:${item.item.id.value}", item.item.name + (item.price?.let { p -> " " + currency.format(p.toLong()) } ?: "") + by)
        }))
    }

    /** `sell`: at a Poké Mart (walking there, the clerk's menu or the selling bag), the bag's items but the key items. */
    protected open fun sellAvailability(state: GameState): Availability {
        val atShop = ActionConditions.shopStage(state).let { it == ShopStage.OVERWORLD || it == ShopStage.CLERK_MENU } ||
            (state.screen is Screen.Bag && state.field != null && ActionConditions.shopStage(state.copy(screen = Screen.Overworld(awaiting = Awaiting.INPUT))) != null)
        if (!atShop) return Availability.Hidden
        return Availability.Available(mapOf("item" to state.bag.orEmpty().filter { it.name != "key_items" }.flatMap { it.items }
            .map { Choice("item:${it.item.id.value}", "${it.item.name} x${it.quantity}") }))
    }

    /** `set_quantity`: on a quantity screen. */
    protected open fun setQuantityAvailability(state: GameState): Availability {
        val screen = state.screen as? Screen.Quantity ?: return Availability.Hidden
        return Availability.Available(mapOf("value" to listOf(Choice("${screen.min}..${screen.max}", "now ${screen.value}"))))
    }

    // endregion

    // region Pokémon Center

    /**
     * Heals the party at a Pokémon Center: talk to the nurse, answer YES to "Would you like to rest your Pokémon?",
     * then read the messages until the player can walk again.
     */
    override fun heal(action: GameAction.Heal, context: PlanContext): ActionOutcome {
        val nurse = context.state().field?.objects?.firstOrNull { it.role == PersonRole.NURSE }
            ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no nurse here", "go to a Pokémon Center"))
        when (val talk = interact(GameAction.Interact(nurse.id), context)) {
            is ActionOutcome.Failed -> return talk
            is ActionOutcome.Done -> Unit
        }
        return context.navigator.advanceUntil(HEAL_WAITS) { it.screen is Screen.YesNo || it.screen is Screen.Overworld }.andThen { state ->
            if (state.screen is Screen.YesNo) context.navigator.choose(Screen.YesNo::class, "YES (heal)") { it.id == "option:yes" } else Step.Done(state)
        }.andThen {
            context.navigator.advanceUntil(HEAL_WAITS) { it.screen is Screen.Overworld }
        }.then { state ->
            val hurt = state.party.filter { !it.isEgg && it.hp < it.maxHp }
            if (hurt.isEmpty()) ActionOutcome.Done("party healed")
            else ActionOutcome.Failed(ActionError.Timeout("still hurt after the nurse: ${hurt.joinToString { it.displayName }}"))
        }
    }

    // endregion

    // region PC: the Pokémon storage system
    //
    // Walk to the nearest PC, boot it, open the storage, run one or several operations (deposit, withdraw, move to
    // another box, swap a party Pokémon with a stored one) without switching the PC off in between, staying on the box
    // screen while the operations keep its mode (several deposits in a row), then switch it off. A chain's `deposit` /
    // `withdraw` / `pc` steps in a row are one session ([ActionChains.coalesce]). Pokémon are found by their ids,
    // never by name.
    //
    // The PC's two menus are multichoices whose entries are always in the same order, whatever the language: storage
    // first in the top menu (SOMEONE'S / BILL'S PC), then DEPOSIT, WITHDRAW, MOVE, MOVE ITEMS, SEE YA! in the storage
    // menu. Leaving needs only B: B picks the last entry (SEE YA!, SWITCH OFF) of these menus and confirms it.
    //
    // Every operation is checked on the party and the boxes read from the save data after it, and reported.

    override fun deposit(action: GameAction.Deposit, context: PlanContext): ActionOutcome = session(context, listOf(PcOperation.Deposit(action.mon)))

    override fun withdraw(action: GameAction.Withdraw, context: PlanContext): ActionOutcome = session(context, listOf(PcOperation.Withdraw(action.mon)))

    override fun pc(action: GameAction.Pc, context: PlanContext): ActionOutcome = session(context, action.operations)

    /**
     * Releases a Pokémon for good, from the PC of this building: a party Pokémon from DEPOSIT's party view, a stored one
     * from WITHDRAW's box view, then RELEASE and YES. Refused unless the action says `confirm` (the id is the
     * confirmation of which one), and for the last Pokémon able to battle or one holding Mail. Checked on the party
     * and the boxes after.
     */
    override fun release(action: GameAction.Release, context: PlanContext): ActionOutcome {
        val state = context.state()
        val mon = action.mon
        if (!action.confirm) {
            return ActionOutcome.Failed(ActionError.InvalidParameter("confirm", "false", listOf("true (releasing $mon is for good)")))
        }
        val partyMon = state.party.firstOrNull { it.id == mon }
        val stored = state.storage?.find(mon)
        when {
            partyMon == null && stored == null -> return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "$mon is neither in the party nor in a box"))
            partyMon != null && state.party.count { !it.isEgg && !it.fainted && it.id != mon } == 0 ->
                return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.LAST_POKEMON, "$mon is your last Pokémon able to battle"))
            partyMon?.heldItem?.let { isMail(context, it.id) } == true ->
                return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.MAIL_BLOCKS_DEPOSIT, "$mon holds ${partyMon.heldItem.name}: take the Mail first (take_item)"))
        }
        val name = partyMon?.displayName ?: stored?.displayName ?: mon.toString()
        val opened = openStorage(context)
        if (opened is Step.Failed) {
            closeToOverworld(context, maxPresses = PC_CLOSE_PRESSES)
            return ActionOutcome.Failed(opened.error)
        }
        val released = openMode(context, if (partyMon != null) PcMode.DEPOSIT else PcMode.WITHDRAW).andThen {
            if (partyMon != null) Step.Done(it) else showBoxWith(context, mon)
        }.andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to release") { it.id == mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "RELEASE") { it.id == "option:release" }
        }.andThen {
            context.navigator.advanceUntil(PC_WAITS) { it.screen is Screen.YesNo }
        }.andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (release $name)") { it.id == "option:yes" }
        }.andThen {
            // "X was released outside." / "Bye-bye, X!", back on the box.
            context.navigator.advanceUntil(PC_WAITS) { it.screen is Screen.PcBox }
        }.andThen { backToStorageMenu(context) }
        closeToOverworld(context, maxPresses = PC_CLOSE_PRESSES)
        if (released is Step.Failed) return ActionOutcome.Failed(released.error)
        val after = context.state()
        if (after.party.any { it.id == mon } || after.storage?.find(mon) != null) {
            return ActionOutcome.Failed(ActionError.Timeout("$name is still there after RELEASE"))
        }
        return ActionOutcome.Done("released $name ($mon)")
    }

    /** Boots the PC once, runs [operations] in order (stopping at the first failure), switches it off. */
    private fun session(context: PlanContext, operations: List<PcOperation>): ActionOutcome {
        if (operations.isEmpty()) return ActionOutcome.Failed(ActionError.InvalidParameter("operations", "empty", listOf("deposit", "withdraw", "move", "swap")))
        val plan = validate(context.state(), operations.map { normalize(context.state(), it) }) { item -> isMail(context, item) }
        if (plan is Step.Failed) return ActionOutcome.Failed(plan.error)
        val ops = (plan as Step.Done).value
        val opened = openStorage(context)
        if (opened is Step.Failed) {
            closeToOverworld(context, maxPresses = PC_CLOSE_PRESSES)
            return ActionOutcome.Failed(opened.error)
        }
        val report = mutableListOf<String>()
        var failure: ActionError? = null
        for (op in ops) {
            val before = context.state()
            // Each operation starts from where the previous one left the PC: the same box screen when it works in the
            // same mode (several deposits, withdrawals or moves in a row), else the storage menu.
            val step = when (op) {
                is PcOperation.Deposit -> runDeposit(context, op)
                is PcOperation.Withdraw -> runWithdraw(context, op)
                is PcOperation.Move -> runMove(context, op)
                is PcOperation.Swap -> runSwap(context, op)
            }.andThen { afterOperation(context) }
            if (step is Step.Failed) {
                failure = step.error
                break
            }
            when (val checked = check(before, context.state(), op)) {
                is Step.Done -> report += checked.value
                is Step.Failed -> {
                    failure = checked.error
                    break
                }
            }
        }
        // Out of the box screen the checked way (its two questions told apart), then the PC switched off. Its failure
        // (a question it doesn't know, a Pokémon still carried) is told: the PC may still be open.
        val left = backToStorageMenu(context) as? Step.Failed
        closeToOverworld(context, maxPresses = PC_CLOSE_PRESSES)
        val leaving = left?.let { "; then leaving the box failed: ${it.error.code} ${it.error.message}" } ?: ""
        return when {
            failure == null && left != null && report.isEmpty() -> ActionOutcome.Failed(left.error)
            failure == null -> ActionOutcome.Done(report.joinToString("; ") + leaving)
            report.isEmpty() -> ActionOutcome.Failed(failure)
            else -> ActionOutcome.Done(report.joinToString("; ") + "; stopped: ${failure.code} ${failure.message}" + leaving)
        }
    }

    // region Checks

    /** A move of a party Pokémon is a deposit into that box. */
    private fun normalize(state: GameState, op: PcOperation): PcOperation =
        if (op is PcOperation.Move && state.party.any { it.id == op.mon }) PcOperation.Deposit(op.mon, op.box) else op

    /** Whether [item] is a Mail (the game's MAIL pocket): a Pokémon holding one can't go into a box. */
    private fun isMail(context: PlanContext, item: ItemId): Boolean = context.game.data?.item(item)?.pocket == ItemPocket.MAIL

    /**
     * Refuses impossible sessions before touching the PC, following the party size and boxes through the operations.
     * A party Pokémon holding Mail can't be stored (the game asks to remove the Mail first): [MAIL_BLOCKS_DEPOSIT].
     */
    private fun validate(state: GameState, operations: List<PcOperation>, isMail: (ItemId) -> Boolean): Step<List<PcOperation>> {
        fun mailOf(mon: MonId) = state.party.firstOrNull { it.id == mon }?.heldItem?.takeIf { isMail(it.id) }
        fun mailBlocks(mon: MonId) = mailOf(mon)?.let {
            unavailable(UnavailableReason.MAIL_BLOCKS_DEPOSIT, "$mon holds ${it.name}: a Pokémon holding Mail can't be stored", "take the Mail first (take_item)")
        }
        val party = state.party.map { it.id }.toMutableList()
        val able = state.party.filter { !it.isEgg && !it.fainted }.map { it.id }.toMutableSet()
        val storage = state.storage
        val stored = storage?.mons?.associate { it.id to it.box }?.toMutableMap()
        val counts = storage?.boxes?.associate { it.index to it.mons.size }?.toMutableMap()
        fun boxFull(box: Int) = counts != null && (counts[box] ?: 0) >= BOX_SLOTS
        for (op in operations) {
            when (op) {
                is PcOperation.Deposit -> {
                    if (op.mon !in party) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.mon} isn't in the party")
                    mailBlocks(op.mon)?.let { return it }
                    if (party.size <= 1 || (op.mon in able && able.size <= 1)) return unavailable(UnavailableReason.LAST_POKEMON, "${op.mon} is your last Pokémon able to battle")
                    if (op.box != null && op.box !in 0 until BOXES) return Step.Failed(ActionError.InvalidParameter("box", op.box.toString(), listOf("0..${BOXES - 1}")))
                    if (op.box != null && boxFull(op.box)) return unavailable(UnavailableReason.PARTY_FULL, "Box ${op.box + 1} is full")
                    party -= op.mon
                    able -= op.mon
                    val box = op.box ?: counts?.entries?.sortedBy { it.key }?.firstOrNull { it.value < BOX_SLOTS }?.key
                    if (box != null) {
                        counts?.set(box, (counts[box] ?: 0) + 1)
                        stored?.set(op.mon, box)
                    }
                }
                is PcOperation.Withdraw -> {
                    if (stored != null && op.mon !in stored) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.mon} isn't in any box", "the boxes hold: ${storage.mons.joinToString { "${it.id} ${it.displayName}" }}")
                    if (party.size >= PARTY_SIZE) return unavailable(UnavailableReason.PARTY_FULL, "The party is full", "deposit one first (in the same pc session)")
                    stored?.remove(op.mon)?.let { counts?.set(it, (counts[it] ?: 1) - 1) }
                    party += op.mon
                    able += op.mon
                }
                is PcOperation.Move -> {
                    if (op.box !in 0 until BOXES) return Step.Failed(ActionError.InvalidParameter("box", op.box.toString(), listOf("0..${BOXES - 1}")))
                    if (stored != null && op.mon !in stored) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.mon} isn't in any box")
                    if (boxFull(op.box)) return unavailable(UnavailableReason.PARTY_FULL, "Box ${op.box + 1} is full")
                    stored?.put(op.mon, op.box)?.let { from -> counts?.set(from, (counts[from] ?: 1) - 1) }
                    counts?.set(op.box, (counts[op.box] ?: 0) + 1)
                }
                is PcOperation.Swap -> {
                    if (op.partyMon !in party) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.partyMon} isn't in the party")
                    mailBlocks(op.partyMon)?.let { return it }
                    if (stored != null && op.boxMon !in stored) return unavailable(UnavailableReason.UNKNOWN_POKEMON, "${op.boxMon} isn't in any box")
                    if (op.partyMon in able && able.size <= 1) return unavailable(UnavailableReason.LAST_POKEMON, "${op.partyMon} is your last Pokémon able to battle")
                    party[party.indexOf(op.partyMon)] = op.boxMon
                    able -= op.partyMon
                    able += op.boxMon
                    stored?.remove(op.boxMon)?.let { box -> stored[op.partyMon] = box }
                }
            }
        }
        return Step.Done(operations)
    }

    private fun unavailable(reason: UnavailableReason, detail: String, hint: String? = null) = Step.Failed(ActionError.Unavailable(reason, detail, hint))

    /** What the operation changed, read from the party and the boxes; a typed error when it didn't happen. */
    private fun check(before: GameState, after: GameState, op: PcOperation): Step<String> {
        fun name(id: MonId) = before.party.firstOrNull { it.id == id }?.displayName ?: before.storage?.find(id)?.displayName ?: id.toString()
        fun where(id: MonId) = after.storage?.find(id)?.let { mon -> after.storage.boxes.getOrNull(mon.box)?.let { "${it.name} (${it.mons.size}/${it.capacity})" } }
        return when (op) {
            is PcOperation.Deposit ->
                if (after.party.none { it.id == op.mon }) Step.Done("deposited ${name(op.mon)}" + (where(op.mon)?.let { " in $it" } ?: ""))
                else Step.Failed(ActionError.Timeout("${name(op.mon)} is still in the party"))
            is PcOperation.Withdraw ->
                if (after.party.any { it.id == op.mon }) Step.Done("withdrew ${name(op.mon)} (party: ${after.party.size}/$PARTY_SIZE)")
                else Step.Failed(ActionError.Timeout("${name(op.mon)} didn't join the party"))
            is PcOperation.Move -> {
                val box = after.storage?.find(op.mon)?.box
                if (box == op.box) Step.Done("moved ${name(op.mon)} to ${where(op.mon)}")
                else Step.Failed(ActionError.Timeout("${name(op.mon)} is in box ${box?.plus(1)} instead of ${op.box + 1}"))
            }
            is PcOperation.Swap ->
                if (after.party.any { it.id == op.boxMon } && after.party.none { it.id == op.partyMon }) {
                    Step.Done("swapped ${name(op.partyMon)} (now in ${where(op.partyMon) ?: "the PC"}) with ${name(op.boxMon)} (now in the party)")
                } else Step.Failed(ActionError.Timeout("the swap of ${name(op.partyMon)} and ${name(op.boxMon)} didn't happen"))
        }
    }

    // endregion

    // region Operations (each starts on the box screen of its mode, [enterMode], and ends on a box screen, [afterOperation])

    private fun runDeposit(context: PlanContext, op: PcOperation.Deposit): Step<GameState> =
        enterMode(context, PcMode.DEPOSIT).andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to deposit") { it.id == op.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "DEPOSIT") { it.id == "option:deposit" }
        }.andThen { picker ->
            // "Deposit in which box?": the box asked for, or the first box with room (full boxes aren't selectable).
            val boxes = picker.screen as? Screen.ListMenu ?: return@andThen Step.Failed(ActionError.UnexpectedScreen("the box picker", picker.screen))
            val box = boxes.entries.firstOrNull { if (op.box != null) it.id == "box:${op.box}" else it.id.startsWith("box:") && it.selectable }
                ?: return@andThen Step.Failed(ActionError.Unavailable(UnavailableReason.PARTY_FULL, "Every box is full"))
            context.navigator.select(Screen.ListMenu::class, box.label) { it.id == box.id }.andThen {
                context.navigator.choose(Screen.ListMenu::class, "DEPOSIT POKéMON") { it.id == "option:deposit" }
            }
        }

    private fun runWithdraw(context: PlanContext, op: PcOperation.Withdraw): Step<GameState> =
        enterMode(context, PcMode.WITHDRAW).andThen { showBoxWith(context, op.mon) }.andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to withdraw") { it.id == op.mon.toString() }
        }.andThen {
            context.navigator.choose(Screen.ContextMenu::class, "WITHDRAW") { it.id == "option:withdraw" }
        }

    /** MOVE POKéMON: pick the Pokémon up, bring the cursor to the box tabs, highlight the box, put it down there. */
    private fun runMove(context: PlanContext, op: PcOperation.Move): Step<GameState> =
        pickUp(context, op.mon).andThen {
            context.navigator.select(Screen.PcBox::class, "the box tabs") { it.id.startsWith("box:") || it.id.startsWith("tab:") }
        }.andThen { highlightBox(context, op.box) }.andThen {
            context.navigator.confirm("box ${op.box + 1}", target = { it.id == "box:${op.box}" })
        }

    /**
     * MOVE POKéMON: pick the stored Pokémon up and put it on the party Pokémon: the game swaps them at once (the party
     * Pokémon goes to the freed box slot, verified live). Should the cursor still carry the party Pokémon, it is put
     * down in the slot the stored one came from.
     */
    private fun runSwap(context: PlanContext, op: PcOperation.Swap): Step<GameState> {
        val slot = context.state().storage?.find(op.boxMon)?.slot
        return pickUp(context, op.boxMon).andThen {
            context.navigator.choose(Screen.PcBox::class, "the party Pokémon") { it.id == op.partyMon.toString() }
        }.andThen { swapped ->
            val box = swapped.screen as? Screen.PcBox ?: return@andThen Step.Done(swapped)
            if (box.holding == null) return@andThen Step.Done(swapped)
            if (box.holding != op.partyMon || slot == null) return@andThen Step.Failed(ActionError.UnexpectedScreen("the swap done", swapped.screen))
            context.navigator.choose(Screen.PcBox::class, "the box slot") { it.id == "slot:$slot" || it.id == op.boxMon.toString() }
        }
    }

    /** In MOVE POKéMON: shows the box holding [mon] and picks it up (through MOVE in the menu when one opens). */
    private fun pickUp(context: PlanContext, mon: MonId): Step<GameState> =
        enterMode(context, PcMode.MOVE).andThen { showBoxWith(context, mon) }.andThen {
            context.navigator.choose(Screen.PcBox::class, "the Pokémon to move") { it.id == mon.toString() }
        }.andThen { state ->
            if (state.screen is Screen.ContextMenu) context.navigator.choose(Screen.ContextMenu::class, "MOVE") { it.id == "option:move" } else Step.Done(state)
        }.andThen { state ->
            if ((state.screen as? Screen.PcBox)?.holding == mon) Step.Done(state)
            else Step.Failed(ActionError.UnexpectedScreen("the cursor carrying $mon", state.screen))
        }

    /**
     * With the cursor on the box tabs: LEFT / RIGHT (the shorter way round) until box [box] is highlighted, re-reading
     * after every press; an unexpected highlight counts as a correction (at most [RetryPolicy.maxCorrections]).
     */
    private fun highlightBox(context: PlanContext, box: Int): Step<GameState> {
        var corrections = 0
        var expected: Int? = null
        repeat(BOXES * 2) {
            val state = context.navigator.settle()
            val screen = state.screen as? Screen.PcBox ?: return Step.Failed(ActionError.UnexpectedScreen("the box tabs", state.screen))
            val current = (screen.cursor as? Cursor.At)?.let { screen.entries.getOrNull(it.index) }?.id?.takeIf { it.startsWith("box:") }?.removePrefix("box:")?.toIntOrNull()
                ?: return Step.Failed(ActionError.UnexpectedScreen("the cursor on a box tab", screen))
            if (current == box) return Step.Done(state)
            if (expected != null && current != expected) corrections++
            if (corrections > MAX_CORRECTIONS) return Step.Failed(ActionError.VerificationFailed("box ${box + 1}", "box ${box + 1}", "box ${current + 1}", corrections))
            val right = (box - current).mod(BOXES) <= BOXES / 2
            expected = (current + if (right) 1 else -1).mod(BOXES)
            context.scope.tap(if (right) Button.RIGHT else Button.LEFT)
            context.navigator.awaitChange(screen, maxFrames = TAB_FRAMES)
        }
        return Step.Failed(ActionError.Timeout("box ${box + 1} was never highlighted"))
    }

    // endregion

    // region Menus

    /** Walks to the PC, boots it and opens the storage menu. */
    protected open fun openStorage(context: PlanContext): Step<GameState> {
        when (val booted = interact(GameAction.Interact(MovePlans.PC), context)) {
            is ActionOutcome.Failed -> return Step.Failed(booted.error)
            is ActionOutcome.Done -> Unit
        }
        return pcMenu(context).andThen {
            context.navigator.choose(Screen.ListMenu::class, "the storage system") { it.id == "option:$TOP_STORAGE" }
        }.andThen { pcMenu(context) }
    }

    /**
     * The box screen in [mode] for the next operation: the one on screen when the previous operation left it there
     * (same mode, nothing carried: several deposits in a row stay on it, like a player would), else back to the
     * storage menu ([backToStorageMenu]) and that mode opened. Leaving and reopening the box costs its animations each
     * time (NOTES: a deposit then a withdrawal took 30 s).
     */
    private fun enterMode(context: PlanContext, mode: PcMode): Step<GameState> {
        val now = context.navigator.settle()
        val box = now.screen as? Screen.PcBox
        if (box != null && box.mode == mode && box.holding == null) return Step.Done(now)
        if ((now.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE) return openMode(context, mode)
        return backToStorageMenu(context).andThen { openMode(context, mode) }
    }

    /**
     * After an operation: its messages read ("X was deposited..."), until the box screen waits again (or the storage
     * menu, when the game left the box by itself). The next operation decides whether to stay there ([enterMode]).
     */
    private fun afterOperation(context: PlanContext): Step<GameState> =
        context.navigator.advanceUntil(PC_WAITS) { state ->
            val screen = state.screen
            (screen is Screen.PcBox && screen.awaiting == Awaiting.INPUT) ||
                (screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE
        }

    /** From the storage menu, opens the box in [mode]. */
    private fun openMode(context: PlanContext, mode: PcMode): Step<GameState> {
        val entry = when (mode) {
            PcMode.DEPOSIT -> STORAGE_DEPOSIT
            PcMode.WITHDRAW -> STORAGE_WITHDRAW
            PcMode.MOVE -> STORAGE_MOVE
            PcMode.MOVE_ITEMS -> STORAGE_MOVE_ITEMS
        }
        return context.navigator.choose(Screen.ListMenu::class, mode.name) { it.id == "option:$entry" }.andThen {
            context.navigator.advanceUntil(PC_WAITS) { (it.screen as? Screen.PcBox)?.mode == mode }
        }
    }

    /** Reads messages until the next PC multichoice. */
    private fun pcMenu(context: PlanContext) =
        context.navigator.advanceUntil(PC_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }

    /**
     * Leaves the box screen for the storage menu: B on the box (twice from the MOVE party panel) asks "Continue Box
     * operations?", where NO leaves; the RETURN button would ask "Exit the Box?", where YES leaves. Questions are told
     * apart by what the answer does, never by their text: answer NO first, and YES the next time if NO kept the box
     * open. Messages are read; never leaves while the cursor carries a Pokémon.
     */
    private fun backToStorageMenu(context: PlanContext): Step<GameState> {
        var answerYes = false
        var answeredNo = false
        repeat(EXIT_PRESSES) {
            val state = context.navigator.settle()
            when (val screen = state.screen) {
                is Screen.ListMenu -> if (screen.kind == MenuKind.MULTICHOICE) return Step.Done(state) else context.scope.tap(Button.B)
                is Screen.YesNo -> {
                    if (screen.entries.any { it.dangerous }) return Step.Failed(ActionError.UnexpectedScreen("the box", screen))
                    val answer = if (answerYes) "option:yes" else "option:no"
                    val answered = context.navigator.choose(Screen.YesNo::class, answer) { it.id == answer }
                    if (answered is Step.Failed) return answered
                    answeredNo = !answerYes
                    return@repeat
                }
                is Screen.PcBox -> {
                    if (screen.holding != null) return Step.Failed(ActionError.UnexpectedScreen("empty hands (the cursor still carries ${screen.holding})", screen))
                    // Back on the box right after a NO: that question was "Exit the Box?", answer YES next time.
                    if (answeredNo) answerYes = true
                    context.scope.tap(Button.B)
                }
                is Screen.Dialogue, is Screen.PressToContinue -> context.scope.tap(Button.A)
                is Screen.ContextMenu -> context.scope.tap(Button.B)
                else -> context.scope.step(10)
            }
            context.navigator.awaitChange(state.screen, maxFrames = 60)
        }
        return Step.Failed(ActionError.Timeout("couldn't get back to the storage menu"))
    }

    /** Shows each box in turn (touching the next-box arrow) until one holds [mon]. */
    private fun showBoxWith(context: PlanContext, mon: MonId): Step<GameState> {
        val monId = mon.toString()
        repeat(BOXES) {
            val state = context.navigator.settle()
            val box = state.screen as? Screen.PcBox ?: return Step.Failed(ActionError.UnexpectedScreen("a PC box", state.screen))
            if (box.entries.any { it.id == monId }) return Step.Done(state)
            // The stored box is known from the save data: go the shorter way round.
            val target = state.storage?.find(mon)?.box
            val backwards = target != null && (box.box - target).mod(BOXES) < (target - box.box).mod(BOXES)
            val arrow = box.entries.firstOrNull { it.id == if (backwards) "option:prev_box" else "option:next_box" }?.touch
                ?: return Step.Failed(ActionError.NotOnScreen("the box arrow", "the PC box", box.entries.map { it.id }))
            context.scope.touch(arrow)
            context.navigator.awaitChange(box)
        }
        return Step.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_POKEMON, "$monId isn't in any box"))
    }

    // endregion

    // endregion

    // region Poké Mart
    //
    // Buying: get to the shop list (talk to the clerk and pick BUY — the first entry of the clerk's menu whatever the
    // language — unless already there), then for each purchase pick the item by its id, set the quantity one press at
    // a time (read back after each press), confirm; finally leave with B (it picks SEE YA!). The result is checked on
    // the bag and the money, never on the clerk's words.

    /** True when [items] has the item [ref] names (`item:<id>` or its name). */
    private fun sells(items: List<ShopItem>, ref: ItemRef) = items.any { matchesRef(ref.raw, "item", it.item.id.value, it.item.name) }

    /**
     * The clerk to buy [purchases] from, walking in from the field: the nearest one whose catalog has them all; else
     * the nearest whose catalog isn't known (talking shows it). Null when the player is at a counter already (the
     * shop list or the clerk's menu: that clerk). A known set of clerks none of whom sells them all is refused
     * before moving, with what each one sells.
     */
    internal fun clerkFor(state: GameState, purchases: List<Purchase>): Step<FieldObject?> {
        if (ActionConditions.shopStage(state) != ShopStage.OVERWORLD) return Step.Done(null)
        val all = ActionConditions.shopClerks(state)
        all.firstOrNull { c -> c.catalog?.let { items -> purchases.all { sells(items, it.item) } } == true }?.let { return Step.Done(it) }
        all.firstOrNull { it.catalog == null }?.let { return Step.Done(it) }
        if (all.isEmpty()) return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
        val missing = purchases.firstOrNull { p -> all.none { c -> sells(c.catalog.orEmpty(), p.item) } } ?: purchases.first()
        val allowed = all.flatMap { c -> c.catalog.orEmpty().map { "item:${it.item.id.value} (${it.item.name}${it.price?.let { p -> ", ₽$p" } ?: ""}, ${c.id})" } }
        // Every line sold, but by different clerks: one buy per clerk.
        val split = purchases.all { p -> all.any { c -> sells(c.catalog.orEmpty(), p.item) } }
        return Step.Failed(
            if (split) ActionError.Unavailable(UnavailableReason.NO_STOCK, "No single clerk here sells all of ${purchases.joinToString { it.item.raw }}: " +
                describeStocks(all.mapNotNull { c -> c.catalog?.let { ShopStock(c, it) } }).removePrefix("nothing bought; "), "buy from each clerk in its own buy")
            else ActionError.InvalidParameter("item", missing.item.raw, allowed),
        )
    }

    override fun buy(action: GameAction.Buy, context: PlanContext): ActionOutcome {
        val start = context.state()
        if (action.purchases.isEmpty()) return listCatalog(context, start)
        val seller = clerkFor(start, action.purchases)
        if (seller is Step.Failed) return ActionOutcome.Failed(seller.error)
        val opened = openShop(context, (seller as Step.Done).value)
        if (opened is Step.Failed) {
            closeToOverworld(context, maxPresses = SHOP_CLOSE_PRESSES)
            return ActionOutcome.Failed(opened.error)
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
        closeToOverworld(context, maxPresses = SHOP_CLOSE_PRESSES)
        val remaining = if (currency == ShopCurrency.MONEY) context.state().player?.money else left
        val summary = "bought ${bought.joinToString()}" + (remaining?.let { ", ${currency.format(it)} left" } ?: "")
        return when {
            failure == null -> ActionOutcome.Done(summary)
            bought.isEmpty() -> ActionOutcome.Failed(failure)
            // Some lines were bought: say so, with why the rest wasn't.
            else -> ActionOutcome.Done("$summary; stopped: ${failure.code} ${failure.message}")
        }
    }

    /**
     * `buy` without an item: nothing is bought, the answer lists what is sold, clerk by clerk ([ActionConditions.shopStock]): the catalogs
     * the game data knows (nothing moves); a clerk whose catalog isn't known is talked to and its shop list read on
     * screen (BUY, read, leave). At a counter already: that clerk's list.
     */
    private fun listCatalog(context: PlanContext, start: GameState): ActionOutcome {
        val atCounter = ActionConditions.shopStage(start) != ShopStage.OVERWORLD
        (start.screen as? Screen.Shop)?.takeIf { it.goods != ShopGoods.ITEMS }?.let { return ActionOutcome.Failed(notItems(it)) }
        val known = ActionConditions.shopStock(start).filter { it.items.isNotEmpty() }
        val unknown = if (atCounter) emptyList() else ActionConditions.shopClerks(start).filter { it.catalog == null }
        val canLearn = partyCanLearn(context, start)
        if (known.isNotEmpty() && unknown.isEmpty()) return ActionOutcome.Done(describeStocks(known, canLearn))
        val read = mutableListOf<ShopStock>()
        for (clerk in if (atCounter) listOf(null) else unknown) {
            val opened = openShop(context, clerk)
            val shop = (opened as? Step.Done)?.value?.screen as? Screen.Shop
            closeToOverworld(context, maxPresses = SHOP_CLOSE_PRESSES)
            if (opened is Step.Failed) return ActionOutcome.Failed(opened.error)
            if (shop != null && shop.goods != ShopGoods.ITEMS) return ActionOutcome.Failed(notItems(shop))
            if (shop != null && shop.items.isNotEmpty()) read += ShopStock(clerk, shop.items, shop.currency)
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
    private fun partyCanLearn(context: PlanContext, state: GameState): (ItemId) -> List<String>? {
        val data = context.game.data?.takeIf { context.settings.pokedex } ?: return { null }
        return { item -> data.machineOf(item)?.let { MachineCompatibility.partyCanLearn(data, state.party, it).ifEmpty { listOf("nobody") } } }
    }

    /**
     * "nothing bought; sold here: item:4 (Poké Ball, ₽200), item:17 (Potion, ₽300)" for one clerk; with several,
     * each clerk's own list: "nothing bought; person:3 sells: item:17 (Potion, ₽300); person:5 sells: item:4 (...)".
     * A TM says who of the party can learn it when [canLearn] tells ("item:340 (TM13, ₽3000, party can learn: ...)").
     */
    private fun describeStocks(stocks: List<ShopStock>, canLearn: (ItemId) -> List<String>? = { null }): String {
        fun items(stock: ShopStock) = stock.items.joinToString {
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
    private fun bonus(before: GameState, after: GameState, bought: Int): List<String> {
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
    override fun sell(action: GameAction.Sell, context: PlanContext): ActionOutcome {
        val start = context.state()
        val stack = start.bag.orEmpty().flatMap { it.items }.firstOrNull { matchesRef(action.item.raw, "item", it.item.id.value, it.item.name) }
            ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.UNKNOWN_ITEM, "There's no ${action.item.raw} in the bag"))
        if (action.quantity > stack.quantity) {
            return ActionOutcome.Failed(ActionError.InvalidParameter("quantity", action.quantity.toString(), listOf("1..${stack.quantity}")))
        }
        val price = context.game.data?.item(stack.item.id)?.price
        if (price == 0) return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_EFFECT, "${stack.item.name} can't be sold (no price: key items, ...)"))
        val itemId = stack.item.id.value
        val sold = openSellBag(context).andThen {
            bagItem(context, action.item)
        }.andThen { entry ->
            context.navigator.choose(Screen.Bag::class, entry.label) { it.id == entry.id }
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Quantity || it.screen is Screen.YesNo }
        }.andThen { asked ->
            // A single item skips the number: the price question comes at once.
            if (asked.screen is Screen.YesNo) return@andThen Step.Done(asked)
            dialQuantity(context, action.quantity).andThen { quantity ->
                context.scope.tap(Button.A)
                context.navigator.awaitChange(quantity.screen)
                context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.YesNo }
            }
        }.andThen {
            context.navigator.choose(Screen.YesNo::class, "YES (sell)") { it.id == "option:yes" }
        }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Bag }
        }
        closeToOverworld(context, maxPresses = SHOP_CLOSE_PRESSES)
        if (sold is Step.Failed) return ActionOutcome.Failed(sold.error)
        val after = context.state()
        val gone = count(start, itemId) - count(after, itemId)
        val earned = (after.player?.money ?: 0) - (start.player?.money ?: 0)
        if (gone != action.quantity) return ActionOutcome.Failed(ActionError.Timeout("the bag lost $gone ${stack.item.name} instead of ${action.quantity}"))
        return ActionOutcome.Done("sold $gone ${stack.item.name} for ₽$earned" + (after.player?.money?.let { ", ₽$it now" } ?: ""))
    }

    /** Talks to the clerk (unless the clerk's menu is open) and picks SELL, up to the bag the game opens for selling. */
    protected open fun openSellBag(context: PlanContext): Step<GameState> {
        var state = context.navigator.settle()
        if (state.screen is Screen.Bag) return Step.Done(state)
        if (ActionConditions.shopStage(state) == ShopStage.OVERWORLD) {
            val clerk = ActionConditions.shopClerks(state).firstOrNull() ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
            when (val talk = interact(GameAction.Interact(clerk.id), context)) {
                is ActionOutcome.Failed -> return Step.Failed(talk.error)
                is ActionOutcome.Done -> Unit
            }
            val menu = context.navigator.advanceUntil(SHOP_WAITS) { (it.screen as? Screen.ListMenu)?.kind == MenuKind.MULTICHOICE }
            if (menu is Step.Failed) return menu
            state = (menu as Step.Done).value
        }
        if (ActionConditions.shopStage(state) != ShopStage.CLERK_MENU) return Step.Failed(ActionError.UnexpectedScreen("the clerk's menu", state.screen))
        return context.navigator.choose(Screen.ListMenu::class, "SELL") { it.id == "option:$CLERK_SELL" }.andThen {
            context.navigator.advanceUntil(SHOP_WAITS) { it.screen is Screen.Bag }
        }
    }

    /** Sets the number of a quantity screen, then confirms it with A when asked. */
    override fun setQuantity(action: GameAction.SetQuantity, context: PlanContext): ActionOutcome {
        return dialQuantity(context, action.value).then { state ->
            if (!action.confirm) return@then ActionOutcome.Done("quantity ${action.value}")
            context.scope.tap(Button.A)
            context.navigator.awaitChange(state.screen)
            ActionOutcome.Done("quantity ${action.value} confirmed")
        }
    }

    /** From wherever [ActionConditions.shopStage] says, to the shop list; from the field, talking to [chosen] (else the nearest clerk). */
    protected open fun openShop(context: PlanContext, chosen: FieldObject? = null): Step<GameState> {
        var state = context.navigator.settle()
        if (ActionConditions.shopStage(state) == ShopStage.QUANTITY) {
            // B on the quantity goes back to the list.
            context.scope.tap(Button.B)
            context.navigator.awaitChange(state.screen)
            state = context.navigator.settle()
        }
        if (ActionConditions.shopStage(state) == ShopStage.OVERWORLD) {
            val clerk = chosen ?: ActionConditions.shopClerks(state).firstOrNull() ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "There is no shop clerk here", "go to a Poké Mart"))
            when (val talk = interact(GameAction.Interact(clerk.id), context)) {
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
                dialQuantity(context, purchase.quantity)
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
    private fun dialQuantity(context: PlanContext, quantity: Int): Step<GameState> {
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

    private fun count(state: GameState, itemId: Int) =
        state.bag.orEmpty().flatMap { it.items }.filter { it.item.id.value == itemId }.sumOf { it.quantity }

    private fun itemName(state: GameState, itemId: Int) =
        state.bag.orEmpty().flatMap { it.items }.firstOrNull { it.item.id.value == itemId }?.item?.name ?: "item:$itemId"

    // endregion

    private companion object {
        const val HEAL_WAITS = 120

        const val TOP_STORAGE = 0
        const val STORAGE_DEPOSIT = 0
        const val STORAGE_WITHDRAW = 1
        const val STORAGE_MOVE = 2
        const val STORAGE_MOVE_ITEMS = 3
        const val PC_WAITS = 40
        const val BOXES = 18
        const val BOX_SLOTS = 30
        const val PARTY_SIZE = 6
        const val PC_CLOSE_PRESSES = 16
        const val EXIT_PRESSES = 12
        const val MAX_CORRECTIONS = 3
        const val TAB_FRAMES = 30

        /** BUY / SELL / SEE YA!: BUY is always first. */
        const val CLERK_BUY = 0
        const val CLERK_SELL = 1
        const val SHOP_WAITS = 40
        const val MAX_QUANTITY_PRESSES = 60
        const val QUANTITY_CHANGE_FRAMES = 20
        const val TEN = 10
        const val SHOP_CLOSE_PRESSES = 12
    }
}
