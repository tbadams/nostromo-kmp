package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action.Get
import org.tadams.nostromo.core.action.Action.Drop
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.Action.Swap
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent

/** Picking up, dropping and swapping items (docs/mechanics.md §4). */
class ItemsTest {

    private fun sim() = Simulation(corridor().withWeapons(), seed = 1)

    @Test
    fun getIsInstantAndFillsInUseFirstThenCarried() {
        val sim = sim()
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Get(LASER_1))))
        val taken = sim.runTicks(2).filterIsInstance<GameEvent.ItemTaken>()

        assertEquals(listOf(SPANNER_1, LASER_1), taken.map { it.item })
        val roby = sim.state().actor(ROBY)
        assertEquals(SPANNER_1, roby.inUse)
        assertEquals(LASER_1, roby.carried)
        assertNull(sim.state().item(SPANNER_1).room, "held items aren't lying anywhere")
    }

    @Test
    fun getIsntSlowedByTheActorsSpeedPenalty() {
        val sim = sim()
        sim.submit(Order(HUNTER, Get(SPANNER_1))) // Hunter is 1 s slower at everything else
        assertEquals(1, sim.tick().filterIsInstance<GameEvent.ItemTaken>().size)
    }

    @Test
    fun cantHoldMoreThanTwo() {
        val sim = sim()
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Get(LASER_1))))
        sim.runTicks(2)
        assertTrue(sim.availableActions(ROBY).none { it.action is Get }, "hands full: no more gets offered")

        sim.submit(Order(ROBY, Get(SPANNER_2)))
        val failed = sim.tick().filterIsInstance<GameEvent.ActionFailed>().single()
        assertEquals(FailReason.HANDS_FULL, failed.reason)
    }

    @Test
    fun onlyItemsHereAreOffered() {
        val sim = sim()
        assertEquals(
            listOf("Get Spanner", "Get Spanner", "Get Laser"),
            sim.availableActions(ROBY).filter { it.action is Get }.map { it.label },
        )
        sim.submit(Order(ROBY, Move(B)))
        sim.runTicks(50)
        assertTrue(sim.availableActions(ROBY).none { it.action is Get })
    }

    @Test
    fun twoCrewCantTakeTheSameItem() {
        val sim = sim()
        sim.submit(Order(ROBY, Get(LASER_1)))
        sim.submit(Order(HUNTER, Get(LASER_1)))
        val events = sim.tick()
        assertEquals(ROBY, events.filterIsInstance<GameEvent.ItemTaken>().single().actor)
        assertEquals(FailReason.ITEM_NOT_HERE, events.filterIsInstance<GameEvent.ActionFailed>().single().reason)
    }

    @Test
    fun dropPutsItDownWhereTheyAre() {
        val sim = sim()
        sim.submit(Order(ROBY, listOf(Get(LASER_1), Move(B), Drop(LASER_1))))
        sim.runTicks(52)
        assertNull(sim.state().actor(ROBY).inUse)
        assertEquals(B, sim.state().item(LASER_1).room)
    }

    @Test
    fun swapPutsCarriedIntoUse() {
        val sim = sim()
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Get(LASER_1), Swap(LASER_1))))
        sim.runTicks(3)
        val roby = sim.state().actor(ROBY)
        assertEquals(LASER_1, roby.inUse)
        assertEquals(SPANNER_1, roby.carried)
        assertEquals(listOf("Swap to Spanner"), sim.availableActions(ROBY).filter { it.action is Swap }.map { it.label })
    }
}
