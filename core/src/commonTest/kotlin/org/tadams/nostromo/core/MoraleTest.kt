package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action.Attack
import org.tadams.nostromo.core.action.Action.Get
import org.tadams.nostromo.core.action.Action.Drop
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.LinkDef
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomDef
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Scenario

/** Effective morale and the obedience gate (docs/mechanics.md §5). */
class MoraleTest {

    private val duct = RoomId("a-duct")

    /** The corridor with weapons in A, a duct off A, and each crew member's base morale set. */
    private fun scenario(roby: Int, hunter: Int): Scenario = corridor().withWeapons().let { s ->
        s.copy(
            rooms = s.rooms + RoomDef(duct, "A duct", kind = RoomKind.DUCT),
            links = s.links + LinkDef(A, duct, LinkKind.GRILLE),
            actors = s.actors.map {
                when (it.id) {
                    ROBY -> it.copy(morale = roby)
                    HUNTER -> it.copy(morale = hunter)
                    else -> it
                }
            },
        )
    }

    private fun Simulation.morale(actor: org.tadams.nostromo.core.model.ActorId) = state().actor(actor).morale

    @Test
    fun companyAddsTheirBaseMinusTwo() {
        val sim = Simulation(scenario(roby = 3, hunter = 4), seed = 1)
        assertEquals(3 + (4 - 2), sim.morale(ROBY))
        assertEquals(4 + (3 - 2), sim.morale(HUNTER))
    }

    @Test
    fun frightenedCompanyDragsMoraleDown() {
        val sim = Simulation(scenario(roby = 3, hunter = 0), seed = 1)
        assertEquals(3 - 2, sim.morale(ROBY))
        assertEquals(0 + 1, sim.morale(HUNTER))
    }

    @Test
    fun moraleNeverGoesBelowZero() {
        val sim = Simulation(scenario(roby = 0, hunter = 0), seed = 1)
        assertEquals(0, sim.morale(ROBY))
    }

    @Test
    fun theItemInUseAddsItsBonusButTheCarriedOneDoesnt() {
        val sim = Simulation(scenario(roby = 3, hunter = 2), seed = 1) // Hunter adds 0
        sim.submit(Order(ROBY, Get(LASER_1)))
        sim.tick()
        assertEquals(3 + 2, sim.morale(ROBY))

        sim.submit(Order(ROBY, Get(SPANNER_1)))
        sim.tick()
        assertEquals(3 + 2, sim.morale(ROBY), "the carried spanner gives nothing")

        sim.submit(Order(ROBY, Drop(LASER_1)))
        sim.tick()
        assertEquals(3, sim.morale(ROBY), "putting it down takes the bonus away")
    }

    @Test
    fun anArmedFriendLiftsMorale() {
        val sim = Simulation(scenario(roby = 3, hunter = 2), seed = 1)
        sim.submit(Order(HUNTER, Get(LASER_1)))
        sim.tick()
        assertEquals(3 + (2 + 2 - 2), sim.morale(ROBY))
    }

    @Test
    fun aBodyInTheRoomCostsOne() {
        val sim = Simulation(
            scenario(roby = 3, hunter = 3).let { s -> s.copy(actors = s.actors.map { if (it.id == HUNTER) it.copy(health = 1) else it }) },
            seed = 1,
        )
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Attack(HUNTER))))
        sim.runTicks(40)
        assertTrue(sim.state().actor(HUNTER).isDown)
        assertEquals(3 + 1 - 1, sim.morale(ROBY), "spanner bonus, minus the body")
    }

    @Test
    fun lowMoraleCrewRefuseToAttack() {
        val sim = Simulation(scenario(roby = 1, hunter = 2), seed = 1) // Roby: 1 + 0 = Shaken
        sim.submit(Order(ROBY, Attack(HUNTER)))
        val failed = sim.tick().filterIsInstance<GameEvent.ActionFailed>().single()
        assertEquals(FailReason.TOO_SCARED, failed.reason)
    }

    @Test
    fun theOrderIsStillOfferedSoThePlayerSeesTheRefusal() {
        val sim = Simulation(scenario(roby = 1, hunter = 2), seed = 1)
        assertTrue(sim.availableActions(ROBY).any { it.action is Attack })
    }

    @Test
    fun lowMoraleCrewWontEnterDucts() {
        val sim = Simulation(scenario(roby = 1, hunter = 2), seed = 1)
        sim.submit(Order(ROBY, Move(duct)))
        assertEquals(FailReason.TOO_SCARED, sim.tick().filterIsInstance<GameEvent.ActionFailed>().single().reason)
    }

    @Test
    fun steadyCrewObey() {
        val sim = Simulation(scenario(roby = 2, hunter = 2), seed = 1) // exactly the threshold
        sim.submit(Order(ROBY, Move(duct)))
        assertTrue(sim.tick().none { it is GameEvent.ActionFailed })
    }

    @Test
    fun aWeaponCanGiveTheNerveToAttack() {
        val sim = Simulation(scenario(roby = 1, hunter = 2), seed = 1)
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Attack(HUNTER)))) // +1 makes Roby Uneasy (2)
        val events = sim.runTicks(40)
        assertTrue(events.none { it is GameEvent.ActionFailed })
        assertEquals(1, events.filterIsInstance<GameEvent.AttackHit>().single().damage)
    }
}
