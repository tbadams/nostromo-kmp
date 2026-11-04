package org.tadams.nostromo.core

import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.LinkDef
import org.tadams.nostromo.core.scenario.RoomDef
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.Scenario

val A = RoomId("a")
val B = RoomId("b")
val C = RoomId("c")
val ROBY = ActorId("roby")
val HUNTER = ActorId("hunter")

/** Corridor of three rooms, a - b - c. Moves take exactly 5 s (50 ticks) for Roby; Hunter is 1 s slower. */
fun corridor(rules: Rules = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0))) = Scenario(
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

/** Runs [n] ticks and returns every event produced. */
fun Simulation.runTicks(n: Int) = (1..n).flatMap { tick() }
