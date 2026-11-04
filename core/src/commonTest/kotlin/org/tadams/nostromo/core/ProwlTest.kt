package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action
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

/** The creature lingering in place, and the room damage that causes. */
class ProwlTest {

    private val creature = ActorId("creature")

    /**
     * The brain picks a prowl at the end of tick 0, so it starts on tick 1 and its 10 ticks end on
     * tick 10, the 11th tick run. A few spare ticks, but well short of a second prowl landing.
     */
    private val FIRST_PROWL_LANDS = 15
    private val duct = RoomId("c-duct")

    /** Always rolls the highest value: with moves weighted before prowling, that picks Prowl. */
    private object LastChoice : Dice {
        override fun roll(sides: Int) = sides - 1
    }

    /** Corridor plus a duct off room C; the creature starts in [start]. Prowling takes 1 s. */
    private fun scenario(start: RoomId, rules: Rules = Rules(actionSeconds = mapOf(ActionType.PROWL to 1.0))) =
        corridor(rules).let {
            it.copy(
                rooms = it.rooms + RoomDef(duct, "C duct", kind = RoomKind.DUCT),
                links = it.links + LinkDef(C, duct, LinkKind.GRILLE),
                actors = it.actors + ActorDef(creature, "Creature", ActorKind.CREATURE, start, brain = "wumpus", health = 15),
            )
        }

    @Test
    fun crewCannotProwl() {
        val sim = Simulation(scenario(C), LastChoice)
        assertTrue(sim.availableActions(ROBY).none { it.action == Action.Prowl })
    }

    @Test
    fun prowlingInARoomDamagesIt() {
        val sim = Simulation(scenario(C), LastChoice)
        val damage = sim.runTicks(FIRST_PROWL_LANDS).filterIsInstance<GameEvent.RoomDamaged>().single()
        assertEquals(C, damage.room)
        assertEquals(5, damage.damagePercent)
        assertEquals(5, sim.state().room(C).damagePercent)
    }

    @Test
    fun prowlingInADuctDoesNoDamage() {
        val sim = Simulation(scenario(duct), LastChoice)
        val events = sim.runTicks(30)
        assertTrue(events.any { it is GameEvent.ActionStarted && it.actor == creature && it.action == Action.Prowl })
        assertTrue(events.none { it is GameEvent.RoomDamaged })
    }

    @Test
    fun damageIsSeenOnlyByCrewInTheRoom() {
        val unseen = Simulation(scenario(C), LastChoice).runTicks(FIRST_PROWL_LANDS).filterIsInstance<GameEvent.RoomDamaged>().single()
        assertEquals(Visibility.Perceived(emptySet()), unseen.visibility, "nobody was there to see it")

        // With Roby in C the creature would attack him, so take away its attack to make it prowl.
        val watched = scenario(C).let { s ->
            s.copy(actors = s.actors.map {
                when (it.id) {
                    ROBY -> it.copy(room = C)
                    creature -> it.copy(capabilities = setOf(ActionType.MOVE, ActionType.PROWL))
                    else -> it
                }
            })
        }
        val seen = Simulation(watched, LastChoice).runTicks(FIRST_PROWL_LANDS).filterIsInstance<GameEvent.RoomDamaged>().single()
        assertEquals(Visibility.Perceived(setOf(ROBY)), seen.visibility)
    }

    @Test
    fun damageIsCappedAt100() {
        val sim = Simulation(scenario(C, Rules(actionSeconds = mapOf(ActionType.PROWL to 0.1), prowlDamagePercent = 60)), LastChoice)
        sim.runTicks(10)
        assertEquals(100, sim.state().room(C).damagePercent)
    }

    @Test
    fun zeroWeightMeansTheCreatureNeverLingers() {
        val sim = Simulation(scenario(C, Rules(prowlWeight = 0)), LastChoice)
        val started = sim.runTicks(200).filterIsInstance<GameEvent.ActionStarted>().filter { it.actor == creature }
        assertTrue(started.isNotEmpty())
        assertTrue(started.none { it.action == Action.Prowl })
    }

    @Test
    fun anOrderedProwlFailsForCrew() {
        val sim = Simulation(scenario(C), LastChoice)
        sim.submit(Order(ROBY, Action.Prowl))
        val failed = sim.tick().filterIsInstance<GameEvent.ActionFailed>().first { it.actor == ROBY }
        assertEquals(FailReason.NOT_CAPABLE, failed.reason)
    }
}
