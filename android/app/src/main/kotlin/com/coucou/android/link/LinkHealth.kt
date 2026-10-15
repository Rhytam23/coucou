package com.coucou.android.link

/**
 * What the phone knows about the health of the link, in plain terms, so Home can say what happened ("Connection lost 2 min ago,
 * reconnecting") instead of showing a silent state. Pure: the clock is passed in, so every sentence is a unit test.
 */
data class LinkHealth(
    /** When the link that had been working ended (null: it never did, or it is back). */
    val lostAtMs: Long? = null,
    val reason: LostReason? = null,
    /** Failed attempts since then. */
    val attempts: Int = 0,
    /** How many times a link ended on its own after the screen had been off (this is what the battery hint is about). */
    val dropsWhileScreenOff: Int = 0,
    /** What the latest failed attempt to reconnect raised (null: none yet). */
    val failure: ConnectFailure? = null,
) {
    fun lost(atMs: Long, why: LostReason, screenWasOff: Boolean) =
        copy(lostAtMs = atMs, reason = why, attempts = 0, failure = null, dropsWhileScreenOff = dropsWhileScreenOff + if (screenWasOff) 1 else 0)

    fun failedAttempt(count: Int) = copy(attempts = count)

    fun failedWith(kind: ConnectFailure) = copy(failure = kind)

    /** Connected again: the story is over (the number of drops while the screen was off stays). */
    fun recovered() = copy(lostAtMs = null, reason = null, attempts = 0, failure = null)
}

object LinkHealthText {
    /** "less than a minute", "2 min", "1 h 5 min", "3 h". */
    fun ago(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0) / 60_000).toInt()
        return when {
            minutes < 1 -> "less than a minute"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /** What the phone itself knows about its network when the link is judged. */
    enum class PhoneNet { OFFLINE, WIFI, CELLULAR }

    /**
     * The most likely cause, from the most certain clue to the least: the phone has no network at all; the address answered "no" (the
     * computer is on, Coucou is closed or its Phone link is off); nobody answered (computer off or asleep, or another network - on
     * mobile data without a relay, certainly another network); then what the ended link itself showed.
     */
    fun why(health: LinkHealth, net: PhoneNet, hasRelay: Boolean): String = when {
        net == PhoneNet.OFFLINE -> "The phone has no network."
        health.failure == ConnectFailure.REFUSED ->
            "The computer answers, but Coucou does not: its app is closed, or Phone link is off in its Settings."
        health.failure == ConnectFailure.UNREACHABLE && net == PhoneNet.CELLULAR && !hasRelay ->
            "The phone is on mobile data, not on the computer's Wi-Fi."
        health.failure == ConnectFailure.UNREACHABLE ->
            "The computer cannot be reached: it is off or asleep, or the phone is on another network."
        health.reason == LostReason.SILENT -> "The computer stopped answering. This is usual after the phone slept."
        health.reason == LostReason.CLOSED -> "Coucou on the computer closed the connection: it was closed, restarted, or the computer went to sleep."
        health.reason == LostReason.NETWORK && net == PhoneNet.CELLULAR && !hasRelay -> "The phone left the computer's Wi-Fi."
        health.reason == LostReason.NETWORK -> "The connection broke on the way to the computer."
        else -> ""
    }

    /** The two lines for Home while the link is down after having worked; null when there is nothing to say. */
    fun lines(
        connected: Boolean, health: LinkHealth, nowMs: Long,
        net: PhoneNet = PhoneNet.WIFI, hasRelay: Boolean = false,
    ): Pair<String, String>? {
        val at = health.lostAtMs ?: return null
        if (connected) return null
        val head = "Connection lost ${ago(nowMs - at)} ago, reconnecting" + if (health.attempts >= 3) " (${health.attempts} tries)" else ""
        return head to why(health, net, hasRelay)
    }
}

/** The alarm that keeps the link honest while the phone sleeps (see docs/ANDROID_LINK.md, "Keeping the link alive"). */
object KeepAlivePolicy {
    /**
     * How often the alarm asks for a check. Android may delay it in Doze (to about 9 minutes, or a maintenance window), never make it
     * more frequent. The computer allows [COMPUTER_IDLE_MS] of silence, which is several times longer.
     */
    const val INTERVAL_MS = 4 * 60_000L
    /** How long the computer tolerates silence from an authenticated phone (windows/src-tauri/.../server.rs, IDLE_TIMEOUT). */
    const val COMPUTER_IDLE_MS = 20 * 60_000L
    /** The wake lock held during one check, only so the question and its answer can cross; released as soon as it is done. */
    const val WAKE_LOCK_MS = 15_000L

    /** An alarm exists only while a real computer is paired. */
    fun shouldArm(paired: Boolean, demo: Boolean) = paired && !demo
}

/**
 * When to suggest the battery setting. Only when it would help: the link ended on its own after the screen had been off, the
 * app is still restricted, and the user did not say "not now" recently.
 */
object BatteryHint {
    const val SNOOZE_MS = 7 * 24 * 3_600_000L

    fun shouldShow(paired: Boolean, health: LinkHealth, ignoringOptimizations: Boolean, snoozedAtMs: Long?, nowMs: Long): Boolean =
        paired && !ignoringOptimizations && health.dropsWhileScreenOff >= 1 &&
            (snoozedAtMs == null || nowMs - snoozedAtMs >= SNOOZE_MS)

    /** Where to look: Samsung hides the setting in two places. */
    fun isSamsung(manufacturer: String) = manufacturer.equals("samsung", ignoreCase = true)

    fun steps(samsung: Boolean): List<String> =
        if (samsung) listOf(
            "Open Settings > Apps > Coucou for Android > Battery and choose Unrestricted.",
            "Then Settings > Battery > Background usage limits: make sure Coucou is not in Sleeping apps or Deep sleeping apps (add it to Never sleeping apps).",
        ) else listOf(
            "Open the app's battery setting and choose Unrestricted (or Don't optimise).",
        )
}
