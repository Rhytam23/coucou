package com.coucou.android.core

import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.Rgb

/**
 * What the Home screen shows, like the PC's Home panel: one agent card for the agent that matters
 * most (or the one the user tapped), and a grid of the others. Pure, so it is unit-tested.
 */
object HomePanel {
    private val URGENCY = listOf(
        BotState.APPROVAL, BotState.QUESTION, BotState.ERROR, BotState.RATELIMIT, BotState.DIZZY,
        BotState.WORKING, BotState.SEARCHING, BotState.THINKING, BotState.FINISHED, BotState.SLEEPING, BotState.IDLE,
    )

    /** The tapped agent if it is still there, else the one that needs the user most (the first on a tie). */
    fun focus(sessions: List<SessionInfo>, selectedPillId: String?): SessionInfo? =
        sessions.firstOrNull { it.pillId == selectedPillId }
            ?: sessions.minByOrNull { URGENCY.indexOf(it.state).let { i -> if (i < 0) URGENCY.size else i } }

    /** The others, in the order the desktop sent them. */
    fun others(sessions: List<SessionInfo>, focus: SessionInfo?): List<SessionInfo> =
        sessions.filter { it.pillId != focus?.pillId }

    /** Two pills per row, as on the PC. */
    fun rows(others: List<SessionInfo>): List<List<SessionInfo>> = others.chunked(2)

    /** Two equal columns, as on the PC. */
    const val COLUMNS = 2

    /**
     * A row always has [COLUMNS] cells: a lone pill keeps its column (left) and the other cell is
     * empty, so every pill has the same width whatever the number of agents.
     */
    fun cells(row: List<SessionInfo>): List<SessionInfo?> = List(COLUMNS) { row.getOrNull(it) }

    /**
     * The one link on the agent card: answer a waiting request first, else pair when there is no
     * computer yet, else nothing (Settings has its own button at the top).
     */
    enum class Link { NONE, APPROVAL, PAIR }

    fun link(paired: Boolean, demo: Boolean, hasApproval: Boolean): Link = when {
        hasApproval -> Link.APPROVAL
        !paired && !demo -> Link.PAIR
        else -> Link.NONE
    }

    /** "#8AB4F8" to a colour for a mini Mochi's body; a malformed value gives null (the default body). */
    fun rgb(hex: String): Rgb? {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6) return null
        val v = h.toIntOrNull(16) ?: return null
        return Rgb(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
    }
}
