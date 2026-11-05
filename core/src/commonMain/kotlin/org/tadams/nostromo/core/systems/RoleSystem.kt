package org.tadams.nostromo.core.systems

import org.tadams.nostromo.core.TickScope
import org.tadams.nostromo.core.event.GameEvent
import org.tadams.nostromo.core.model.ActorId
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.rng.Dice
import org.tadams.nostromo.core.scenario.ActorKind
import org.tadams.nostromo.core.scenario.RoleChoice
import org.tadams.nostromo.core.scenario.RolePick
import org.tadams.nostromo.core.scenario.Scenario

/** Hidden roles (docs/mechanics.md §8): handing them out at the start, and the android's reveal. */
internal object RoleSystem {

    /** Who got which role. Either may be null. */
    data class Assignment(val host: ActorId?, val android: ActorId?)

    /**
     * Picks the host first, then the android from the rest, using [dice] directly (before tick 0, so
     * there's no tick scope to log the rolls in).
     */
    fun assign(scenario: Scenario, dice: Dice): Assignment {
        val crew = scenario.actors.filter { it.kind == ActorKind.CREW }.map { it.id }
        val host = pick(scenario.roles.host, crew, dice)
        val android = pick(scenario.roles.android, crew - setOfNotNull(host), dice)
        return Assignment(host, android)
    }

    private fun pick(choice: RoleChoice, crew: List<ActorId>, dice: Dice): ActorId? {
        val weighted = when (choice.pick) {
            RolePick.NONE -> return null
            RolePick.ANY -> crew.map { it to 1 }
            RolePick.WEIGHTED -> choice.weights.filter { it.actor in crew && it.weight > 0 }.map { it.actor to it.weight }
        }
        val total = weighted.sumOf { it.second }
        if (total == 0) return null
        var r = dice.roll(total)
        for ((actor, weight) in weighted) {
            if (r < weight) return actor
            r -= weight
        }
        return null
    }

    /**
     * Each tick: once a creature has taken [org.tadams.nostromo.core.scenario.Rules.androidRevealDamage],
     * the android (if still up) turns. It drops whatever it was doing, stops taking orders, and from
     * then on its brain ("android") attacks crew it finds and otherwise wanders.
     */
    fun run(world: World, scope: TickScope) {
        val android = world.actors.values.firstOrNull { it.isAndroid && !it.revealed && !it.outOfPlay } ?: return
        val hurt = world.actors.values.any {
            it.kind == ActorKind.CREATURE && it.maxHealth - it.health >= scope.rules.androidRevealDamage
        }
        if (!hurt) return
        android.revealed = true
        android.brain = "android"
        android.current = null
        android.queue.clear()
        scope.emit(GameEvent.AndroidRevealed(scope.tick, android.id))
    }
}
