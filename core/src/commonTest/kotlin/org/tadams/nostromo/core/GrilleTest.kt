package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
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
import org.tadams.nostromo.core.scenario.ScenarioValidator

/** Grilles between rooms and their ducts (docs/mechanics.md §3, §6). */
class GrilleTest {

    private val duct = RoomId("a-duct")
    private val creature = ActorId("creature")

    /** Always rolls 0: under the creature's grille odds, so it goes for the grille. */
    private object FirstChoice : Dice {
        override fun roll(sides: Int) = 0
    }

    /** The corridor with a duct off A behind a grille. Removing a grille takes 2 s, moves 5 s. */
    private fun scenario(removed: Boolean = false, extra: List<ActorDef> = emptyList()): Scenario = corridor(
        Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0, ActionType.REMOVE_GRILLE to 2.0)),
    ).let { s ->
        s.copy(
            rooms = s.rooms.map { if (it.id == A) it.copy(grilleRemoved = removed) else it } +
                RoomDef(duct, "A duct", kind = RoomKind.DUCT),
            links = s.links + LinkDef(A, duct, LinkKind.GRILLE),
            actors = s.actors + extra,
        )
    }

    @Test
    fun aGrilleInPlaceBlocksTheWay() {
        val sim = Simulation(scenario(), seed = 1)
        assertTrue(sim.availableActions(ROBY).none { it.action == Move(duct) }, "not offered")
        sim.submit(Order(ROBY, Move(duct)))
        assertEquals(FailReason.GRILLE_IN_PLACE, sim.tick().filterIsInstance<GameEvent.ActionFailed>().single().reason)
    }

    @Test
    fun removingItOpensTheWay() {
        val sim = Simulation(scenario(), seed = 1)
        sim.submit(Order(ROBY, listOf(Action.RemoveGrille, Move(duct))))
        val events = sim.runTicks(20 + 50)

        val removed = events.filterIsInstance<GameEvent.GrilleRemoved>().single()
        assertEquals(A, removed.room)
        assertEquals(Visibility.Perceived(setOf(ROBY, HUNTER)), removed.visibility)
        assertEquals(duct, sim.state().actor(ROBY).room)
        assertEquals(true, sim.state().room(A).grilleRemoved)
        assertEquals(true, sim.state().room(duct).grilleRemoved, "a duct reports its room's grille")
    }

    @Test
    fun onceOffItCantBeRemovedAgain() {
        val sim = Simulation(scenario(removed = true), seed = 1)
        assertTrue(sim.availableActions(ROBY).none { it.action == Action.RemoveGrille })
        assertTrue(sim.availableActions(ROBY).any { it.action == Move(duct) })
    }

    @Test
    fun roomsWithoutAGrilleOfferNoRemoval() {
        val sim = Simulation(scenario(), seed = 1)
        sim.submit(Order(ROBY, Move(B)))
        sim.runTicks(50)
        assertTrue(sim.availableActions(ROBY).none { it.action == Action.RemoveGrille })
        assertEquals(null, sim.state().room(B).grilleRemoved)
    }

    @Test
    fun itCanBeRemovedFromTheDuctSide() {
        val creatureInDuct = ActorDef(creature, "Creature", ActorKind.CREATURE, duct, brain = "wumpus", health = 15)
        val sim = Simulation(scenario(extra = listOf(creatureInDuct)), FirstChoice)
        val removed = sim.runTicks(25).filterIsInstance<GameEvent.GrilleRemoved>().single()
        assertEquals(creature, removed.actor)
        assertEquals(A, removed.room)
        assertTrue(sim.runTicks(100).any { it is GameEvent.ActorMoved && it.actor == creature && it.to == A })
    }

    @Test
    fun aCreatureBehindAGrilleStaysPutWhenItDoesntTryIt() {
        // Rolling high: never tries the grille, and with no other way out it lingers.
        val highRolls = object : Dice {
            override fun roll(sides: Int) = sides - 1
        }
        val creatureInDuct = ActorDef(creature, "Creature", ActorKind.CREATURE, duct, brain = "wumpus", health = 15)
        val sim = Simulation(scenario(extra = listOf(creatureInDuct)), highRolls)
        val events = sim.runTicks(200)
        assertTrue(events.none { it is GameEvent.GrilleRemoved })
        assertEquals(duct, sim.state().actor(creature).room)
    }

    @Test
    fun validationCatchesBadGrilles() {
        val base = scenario()
        val twoRooms = base.copy(links = base.links + LinkDef(A, B, LinkKind.GRILLE))
        assertTrue(ScenarioValidator.problems(twoRooms).any { "must join a room and a duct" in it })

        val noGrille = base.copy(rooms = base.rooms.map { if (it.id == B) it.copy(grilleRemoved = true) else it })
        assertTrue(ScenarioValidator.problems(noGrille).any { "has no grille" in it })

        assertEquals(emptyList(), ScenarioValidator.problems(base))
    }
}
