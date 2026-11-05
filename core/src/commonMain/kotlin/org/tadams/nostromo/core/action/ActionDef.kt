package org.tadams.nostromo.core.action

import org.tadams.nostromo.core.event.FailReason
import org.tadams.nostromo.core.model.Actor
import org.tadams.nostromo.core.model.World
import org.tadams.nostromo.core.scenario.Rules
import org.tadams.nostromo.core.TickScope

/**
 * Everything about one type of action: which specific actions an actor could take ([candidates]),
 * whether one is allowed right now ([check]), and what it does when it finishes ([complete]).
 *
 * This is the only place an action's rules live. The action bar, brains, and the scheduler all go
 * through it, so what's offered, what's allowed and what's applied can't drift apart. Durations
 * are generic and live in [org.tadams.nostromo.core.systems.ActionSystem]; wording lives in
 * [org.tadams.nostromo.core.event.EventText].
 */
internal interface ActionDef<A : Action> {
    val type: ActionType

    /** Every specific action of this type [actor] could attempt from where they are, valid or not. */
    fun candidates(actor: Actor, world: World): List<A>

    /** Why [actor] can't do [action] right now under [rules], or null if they can. Checked when it's offered, starts and lands. */
    fun check(actor: Actor, world: World, action: A, rules: Rules): FailReason?

    /** Applies [action]'s effect. Called only after [check] passes again at completion. */
    fun complete(actor: Actor, world: World, action: A, scope: TickScope)
}

/**
 * Looks up the definition for each action by its [ActionType]. The one place an [Action] is
 * matched to its rules, so adding an action means writing its [ActionDef] and listing it in [all].
 */
internal object ActionDefs {
    /** In menu order: the most urgent options first. */
    val all: List<ActionDef<*>> = listOf(AttackDef, MoveDef, RemoveGrilleDef, SwapDef, GetDef, DropDef, ProwlDef)

    private val byType: Map<ActionType, ActionDef<*>> = all.associateBy { it.type }

    init {
        require(all.map { it.type }.sorted() == ActionType.entries.sorted()) { "Every ActionType needs exactly one ActionDef" }
    }

    fun check(actor: Actor, world: World, action: Action, rules: Rules): FailReason? =
        defFor(action).check(actor, world, action, rules)

    fun complete(actor: Actor, world: World, action: Action, scope: TickScope) =
        defFor(action).complete(actor, world, action, scope)

    /**
     * The definition for [action]. The cast is safe: each definition is registered under the type
     * its own actions report ([Action.type]), and the init check guarantees one per type.
     */
    @Suppress("UNCHECKED_CAST")
    private fun defFor(action: Action): ActionDef<Action> = byType.getValue(action.type) as ActionDef<Action>
}
