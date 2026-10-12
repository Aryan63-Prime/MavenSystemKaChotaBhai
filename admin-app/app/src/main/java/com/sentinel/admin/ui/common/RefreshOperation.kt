package com.sentinel.admin.ui.common

/**
 * Authoritative production lifecycle and outcome owner for refresh operations.
 *
 * Implements Pass 5 Work Order 1:
 * - Monotonic attempt IDs distinguish distinct user actions
 * - Idle -> Running(id) -> Success(id) | Error(id, message) | Cancelled(id)
 * - Synchronously guarded execution prevents concurrent jobs
 * - Settling is scoped to attemptId so obsolete timers cannot mutate newer state
 */
sealed interface RefreshOperation {
    val isRunning: Boolean get() = this is Running
    val attemptId: Long?

    data object Idle : RefreshOperation {
        override val attemptId: Long? = null
    }
    data class Running(override val attemptId: Long) : RefreshOperation
    data class Success(override val attemptId: Long) : RefreshOperation
    data class Error(override val attemptId: Long, val message: String) : RefreshOperation
    data class Cancelled(override val attemptId: Long) : RefreshOperation
}
