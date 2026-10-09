package com.coucou.android.core

import com.coucou.android.mochi.BotState

/**
 * When the small pill drops from the top of the screen, like the notch on the computer: it stays
 * out of sight, wakes when an agent finishes, fails, asks something or is rate limited, shows for a
 * moment, then slides away. A permission request is not here: it has its own card that stays until
 * it is answered.
 */
object OverlayPolicy {
    private val FLASH = setOf(BotState.FINISHED, BotState.ERROR, BotState.QUESTION, BotState.RATELIMIT)

    /** How long a status pill stays before it slides away. */
    const val STATUS_MS = 4_500L

    /** Only a change into one of those states wakes it; the same state sent again does not. */
    fun shouldFlash(previous: BotState?, now: BotState): Boolean = now in FLASH && previous != now

    /** The pill is for when you are in another app; inside Coucou the screen already shows it. */
    fun shouldShow(enabled: Boolean, permitted: Boolean, appInForeground: Boolean): Boolean =
        enabled && permitted && !appInForeground
}
