package org.tadams.nostromo.core.ai

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.TickScope

/**
 * Decides what an AI-driven actor does next. Asked only when the actor is idle.
 *
 * A brain only chooses: [options] is everything the actor can do right now (from
 * [org.tadams.nostromo.core.action.Options]), the same list the player's action bar shows for crew.
 * The rules for what's possible live in the action definitions, not here.
 */
internal fun interface Brain {
    /** One of [options] for [actor] to do next, or null to stay idle this tick. [world] is for judging, not for inventing actions. */
    fun decide(actor: Actor, options: List<Action>, world: World, scope: TickScope): Action?
}

/** Every brain a scenario can name in `ActorDef.brain`. */
internal object Brains {
    private val all: Map<String, Brain> = mapOf(
        "wumpus" to Wumpus,
        "android" to AndroidBrain,
        "cat" to CatBrain,
    )

    val ids: Set<String> get() = all.keys

    fun get(id: String): Brain = all.getValue(id)
}
