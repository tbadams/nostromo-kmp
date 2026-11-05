package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules

/** A specific action an actor could take now, and why not if they can't ([problem] null means doable). */
internal data class Option(val action: Action, val problem: FailReason?) {
    val isValid get() = problem == null
}

/** Answers "what can this actor do right now?" for the player's action bar and for brains alike. */
internal object Options {
    /**
     * Every action [actor] is capable of from where they are, each checked against the current
     * world. Empty if they're down. Order is stable (by action type, then candidate order), so
     * menus don't jump around and seeded AI choices are reproducible.
     */
    fun forActor(actor: Actor, world: World, rules: Rules): List<Option> {
        if (actor.outOfPlay) return emptyList()
        return ActionDefs.all
            .filter { it.type in actor.capabilities }
            .flatMap { def -> def.candidates(actor, world) }
            .map { Option(it, ActionDefs.check(actor, world, it, rules)) }
    }

    /** Just the ones [actor] could start right now. */
    fun valid(actor: Actor, world: World, rules: Rules): List<Action> =
        forActor(actor, world, rules).filter { it.isValid }.map { it.action }
}
