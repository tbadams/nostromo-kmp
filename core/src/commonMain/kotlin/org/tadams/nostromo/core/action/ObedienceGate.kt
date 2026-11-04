package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.scenario.RoomKind
import org.tadams.nostromo.core.systems.MoraleSystem

/**
 * Whether crew obey an order, checked when each action is about to start (docs/mechanics.md §5).
 * Unlike [ActionDef.check], this isn't used to filter what's
 * offered: the action bar still shows the order, and the crew member refuses it, as in the original.
 *
 * Crew left without orders are never checked, so a frightened crew member just stands there.
 *
 * TODO panic at morale 0 (drop everything, run to a random neighbouring location instead), and the
 *  original's in-duct panic (rip out the grille and climb out). Rest of build step 3.
 */
internal object ObedienceGate {

    /** Why [actor] refuses [action], or null if they'll do it. Only crew can refuse. */
    fun refusal(actor: Actor, world: World, action: Action, rules: Rules): FailReason? {
        if (!actor.isCrew) return null
        val needsNerve = when (action) {
            is Action.Attack -> true
            // Into or along ducts; climbing out of one always works, so no one is trapped.
            is Action.Move -> world.room(action.to).kind == RoomKind.DUCT
            else -> false
        }
        return if (needsNerve && MoraleSystem.effective(actor, world, rules) < rules.obedienceMorale) FailReason.TOO_SCARED else null
    }
}
