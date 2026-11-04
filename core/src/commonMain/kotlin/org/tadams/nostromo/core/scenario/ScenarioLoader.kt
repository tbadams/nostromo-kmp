package org.tadams.nostromo.core.scenario

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.tadams.nostromo.core.ai.Brains
import org.tadams.nostromo.core.model.RoomId

/** A scenario couldn't be loaded or failed validation; [problems] lists every issue found. */
class ScenarioException(val problems: List<String>) :
    Exception("Invalid scenario:\n" + problems.joinToString("\n") { "- $it" })

/** Reads scenarios from JSON. */
object ScenarioLoader {
    // Strict by default: unknown keys are errors, so typos in hand-written scenarios get caught.
    private val json = Json

    /** Parses and validates [text]; throws [ScenarioException] if anything is wrong. */
    fun fromJson(text: String): Scenario {
        val scenario = try {
            json.decodeFromString<Scenario>(text)
        } catch (e: SerializationException) {
            throw ScenarioException(listOf(e.message ?: "Malformed scenario JSON"))
        }
        ScenarioValidator.check(scenario)
        return scenario
    }
}

/** Checks a scenario is self-consistent: unique ids, known rooms, one connected map. */
object ScenarioValidator {

    /** Throws [ScenarioException] if [problems] finds anything. */
    fun check(scenario: Scenario) {
        val problems = problems(scenario)
        if (problems.isNotEmpty()) throw ScenarioException(problems)
    }

    /** Every problem with [scenario], or empty if it's valid. */
    fun problems(scenario: Scenario): List<String> = buildList {
        val roomIds = scenario.rooms.map { it.id }
        if (roomIds.isEmpty()) add("Scenario has no rooms")
        duplicates(roomIds).forEach { add("Duplicate room id '$it'") }
        duplicates(scenario.actors.map { it.id }).forEach { add("Duplicate actor id '$it'") }

        val known = roomIds.toSet()
        for ((from, to) in scenario.links) {
            if (from !in known) add("Link from unknown room '$from'")
            if (to !in known) add("Link to unknown room '$to'")
            if (from == to) add("Link from '$from' to itself")
        }
        for (actor in scenario.actors) {
            if (actor.room !in known) add("Actor '${actor.id}' starts in unknown room '${actor.room}'")
            if (actor.speedPenaltySeconds < 0) add("Actor '${actor.id}' has negative speedPenaltySeconds")
            if (actor.health <= 0) add("Actor '${actor.id}' health must be positive")
            val brain = actor.brain
            if (brain != null && brain !in Brains.ids) add("Actor '${actor.id}' has unknown brain '$brain'")
            for ((type, seconds) in actor.actionSeconds) {
                if (seconds <= 0) add("Actor '${actor.id}' actionSeconds for $type must be positive")
            }
        }
        if (scenario.rules.attackDamage <= 0) add("rules.attackDamage must be positive")
        for ((type, seconds) in scenario.rules.actionSeconds) {
            if (seconds <= 0) add("rules.actionSeconds for $type must be positive")
        }

        if (roomIds.isNotEmpty()) {
            val unreachable = known - reachable(roomIds.first(), scenario.links)
            unreachable.forEach { add("Room '$it' is not connected to the rest of the map") }
        }
    }

    private fun <T> duplicates(items: List<T>): Set<T> =
        items.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    /** Rooms reachable from [start], ignoring link direction: is the map one piece? */
    private fun reachable(start: RoomId, links: List<LinkDef>): Set<RoomId> {
        val neighbours = mutableMapOf<RoomId, MutableSet<RoomId>>()
        for (link in links) {
            neighbours.getOrPut(link.from) { mutableSetOf() } += link.to
            neighbours.getOrPut(link.to) { mutableSetOf() } += link.from
        }
        val seen = mutableSetOf(start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            for (next in neighbours[frontier.removeFirst()].orEmpty()) {
                if (seen.add(next)) frontier.addLast(next)
            }
        }
        return seen
    }
}
