package org.tadams.nostromo.core

import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.ItemDef
import org.tadams.nostromo.core.scenario.ItemTypeDef
import org.tadams.nostromo.core.scenario.LinkDef
import org.tadams.nostromo.core.scenario.RoomDef
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.Scenario

val A = RoomId("a")
val B = RoomId("b")
val C = RoomId("c")
val ROBY = ActorId("roby")
val HUNTER = ActorId("hunter")

/**
 * Corridor of three rooms, a - b - c. Moves take exactly 5 s (50 ticks) for Roby; Hunter is 1 s slower.
 * Friendly fire is on by default here: crew hitting each other is the simplest way to test damage.
 */
fun corridor(rules: Rules = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0), friendlyFire = true)) = Scenario(
    id = "test",
    name = "Test corridor",
    rooms = listOf(RoomDef(A, "Alpha"), RoomDef(B, "Bravo"), RoomDef(C, "Charlie")),
    links = listOf(LinkDef(A, B), LinkDef(B, C)),
    actors = listOf(
        ActorDef(ROBY, "Roby", ActorKind.CREW, A),
        ActorDef(HUNTER, "Hunter", ActorKind.CREW, A, speedPenaltySeconds = 1.0),
    ),
    rules = rules,
)

/** A 1-damage, +1-morale weapon, like the original's spanner. */
val SPANNER = ItemTypeDef("spanner", "Spanner", damage = 1, morale = 1)

/** A 2-damage, +2-morale weapon, like the original's laser. */
val LASER = ItemTypeDef("laser", "Laser", damage = 2, morale = 2)

/** Spanners lying in room A, one per id. */
val SPANNER_1 = ItemId("spanner-1")
val SPANNER_2 = ItemId("spanner-2")
val LASER_1 = ItemId("laser-1")

/** [this] scenario plus two spanners and a laser lying in room A. */
fun Scenario.withWeapons() = copy(
    itemTypes = listOf(SPANNER, LASER),
    items = listOf(ItemDef(SPANNER_1, "spanner", A), ItemDef(SPANNER_2, "spanner", A), ItemDef(LASER_1, "laser", A)),
)

/** Runs [n] ticks and returns every event produced. */
fun Simulation.runTicks(n: Int) = (1..n).flatMap { tick() }
