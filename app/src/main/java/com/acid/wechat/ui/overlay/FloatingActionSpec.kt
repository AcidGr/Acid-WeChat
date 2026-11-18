package com.acid.wechat.ui.overlay

import android.app.Activity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Declarative descriptor for an external floating entry point attached to matching WeChat activities.
 */
data class FloatingActionSpec(
    val id: String,
    val label: String,
    val isEnabled: () -> Boolean,
    val matchesActivity: (Activity) -> Boolean,
    val onTrigger: (Activity) -> Unit,
)

/**
 * Central registry for contextual floating overlay actions.
 */
object FloatingActionRegistry {
    private val specs = CopyOnWriteArrayList<FloatingActionSpec>()

    fun register(spec: FloatingActionSpec) {
        specs.removeAll { it.id == spec.id }
        specs.add(spec)
    }

    fun matching(activity: Activity): List<FloatingActionSpec> =
        specs.filter { spec ->
            runCatching { spec.isEnabled() && spec.matchesActivity(activity) }.getOrDefault(false)
        }
}
