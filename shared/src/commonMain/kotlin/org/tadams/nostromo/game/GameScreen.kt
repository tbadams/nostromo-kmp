package org.tadams.nostromo.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tadams.nostromo.core.ActorState
import org.tadams.nostromo.core.GameState
import org.tadams.nostromo.core.Ending
import org.tadams.nostromo.core.GameResult
import org.tadams.nostromo.core.RoomState
import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.event.EventText
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.ui.Amber
import org.tadams.nostromo.ui.Background
import org.tadams.nostromo.ui.DimGreen
import org.tadams.nostromo.ui.Green

private val Mono = FontFamily.Monospace

/**
 * The game screen:
 * - top: ship status (clock; oxygen and self-destruct once they exist)
 * - middle: log on the left, ship map in the centre, the selected crew member's actions on the right
 * - below that: crew tabs
 * - bottom: the selected crew member's surroundings (room; morale and damage once they exist)
 *
 * Keys: 1-9 select crew, Tab / Shift+Tab (or [ and ]) cycle, Esc cancels the current order.
 */
@Composable
fun GameScreen(viewModel: GameViewModel) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .safeContentPadding()
            .padding(8.dp)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && handleKey(event.key, event.isShiftPressed, viewModel) },
    ) {
        StatusBar(ui, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            EventLog(ui, viewModel::setDebug, Modifier.width(400.dp).fillMaxHeight())
            Spacer(Modifier.width(8.dp))
            ShipMap(ui, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(8.dp))
            ActionBar(ui, viewModel, Modifier.width(240.dp).fillMaxHeight())
        }
        Spacer(Modifier.height(8.dp))
        CrewTabs(ui, viewModel::select)
        Spacer(Modifier.height(8.dp))
        BottomBar(ui, Modifier.fillMaxWidth())
    }
}

private fun handleKey(key: Key, shift: Boolean, viewModel: GameViewModel): Boolean {
    val digit = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        .indexOf(key)
    when {
        digit >= 0 -> viewModel.selectIndex(digit)
        key == Key.Tab -> viewModel.cycleSelection(if (shift) -1 else 1)
        key == Key.RightBracket -> viewModel.cycleSelection(1)
        key == Key.LeftBracket -> viewModel.cycleSelection(-1)
        key == Key.Escape -> viewModel.cancel()
        else -> return false
    }
    return true
}

/**
 * Ship-wide status. Today just the clock and the outcome.
 * TODO add the oxygen countdown (always) and the self-destruct timer (only while armed) when :core
 *  has them (build step 5). Show both as real-time m:ss via Time.clock().
 */
@Composable
private fun StatusBar(ui: GameUiState, modifier: Modifier) {
    Row(
        modifier.border(1.dp, DimGreen).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText("TIME ${clock(ui.game.tick)}", Green, bold = true)
        Spacer(Modifier.weight(1f))
        ui.game.result?.let { MonoText(resultText(it), Amber, bold = true) }
    }
}

/**
 * Status of the selected crew member: health, morale and what they hold; then their location, its
 * damage and grille, as the original's bottom line showed, and what's lying there.
 */
@Composable
private fun BottomBar(ui: GameUiState, modifier: Modifier) {
    val actor = ui.selected?.let(ui.game::actor)
    Column(modifier.border(1.dp, DimGreen).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (actor != null) {
                MonoText(actor.name, Amber, bold = true)
                val condition = condition(actor, ui)
                if (condition != Condition.UP) {
                    MonoText(condition.label(), Green)
                    return@Row
                }
                MonoText("health ${actor.health}", Green)
                actor.morale?.let { MonoText("morale ${EventText.morale(it)} ($it)", Green) }
                val held = listOfNotNull(
                    actor.inUse?.let { "${ui.game.item(it).name} (in use)" },
                    actor.carried?.let { ui.game.item(it).name },
                )
                MonoText("holding ${held.joinToString().ifEmpty { "nothing" }}", Green)
            }
        }
        // Where they are: only what they can see from there, so the map doesn't give away changes nobody saw.
        if (actor != null && !actor.isDown) {
            val room = ui.game.room(actor.room)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MonoText(room.name, Green)
                if (room.kind == RoomKind.ROOM) MonoText("damage ${room.damagePercent}%", Green)
                room.grilleRemoved?.let { MonoText(if (it) "grille off" else "grille in place", Green) }
                val here = ui.game.itemsIn(actor.room).map { it.name }
                if (here.isNotEmpty()) MonoText("here: ${here.joinToString()}", Green)
            }
        }
        // TODO temporary: the selected crew member's own stats, while tuning. Remove or move to the stats screen.
        val def = actor?.takeUnless { it.isDown }?.let { a -> ui.scenario.actors.firstOrNull { it.id == a.id } }
        if (def != null) {
            val rules = ui.scenario.rules
            val grille = def.actionSeconds[ActionType.REMOVE_GRILLE] ?: rules.baseSeconds(ActionType.REMOVE_GRILLE)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MonoText("speed penalty +${def.speedPenaltySeconds}s", DimGreen)
                // Total wait: own base time plus the speed penalty, rounded to the tick as the simulation does.
                MonoText("grille removal ${Time.seconds(Time.ticks(grille + def.speedPenaltySeconds))}", DimGreen)
            }
        }
    }
}

@Composable
private fun CrewTabs(ui: GameUiState, onSelect: (ActorId) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ui.game.actors.filter { it.kind == ActorKind.CREW }.forEachIndexed { index, actor ->
            val selected = actor.id == ui.selected
            val condition = condition(actor, ui)
            val down = condition != Condition.UP
            val colour = when {
                down -> DimGreen
                selected -> Amber
                else -> Green
            }
            Column(
                Modifier
                    .border(1.dp, colour)
                    .background(if (selected) colour.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(actor.id) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .width(110.dp),
            ) {
                MonoText("${index + 1} ${actor.name}", colour, bold = selected)
                MonoText(if (down) condition.label() else "HP ${"■".repeat(actor.health)}", colour)
                val activity = actor.activity
                if (activity != null) {
                    LinearProgressIndicator(
                        progress = { activity.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        color = colour,
                        trackColor = colour.copy(alpha = 0.2f),
                        drawStopIndicator = {},
                    )
                } else {
                    MonoText(if (down) "" else "idle", colour.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun ActionBar(ui: GameUiState, viewModel: GameViewModel, modifier: Modifier) {
    val actor = ui.selected?.let(ui.game::actor)
    Column(modifier.border(1.dp, DimGreen).padding(8.dp)) {
        if (actor == null) return@Column
        MonoText(actor.name, Amber, bold = true)
        val condition = condition(actor, ui)
        if (condition != Condition.UP) {
            MonoText(condition.label(), Green)
            return@Column
        }
        MonoText(ui.game.room(actor.room).name, Green)
        val activity = actor.activity
        if (activity != null) {
            MonoText("Busy: ${actionText(activity.action, ui.game)}", Green)
            TerminalButton("Stop (Esc)", onClick = viewModel::cancel)
        }
        Spacer(Modifier.height(8.dp))
        // TODO make groups of actions easier to tell apart: today attacks, moves (and later items,
        //  grilles, room specials) are one undifferentiated list of identical buttons. E.g. a small
        //  header or divider per action type, and/or a distinct colour or icon per group (attacks
        //  amber). The list is already ordered by type (ActionDefs.all), so grouping by
        //  option.action.type is enough.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ui.actions) { option ->
                TerminalButton(option.label, onClick = { viewModel.order(option.action) })
            }
        }
    }
}

/**
 * A text map, one line per location, grouped by deck. Crew are always shown; anything else only
 * where a crew member who is up can see it (or everywhere in debug mode).
 */
@Composable
private fun ShipMap(ui: GameUiState, modifier: Modifier) {
    // Rooms a crew member who is up is standing in: everything there is visible.
    val seenRooms = ui.game.actors.filter { it.kind == ActorKind.CREW && !it.isDown }.map { it.room }.toSet()
    val byDeck = ui.game.rooms.groupBy { it.deck ?: "?" }
    val selectedRoom = ui.selected?.let(ui.game::actor)?.room

    fun shown(room: RoomState) = room.kind == RoomKind.ROOM || ui.debug || room.id in seenRooms
    fun visibleIn(room: RoomState) = occupants(ui.game, room)
        .filter { it.kind == ActorKind.CREW || ui.debug || room.id in seenRooms }

    LazyColumn(modifier.border(1.dp, DimGreen).padding(8.dp)) {
        byDeck.forEach { (deck, rooms) ->
            item { MonoText(deck.uppercase(), Amber, bold = true) }
            items(rooms.filter(::shown)) { room ->
                val names = visibleIn(room).joinToString {
                    when (condition(it, ui)) {
                        Condition.UP -> it.name
                        Condition.DOWN -> "${it.name} (down)"
                        // Not given away by the map: they're wherever they fell, but nobody knows.
                        Condition.NO_RESPONSE -> it.name
                    }
                }
                // TODO a duct is listed (via shown()) when a crew member who is up is in it, but it's still
                //  drawn in DimGreen, the colour debug mode uses for hidden entries, so a duct that's
                //  visible because someone is there looks the same as one only debug reveals.
                val colour = if (room.id == selectedRoom) Amber else if (room.kind == RoomKind.DUCT) DimGreen else Green
                MonoText("  ${room.name.padEnd(22)} $names", colour)
            }
        }
    }
}

@Composable
private fun EventLog(ui: GameUiState, onDebug: (Boolean) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Follow new entries, except in debug mode, where the flood of hidden lines would make it hard to read back.
    LaunchedEffect(ui.log.size) { if (ui.log.isNotEmpty() && !ui.debug) listState.animateScrollToItem(ui.log.lastIndex) }
    Column(modifier.border(1.dp, DimGreen).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText("LOG", Amber, bold = true)
            Spacer(Modifier.weight(1f))
            MonoText("debug ", Green)
            Switch(
                checked = ui.debug,
                onCheckedChange = onDebug,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Green,
                    checkedTrackColor = Green.copy(alpha = 0.3f),
                    checkedBorderColor = Green,
                    uncheckedThumbColor = DimGreen,
                    uncheckedTrackColor = Background,
                    uncheckedBorderColor = DimGreen,
                ),
            )
        }
        LazyColumn(state = listState) {
            items(ui.log) { entry ->
                MonoText("${clock(entry.tick)}  ${entry.text}", if (entry.hidden) DimGreen else Green)
            }
        }
    }
}

/** A rectangular button with a green outline and a faded green fill, matching the terminal look. */
@Composable
private fun TerminalButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Green, RectangleShape)
            .background(Green.copy(alpha = 0.12f), RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        MonoText(label, Green)
    }
}

@Composable
private fun MonoText(text: String, colour: Color, bold: Boolean = false) {
    Text(text, color = colour, fontFamily = Mono, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall)
}

/** What the player can tell about a crew member's condition. */
private enum class Condition { UP, NO_RESPONSE, DOWN }

/**
 * [actor]'s condition as the player knows it. A crew member who is down but whom nobody has found
 * (and not in debug) shows as [Condition.NO_RESPONSE]: the player knows something's wrong, not what,
 * matching the narrator, which doesn't report a collapse nobody saw.
 */
private fun condition(actor: ActorState, ui: GameUiState) = when {
    !actor.isDown -> Condition.UP
    actor.kind != ActorKind.CREW || actor.bodyFound || ui.debug -> Condition.DOWN
    else -> Condition.NO_RESPONSE
}

private fun Condition.label() = if (this == Condition.DOWN) "DOWN" else "NO RESPONSE"

private fun occupants(game: GameState, room: RoomState): List<ActorState> = game.actors.filter { it.room == room.id }

private fun clock(tick: Long): String {
    val seconds = tick / Time.TICKS_PER_SECOND
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

// TODO replace with the full endgame report (build step 5; docs/mechanics.md §10): an end screen that
//  replaces the game view and shows what happened, who died, who the android was, and the
//  Competence Rating (crew health + undamaged rooms + oxygen left), revealed line by line as
//  nostromo-js did. :core's GameResult already carries the ending, casualties, android and rating parts.
private fun resultText(result: GameResult): String {
    val ending = when (result.ending) {
        Ending.CREATURE_KILLED -> "The creature is dead. You win."
        Ending.CREW_GONE -> "The crew is lost."
    }
    return "$ending  Competence rating: ${result.rating.total}"
}

private fun actionText(action: Action, game: GameState) = when (action) {
    is Action.Move -> "moving to ${game.room(action.to).name}"
    is Action.Attack -> "attacking ${game.actor(action.target).name}"
    Action.Prowl -> "prowling"
    Action.RemoveGrille -> "removing the grille"
    is Action.Get -> "picking up the ${game.item(action.item).name}"
    is Action.Drop -> "dropping the ${game.item(action.item).name}"
    is Action.Swap -> "swapping to the ${game.item(action.item).name}"
}
