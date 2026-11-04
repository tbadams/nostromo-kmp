package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.tadams.nostromo.core.action.ActionType
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.scenario.LinkDef
import org.tadams.nostromo.core.scenario.LinkKind
import org.tadams.nostromo.core.scenario.RoomDef
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.ScenarioException
import org.tadams.nostromo.core.scenario.ScenarioLoader
import org.tadams.nostromo.core.scenario.ScenarioValidator

class ScenarioLoaderTest {

    private val defaultRules = """, "rules": { "actionSeconds": { "move": 3.0 } }"""

    private fun json(rules: String = defaultRules) = """
        {
          "id": "mini",
          "name": "Mini ship",
          "rooms": [
            { "id": "bridge", "name": "Bridge", "deck": "upper" },
            { "id": "bridge-duct", "name": "Bridge duct", "kind": "duct" }
          ],
          "links": [ { "from": "bridge", "to": "bridge-duct", "kind": "grille" } ],
          "actors": [
            { "id": "roby", "name": "Roby", "kind": "crew", "room": "bridge", "speedPenaltySeconds": 0.5 },
            { "id": "creature", "name": "Creature", "kind": "creature", "room": "bridge-duct", "brain": "wumpus",
              "actionSeconds": { "move": 6.0 } }
          ]$rules
        }
    """.trimIndent()

    @Test
    fun loadsScenarioFromJson() {
        val scenario = ScenarioLoader.fromJson(json())
        assertEquals("Mini ship", scenario.name)
        assertEquals(RoomKind.DUCT, scenario.rooms[1].kind)
        assertEquals(LinkKind.GRILLE, scenario.links.single().kind)
        assertEquals("wumpus", scenario.actors[1].brain)
        assertEquals(0.5, scenario.actors[0].speedPenaltySeconds)
        assertEquals(mapOf(ActionType.MOVE to 6.0), scenario.actors[1].actionSeconds)
        assertEquals(3.0, scenario.rules.baseSeconds(ActionType.MOVE))
    }

    @Test
    fun rulesDefaultWhenOmitted() {
        val scenario = ScenarioLoader.fromJson(json(rules = ""))
        assertEquals(ActionType.MOVE.defaultSeconds, scenario.rules.baseSeconds(ActionType.MOVE))
    }

    @Test
    fun unknownKeysAreRejected() {
        assertFailsWith<ScenarioException> {
            ScenarioLoader.fromJson(json().replace("\"deck\"", "\"dekc\""))
        }
    }

    @Test
    fun reportsDanglingReferences() {
        val bad = corridor().copy(links = corridor().links + LinkDef(RoomId("c"), RoomId("nowhere")))
        val problems = ScenarioValidator.problems(bad)
        assertTrue(problems.any { "unknown room 'nowhere'" in it }, problems.toString())
    }

    @Test
    fun reportsDisconnectedRooms() {
        val bad = corridor().copy(rooms = corridor().rooms + RoomDef(RoomId("island"), "Island"))
        val problems = ScenarioValidator.problems(bad)
        assertEquals(listOf("Room 'island' is not connected to the rest of the map"), problems)
    }

    @Test
    fun reportsNonPositiveDurations() {
        val bad = corridor(Rules(actionSeconds = mapOf(ActionType.MOVE to 0.0)))
        assertEquals(listOf("rules.actionSeconds for MOVE must be positive"), ScenarioValidator.problems(bad))
    }

    @Test
    fun reportsDuplicateIds() {
        val bad = corridor().copy(actors = corridor().actors + corridor().actors.first())
        assertEquals(listOf("Duplicate actor id 'roby'"), ScenarioValidator.problems(bad))
    }
}
