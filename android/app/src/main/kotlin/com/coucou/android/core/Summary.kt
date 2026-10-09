package com.coucou.android.core

import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotState

/** Small pure helpers for what the home screen shows, kept out of Compose so they can be tested. */
object Summary {
    /** What needs the user most comes first: an answer to give, then trouble, then work, then rest. */
    private val PRIORITY = listOf(
        BotState.APPROVAL, BotState.QUESTION, BotState.ERROR, BotState.RATELIMIT, BotState.DIZZY,
        BotState.WORKING, BotState.SEARCHING, BotState.THINKING, BotState.FINISHED, BotState.SLEEPING, BotState.IDLE,
    )

    /** The state the header Mochi shows for all sessions together. */
    fun headerState(sessions: List<SessionInfo>, hasApproval: Boolean): BotState {
        if (hasApproval) return BotState.APPROVAL
        val states = sessions.map { it.state }.toSet()
        return PRIORITY.firstOrNull { it in states } ?: BotState.IDLE
    }

    /**
     * "Step 3 of 8": the desktop counts steps from 0, so the step being worked on is index + 1.
     * Null when the agent does not report steps. Never above the count, never below 1.
     */
    fun stepNumber(stepIndex: Int, stepCount: Int): Int? =
        if (stepCount <= 0) null else (stepIndex + 1).coerceIn(1, stepCount)

    /** 0..1 for the thin progress bar; no bar when the agent does not report steps. */
    fun progress(stepIndex: Int, stepCount: Int): Float =
        stepNumber(stepIndex, stepCount)?.let { it.toFloat() / stepCount } ?: 0f
}
