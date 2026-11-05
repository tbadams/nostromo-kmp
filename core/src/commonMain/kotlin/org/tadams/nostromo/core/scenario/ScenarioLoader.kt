package org.tadams.nostromo.core.scenario

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.tadams.nostromo.core.ai.Brains
import org.tadams.nostromo.core.model.RoomId
import org.tadams.nostromo.core.rng.ODDS

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
        val kinds = scenario.rooms.associate { it.id to it.kind }
        val grilles = scenario.links.filter { it.kind == LinkKind.GRILLE }
        for (grille in grilles) {
            val ends = setOf(kinds[grille.from], kinds[grille.to])
            if (ends != setOf(RoomKind.ROOM, RoomKind.DUCT)) add("Grille '${grille.from}'-'${grille.to}' must join a room and a duct")
        }
        duplicates(grilles.flatMap { listOf(it.from, it.to) }).forEach { add("Location '$it' has more than one grille") }
        for (room in scenario.rooms) {
            if (room.grilleRemoved && grilles.none { room.id == it.from || room.id == it.to }) {
                add("Room '${room.id}' sets grilleRemoved but has no grille")
            }
            if (room.grilleRemoved && room.kind == RoomKind.DUCT) add("Duct '${room.id}' sets grilleRemoved; set it on the room")
        }
        for (actor in scenario.actors) {
            if (actor.room !in known) add("Actor '${actor.id}' starts in unknown room '${actor.room}'")
            if (actor.speedPenaltySeconds < 0) add("Actor '${actor.id}' has negative speedPenaltySeconds")
            if (actor.health <= 0) add("Actor '${actor.id}' health must be positive")
            if (actor.morale < 0) add("Actor '${actor.id}' morale must not be negative")
            val brain = actor.brain
            if (brain != null && brain !in Brains.ids) add("Actor '${actor.id}' has unknown brain '$brain'")
            for ((type, seconds) in actor.actionSeconds) {
                if (seconds <= 0) add("Actor '${actor.id}' actionSeconds for $type must be positive")
            }
        }
        duplicates(scenario.itemTypes.map { it.id }).forEach { add("Duplicate item type '$it'") }
        duplicates(scenario.items.map { it.id }).forEach { add("Duplicate item id '$it'") }
        for (type in scenario.itemTypes) {
            if (type.damage < 0) add("Item type '${type.id}' damage must not be negative")
            if ((type.charges ?: 1) < 1) add("Item type '${type.id}' charges must be at least 1 (or left out for unlimited)")
            if (type.roomDamagePercent < 0) add("Item type '${type.id}' roomDamagePercent must not be negative")
            if ((type.catchBonus ?: 0) < 0) add("Item type '${type.id}' catchBonus must not be negative")
        }
        val crewIds = scenario.actors.filter { it.kind == ActorKind.CREW }.map { it.id }.toSet()
        for ((role, choice) in listOf("android" to scenario.roles.android, "host" to scenario.roles.host)) {
            for (weight in choice.weights) {
                if (weight.actor !in crewIds) add("roles.$role names '${weight.actor}', who isn't crew")
                if (weight.weight < 0) add("roles.$role weight for '${weight.actor}' must not be negative")
            }
            if (choice.pick != RolePick.WEIGHTED && choice.weights.isNotEmpty()) add("roles.$role has weights but pick isn't \"weighted\"")
        }
        if (scenario.roles.host.pick != RolePick.NONE && crewIds.size < 2) add("roles.host needs at least two crew, so someone is left")
        val types = scenario.itemTypes.map { it.id }.toSet()
        for (item in scenario.items) {
            if (item.type !in types) add("Item '${item.id}' has unknown type '${item.type}'")
            if (item.room !in known) add("Item '${item.id}' starts in unknown room '${item.room}'")
        }
        if (scenario.rules.attackDamage <= 0) add("rules.attackDamage must be positive")
        if (scenario.rules.prowlDamagePercent < 0) add("rules.prowlDamagePercent must not be negative")
        with(scenario.rules) {
            if (roomCapacity < 1 || ductCapacity < 1) add("rules.roomCapacity and rules.ductCapacity must be at least 1")
            if (crowdedWaitSeconds <= 0) add("rules.crowdedWaitSeconds must be positive")
            if (ductMorale < 0 || bodyFoundMorale < 0) add("rules.ductMorale and rules.bodyFoundMorale must not be negative")
        }
        if (scenario.rules.creatureGrilleOdds !in 0..ODDS) add("rules.creatureGrilleOdds must be 0-$ODDS")
        if (scenario.rules.creatureGrilleMoveOdds !in 0..ODDS) add("rules.creatureGrilleMoveOdds must be 0-$ODDS")
        if (scenario.rules.fleeDamageDivisor < 1) add("rules.fleeDamageDivisor must be at least 1")
        if (scenario.rules.netFreezeSeconds <= 0) add("rules.netFreezeSeconds must be positive")
        with(scenario.rules) {
            if (destructSeconds <= 0 || fireIntervalSeconds <= 0) add("rules.destructSeconds and rules.fireIntervalSeconds must be positive")
            if (oxygenStart <= 0 || oxygenPerCrewSecond < 0) add("rules.oxygenStart must be positive and rules.oxygenPerCrewSecond not negative")
            if (fireFightsNeeded.isEmpty() || fireFightsNeeded.any { it.uses < 1 }) add("rules.fireFightsNeeded must list at least one entry, each needing 1+ uses")
            if (airlockSurvivalOdds !in 0..ODDS) add("rules.airlockSurvivalOdds must be 0-$ODDS")
        }
        for (room in scenario.rooms) {
            if (room.kind == RoomKind.DUCT && room.features.isNotEmpty()) add("Duct '${room.id}' has features; only rooms can")
        }
        val airlocks = scenario.rooms.count { RoomFeature.AIRLOCK in it.features }
        val airlockControls = scenario.rooms.count { RoomFeature.AIRLOCK_CONTROLS in it.features }
        if (airlocks > 0 && airlockControls == 0) add("Scenario has airlocks but no room with airlockControls")
        for (feature in listOf(RoomFeature.AIRLOCK_CONTROLS, RoomFeature.INTRUDER_ENTRY)) {
            if (scenario.rooms.count { feature in it.features } > 1) add("More than one room has $feature")
        }
        if (scenario.rules.prowlWeight < 0) add("rules.prowlWeight must not be negative")
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
