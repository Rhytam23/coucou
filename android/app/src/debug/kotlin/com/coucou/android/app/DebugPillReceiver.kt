package com.coucou.android.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coucou.android.link.ApprovalRequest
import com.coucou.android.link.SessionInfo
import com.coucou.android.mochi.BotState

/**
 * Debug builds only: feeds the app the same messages the desktop would send, so the pill can be tried
 * without a real agent. It goes through the real code path ([AppModel.onSessions] / [AppModel.onApproval]),
 * so every rule applies: the switch, the system permission and "the app is not on screen".
 *
 *   adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind finished
 *
 * kind = finished | error | question | ratelimit | approval
 */
class DebugPillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val model = (context.applicationContext as CoucouApp).model
        val kind = intent.getStringExtra("kind") ?: "finished"
        val now = System.currentTimeMillis()
        fun session(state: BotState, text: String) =
            SessionInfo("agent_gemini", "Gemini CLI", state, text, 1, 3, now)
        when (kind) {
            "approval" -> model.onApproval(
                ApprovalRequest("agent_gemini", "debug-$now", "Bash", "rm -rf node_modules && npm install", now),
            )
            else -> {
                val target = when (kind) {
                    "error" -> BotState.ERROR to "Build failed"
                    "question" -> BotState.QUESTION to "Which database should I use?"
                    "ratelimit" -> BotState.RATELIMIT to "Rate limited, retrying soon"
                    else -> BotState.FINISHED to "All done"
                }
                // A change is what wakes the pill: first working, then the state.
                model.onSessions(listOf(session(BotState.WORKING, "Working")))
                model.onSessions(listOf(session(target.first, target.second)))
            }
        }
    }
}
