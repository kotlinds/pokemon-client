package dev.kotlinds.pokemonclient.state

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint

/**
 * What the game is waiting for right now, decoded from RAM into one common model shared by every game.
 *
 * Rule: a screen that waits for a key always says so. There is never a "nothing to do" state while the game
 * waits: either a [Selectable] (choose an entry), a [PressToContinue] (press A), a [Dialogue], or [Unknown]
 * (not decoded yet: be careful, use raw buttons or a screenshot).
 */
sealed interface Screen {
    /** What the game expects. */
    val awaiting: Awaiting

    /**
     * A screen where a cursor picks one entry. This is the only abstraction the navigator needs: read the
     * cursor, move along the [topology], re-read, confirm only on the target.
     */
    sealed interface Selectable : Screen {
        val entries: List<Entry>
        val cursor: Cursor
        val topology: Topology
        val cancel: CancelBehavior
        override val awaiting get() = Awaiting.INPUT
    }

    /** A yes / no question. */
    data class YesNo(
        val question: String?,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CONFIRMS_LAST,
        /** For the "forget a move?" / "give up on the move?" prompts of a level up or evolution: who learns what. */
        val learning: MoveOffer? = null,
    ) : Selectable

    /** A list or grid of options: multichoice, start menu, PC menus, BUY / SELL... */
    data class ListMenu(
        val kind: MenuKind,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CLOSES,
    ) : Selectable

    /** The battle command menu (FIGHT / BAG / RUN / POKéMON) of [actor]. */
    data class BattleCommand(
        val actor: BattlerRef?,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.NONE,
    ) : Selectable

    /** Choosing a move: to use in battle, or to forget when learning a new one ([newMove] not null). */
    data class MoveSelect(
        val context: MoveContext,
        val mon: MonId?,
        val newMove: Named<MoveId>?,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior,
    ) : Selectable

    /** Choosing the target of a move in a double battle (only live targets are selectable). */
    data class TargetSelect(
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CLOSES,
    ) : Selectable

    /** The party grid, for a [purpose] (in battle, in the battle's order). */
    data class PartyGrid(
        val purpose: PartyPurpose,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior,
        /** In switch mode ("Move to where?"), the Pokémon being moved. */
        val moving: MonId? = null,
    ) : Selectable

    /** The small menu opened on a Pokémon or a box slot: SUMMARY / SWITCH / ITEM..., DEPOSIT / RELEASE... */
    data class ContextMenu(
        val owner: MonId?,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CLOSES,
        /** For the bag's action menu (USE / GIVE / ...), the item it applies to. */
        val item: ItemId? = null,
    ) : Selectable

    /** The bag, open on [pocket] (page [page] for paged pockets). */
    data class Bag(
        val pocket: String,
        val pockets: List<String>,
        val page: Int,
        val pages: Int,
        val inBattle: Boolean,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CLOSES,
        /**
         * The items of every pocket by pocket entry id (`pocket:hp_pp_restore` → items), when the bag holds them all
         * at once (the battle bag): tells which pocket to open for an item. Empty when unknown.
         */
        val pocketContents: Map<String, List<ItemId>> = emptyMap(),
    ) : Selectable

    /** The naming keyboard: [buffer] is what is typed so far. */
    data class Keyboard(
        val purpose: String,
        val page: String,
        val buffer: String,
        val maxLength: Int,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.NONE,
    ) : Selectable

    /** A PC box. */
    data class PcBox(
        val box: Int,
        val boxName: String,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CLOSES,
        /** What the PC was opened for (null when unknown). */
        val mode: PcMode? = null,
        /** In MOVE POKéMON, the Pokémon picked up and carried by the cursor (placed with A on a slot or a box tab). */
        val holding: MonId? = null,
    ) : Selectable

    /**
     * A shop's buy list, as the game runs it:
     * - [goods]: what the list sells. Items ([ShopGoods.ITEMS]): [items] is what it sells, typed from the game's data
     *   (the ids of the list in RAM, their names in the ROM's language, their prices where the game takes them: the
     *   item data, or the shop's own price list), in the list's order, each one the entry `item:<id>`. Seals and
     *   decorations are not items: their entries are `seal:<id>` / `decoration:<id>` and [items] is empty;
     * - [currency] and [balance]: what the prices are paid with and how much of it the player has, read where the game
     *   reads it (the money for a Poké Mart, the athlete points for the Pokéathlon Dome's shops);
     * - [oneOfEach]: no quantity is asked, each line is bought one at a time and is then sold out
     *   ([ShopItem.soldOut], e.g. the Pokéathlon Dome's daily shop and its Data Cards).
     * The entries' labels are for display only (never parsed: the game may run in another language).
     */
    data class Shop(
        val balance: Long,
        val items: List<ShopItem>,
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CLOSES,
        val currency: ShopCurrency = ShopCurrency.MONEY,
        val goods: ShopGoods = ShopGoods.ITEMS,
        val oneOfEach: Boolean = false,
    ) : Selectable

    /** The fly map: destinations already visited. */
    data class FlyMap(
        override val entries: List<Entry>,
        override val cursor: Cursor,
        override val topology: Topology,
        override val cancel: CancelBehavior = CancelBehavior.CLOSES,
        /** The map cell under the cursor (it moves one cell per D-pad press), when known. */
        val cursorCell: MapCell? = null,
        /** The cell of each destination, by entry id: lets a recipe steer the cursor to one that is off screen. */
        val cells: Map<String, MapCell> = emptyMap(),
        /** Destinations visited but in another region than the player's (not selectable for that reason), by entry id. */
        val otherRegion: Set<String> = emptySet(),
        /**
         * The destination that links the regions, by entry id, when the game has one: once there, Fly reaches the
         * visited towns of every region (HGSS: Indigo Plateau). A [otherRegion] town is reached by flying there first.
         */
        val regionHub: String? = null,
    ) : Selectable

    /** A cell of a map screen's grid (the fly map), x to the east, y to the south. */
    data class MapCell(val x: Int, val y: Int)

    /** A number to pick (shop quantity...): UP/DOWN change it, A confirms. */
    data class Quantity(val value: Int, val min: Int, val max: Int) : Screen {
        override val awaiting get() = Awaiting.INPUT
    }

    /** A screen that only waits for A: Pokédex page after a capture, level-up stats, end of a phone call... */
    data class PressToContinue(val reason: ContinueReason, val text: String? = null) : Screen {
        override val awaiting get() = Awaiting.INPUT
    }

    /** A message box (field, battle, phone, sign). [awaiting] tells if it waits for A or is still printing. */
    data class Dialogue(
        val source: TextSource,
        val speaker: String?,
        /**
         * What the box shows, in the same format in every game: the lines of the page on screen (printed so far)
         * separated by `\n`, exactly where the box breaks them; earlier pages are gone, as on screen. (Entry labels,
         * single lines, have their line breaks turned into spaces.)
         */
        val text: String,
        override val awaiting: Awaiting,
        /**
         * Which message of the game this is, when it is one the game shows by itself on the field, outside any scene
         * (told by the script that prints it, never by its text): null for every other message.
         */
        val notice: FieldNotice? = null,
    ) : Screen

    /**
     * A Pokémon is evolving: an animation to wait for ([canCancel]: B stops it now). The scene's messages ("What? X
     * is evolving!", "Congratulations!...") are [Dialogue] screens and its prompts [YesNo] screens.
     */
    data class Evolution(
        val from: Named<SpeciesId>,
        val to: Named<SpeciesId>?,
        override val awaiting: Awaiting,
        /** The message shown in the scene's box, when one is (the scene's own text pages are [Dialogue] screens). */
        val text: String? = null,
        /** True while B stops the evolution (during the morphing, when the game allows it). */
        val canCancel: Boolean = false,
    ) : Screen

    /**
     * The professor's machine with the starters' balls: [front] (an index into [starters]) is the ball facing the
     * player, [stage] how far the choice went. The `choose_starter` action does the whole choice.
     */
    data class StarterChoice(
        val starters: List<Named<SpeciesId>>,
        val front: Int,
        val stage: StarterStage,
        override val awaiting: Awaiting,
    ) : Screen

    /**
     * A long animation with nothing to do (trade, egg hatching, cut scene...). [hint]: what is known about it (what it
     * is, how to get through it faster) when there is something to say.
     */
    data class Animation(val kind: AnimationKind, val hint: String? = null) : Screen {
        override val awaiting get() = Awaiting.ANIMATION
    }

    /**
     * Walking around. [banner] is a sign banner shown while walking past a sign: information only. [incomingCall]: the
     * phone rings (answering it opens the call; walking on ignores it).
     */
    data class Overworld(val banner: String? = null, override val awaiting: Awaiting, val incomingCall: IncomingCall? = null) : Screen

    /** A battle with no decision to make right now (animations, messages printing). */
    data class Battle(override val awaiting: Awaiting) : Screen

    /**
     * Before the game itself: the intro movie, the title screen, loading, the main menu while it isn't ready yet, the
     * new-game intro. [goesOnWith]: what passes this screen right now (null while it ignores every input: an animation,
     * a fade, the title screen's first frames). The main menu, once it reads input, is a [ListMenu] of kind
     * [MenuKind.MAIN_MENU].
     */
    data class Intro(
        val stage: IntroStage,
        override val awaiting: Awaiting,
        val goesOnWith: IntroInputs? = null,
    ) : Screen {
        /** The stage's language-independent id (`title_screen`...), as agents see it. */
        val detail: String get() = stage.wire
    }

    /**
     * A full-screen application to look at, with nothing to choose: the Pokédex, the trainer card, a Pokémon's
     * summary, the Pokégear's map or radio, the Hall of Fame. [exit] says how to leave it; [details] what it shows
     * that matters (the Hall of Fame: the team being registered and the Pokémon presented now); [radio]: the Pokégear
     * radio's dial and programme; [apps]: the other applications reachable from here by touch (the Pokégear's app
     * bar); [hallOfFame]: how far the registration in the Hall of Fame went.
     */
    data class Viewer(
        val app: ViewerApp,
        val exit: ViewerExit,
        override val awaiting: Awaiting,
        val details: List<String> = emptyList(),
        val radio: PokegearRadio? = null,
        val apps: List<Entry> = emptyList(),
        val hallOfFame: HallOfFameStage? = null,
    ) : Screen

    /**
     * Not decoded yet. Actions refuse to act on it; the agent keeps raw buttons, touch and screenshots.
     * [hint] says what the reader could tell (an app name, a raw wait function...).
     */
    data class Unknown(val hint: String?, override val awaiting: Awaiting) : Screen
}

/**
 * True when [other] shows the same thing as this screen. Plain `==` doesn't work on [Screen.Selectable]s: their
 * [Topology] is a function rebuilt on every decode, so two decodes of an unchanged menu are never equal. Every
 * other field (kind, entries, cursor, question, page...) is compared.
 */
fun Screen.sameAs(other: Screen): Boolean {
    if (this !is Screen.Selectable || other !is Screen.Selectable) return this == other
    return this::class == other::class && withoutTopology(this) == withoutTopology(other)
}

private val TOPOLOGY_FIELD = Regex("topology=[^,)]*")

private fun withoutTopology(screen: Screen.Selectable) = screen.toString().replace(TOPOLOGY_FIELD, "")

/** What the game expects right now. */
enum class Awaiting {
    /** A button, a touch, a choice. */
    INPUT,

    /** Nothing: an animation, a transition, a scene is playing. */
    ANIMATION,

    /** Text is being printed: A speeds it up. */
    TEXT_PRINTING,
}

/** One entry of a [Screen.Selectable]. */
data class Entry(
    /** Stable typed id of what the entry stands for, e.g. `option:yes`, `move:52`, `mon:a3f1….0e21…`, `item:17` (game ids, never displayed text). */
    val id: String,
    val label: String,
    /** False for entries the game shows but refuses (fainted Pokémon, empty slot, no PP...). */
    val selectable: Boolean = true,
    /** True for entries with a lasting consequence that must be asked for explicitly (RELEASE...). */
    val dangerous: Boolean = false,
    /** Set when the entry can only be reached by touching the screen. */
    val touch: TouchPoint? = null,
)

/** Where the cursor is. */
sealed interface Cursor {
    /** No cursor shown: the first D-pad press only makes it appear (battle menus...). */
    data object Hidden : Cursor
    data class At(val index: Int) : Cursor
}

/**
 * How the cursor moves between entries: for each entry and D-pad button, the entry reached (null = doesn't
 * move). Covers lists, grids, wrapping and irregular layouts alike.
 */
fun interface Topology {
    fun next(from: Int, button: Button): Int?

    companion object {
        /** A vertical list; [wrap] when going past an end jumps to the other end. */
        fun vertical(size: Int, wrap: Boolean = false) = Topology { from, button ->
            when (button) {
                Button.UP -> if (from > 0) from - 1 else if (wrap) size - 1 else null
                Button.DOWN -> if (from < size - 1) from + 1 else if (wrap) 0 else null
                else -> null
            }
        }

        /** A horizontal row. */
        fun horizontal(size: Int, wrap: Boolean = false) = Topology { from, button ->
            when (button) {
                Button.LEFT -> if (from > 0) from - 1 else if (wrap) size - 1 else null
                Button.RIGHT -> if (from < size - 1) from + 1 else if (wrap) 0 else null
                else -> null
            }
        }

        /** A grid filled row by row with [columns] columns. */
        fun grid(size: Int, columns: Int, wrap: Boolean = false) = Topology { from, button ->
            val rows = (size + columns - 1) / columns
            val row = from / columns
            val column = from % columns
            val target = when (button) {
                Button.UP -> if (row > 0) from - columns else if (wrap) (rows - 1) * columns + column else null
                Button.DOWN -> if (row < rows - 1) from + columns else if (wrap) column else null
                Button.LEFT -> if (column > 0) from - 1 else if (wrap) from + columns - 1 else null
                Button.RIGHT -> if (column < columns - 1) from + 1 else if (wrap) from - column else null
                else -> null
            }
            target?.takeIf { it in 0 until size }
        }

        /** Explicit links, for irregular layouts: `links[from][button] = to`. */
        fun of(links: Map<Int, Map<Button, Int>>) = Topology { from, button -> links[from]?.get(button) }
    }
}

/** What B does on a [Screen.Selectable]. */
enum class CancelBehavior {
    /** Closes the screen (goes back). */
    CLOSES,

    /** Selects the last entry (often NO / CANCEL / QUIT) without confirming it. */
    SELECTS_LAST,

    /** Picks the last entry (NO, KEEP, FLEE...) and confirms it at once: B is an answer, not a way back. */
    CONFIRMS_LAST,

    /** B does nothing here. */
    NONE,
}

/** Kinds of [Screen.ListMenu]. */
enum class MenuKind {
    MULTICHOICE, START_MENU, PC, SHOP_ACTION, PHONE_CONTACTS, BATTLE_SWITCH_OR_KEEP, OTHER,

    /** The menu after the title screen: continue the saved game, new game... */
    MAIN_MENU,
}

/** A Pokémon wanting to learn a move (level up, evolution): [mon] (null when unknown) and the new [move]. */
data class MoveOffer(
    val mon: MonId?,
    val monName: String?,
    val move: Named<MoveId>,
    /** For a plain YES / NO prompt: what YES means. */
    val question: LearnQuestion = LearnQuestion.FORGET_A_MOVE,
)

/** What YES answers on a learn-move YES / NO prompt (the field asks with plain YES / NO entries). */
enum class LearnQuestion {
    /** "Should a move be deleted and replaced with Y?": YES opens the list of moves to forget. */
    FORGET_A_MOVE,

    /** "Stop trying to teach Y?": YES gives up the new move. */
    GIVE_UP,
}

/** Why a [Screen.MoveSelect] is shown. */
enum class MoveContext {
    /** Choosing the move to use this turn. */
    BATTLE,

    /** Choosing a move to forget, during or after a battle (level up). */
    FORGET_IN_BATTLE,

    /** Choosing a move to forget from the summary screen (evolution, TM / HM, move tutor). */
    FORGET_SUMMARY,
}

/** Why the party grid is shown. */
enum class PartyPurpose { FIELD, SWITCH, USE_ITEM, GIVE_ITEM, TEACH, BATTLE_SWITCH, BATTLE_REPLACE_FAINTED, BATTLE_USE_ITEM, OTHER }

/**
 * Messages the game shows by itself while the player walks, outside any scene: closing the box gives the control back
 * where the player stands, nothing else happens. Each game names the script that prints it (a common script id, the
 * same in every language), so a walk can tell it from a scene that started ([Screen.Dialogue.notice]).
 */
enum class FieldNotice {
    /** The Repel's steps ran out ("REPEL's effect wore off..."): HGSS `std_repel_wore_off`, Platinum common script 32. */
    REPEL_WORE_OFF,
}

/** Sources of text. */
enum class TextSource {
    FIELD, BATTLE, PHONE, SIGN,

    /** Messages of menus and apps (party menu, bag: "can't use that here"...). */
    MENU,

    /** The professor's speech of a new game, before the player is in the world. */
    INTRO,
}

/** Why the game waits for A on a [Screen.PressToContinue]. */
enum class ContinueReason {
    POKEDEX_ENTRY, LEVEL_UP_STATS, PHONE_CALL_ENDED, MESSAGE,

    /** Something bit the fishing line: A now (within about a second) hooks it. */
    FISHING_BITE,
    OTHER,

    /** The clerk gives a bonus with the purchase (a Premier Ball for 10 Poké Balls): the item is already in the bag. */
    SHOP_BONUS,

    /**
     * The game stopped on a wireless communication error: A restarts it at the title screen (what was not saved is
     * lost). An emulator without wireless (melonDS) shows it on the main menu when there is a save.
     */
    COMMUNICATION_ERROR,

    /** "The End" after the credits: A (or START, a touch) restarts the game at the title screen (it was saved before). */
    THE_END,
}

/** What a [Screen.PcBox] was opened for. */
enum class PcMode { DEPOSIT, WITHDRAW, MOVE, MOVE_ITEMS }

/** Where a [Screen.Intro] is, before the game itself. */
enum class IntroStage {
    /** Nothing running yet, or between two applications (power-on, a soft reset, the save being checked or loaded). */
    LOADING,

    /** The opening movie played before the title screen (and again when the title screen is left alone). */
    INTRO_MOVIE,

    /** The title screen: it leads to the main menu (CONTINUE / NEW GAME...). */
    TITLE_SCREEN,

    /** The main menu while it isn't reading input yet (opening, fading); once it does, it is a [Screen.ListMenu]. */
    MAIN_MENU,

    /** The professor's speech of a new game, before the player's first steps. */
    NEW_GAME_INTRO;

    /** Stable id shown to agents. */
    val wire: String get() = name.lowercase()
}

/**
 * The inputs that pass an intro screen ([Screen.Intro]) right now: any one of [buttons], pressed once, or, when
 * [touchAnywhere], a touch anywhere on the bottom screen. Every one of them does the same thing.
 *
 * Never empty, by construction (an [IllegalArgumentException] otherwise): a screen that takes no input has none at
 * all ([Screen.Intro.goesOnWith] null), and `continue_game` always has an input to try on a screen that has some.
 */
data class IntroInputs(val buttons: Set<Button>, val touchAnywhere: Boolean = false) {
    init {
        require(buttons.isNotEmpty() || touchAnywhere) { "an intro screen that goes on takes at least one input (else goesOnWith is null)" }
    }
}

/** Kinds of [Screen.Animation]. */
enum class AnimationKind {
    TRADE, EGG_HATCH, CUTSCENE, TRANSITION,

    /** The game saves by itself (after the Hall of Fame): nothing to do, and a reset is refused until it is done. */
    SAVING,

    /** The credits after the Hall of Fame ([Screen.Animation.hint]: whether and how they can be skipped). */
    CREDITS,
}

/**
 * How far the registration in the Hall of Fame went ([Screen.Viewer.hallOfFame]), after beating the Champion: each
 * team member is presented in turn, then the whole team with the player (the "League Champion" photo), which waits
 * for A; the game then saves and the credits start.
 */
sealed interface HallOfFameStage {
    /** The [index]th team member (from 1) of [count] is presented: [name] as shown (nickname, else species). */
    data class Presenting(val index: Int, val count: Int, val name: String) : HallOfFameStage

    /** The whole team with the player: an animation, then it waits for A (the screen's [Awaiting.INPUT]). */
    data object WholeTeam : HallOfFameStage

    /** A was pressed: the last photo flash and the fade out, before the game saves. */
    data object Leaving : HallOfFameStage
}

/** How far the choice of a starter went on [Screen.StarterChoice]. */
enum class StarterStage {
    /** Looking at the machine: A looks at the ball in front, LEFT / RIGHT turn the machine. */
    LOOKING,

    /** Looking at the ball in front: A picks it (the professor then asks to confirm). */
    INSPECTING,

    /** The professor asks "do you want this one?": A takes it for good, B looks again. */
    CONFIRMING,
}

/** The applications shown as a [Screen.Viewer]. */
enum class ViewerApp {
    POKEDEX, TRAINER_CARD, SUMMARY, POKEGEAR_MAP, POKEGEAR_RADIO,

    /** Registering the team in the Hall of Fame after becoming Champion (an animation, then A): see [HallOfFameStage]. */
    HALL_OF_FAME_REGISTER,

    /** Looking at the Hall of Fame (from a PC). */
    HALL_OF_FAME,
}

/** How to leave a [Screen.Viewer]: a [button] (pressed until the viewer is left), or a [touch] of the bottom screen. */
data class ViewerExit(val button: Button? = null, val touch: TouchPoint? = null)

/** A short name for the kind of screen, for [GameEvent.ScreenChanged] and logs. */
val Screen.kind: String
    get() = when (this) {
        is Screen.YesNo -> "yes_no"
        is Screen.ListMenu -> "menu:${kind.name.lowercase()}"
        is Screen.BattleCommand -> "battle_command"
        is Screen.MoveSelect -> "move_select:${context.name.lowercase()}"
        is Screen.TargetSelect -> "target_select"
        is Screen.PartyGrid -> "party:${purpose.name.lowercase()}"
        is Screen.ContextMenu -> "context_menu"
        is Screen.Bag -> "bag"
        is Screen.Keyboard -> "keyboard"
        is Screen.PcBox -> "pc_box"
        is Screen.Shop -> "shop"
        is Screen.FlyMap -> "fly_map"
        is Screen.Quantity -> "quantity"
        is Screen.PressToContinue -> "press_to_continue:${reason.name.lowercase()}"
        is Screen.Dialogue -> "dialogue:${source.name.lowercase()}"
        is Screen.Evolution -> "evolution"
        is Screen.StarterChoice -> "starter_choice"
        is Screen.Animation -> "animation:${kind.name.lowercase()}"
        is Screen.Overworld -> "overworld"
        is Screen.Battle -> "battle"
        is Screen.Intro -> "intro:${stage.wire}"
        is Screen.Viewer -> "viewer:${app.name.lowercase()}"
        is Screen.Unknown -> "unknown"
    }
