package org.tadams.nostromo.game

import kotlin.test.Test
import kotlin.test.assertEquals
import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.event.EventText
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.event.NoiseKind
import org.tadams.nostromo.core.event.Visibility
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.ActorDef
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.LinkDef
import org.tadams.nostromo.core.scenario.RoomDef
import org.tadams.nostromo.core.scenario.Scenario

class NarratorTest {
    private val a = RoomId("a")
    private val b = RoomId("b")
    private val roby = ActorId("roby")
    private val hunter = ActorId("hunter")
    private val creature = ActorId("creature")

    private val scenario = Scenario(
        id = "test",
        name = "Test",
        rooms = listOf(RoomDef(a, "Alpha"), RoomDef(b, "Bravo")),
        links = listOf(LinkDef(a, b)),
        actors = listOf(
            ActorDef(roby, "Roby", ActorKind.CREW, a),
            ActorDef(hunter, "Hunter", ActorKind.CREW, a),
            ActorDef(creature, "Creature", ActorKind.CREATURE, a),
        ),
    )

    private val narrator = Narrator(EventText(scenario))

    // Value classes like ActorId can't be varargs, so witnesses are passed as lists.
    private fun seen(by: List<ActorId> = emptyList()) = Visibility.Perceived(by.toSet())

    private fun hit(attacker: ActorId, target: ActorId, healthLeft: Int, by: List<ActorId> = listOf(target)) =
        GameEvent.AttackHit(0, attacker, target, 1, healthLeft, seen(by))

    private fun shown(vararg events: GameEvent) =
        events.flatMap(narrator::narrate).filterNot { it.hidden }.map { it.text }

    @Test
    fun creatureFightIsOneLinePlusOneWhenWounded() {
        val lines = shown(
            GameEvent.ActionStarted(0, creature, Action.Attack(roby), 60, Visibility.Hidden),
            hit(creature, roby, 3),
            hit(creature, roby, 2),
            hit(creature, roby, 1),
        )
        assertEquals(listOf("Creature is attacking Roby", "Roby is being wounded"), lines)
    }

    @Test
    fun everyEventStillHasALineForDebug() {
        val entries = listOf(
            GameEvent.ActionStarted(0, creature, Action.Attack(roby), 60, Visibility.Hidden),
            hit(creature, roby, 3),
            hit(creature, roby, 2),
            hit(creature, roby, 1),
        ).map { narrator.narrate(it) }
        assertEquals(listOf(1, 1, 1, 1), entries.map { it.size })
        assertEquals(listOf(true, false, false, true), entries.map { it.single().hidden })
    }

    @Test
    fun crewAttackOpensOnStartAndHitsAddNothing() {
        val lines = shown(
            GameEvent.ActionStarted(0, roby, Action.Attack(creature), 30, seen(listOf(roby))),
            hit(roby, creature, 3, listOf(roby)),
        )
        assertEquals(listOf("Roby is attacking Creature"), lines)
    }

    @Test
    fun fightAlreadyAtWoundedOpensWithoutASecondLine() {
        val lines = shown(hit(creature, roby, 2), hit(creature, roby, 1))
        assertEquals(listOf("Creature is attacking Roby"), lines)
    }

    @Test
    fun collapseSeenOnlyByTheVictimIsHidden() {
        assertEquals(emptyList(), shown(GameEvent.ActorDown(0, roby, seen(listOf(roby)))))
    }

    @Test
    fun collapseSeenByAnotherCrewMemberIsShown() {
        assertEquals(listOf("Roby is down"), shown(GameEvent.ActorDown(0, roby, seen(listOf(roby, hunter)))))
    }

    @Test
    fun movingAwayEndsTheFight() {
        val lines = shown(
            hit(creature, roby, 3),
            GameEvent.ActorMoved(0, roby, a, b, seen(listOf(roby))),
            GameEvent.ActorMoved(0, roby, b, a, seen(listOf(roby))),
            hit(creature, roby, 2),
        )
        assertEquals(
            listOf("Creature is attacking Roby", "Roby enters Bravo", "Roby enters Alpha", "Creature is attacking Roby"),
            lines,
        )
    }

    @Test
    fun doorSoundsArentLogged() {
        val entries = narrator.narrate(GameEvent.Noise(5, NoiseKind.DOOR, b, creature))
        assertEquals(listOf(true), entries.map { it.hidden }, "only the dimmed debug line")
    }

    @Test
    fun anUnseenGrilleIsHeardSomewhere() {
        val lines = shown(
            GameEvent.GrilleRemoved(5, creature, b, seen()),
            GameEvent.Noise(5, NoiseKind.GRILLE, b, creature),
        )
        assertEquals(listOf("A grille clangs somewhere"), lines)
    }

    @Test
    fun aGrilleSomeoneSawIsntHeardAsWell() {
        val lines = shown(
            GameEvent.GrilleRemoved(5, roby, a, seen(listOf(roby))),
            GameEvent.Noise(5, NoiseKind.GRILLE, a, roby),
        )
        assertEquals(listOf("Roby removes the grille in Alpha"), lines)
    }

    @Test
    fun theAndroidRevealIsNeverTold() {
        assertEquals(emptyList(), shown(GameEvent.AndroidRevealed(5, hunter)))
    }

    @Test
    fun unseenEventsAreASingleHiddenRawLine() {
        val entries = narrator.narrate(GameEvent.ActorMoved(0, creature, a, b, seen()))
        assertEquals(listOf(LogEntry(0, "Creature enters Bravo", hidden = true)), entries)
    }
}
