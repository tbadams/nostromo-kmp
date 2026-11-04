package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.action.Command.Cancel
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.scenario.Rules

class SimulationTest {

    private fun sim() = Simulation(corridor(), seed = 1)

    @Test
    fun moveCompletesAfterItsDuration() {
        val sim = sim()
        sim.submit(Order(RIPLEY, Move(B)))

        sim.runTicks(49)
        assertEquals(A, sim.state().actor(RIPLEY).room)
        assertEquals(49, sim.state().actor(RIPLEY).activity?.elapsedTicks)

        val events = sim.tick()
        assertEquals(B, sim.state().actor(RIPLEY).room)
        assertNull(sim.state().actor(RIPLEY).activity)
        assertIs<GameEvent.ActorMoved>(events.single())
    }

    @Test
    fun personalDelayAddsToMoveTime() {
        val sim = sim()
        sim.submit(Order(PARKER, Move(B)))
        val started = sim.tick().filterIsInstance<GameEvent.ActionStarted>().single()
        assertEquals(60, started.totalTicks)
    }

    @Test
    fun actorsOwnBaseDurationReplacesTheScenariosButKeepsTheirPenalty() {
        val scenario = corridor().let { s ->
            s.copy(actors = s.actors.map {
                if (it.id == PARKER) it.copy(actionSeconds = mapOf(ActionType.MOVE to 2.0)) else it
            })
        }
        val sim = Simulation(scenario, seed = 1)
        sim.submit(Order(PARKER, Move(B)))
        val started = sim.tick().filterIsInstance<GameEvent.ActionStarted>().single()
        assertEquals(30, started.totalTicks, "2 s own base + 1 s penalty")
    }

    @Test
    fun scenarioDefaultsToTheOriginalsMoveTime() {
        val sim = Simulation(corridor(Rules()), seed = 1)
        sim.submit(Order(RIPLEY, Move(B)))
        val started = sim.tick().filterIsInstance<GameEvent.ActionStarted>().single()
        assertEquals(47, started.totalTicks)
    }

    @Test
    fun queuedMovesRunInOrder() {
        val sim = sim()
        sim.submit(Order(RIPLEY, listOf(Move(B), Move(C))))

        sim.runTicks(50)
        assertEquals(B, sim.state().actor(RIPLEY).room)
        assertEquals(Move(C), sim.state().actor(RIPLEY).activity?.action)

        sim.runTicks(50)
        assertEquals(C, sim.state().actor(RIPLEY).room)
    }

    @Test
    fun newOrderReplacesCurrentActionWithNoPartialEffect() {
        val sim = sim()
        sim.submit(Order(RIPLEY, listOf(Move(B), Move(C))))
        sim.runTicks(30)

        sim.submit(Order(RIPLEY, Move(B)))
        val events = sim.tick()

        assertIs<GameEvent.ActionCancelled>(events[0])
        assertIs<GameEvent.ActionStarted>(events[1])
        val ripley = sim.state().actor(RIPLEY)
        assertEquals(A, ripley.room, "a cancelled move must not move her")
        assertEquals(1, ripley.activity?.elapsedTicks, "the new move starts from scratch")
        assertTrue(ripley.queued.isEmpty(), "the old queue is dropped")
    }

    @Test
    fun cancelStopsEverything() {
        val sim = sim()
        sim.submit(Order(RIPLEY, listOf(Move(B), Move(C))))
        sim.runTicks(10)

        sim.submit(Cancel(RIPLEY))
        val events = sim.tick()

        assertIs<GameEvent.ActionCancelled>(events.single())
        sim.runTicks(200)
        assertEquals(A, sim.state().actor(RIPLEY).room)
        assertNull(sim.state().actor(RIPLEY).activity)
    }

    @Test
    fun moveToNonAdjacentRoomFails() {
        val sim = sim()
        sim.submit(Order(RIPLEY, Move(C)))
        val failed = sim.tick().single()
        assertIs<GameEvent.ActionFailed>(failed)
        assertEquals(FailReason.NOT_ADJACENT, failed.reason)
        assertNull(sim.state().actor(RIPLEY).activity)
    }

    @Test
    fun unknownActorIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { sim().submit(Cancel(ActorId("ash"))) }
    }

    @Test
    fun moveIsSeenByCrewInBothRooms() {
        val sim = sim()
        sim.submit(Order(RIPLEY, Move(B)))
        val moved = sim.runTicks(50).filterIsInstance<GameEvent.ActorMoved>().single()
        assertEquals(Visibility.Perceived(setOf(RIPLEY, PARKER)), moved.visibility)
    }

    @Test
    fun availableActionsListExits() {
        val sim = sim()
        assertEquals(listOf("Move to Bravo"), sim.availableActions(RIPLEY).map { it.label })
        sim.submit(Order(RIPLEY, Move(B)))
        sim.runTicks(50)
        assertEquals(listOf("Move to Alpha", "Move to Charlie"), sim.availableActions(RIPLEY).map { it.label })
    }

    @Test
    fun sameSeedAndCommandsGiveSameEvents() {
        fun play(): List<GameEvent> {
            val sim = Simulation(corridor(), seed = 42)
            sim.submit(Order(RIPLEY, listOf(Move(B), Move(C))))
            sim.submit(Order(PARKER, Move(B)))
            return sim.runTicks(150)
        }
        assertEquals(play(), play())
    }

    @Test
    fun eventTextIsReadable() {
        val sim = sim()
        sim.submit(Order(RIPLEY, Move(B)))
        val texts = sim.runTicks(50).map(sim.text::describe)
        assertEquals(listOf("Ripley starts moving to Bravo (5.0s)", "Ripley enters Bravo"), texts)
    }
}
