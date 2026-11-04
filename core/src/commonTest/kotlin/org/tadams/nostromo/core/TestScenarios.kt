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
val RIPLEY = ActorId("ripley")
val PARKER = ActorId("parker")

/** Corridor of three rooms, a - b - c. Moves take exactly 5 s (50 ticks) for Ripley; Parker is 1 s slower. */
fun corridor(rules: Rules = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0))) = Scenario(
    id = "test",
    name = "Test corridor",
    rooms = listOf(RoomDef(A, "Alpha"), RoomDef(B, "Bravo"), RoomDef(C, "Charlie")),
    links = listOf(LinkDef(A, B), LinkDef(B, C)),
    actors = listOf(
        ActorDef(RIPLEY, "Ripley", ActorKind.CREW, A),
        ActorDef(PARKER, "Parker", ActorKind.CREW, A, speedPenaltySeconds = 1.0),
    ),
    rules = rules,
)

/** Runs [n] ticks and returns every event produced. */
fun Simulation.runTicks(n: Int) = (1..n).flatMap { tick() }
