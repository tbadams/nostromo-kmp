package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.Action.Attack
import org.tadams.nostromo.core.action.Action.Get
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.LinkDef
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomDef
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.Scenario

/** Panic, duct morale, finding bodies and room capacity (docs/mechanics.md §2, §5). */
class CrewDepthTest {

    private val duct = RoomId("b-duct")
    private val kane = ActorId("kane")
    private val ash = ActorId("ash")

    private object FirstChoice : Dice {
        override fun roll(sides: Int) = 0
    }

    /**
     * a - b - c, with an open duct off B and weapons in A. Moves 5 s, attacks 1 s, the crowded wait
     * 1 s. Roby and Hunter (1 s slower) start in A with the given base morale.
     */
    private fun scenario(roby: Int = 3, hunter: Int = 3, extra: List<ActorDef> = emptyList(), rules: Rules = rules()): Scenario =
        corridor(rules).withWeapons().let { s ->
            s.copy(
                rooms = s.rooms.map { if (it.id == B) it.copy(grilleRemoved = true) else it } +
                    RoomDef(duct, "B duct", kind = RoomKind.DUCT),
                links = s.links + LinkDef(B, duct, LinkKind.GRILLE),
                actors = s.actors.map {
                    when (it.id) {
                        ROBY -> it.copy(morale = roby)
                        HUNTER -> it.copy(morale = hunter)
                        else -> it
                    }
                } + extra,
            )
        }

    private fun rules(roomCapacity: Int = 3) = Rules(
        actionSeconds = mapOf(ActionType.MOVE to 5.0, ActionType.ATTACK to 1.0),
        friendlyFire = true,
        crowdedWaitSeconds = 1.0,
        roomCapacity = roomCapacity,
    )

    private fun Simulation.morale(actor: ActorId) = state().actor(actor).morale

    // ---- Panic

    @Test
    fun aBrokenCrewMemberPanicsInsteadOfObeying() {
        // Roby (0) with a spanner (+1) next to Broken Hunter (0 − 2): effective 0. Ordered to attack,
        // he drops the spanner and runs to B, the only way out of A.
        val sim = Simulation(scenario(roby = 0, hunter = 0), FirstChoice)
        sim.submit(Order(ROBY, Get(SPANNER_1)))
        sim.tick()
        assertEquals(SPANNER_1, sim.state().actor(ROBY).inUse, "items are never refused, even when Broken")

        sim.submit(Order(ROBY, listOf(Attack(HUNTER), Move(B))))
        val events = sim.tick()
        assertEquals(Attack(HUNTER), events.filterIsInstance<GameEvent.Panicked>().single().ignored)
        assertEquals(SPANNER_1, events.filterIsInstance<GameEvent.ItemDropped>().single().item)
        assertNull(sim.state().actor(ROBY).inUse)
        assertEquals(Move(B), sim.state().actor(ROBY).activity?.action)
        assertTrue(sim.state().actor(ROBY).queued.isEmpty(), "the rest of the order is dropped too")
    }

    @Test
    fun panicInADuctRipsOutTheGrilleAndClimbsOut() {
        val closed = scenario(roby = 0).let { s ->
            s.copy(
                rooms = s.rooms.map { if (it.id == B) it.copy(grilleRemoved = false) else it },
                actors = s.actors.map { if (it.id == ROBY) it.copy(room = duct) else it },
            )
        }
        val sim = Simulation(closed, FirstChoice)
        sim.submit(Order(ROBY, Action.RemoveGrille))
        val events = sim.tick()
        assertTrue(events.any { it is GameEvent.Panicked })
        assertEquals(B, events.filterIsInstance<GameEvent.GrilleRemoved>().single().room)
        assertEquals(Move(B), sim.state().actor(ROBY).activity?.action)
    }

    @Test
    fun aBrokenCrewMemberLeftAloneDoesNothing() {
        val sim = Simulation(scenario(roby = 0), FirstChoice)
        assertTrue(sim.runTicks(100).none { it is GameEvent.Panicked })
    }

    // ---- Duct morale

    @Test
    fun enteringADuctCostsMoraleAndLeavingGivesItBack() {
        val sim = Simulation(scenario(roby = 3, hunter = 2), FirstChoice)
        sim.submit(Order(ROBY, listOf(Move(B), Move(duct))))
        sim.runTicks(100)
        assertEquals(duct, sim.state().actor(ROBY).room)
        assertEquals(3 - 1, sim.morale(ROBY), "alone, so effective is base")

        sim.submit(Order(ROBY, Move(B)))
        sim.runTicks(50)
        assertEquals(3, sim.morale(ROBY))
    }

    // ---- Bodies

    @Test
    fun findingABodyCostsEveryoneMorale() {
        // Kane lies unfound in C; Ash, far away in A, loses morale too.
        val sim = Simulation(
            scenario(roby = 3, hunter = 2, extra = listOf(
                ActorDef(kane, "Kane", ActorKind.CREW, C, health = 1, morale = 3),
                ActorDef(ash, "Ash", ActorKind.CREW, A, morale = 4),
            )),
            FirstChoice,
        )
        // Hunter walks to C and downs Kane; he finds the body at once.
        sim.submit(Order(HUNTER, listOf(Get(SPANNER_1), Move(B), Move(C), Attack(kane))))
        assertEquals(4 + (3 - 2), sim.morale(ash), "before: Ash 4, plus Roby's company")
        val events = sim.runTicks(200)

        val found = events.filterIsInstance<GameEvent.BodyFound>().single()
        assertEquals(kane, found.body)
        assertEquals(HUNTER, found.finder)
        assertTrue(sim.state().actor(kane).bodyFound)
        assertEquals(3 + (2 - 2), sim.morale(ash), "after: both Ash's and Roby's base fell by 1")
    }

    @Test
    fun aBodyIsOnlyFoundOnce() {
        val sim = Simulation(
            scenario(extra = listOf(ActorDef(kane, "Kane", ActorKind.CREW, A, health = 1))),
            FirstChoice,
        )
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Attack(kane))))
        val events = sim.runTicks(200)
        assertEquals(1, events.count { it is GameEvent.BodyFound })
    }

    // ---- Room capacity

    @Test
    fun aFullRoomMakesAMoveWaitNotFail() {
        // Capacity 1: Hunter waits in A until Roby leaves B.
        val sim = Simulation(scenario(rules = rules(roomCapacity = 1)), FirstChoice)
        sim.submit(Order(ROBY, Move(B)))
        sim.runTicks(50)
        sim.submit(Order(HUNTER, listOf(Move(B), Move(C))))
        val waiting = sim.runTicks(80)
        assertTrue(waiting.any { it is GameEvent.ActionDelayed && it.actor == HUNTER && it.reason == FailReason.ROOM_FULL })
        assertTrue(waiting.none { it is GameEvent.ActionFailed })
        assertEquals(A, sim.state().actor(HUNTER).room)

        sim.submit(Order(ROBY, Move(C)))
        sim.runTicks(80) // Roby's 5 s move, then at most one more 2 s wait for Hunter
        assertEquals(B, sim.state().actor(HUNTER).room, "the wait ends once there's room, and the queue survives")
    }

    @Test
    fun aDuctHoldsOneCrewMember() {
        val sim = Simulation(scenario(hunter = 3), FirstChoice)
        sim.submit(Order(ROBY, listOf(Move(B), Move(duct))))
        sim.submit(Order(HUNTER, listOf(Move(B), Move(duct))))
        val events = sim.runTicks(200)
        assertEquals(duct, sim.state().actor(ROBY).room)
        assertEquals(B, sim.state().actor(HUNTER).room)
        assertTrue(events.any { it is GameEvent.ActionDelayed && it.actor == HUNTER })
    }

    @Test
    fun bodiesTakeUpRoom() {
        // A creature that can only attack downs Kane in B; then Kane's body fills B (capacity 1).
        val creature = ActorId("creature")
        val sim = Simulation(
            scenario(
                rules = rules(roomCapacity = 1),
                extra = listOf(
                    ActorDef(kane, "Kane", ActorKind.CREW, B, health = 1),
                    ActorDef(creature, "Creature", ActorKind.CREATURE, B, brain = "wumpus", capabilities = setOf(ActionType.ATTACK)),
                ),
            ),
            FirstChoice,
        )
        sim.runTicks(20)
        assertTrue(sim.state().actor(kane).isDown)

        sim.submit(Order(ROBY, Move(B)))
        val events = sim.runTicks(80)
        assertTrue(events.any { it is GameEvent.ActionDelayed && it.actor == ROBY })
        assertEquals(A, sim.state().actor(ROBY).room)
    }

    @Test
    fun creaturesDontTakeUpRoom() {
        val creature = ActorId("creature")
        val sim = Simulation(
            scenario(
                rules = rules(roomCapacity = 1),
                extra = listOf(ActorDef(creature, "Creature", ActorKind.CREATURE, B, health = 15)),
            ),
            FirstChoice,
        )
        sim.submit(Order(ROBY, Move(B)))
        sim.runTicks(50)
        assertEquals(B, sim.state().actor(ROBY).room, "only crew count towards capacity")
    }
}
