package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action.Attack
import org.tadams.nostromo.core.action.Action.Get
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.Rules

class CombatTest {

    private val creature = ActorId("creature")

    /**
     * The corridor with a creature in room C and weapons lying in A. Attacks take 1 s (10 ticks),
     * moves 5 s. Crew need to pick up a weapon ([Get], one tick) to do any damage.
     */
    private fun withCreature(creatureHealth: Int = 3, creatureBrain: String? = null) = corridor(
        Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0, ActionType.ATTACK to 1.0), friendlyFire = true),
    ).withWeapons().let { it.copy(actors = it.actors + ActorDef(creature, "Creature", ActorKind.CREATURE, C, creatureBrain, creatureHealth)) }

    /** Always rolls 0: the first victim, the first exit. */
    private object FirstChoice : Dice {
        override fun roll(sides: Int) = 0
    }

    @Test
    fun attackLandsAfterItsDurationAndCostsHealth() {
        val sim = Simulation(withCreature(), FirstChoice)
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Attack(HUNTER)))) // the get takes tick 0, the attack ticks 1-10

        sim.runTicks(10)
        assertEquals(4, sim.state().actor(HUNTER).health)

        val hit = sim.tick().filterIsInstance<GameEvent.AttackHit>().single()
        assertEquals(ROBY, hit.attacker)
        assertEquals(1, hit.damage)
        assertEquals(3, hit.healthLeft)
        assertEquals(3, sim.state().actor(HUNTER).health)
    }

    @Test
    fun attackFailsIfTargetLeavesBeforeItLands() {
        val sim = Simulation(withCreature(), FirstChoice)
        sim.submit(Order(HUNTER, Move(B)))
        sim.runTicks(55) // Hunter's 6 s move lands on tick 59...
        sim.submit(Order(ROBY, Attack(HUNTER))) // ...before this 1 s attack lands on tick 64
        val events = sim.runTicks(20)

        val failed = events.filterIsInstance<GameEvent.ActionFailed>().single()
        assertEquals(FailReason.TARGET_NOT_HERE, failed.reason)
        assertTrue(events.none { it is GameEvent.AttackHit })
    }

    @Test
    fun actorGoesDownAtZeroHealthAndStopsActing() {
        val scenario = withCreature().let { s ->
            s.copy(actors = s.actors.map { if (it.id == HUNTER) it.copy(health = 1) else it })
        }
        val sim = Simulation(scenario, FirstChoice)
        sim.submit(Order(HUNTER, Move(B)))
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Attack(HUNTER))))
        val events = sim.runTicks(11)

        assertEquals(HUNTER, events.filterIsInstance<GameEvent.ActorDown>().single().actor)
        val hunter = sim.state().actor(HUNTER)
        assertTrue(hunter.isDown)
        assertNull(hunter.activity, "going down stops the move in progress")
        sim.runTicks(100)
        assertEquals(A, sim.state().actor(HUNTER).room, "down actors stay where they fell")
        assertTrue(sim.availableActions(HUNTER).isEmpty())
    }

    @Test
    fun cannotAttackSomeoneAlreadyDown() {
        val scenario = withCreature().let { s ->
            s.copy(actors = s.actors.map { if (it.id == HUNTER) it.copy(health = 1) else it })
        }
        val sim = Simulation(scenario, FirstChoice)
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Attack(HUNTER), Attack(HUNTER))))
        val failed = sim.runTicks(20).filterIsInstance<GameEvent.ActionFailed>().single()
        assertEquals(FailReason.TARGET_DOWN, failed.reason)
        assertTrue(sim.availableActions(ROBY).none { it.action is Attack }, "no one left to attack")
    }

    @Test
    fun killingTheCreatureWinsTheGame() {
        // One laser shot (2 damage) kills it outright, so there's no flee roll to get in the way.
        val sim = Simulation(withCreature(creatureHealth = 2), FirstChoice)
        sim.submit(Order(ROBY, listOf(Get(LASER_1), Move(B), Move(C), Attack(creature))))
        val events = sim.runTicks(200)

        val result = assertNotNull(sim.result)
        assertEquals(Ending.CREATURE_KILLED, result.ending)
        assertTrue(result.creatureKilled)
        assertEquals(listOf(ROBY, HUNTER), result.survivors)
        assertEquals(emptyList(), result.casualties)
        assertEquals(2 * 4 + 2 * 4, result.rating.total, "2 × health of each survivor")
        assertEquals(result, events.filterIsInstance<GameEvent.GameOver>().single().result)
        assertTrue(sim.tick().isEmpty(), "nothing happens after the game ends")
    }

    @Test
    fun crewCantAttackEachOtherByDefault() {
        val noFriendlyFire = withCreature().let { it.copy(rules = it.rules.copy(friendlyFire = false)) }
        val sim = Simulation(noFriendlyFire, FirstChoice)
        assertTrue(sim.availableActions(ROBY).none { it.action == Attack(HUNTER) }, "not offered")

        sim.submit(Order(ROBY, Attack(HUNTER)))
        assertEquals(FailReason.FRIENDLY_FIRE, sim.tick().filterIsInstance<GameEvent.ActionFailed>().single().reason)
    }

    @Test
    fun crewCanStillAttackTheCreatureWithoutFriendlyFire() {
        val noFriendlyFire = withCreature().let { s ->
            s.copy(
                rules = s.rules.copy(friendlyFire = false),
                actors = s.actors.map { if (it.id == ROBY) it.copy(room = C) else it },
            )
        }
        val sim = Simulation(noFriendlyFire, FirstChoice)
        assertEquals(listOf(Attack(creature)), sim.availableActions(ROBY).map { it.action }.filterIsInstance<Attack>())
    }

    @Test
    fun theItemInUseSetsTheDamage() {
        val sim = Simulation(withCreature(), FirstChoice)
        sim.submit(Order(ROBY, listOf(Get(LASER_1), Attack(HUNTER))))
        val hit = sim.runTicks(11).filterIsInstance<GameEvent.AttackHit>().single()
        assertEquals(2, hit.damage)
        assertEquals(2, sim.state().actor(HUNTER).health)
    }

    @Test
    fun crewWithNothingInUseDoNoDamage() {
        val sim = Simulation(withCreature(), FirstChoice)
        sim.submit(Order(ROBY, Attack(HUNTER)))
        val hit = sim.runTicks(10).filterIsInstance<GameEvent.AttackHit>().single()
        assertEquals(0, hit.damage)
        assertEquals(4, sim.state().actor(HUNTER).health)
        assertEquals("Roby hits Hunter, to no effect", sim.text.describe(hit))
    }

    @Test
    fun goingDownDropsWhatTheyHold() {
        val scenario = withCreature().let { s ->
            s.copy(actors = s.actors.map { if (it.id == HUNTER) it.copy(health = 1) else it })
        }
        val sim = Simulation(scenario, FirstChoice)
        sim.submit(Order(HUNTER, Get(LASER_1)))
        sim.submit(Order(ROBY, listOf(Get(SPANNER_1), Attack(HUNTER))))
        sim.runTicks(11)

        val hunter = sim.state().actor(HUNTER)
        assertTrue(hunter.isDown)
        assertNull(hunter.inUse)
        assertEquals(A, sim.state().item(LASER_1).room)
    }

    @Test
    fun wumpusAttacksCrewInItsRoom() {
        // Start Roby in the creature's room, so its first decision is to attack him.
        val scenario = withCreature(creatureBrain = "wumpus").let { s ->
            s.copy(actors = s.actors.map { if (it.id == ROBY) it.copy(room = C) else it })
        }
        val sim = Simulation(scenario, FirstChoice)
        val hits = sim.runTicks(50).filterIsInstance<GameEvent.AttackHit>()

        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.attacker == creature && it.target == ROBY })
    }

    @Test
    fun wumpusWandersWhenAlone() {
        val sim = Simulation(withCreature(creatureBrain = "wumpus"), FirstChoice)
        val moved = sim.runTicks(60).filterIsInstance<GameEvent.ActorMoved>().first { it.actor == creature }
        assertEquals(C, moved.from)
        assertEquals(B, moved.to)
    }

    @Test
    fun wumpusEventuallyDownsTheWholeCrew() {
        val sim = Simulation(withCreature(creatureBrain = "wumpus"), seed = 7)
        sim.submit(Order(ROBY, listOf(Move(B), Move(C))))
        sim.submit(Order(HUNTER, listOf(Move(B), Move(C))))
        sim.runTicks(2000)
        val result = assertNotNull(sim.result)
        assertEquals(Ending.CREW_GONE, result.ending)
        assertEquals(listOf(ROBY, HUNTER), result.casualties)
        assertEquals(0, result.rating.total)
    }

    @Test
    fun creatureMovesAreHiddenUnlessCrewSeeThem() {
        val sim = Simulation(withCreature(creatureBrain = "wumpus"), FirstChoice)
        val moved = sim.runTicks(60).filterIsInstance<GameEvent.ActorMoved>().first { it.actor == creature }
        assertEquals(org.tadams.nostromo.core.event.Visibility.Perceived(emptySet()), moved.visibility)
    }
}
