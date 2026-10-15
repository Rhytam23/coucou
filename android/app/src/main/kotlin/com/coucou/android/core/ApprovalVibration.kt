package com.coucou.android.core

/**
 * How a request buzzes the phone. A notification channel cannot change once it exists, so each choice has a channel of its own:
 * [DEFAULT] keeps the channel the app has always had (same id, same pattern); the others get a new id. Only one of them exists
 * at a time (the app deletes the rest), so the system's notification settings show a single "Approvals" channel.
 * The sound and the heads-up are the same for all three: a request is never silent, only the buzz changes.
 */
enum class ApprovalVibration(val key: String, val channelId: String, val pattern: LongArray?) {
    DEFAULT("default", "approvals_v3", longArrayOf(0, 500, 150, 500)),
    SHORT("short", "approvals_short", longArrayOf(0, 200)),
    OFF("off", "approvals_novibe", null);

    companion object {
        fun parse(key: String): ApprovalVibration = entries.firstOrNull { it.key == key } ?: DEFAULT
        val allChannelIds: List<String> get() = entries.map { it.channelId }
    }
}
