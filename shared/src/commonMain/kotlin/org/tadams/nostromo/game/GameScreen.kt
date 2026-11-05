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
import org.tadams.nostromo.core.GameResult
import org.tadams.nostromo.core.Rating
import org.tadams.nostromo.core.RoomState
import org.tadams.nostromo.core.Time
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.event.EventText
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.rng.ODDS
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.ui.Amber
import org.tadams.nostromo.ui.Background
import org.tadams.nostromo.ui.DimGreen
import org.tadams.nostromo.ui.Green
import org.tadams.nostromo.ui.LightGreen
import org.tadams.nostromo.ui.Purple
import org.tadams.nostromo.ui.Red

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
        val result = ui.game.result
        if (result != null) {
            EndScreen(ui, result, viewModel::setDebug, Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
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
 * Ship-wide status: the clock, oxygen (always), and the self-destruct countdown while it's armed,
 * as real-time m:ss. Oxygen is shown in the original's units; it turns amber when critical.
 */
@Composable
private fun StatusBar(ui: GameUiState, modifier: Modifier) {
    val ship = ui.game.ship
    Row(
        modifier.border(1.dp, DimGreen).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText("TIME ${clock(ui.game.tick)}", Green, bold = true)
        MonoText("O2 ${ship.oxygen.toString().padStart(4, '0')}", if (ship.oxygenCritical) Amber else Green, bold = ship.oxygenCritical)
        ship.destructTicksLeft?.let { MonoText("DESTRUCT ${Time.clock(it)}", Amber, bold = true) }
        Spacer(Modifier.weight(1f))
        ui.game.result?.let { MonoText(ui.endingText, Amber, bold = true) }
    }
}

/**
 * The end-of-game report, replacing the game view: how it ended, who survived, escaped, slept or
 * died, who the android was, and the Competence Rating in parts (docs/mechanics.md §10).
 */
@Composable
private fun EndScreen(ui: GameUiState, result: GameResult, onDebug: (Boolean) -> Unit, modifier: Modifier) {
    fun names(ids: List<ActorId>) = ids.joinToString { ui.game.actor(it).name }.ifEmpty { "none" }
    val asleep = ui.game.actors.filter { it.asleep }.map { it.id }
    Row(modifier) {
        Column(Modifier.weight(1f).fillMaxHeight().border(1.dp, Amber).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoText("GAME OVER", Amber, bold = true)
            MonoText(ui.endingText, Amber, bold = true)
            Spacer(Modifier.height(8.dp))
            MonoText("Survivors:  ${names(result.survivors)}", Green)
            if (result.escaped.isNotEmpty()) MonoText("Escaped:    ${names(result.escaped)}", Green)
            if (asleep.isNotEmpty()) MonoText("Hypersleep: ${names(asleep)}", Green)
            MonoText("Lost:       ${names(result.casualties)}", Green)
            MonoText("Android:    ${result.android?.let { ui.game.actor(it).name } ?: "none"}", Green)
            MonoText("Self-destruct ${if (result.destructArmed) "armed" else "not armed"}", Green)
            Spacer(Modifier.height(8.dp))
            MonoText("COMPETENCE RATING", Amber, bold = true)
            MonoText("  Crew   ${result.rating.crew}", Green)
            MonoText("  Damage ${result.rating.damage}", Green)
            MonoText("  Time   ${result.rating.time}", Green)
            MonoText("  Total  ${result.rating.total} / ${Rating.MAX}", Amber, bold = true)
        }
        Spacer(Modifier.width(8.dp))
        EventLog(ui, onDebug, Modifier.width(400.dp).fillMaxHeight())
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
        // Nothing for crew who don't answer (down, or the revealed android): their whereabouts are unknown.
        if (actor != null && condition(actor, ui) == Condition.UP) {
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
        val def = actor?.takeIf { condition(it, ui) == Condition.UP }?.let { a -> ui.scenario.actors.firstOrNull { it.id == a.id } }
        if (def != null) {
            val rules = ui.scenario.rules
            val grille = def.actionSeconds[ActionType.REMOVE_GRILLE] ?: rules.baseSeconds(ActionType.REMOVE_GRILLE)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                MonoText("speed penalty +${def.speedPenaltySeconds}s", DimGreen)
                // Total wait: own base time plus the speed penalty, rounded to the tick as the simulation does.
                MonoText("grille removal ${Time.seconds(Time.ticks(grille + def.speedPenaltySeconds))}", DimGreen)
                // Chances in ODDS of catching the cat with the box (the net adds its bonus).
                MonoText("cat affinity ${ODDS - def.catchDifficulty}", DimGreen)
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
                // No progress bar for crew who don't answer: the revealed android is still acting, but that's secret.
                val activity = actor.activity?.takeUnless { down }
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
                TerminalButton(option.label, onClick = { viewModel.order(option.action) }, colour = actionColour(option.action))
            }
        }
    }
}

/**
 * A text map, one line per location, grouped by deck. Crew are always shown, and found bodies
 * where they lie; crew who don't answer (an unfound body, the revealed android) and anything else
 * only where a crew member who answers can see them (or everywhere in debug mode).
 */
@Composable
private fun ShipMap(ui: GameUiState, modifier: Modifier) {
    // Rooms a crew member who answers is standing in: everything there is visible. Not the android's own room.
    val seenRooms = ui.game.actors.filter { it.kind == ActorKind.CREW && it.takesOrders }.map { it.room }.toSet()
    val byDeck = ui.game.rooms.groupBy { it.deck ?: "?" }
    val selectedActor = ui.selected?.let(ui.game::actor)
    val selectedRoom = selectedActor?.room
    // Where the selected crew member could walk or crawl next, not counting through a grille.
    val destinations = selectedActor?.takeIf { it.takesOrders }?.let { ui.game.room(it.room).passages.toSet() }.orEmpty()

    fun shown(room: RoomState) = room.kind == RoomKind.ROOM || ui.debug || room.id in seenRooms || room.id in destinations
    // Crew are shown wherever they are, unless they've stopped answering (an unfound body, the revealed
    // android): then only if someone who answers is there to see them.
    fun visibleIn(room: RoomState) = occupants(ui.game, room).filter {
        (it.kind == ActorKind.CREW && condition(it, ui) != Condition.NO_RESPONSE) || ui.debug || room.id in seenRooms
    }

    LazyColumn(modifier.border(1.dp, DimGreen).padding(8.dp)) {
        byDeck.forEach { (deck, rooms) ->
            item { MonoText(deck.uppercase(), Amber, bold = true) }
            items(rooms.filter(::shown)) { room ->
                val names = visibleIn(room).filterNot { it.asleep || it.gone }.joinToString {
                    if (condition(it, ui) == Condition.DOWN) "${it.name} (down)" else it.name
                }
                // TODO a duct is listed (via shown()) when a crew member who is up is in it, but it's still
                //  drawn in DimGreen, the colour debug mode uses for hidden entries, so a duct that's
                //  visible because someone is there looks the same as one only debug reveals.
                val colour = when {
                    room.id == selectedRoom -> Amber
                    room.id in destinations -> LightGreen
                    room.kind == RoomKind.DUCT -> DimGreen
                    else -> Green
                }
                val marker = if (room.id in destinations) "> " else "  "
                // Ship-wide alarms, so everyone knows: fire, structural damage, a blown airlock.
                val alarms = listOfNotNull(
                    "FIRE".takeIf { room.burning },
                    "STRUCTURAL".takeIf { room.structural },
                    "BLOWN".takeIf { room.blown },
                ).joinToString(" ")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoText("$marker${room.name.padEnd(22)} $names", colour)
                    if (alarms.isNotEmpty()) MonoText(alarms, Amber, bold = true)
                }
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

/** A rectangular button with an outline and a faded fill in [colour], matching the terminal look. */
@Composable
private fun TerminalButton(label: String, onClick: () -> Unit, colour: Color = Green) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colour, RectangleShape)
            .background(colour.copy(alpha = 0.12f), RectangleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        MonoText(label, colour)
    }
}

/** Action bar colour for [action]: red to attack, purple for the ship's controls and the cat, green otherwise. */
private fun actionColour(action: Action): Color = when (action) {
    is Action.Attack -> Red
    is Action.Move -> LightGreen
    is Action.Catch, Action.ArmDestruct, Action.DisarmDestruct, is Action.BlowAirlock, is Action.SealAirlock,
    Action.Hypersleep, Action.Launch, Action.FightFire -> Purple
    else -> Green
}

@Composable
private fun MonoText(text: String, colour: Color, bold: Boolean = false) {
    Text(text, color = colour, fontFamily = Mono, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall)
}

/** What the player can tell about a crew member's condition. */
private enum class Condition { UP, NO_RESPONSE, DOWN, ASLEEP, LOST }

/**
 * [actor]'s condition as the player knows it. A crew member who is down but whom nobody has found
 * (and not in debug) shows as [Condition.NO_RESPONSE]: the player knows something's wrong, not what,
 * matching the narrator, which doesn't report a collapse nobody saw. A revealed android shows the
 * same way: it has stopped answering, and the player finds out why from what the crew see.
 */
private fun condition(actor: ActorState, ui: GameUiState) = when {
    actor.asleep -> Condition.ASLEEP
    // Blown out of an airlock: everyone knows the airlock was blown, so the loss is no secret.
    actor.gone -> Condition.LOST
    actor.isDown -> if (actor.kind != ActorKind.CREW || actor.bodyFound || ui.debug) Condition.DOWN else Condition.NO_RESPONSE
    actor.kind == ActorKind.CREW && !actor.takesOrders -> Condition.NO_RESPONSE
    else -> Condition.UP
}

private fun Condition.label() = when (this) {
    Condition.UP -> ""
    Condition.DOWN -> "DOWN"
    Condition.NO_RESPONSE -> "NO RESPONSE"
    Condition.ASLEEP -> "HYPERSLEEP"
    Condition.LOST -> "LOST"
}

private fun occupants(game: GameState, room: RoomState): List<ActorState> = game.actors.filter { it.room == room.id }

private fun clock(tick: Long): String {
    val seconds = tick / Time.TICKS_PER_SECOND
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}


private fun actionText(action: Action, game: GameState) = when (action) {
    is Action.Move -> "moving to ${game.room(action.to).name}"
    is Action.Attack -> "attacking ${game.actor(action.target).name}"
    Action.Prowl -> "prowling"
    Action.RemoveGrille -> "removing the grille"
    is Action.Get -> "picking up the ${game.item(action.item).name}"
    is Action.Drop -> "dropping the ${game.item(action.item).name}"
    is Action.Swap -> "swapping to the ${game.item(action.item).name}"
    is Action.Catch -> "catching ${game.actor(action.target).name}"
    Action.ArmDestruct -> "arming the self-destruct"
    Action.DisarmDestruct -> "overriding the self-destruct"
    is Action.BlowAirlock -> "blowing ${game.room(action.airlock).name}"
    is Action.SealAirlock -> "sealing ${game.room(action.airlock).name}"
    Action.Hypersleep -> "going into hypersleep"
    Action.Launch -> "launching the shuttle"
    Action.FightFire -> "fighting the fire"
}
