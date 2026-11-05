package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.action.Action.Get
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.WarningKind
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.ItemDef
import org.tadams.nostromo.core.scenario.ItemTypeDef
import org.tadams.nostromo.core.scenario.RoomFeature
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.Scenario
import org.tadams.nostromo.core.scenario.ScenarioValidator

/** Build step 5: room damage, fire, oxygen, the self-destruct, airlocks, hypersleep, launch and the endings. */
class ShipTest {

    private val creature = ActorId("creature")
    private val cat = ActorId("cat")
    private val extinguisher = ItemId("extinguisher")
    private val laser = ItemId("laser")

    private class Fixed(private val value: Int) : Dice {
        override fun roll(sides: Int) = value.coerceAtMost(sides - 1)
    }

    /**
     * The corridor a - b - c with special rooms: A has the destruct and airlock controls, B is an
     * airlock, C is the shuttle, launch pad and cryo vault, and A can burn. An extinguisher and a
     * laser lie in A. A brainless creature waits in [creatureRoom]. Moves take 5 s.
     */
    private fun scenario(
        creatureRoom: RoomId = C,
        extra: List<ActorDef> = emptyList(),
        rules: Rules = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0, ActionType.ATTACK to 1.0)),
    ): Scenario = corridor(rules).let { s ->
        s.copy(
            rooms = s.rooms.map {
                when (it.id) {
                    A -> it.copy(features = setOf(RoomFeature.DESTRUCT_CONTROLS, RoomFeature.AIRLOCK_CONTROLS, RoomFeature.FLAMMABLE, RoomFeature.KEY_SYSTEM))
                    B -> it.copy(features = setOf(RoomFeature.AIRLOCK))
                    C -> it.copy(features = setOf(RoomFeature.LAUNCH, RoomFeature.HYPERSLEEP, RoomFeature.INTRUDER_ENTRY))
                    else -> it
                }
            },
            actors = s.actors + ActorDef(creature, "Creature", ActorKind.CREATURE, creatureRoom, health = 15) + extra,
            itemTypes = listOf(
                ItemTypeDef("extinguisher", "Extinguisher", charges = 3, fightsFire = true),
                ItemTypeDef("laser", "Laser", damage = 2, charges = 8, roomDamagePercent = 30),
            ),
            items = listOf(ItemDef(extinguisher, "extinguisher", A), ItemDef(laser, "laser", A)),
        )
    }

    // ---- Self-destruct

    @Test
    fun theSelfDestructCountsDownInRealTimeAndCanBeOverridden() {
        val sim = Simulation(scenario(), Fixed(15))
        sim.submit(Order(ROBY, Action.ArmDestruct))
        sim.tick()
        assertEquals(4200L - 1, sim.state().ship.destructTicksLeft, "7:00 at 10 ticks a second, one tick gone")
        assertEquals("7:00", Time.clock(sim.state().ship.destructTicksLeft!!))

        sim.submit(Order(ROBY, Action.DisarmDestruct))
        sim.tick()
        assertNull(sim.state().ship.destructTicksLeft)
    }

    @Test
    fun theSelfDestructGoingOffEndsTheGame() {
        val short = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0), destructSeconds = 1.0)
        val sim = Simulation(scenario(rules = short), Fixed(15))
        sim.submit(Order(ROBY, Action.ArmDestruct))
        sim.runTicks(20)
        val result = assertNotNull(sim.result)
        assertEquals(Ending.SHIP_DESTROYED, result.ending)
        assertEquals(0, result.rating.crew)
        assertEquals(14, result.rating.time, "TIM only")
    }

    // ---- Room damage and fire

    @Test
    fun aFlammableRoomCatchesFireAt30PercentAndAnExtinguisherPutsItOut() {
        val sim = Simulation(scenario(creatureRoom = A), Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(laser), Action.Attack(creature))))
        val events = sim.runTicks(15)
        assertTrue(events.any { it is GameEvent.FireStarted && it.room == A }, "one laser shot: 30%")
        assertTrue(events.any { it is GameEvent.DamageWarning && it.kind == WarningKind.SYSTEM_FAILURE })
        assertTrue(sim.state().room(A).burning)

        sim.submit(Order(HUNTER, listOf(Get(extinguisher), Action.FightFire)))
        val out = sim.runTicks(3)
        assertTrue(out.any { it is GameEvent.FireOut }, "under 60%: one go")
        assertFalse(sim.state().room(A).burning)
    }

    @Test
    fun fireKeepsDamagingTheRoom() {
        val sim = Simulation(scenario(creatureRoom = A), Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(laser), Action.Attack(creature))))
        sim.runTicks(15)
        val before = sim.state().room(A).damagePercent
        sim.runTicks(80)
        assertEquals(before + 5, sim.state().room(A).damagePercent, "+5% every 7.4 s")
    }

    @Test
    fun aRoomAt100PercentBlowsUpTheShip() {
        val sim = Simulation(scenario(creatureRoom = A), Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(laser), Action.Attack(creature), Action.Attack(creature), Action.Attack(creature), Action.Attack(creature))))
        sim.runTicks(60)
        assertEquals(Ending.SHIP_DESTROYED, assertNotNull(sim.result).ending)
    }

    // ---- Oxygen

    @Test
    fun oxygenIsUsedPerBreathingCrewMember() {
        val sim = Simulation(scenario(), Fixed(15))
        sim.runTicks(100) // 10 s
        assertEquals(7500 - 23, sim.state().ship.oxygen, "2 crew × 1.13 a second × 10 s = 22.6")
    }

    @Test
    fun runningOutOfOxygenEndsTheGame() {
        val thin = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0), oxygenStart = 5)
        val sim = Simulation(scenario(rules = thin), Fixed(15))
        sim.runTicks(40)
        val result = assertNotNull(sim.result)
        assertEquals(Ending.OXYGEN_OUT, result.ending)
        assertEquals(0, result.rating.total, "not armed: 0")
    }

    // ---- Airlocks

    @Test
    fun blowingAnAirlockUsuallyKillsTheCreatureInside() {
        // Undamaged, it survives only on a roll under 2 in 16; rolling 15, it dies.
        val sim = Simulation(scenario(creatureRoom = B), Fixed(15))
        sim.submit(Order(ROBY, Action.BlowAirlock(B)))
        val events = sim.runTicks(2)
        assertTrue(events.any { it is GameEvent.AirlockBlown })
        assertEquals(Ending.CREATURE_KILLED, assertNotNull(sim.result).ending)
    }

    @Test
    fun aLuckyCreatureComesBackAtTheIntruderEntry() {
        val sim = Simulation(scenario(creatureRoom = B), Fixed(0))
        sim.submit(Order(ROBY, Action.BlowAirlock(B)))
        val events = sim.runTicks(2)
        assertTrue(events.any { it is GameEvent.IntruderEntering && it.room == C })
        assertEquals(C, sim.state().actor(creature).room)
        assertNull(sim.result)
    }

    @Test
    fun crewInABlownAirlockAreLostAndMovesInLandAtTheControls() {
        val sim = Simulation(scenario(), Fixed(15))
        sim.submit(Order(HUNTER, Move(B)))
        sim.runTicks(70)
        sim.submit(Order(ROBY, Action.BlowAirlock(B)))
        sim.runTicks(2)
        assertTrue(sim.state().actor(HUNTER).gone)

        sim.submit(Order(ROBY, Action.SealAirlock(B)))
        sim.runTicks(2)
        sim.submit(Order(ROBY, Action.BlowAirlock(B)))
        sim.runTicks(2)
        sim.submit(Order(ROBY, Move(B)))
        sim.runTicks(60)
        assertEquals(A, sim.state().actor(ROBY).room, "a blown airlock bounces you back to the controls")
    }

    // ---- Hypersleep and launch

    @Test
    fun hypersleepTakesCrewOutOfPlay() {
        val sim = Simulation(scenario(creatureRoom = A), Fixed(15))
        sim.submit(Order(ROBY, listOf(Move(B), Move(C), Action.Hypersleep)))
        sim.runTicks(110)
        assertTrue(sim.state().actor(ROBY).asleep)
        assertTrue(sim.availableActions(ROBY).isEmpty())
    }

    @Test
    fun launchNeedsEveryoneAboardIncludingTheCat() {
        val withCat = scenario(creatureRoom = A, extra = listOf(ActorDef(cat, "Spot", ActorKind.ANIMAL, B, health = 1)))
        val sim = Simulation(withCat, Fixed(15))
        sim.submit(Order(ROBY, listOf(Move(B), Move(C), Action.Launch)))
        val events = sim.runTicks(110)
        val countermanded = events.filterIsInstance<GameEvent.LaunchCountermanded>().single()
        assertEquals(setOf(HUNTER, cat), countermanded.missing.toSet())
        assertNull(sim.result)
    }

    @Test
    fun launchingWithTheDestructArmedScoresCrewAndTime() {
        val sim = Simulation(scenario(creatureRoom = A), Fixed(15))
        sim.submit(Order(ROBY, listOf(Action.ArmDestruct, Move(B), Move(C))))
        sim.submit(Order(HUNTER, listOf(Move(B), Move(C))))
        sim.runTicks(130)
        sim.submit(Order(ROBY, Action.Launch))
        sim.runTicks(2)
        val result = assertNotNull(sim.result)
        assertEquals(Ending.LAUNCHED, result.ending)
        assertEquals(setOf(ROBY, HUNTER), result.escaped.toSet())
        assertEquals(2 * 4 + 2 * 4, result.rating.crew)
        assertEquals(0, result.rating.damage, "DAM counts only for a kill")
    }

    @Test
    fun launchingWithoutTheDestructScoresNothing() {
        val sim = Simulation(scenario(creatureRoom = A), Fixed(15))
        sim.submit(Order(ROBY, listOf(Move(B), Move(C))))
        sim.submit(Order(HUNTER, listOf(Move(B), Move(C))))
        sim.runTicks(130)
        sim.submit(Order(ROBY, Action.Launch))
        sim.runTicks(2)
        assertEquals(0, assertNotNull(sim.result).rating.total)
    }

    // ---- Validation

    @Test
    fun airlocksNeedControls() {
        val bad = scenario().let { s -> s.copy(rooms = s.rooms.map { if (it.id == A) it.copy(features = emptySet()) else it }) }
        assertTrue(ScenarioValidator.problems(bad).any { "airlockControls" in it })
    }
}
