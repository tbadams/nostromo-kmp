package org.tadams.nostromo.core.model

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Identifies a location. Matches `id` in scenario JSON. */
@Serializable
@JvmInline
value class RoomId(val value: String) {
    override fun toString() = value
}

/** Identifies an actor. Matches `id` in scenario JSON. */
@Serializable
@JvmInline
value class ActorId(val value: String) {
    override fun toString() = value
}

/** Identifies one item. Matches `id` in scenario JSON. */
@Serializable
@JvmInline
value class ItemId(val value: String) {
    override fun toString() = value
}
