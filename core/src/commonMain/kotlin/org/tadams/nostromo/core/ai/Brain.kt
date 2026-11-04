package org.tadams.nostromo.core.ai

import org.tadams.nostromo.core.action.Action
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.systems.TickScope

/** Decides what an AI-driven actor does next. Asked only when the actor is idle. */
internal fun interface Brain {
    /** The next action for [actor], or null to stay idle this tick. */
    fun decide(actor: Actor, world: World, scope: TickScope): Action?
}

/** Every brain a scenario can name in `ActorDef.brain`. */
internal object Brains {
    private val all: Map<String, Brain> = mapOf(
        "wumpus" to Wumpus,
    )

    val ids: Set<String> get() = all.keys

    fun get(id: String): Brain = all.getValue(id)
}
