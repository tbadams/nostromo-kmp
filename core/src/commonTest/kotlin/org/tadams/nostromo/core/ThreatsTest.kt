package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.Action.Attack
import org.tadams.nostromo.core.action.Action.Catch
import org.tadams.nostromo.core.action.Action.Get
import org.tadams.nostromo.core.action.Action.Move
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.action.Command.Order
import org.tadams.nostromo.core.action.CommandResult
import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.NoiseKind
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.ItemId
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.ItemDef
import org.tadams.nostromo.core.scenario.ItemEffect
import org.tadams.nostromo.core.scenario.ItemTypeDef
import org.tadams.nostromo.core.scenario.RoleChoice
import org.tadams.nostromo.core.scenario.RolePick
import org.tadams.nostromo.core.scenario.RoleWeight
import org.tadams.nostromo.core.scenario.Roles
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.Scenario
import org.tadams.nostromo.core.scenario.ScenarioValidator

/** Build step 4: the full weapon table, driving the creature off, hidden roles, the cat, detectors and sounds. */
class ThreatsTest {

    private val creature = ActorId("creature")
    private val cat = ActorId("cat")

    private val laserType = ItemTypeDef("laser", "Laser", damage = 2, morale = 2, charges = 2, roomDamagePercent = 30)
    private val harpoonType = ItemTypeDef("harpoon", "Harpoon", damage = 5, morale = 2, charges = 1, roomDamagePercent = 75, effect = ItemEffect.KILL_ROOM)
    private val netType = ItemTypeDef("net", "Net", charges = 1, breaksWhenSpent = true, effect = ItemEffect.FREEZE, catchBonus = 4)
    private val boxType = ItemTypeDef("box", "Box", catchBonus = 0)
    private val trackerType = ItemTypeDef("tracker", "Tracker", damage = 1, charges = 1, breaksWhenSpent = true, detects = true)
    private val extinguisherType = ItemTypeDef("extinguisher", "Extinguisher", morale = 1, charges = 3, scares = true)

    private val laser = ItemId("laser")
    private val harpoon = ItemId("harpoon")
    private val net = ItemId("net")
    private val box = ItemId("box")
    private val tracker = ItemId("tracker")
    private val extinguisher = ItemId("extinguisher")

    /** Always rolls [value] (clamped to the die). */
    private class Fixed(private val value: Int) : Dice {
        override fun roll(sides: Int) = value.coerceAtMost(sides - 1)
    }

    /**
     * The corridor with every test item lying in A and a brainless creature (15 health) in A too, so
     * crew can strike at once. Attacks take 1 s, moves 5 s.
     */
    private fun scenario(
        creatureRoom: org.tadams.nostromo.core.model.RoomId = A,
        extra: List<ActorDef> = emptyList(),
        roles: Roles = Roles(),
        rules: Rules = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0, ActionType.ATTACK to 1.0)),
    ): Scenario = corridor(rules).copy(
        itemTypes = listOf(laserType, harpoonType, netType, boxType, trackerType, extinguisherType),
        items = listOf(
            ItemDef(laser, "laser", A), ItemDef(harpoon, "harpoon", A), ItemDef(net, "net", A),
            ItemDef(box, "box", A), ItemDef(tracker, "tracker", A), ItemDef(extinguisher, "extinguisher", A),
        ),
        roles = roles,
    ).let { it.copy(actors = it.actors + ActorDef(creature, "Creature", ActorKind.CREATURE, creatureRoom, health = 15) + extra) }

    // ---- Weapons

    @Test
    fun aLaserUsesChargesAndDamagesTheRoom() {
        val sim = Simulation(scenario(), Fixed(15)) // 15: never flees
        sim.submit(Order(ROBY, listOf(Get(laser), Attack(creature), Attack(creature), Attack(creature))))
        val events = sim.runTicks(40)

        assertEquals(2, events.count { it is GameEvent.AttackHit })
        assertEquals(60, sim.state().room(A).damagePercent, "30% per shot")
        assertEquals(15 - 4, sim.state().actor(creature).health)
        assertEquals(laser, events.filterIsInstance<GameEvent.ItemEmpty>().single().item, "the third shot finds it empty")
        assertEquals(0, sim.state().item(laser).charges)
    }

    @Test
    fun theHarpoonKillsEveryoneInTheRoomAndDestroysTheItems() {
        val sim = Simulation(scenario(), Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(harpoon), Attack(creature))))
        val events = sim.runTicks(15)

        assertEquals(15 - 5, sim.state().actor(creature).health)
        assertTrue(sim.state().actor(ROBY).isDown, "the shooter too")
        assertTrue(sim.state().actor(HUNTER).isDown)
        assertTrue(sim.state().items.isEmpty(), "every item in the room is gone")
        assertEquals(75, sim.state().room(A).damagePercent)
        assertTrue(events.count { it is GameEvent.ItemDestroyed } >= 6)
    }

    @Test
    fun theNetFreezesAndIsUsedUp() {
        val withBrain = scenario(creatureRoom = B).let { s ->
            s.copy(actors = s.actors.map { if (it.id == creature) it.copy(room = A, brain = "wumpus") else it })
        }
        val sim = Simulation(withBrain, Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(net), Attack(creature))))
        // Hunter, unarmed, keeps the creature busy hitting him until the net lands.
        val events = sim.runTicks(12)
        assertIs<GameEvent.Frozen>(events.single { it is GameEvent.Frozen })
        assertTrue(sim.state().items.none { it.id == net }, "used up")
        assertTrue(sim.state().actor(creature).frozen)

        val frozenFor = sim.runTicks(150)
        assertTrue(frozenFor.none { it is GameEvent.ActionStarted && it.actor == creature }, "does nothing while netted")
        sim.runTicks(100)
        assertFalse(sim.state().actor(creature).frozen)
    }

    @Test
    fun aTrackerBreaksWhenUsedAsAWeapon() {
        val sim = Simulation(scenario(), Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(tracker), Attack(creature))))
        val events = sim.runTicks(15)
        assertEquals(1, events.filterIsInstance<GameEvent.AttackHit>().single().damage)
        assertTrue(sim.state().items.none { it.id == tracker })
    }

    // ---- Driving the creature off

    @Test
    fun aHitCanDriveTheCreatureOffAndLiftsMorale() {
        // Roby (morale 3) with the laser (+2) is Confident: score 1, and a roll of 0 is under it.
        val sim = Simulation(scenario(), Fixed(0))
        sim.submit(Order(ROBY, listOf(Get(laser), Attack(creature))))
        val events = sim.runTicks(15)

        val fled = events.filterIsInstance<GameEvent.CreatureFled>().single()
        assertEquals(listOf(ROBY), fled.attackers)
        assertEquals(B, sim.state().actor(creature).room)
        assertEquals(3 + 1 + 2 + (3 - 2), sim.state().actor(ROBY).morale, "+1 base for driving it off; laser +2; Hunter's company")
    }

    @Test
    fun theExtinguisherScaresWithoutHurting() {
        val sim = Simulation(scenario(), Fixed(0))
        sim.submit(Order(ROBY, listOf(Get(extinguisher), Attack(creature))))
        // Roby 3 + 1 (extinguisher) + 1 (Hunter) = 5: Confident.
        val events = sim.runTicks(15)
        assertEquals(0, events.filterIsInstance<GameEvent.AttackHit>().single().damage)
        assertTrue(events.any { it is GameEvent.CreatureFled })
        assertEquals(15, sim.state().actor(creature).health)
    }

    @Test
    fun aRollOf15NeverDrivesItOff() {
        val sim = Simulation(scenario(), Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(laser), Attack(creature))))
        assertTrue(sim.runTicks(15).none { it is GameEvent.CreatureFled })
    }

    // ---- Wounds

    @Test
    fun woundedCrewAreSlower() {
        val hurt = scenario().let { s -> s.copy(actors = s.actors.map { if (it.id == ROBY) it.copy(health = 1) else it }) }
        val sim = Simulation(hurt, Fixed(0))
        sim.submit(Order(ROBY, Move(B)))
        val started = sim.tick().filterIsInstance<GameEvent.ActionStarted>().single()
        assertEquals(50 + 36, started.totalTicks, "5 s move + 3.6 s at health 1")
    }

    // ---- Hidden roles

    private fun androidIsHunter() = Roles(android = RoleChoice(RolePick.WEIGHTED, listOf(RoleWeight(HUNTER, 1))))

    @Test
    fun theAndroidWontAttackTheCreatureBeforeTheReveal() {
        val sim = Simulation(scenario(roles = androidIsHunter()), Fixed(0))
        sim.submit(Order(HUNTER, listOf(Get(laser), Attack(creature))))
        val events = sim.runTicks(15)
        assertTrue(events.none { it is GameEvent.AttackHit && it.attacker == HUNTER })
        assertEquals(Move(B), sim.state().actor(HUNTER).activity?.action, "the attack order became a move away")
    }

    @Test
    fun theAndroidTurnsOnceTheCreatureIsHurtEnough() {
        val rules = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0, ActionType.ATTACK to 1.0), androidRevealDamage = 2)
        val sim = Simulation(scenario(roles = androidIsHunter(), rules = rules), Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(laser), Attack(creature))))
        val events = sim.runTicks(40)

        val reveal = events.filterIsInstance<GameEvent.AndroidRevealed>().single()
        assertEquals(HUNTER, reveal.actor)
        assertEquals(Visibility.Hidden, reveal.visibility, "the player isn't told")
        assertFalse(sim.state().actor(HUNTER).takesOrders)
        assertTrue(sim.availableActions(HUNTER).isEmpty())
        assertTrue(events.any { it is GameEvent.AttackHit && it.attacker == HUNTER && it.target == ROBY }, "it attacks the crew")
        assertTrue(sim.availableActions(ROBY).any { it.action == Attack(HUNTER) }, "and the crew can hit back")
    }

    @Test
    fun theHostIsOutOfPlay() {
        val roles = Roles(host = RoleChoice(RolePick.WEIGHTED, listOf(RoleWeight(HUNTER, 1))))
        val sim = Simulation(scenario(roles = roles), Fixed(0))
        assertTrue(sim.state().actors.none { it.id == HUNTER })
        assertEquals(HUNTER, sim.tick().filterIsInstance<GameEvent.Hatched>().single().host)
    }

    @Test
    fun theAndroidIsNeverTheHost() {
        // Hunter is the only candidate for both: the host is picked first, so there's no android.
        val onlyHunter = RoleChoice(RolePick.WEIGHTED, listOf(RoleWeight(HUNTER, 1)))
        val sim = Simulation(scenario(roles = Roles(android = onlyHunter, host = onlyHunter)), Fixed(0))
        assertTrue(sim.state().actors.none { it.id == HUNTER })
        sim.submit(Order(ROBY, Get(laser)))
        sim.runTicks(2)
        assertTrue(sim.state().actor(ROBY).takesOrders)
    }

    @Test
    fun ordersToNonCrewAreRejected() {
        val sim = Simulation(scenario(), Fixed(0))
        assertIs<CommandResult.Rejected>(sim.submit(Order(creature, Move(B))))
    }

    // ---- The cat

    private fun withCat(brain: String? = null) = scenario(creatureRoom = C, extra = listOf(ActorDef(cat, "Dan", ActorKind.ANIMAL, A, brain = brain, health = 1)))

    @Test
    fun catchingNeedsABoxOrNet() {
        val sim = Simulation(withCat(), Fixed(15))
        sim.submit(Order(ROBY, Catch(cat)))
        assertEquals(FailReason.NEEDS_CATCHER, sim.tick().filterIsInstance<GameEvent.ActionFailed>().single().reason)
        assertTrue(sim.availableActions(ROBY).none { it.action is Attack && it.action.target == cat }, "the cat isn't a target")
    }

    @Test
    fun aGoodRollCatchesTheCatAndItTravelsWithTheHolder() {
        val sim = Simulation(withCat(), Fixed(15)) // 15 ≥ Roby's default difficulty 14
        sim.submit(Order(ROBY, listOf(Get(box), Catch(cat), Move(B))))
        val events = sim.runTicks(60)
        assertEquals(cat, events.filterIsInstance<GameEvent.AnimalCaught>().single().animal)
        assertTrue(sim.state().actor(cat).caught)
        assertEquals(B, sim.state().actor(cat).room)
    }

    @Test
    fun aMissMakesTheCatBolt() {
        val sim = Simulation(withCat(), Fixed(0))
        sim.submit(Order(ROBY, listOf(Get(box), Catch(cat))))
        val events = sim.runTicks(3)
        assertTrue(events.any { it is GameEvent.AnimalEscaped })
        assertEquals(B, sim.state().actor(cat).room)
    }

    // ---- Detectors and sounds

    @Test
    fun aTrackerReadsMovementNextDoorButNotInItsOwnRoom() {
        // Creature in B, next to A. Hunter's in A with Roby, but the tracker ignores its own room.
        val sim = Simulation(scenario(creatureRoom = B), Fixed(15))
        sim.submit(Order(ROBY, Get(tracker)))
        val reading = sim.runTicks(2).filterIsInstance<GameEvent.DetectorReading>().single()
        assertTrue(reading.movement)
        assertEquals(Visibility.Perceived(setOf(ROBY)), reading.visibility)
    }

    @Test
    fun anAllClearTrackerLiftsMoraleHere() {
        val sim = Simulation(scenario(creatureRoom = C), Fixed(15))
        val before = sim.state().actor(HUNTER).morale!!
        sim.submit(Order(ROBY, Get(tracker)))
        val reading = sim.runTicks(2).filterIsInstance<GameEvent.DetectorReading>().single()
        assertFalse(reading.movement)
        assertEquals(before + 1, sim.state().actor(HUNTER).morale)
    }

    @Test
    fun grillesAndDoorsAreHeardShipWide() {
        val sim = Simulation(scenario(creatureRoom = C), Fixed(15))
        sim.submit(Order(ROBY, Move(B)))
        val noise = sim.runTicks(50).filterIsInstance<GameEvent.Noise>().single()
        assertEquals(NoiseKind.DOOR, noise.kind)
        assertEquals(Visibility.Ship, noise.visibility)
    }

    // ---- Validation

    @Test
    fun rolesMustNameCrew() {
        val bad = scenario(roles = Roles(android = RoleChoice(RolePick.WEIGHTED, listOf(RoleWeight(creature, 1)))))
        assertTrue(ScenarioValidator.problems(bad).any { "isn't crew" in it })
        assertNull(ScenarioValidator.problems(scenario()).firstOrNull())
    }

    @Test
    fun theGameIgnoresARevealedAndroidWhenCheckingForTheEnd() {
        val rules = Rules(actionSeconds = mapOf(ActionType.MOVE to 5.0, ActionType.ATTACK to 1.0), androidRevealDamage = 2)
        val oneCrewPlusAndroid = scenario(roles = androidIsHunter(), rules = rules).let { s ->
            s.copy(actors = s.actors.map { if (it.id == ROBY) it.copy(health = 1) else it })
        }
        val sim = Simulation(oneCrewPlusAndroid, Fixed(15))
        sim.submit(Order(ROBY, listOf(Get(laser), Attack(creature))))
        sim.runTicks(100)
        val result = assertNotNull(sim.result)
        assertEquals(Ending.CREW_GONE, result.ending, "Roby's down; the android doesn't count as crew left")
        assertEquals(HUNTER, result.android)
    }
}
