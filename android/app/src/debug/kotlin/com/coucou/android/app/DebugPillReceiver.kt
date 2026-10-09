package com.coucou.android.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
 * kind = working | finished | error | question | ratelimit | approval | clear | history | tool | long | multi | chat | details
 *
 * The island (a black pill hanging from the camera cut-out, over other apps; the "Show Mochi over other
 * apps" switch must be on, the app in the background):
 *   working   opens it and keeps it open (no timer while an agent works)
 *   finished  shows the result, waits 10 s, then it goes back up (error and ratelimit: same 10 s)
 *   question / approval  stay until answered, expired or cleared
 *   clear     no sessions and no requests: the island goes up
 * `adb logcat -s CoucouIsland` prints every phase change.
 *
 * `history` adds three sample decisions to Settings > History (no agent needed).
 * With the app in the background, finished / error / question / ratelimit also post a quiet notice
 * in the notification shade (channel "Updates") unless Settings turned it off or quiet hours apply.
 *
 * `--ez foreground true` pretends Coucou is on screen for a moment, so the pill must stay away and an
 * approval is announced by the heads-up notification instead (the case "pill not allowed").
 * Watch `adb logcat -s CoucouOverlay CoucouLaunch`: the first says which announcement was chosen
 * (pill / quiet or heads-up notification), the second lists every start of the app and why.
 */
class DebugPillReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val model = (context.applicationContext as CoucouApp).model
        val kind = intent.getStringExtra("kind") ?: "finished"
        val now = System.currentTimeMillis()
        if (intent.getBooleanExtra("foreground", false)) {
            model.inForeground = true
            Handler(Looper.getMainLooper()).postDelayed({ model.inForeground = false }, 400)
        }
        fun session(state: BotState, text: String) =
            SessionInfo("agent_gemini", "Gemini CLI", state, text, 1, 3, now)
        when (kind) {
            "working" -> model.onSessions(listOf(session(BotState.WORKING, "Running npm test")))
            // Looks, not behaviour: raw tool names, long names and several agents, to check the Home screen
            // (try it with the font at 1.3x: adb shell settings put system font_scale 1.3).
            "tool" -> model.onSessions(listOf(session(BotState.WORKING, "ask_question")))
            "long" -> model.onSessions(
                listOf(SessionInfo("agent_gemini", "A very long agent name that keeps going and going", BotState.WORKING, "mcp__github__list_pull_request_review_comments", 4, 8, now)),
            )
            "multi" -> model.onSessions(
                listOf(
                    SessionInfo("agent_gemini", "Gemini CLI", BotState.WORKING, "Bash", 2, 8, now),
                    SessionInfo("integration_claude", "Claude Code", BotState.IDLE, "", 0, 0, now),
                    SessionInfo("agent_codex", "Codex", BotState.THINKING, "Read", 0, 3, now),
                    SessionInfo("agent_claude-desktop", "Claude Desktop with a long name", BotState.FINISHED, "All done", 3, 3, now),
                ),
            )
            // The Chat screen with sample messages, no computer needed (nothing can be sent in this mode).
            "chat" -> model.debugSeedChat(
                listOf(
                    com.coucou.android.link.ChatModel("anthropic/claude-x", "anthropic", "Anthropic · claude-x"),
                    com.coucou.android.link.ChatModel("openai/gpt-y", "openai", "OpenAI · gpt-y"),
                ),
                listOf(
                    com.coucou.android.core.ChatMessage("u-1", com.coucou.android.core.ChatRole.USER, "How do I undo my last commit but keep the changes?"),
                    com.coucou.android.core.ChatMessage(
                        "a-1", com.coucou.android.core.ChatRole.ASSISTANT,
                        "Use **soft reset**:\n\n- `git reset --soft HEAD~1` keeps your changes staged\n- `git reset HEAD~1` keeps them unstaged\n\n```\ngit reset --soft HEAD~1\ngit status\n```\nA very_long_unbroken_word_to_check_wrapping_on_a_narrow_screen_abcdefghijklmnopqrstuvwxyz0123456789",
                    ),
                    com.coucou.android.core.ChatMessage("u-2", com.coucou.android.core.ChatRole.USER, "And on a branch I already pushed?"),
                    com.coucou.android.core.ChatMessage("a-2", com.coucou.android.core.ChatRole.ASSISTANT, "You would need to force-push, which", com.coucou.android.core.ChatStatus.FAILED, "rate"),
                    com.coucou.android.core.ChatMessage("u-3", com.coucou.android.core.ChatRole.USER, "x"),
                    com.coucou.android.core.ChatMessage("a-3", com.coucou.android.core.ChatRole.ASSISTANT, "", com.coucou.android.core.ChatStatus.STREAMING),
                ),
            )
            // Session details (steps, last message, project folder, colour), as with the computer's switch on.
            "details" -> {
                model.onCaps(setOf("details"))
                model.onSessions(
                    listOf(
                        SessionInfo(
                            "integration_claude", "Claude Code", BotState.FINISHED, "Done", 4, 5, now,
                            steps = listOf("Read · README.md", "Grep · TODO", "Edit · src/app.ts", "Bash · npm test", "ask_question"),
                            finalLine = "I fixed the failing test and everything passes now.",
                            project = "coucou", color = "#2DD4BF",
                        ),
                        SessionInfo("agent_codex", "Codex", BotState.WORKING, "Reading files", 1, 3, now, steps = listOf("Search · the code"), project = "api-server", color = "#E879F9"),
                        SessionInfo("agent_gemini", "Gemini CLI", BotState.SLEEPING, "", 0, 0, now),
                    ),
                )
            }
            "clear" -> {
                model.onSessions(emptyList())
                model.approvals.forEach { model.onApprovalResolved(it.fingerprint) }
            }
            "history" -> {
                val names = listOf("Gemini CLI" to "npm test", "Claude Code" to "git push origin main", "Codex" to "rm -rf build")
                names.forEachIndexed { i, (agent, cmd) ->
                    model.recordDecision(com.coucou.android.core.Decision(agent, "Bash", cmd, allowed = i != 2, atMs = now - i * 3_600_000L))
                }
            }
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
