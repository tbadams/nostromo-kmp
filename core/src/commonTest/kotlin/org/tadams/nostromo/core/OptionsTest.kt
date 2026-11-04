package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action.Attack
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind

/** What actors are allowed to do: capabilities, and brains choosing only from valid options. */
class OptionsTest {

    private val cat = ActorId("cat")

    private object FirstChoice : Dice {
        override fun roll(sides: Int) = 0
    }

    @Test
    fun capabilitiesDefaultByKind() {
        val scenario = corridor().let { it.copy(actors = it.actors + ActorDef(cat, "Cat", ActorKind.ANIMAL, A)) }
        val sim = Simulation(scenario, FirstChoice)
        assertTrue(sim.availableActions(cat).all { it.action is Move }, "animals can only move by default")
        assertTrue(sim.availableActions(ROBY).any { it.action is Attack }, "crew can attack")
    }

    @Test
    fun scenarioCanOverrideCapabilities() {
        val scenario = corridor().let { s ->
            s.copy(actors = s.actors.map { if (it.id == ROBY) it.copy(capabilities = setOf(ActionType.MOVE)) else it })
        }
        val sim = Simulation(scenario, FirstChoice)
        assertEquals(listOf("Move to Bravo"), sim.availableActions(ROBY).map { it.label })
    }

    @Test
    fun orderForAnActionTheActorCantTakeFails() {
        val scenario = corridor().let { s ->
            s.copy(actors = s.actors.map { if (it.id == ROBY) it.copy(capabilities = setOf(ActionType.MOVE)) else it })
        }
        val sim = Simulation(scenario, FirstChoice)
        sim.submit(Order(ROBY, Attack(HUNTER)))
        val failed = sim.tick().filterIsInstance<GameEvent.ActionFailed>().single()
        assertEquals(FailReason.NOT_CAPABLE, failed.reason)
    }

    @Test
    fun aBrainOnlyGetsWhatTheActorCanDo() {
        // A wumpus-driven animal can't attack, so even with crew in the room it can only wander.
        val scenario = corridor().let { it.copy(actors = it.actors + ActorDef(cat, "Cat", ActorKind.ANIMAL, A, brain = "wumpus")) }
        val sim = Simulation(scenario, FirstChoice)
        val catEvents = sim.runTicks(60).filter {
            (it is GameEvent.ActionStarted && it.actor == cat) || (it is GameEvent.AttackHit && it.attacker == cat)
        }
        assertTrue(catEvents.isNotEmpty())
        assertTrue(catEvents.all { it is GameEvent.ActionStarted && it.action is Move })
    }
}
