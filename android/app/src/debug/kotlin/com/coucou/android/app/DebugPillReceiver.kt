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
 * kind = working | finished | error | question | ratelimit | approval | clear | history | tool | long | multi | chat | details | scan | addrchange | chatstate | askquestion | outfit | diff
 *
 * The island (a black pill hanging from the camera cut-out, over other apps; the "Show Mochi over other
 * apps" switch must be on, the app in the background):
 *   working   opens it and keeps it open (no timer while an agent works)
 *   finished  shows the result, waits 10 s, then it goes back up (error and ratelimit: same 10 s)
 *   question / approval  stay until answered, expired or cleared
 *   clear     no sessions and no requests: the island goes up
 * `adb logcat -s CoucouIsland` prints every phase change.
 *
 * `outfit` dresses Mochi without a computer: `--es value beanie` is what the computer would say (auto, none,
 * partyHat, beanie, crown, sunglasses, roundGlasses, bow, scarf, witchHat, pumpkin, santaHat, bunnyEars) and
 * `--es local crown` is the phone's own choice from Settings > Mochi's wardrobe (`--es local computer` follows the computer).
 *
 * `diff` shows an agent that changed files (Home > the agent > Files changed): tap a file to open the sheet of its lines
 * (no computer needed). `--es size big` makes the changes longer than the 200 lines the computer sends, to see the note.
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
            // Pairing without the camera: the text is handed over as a scanned code would be (a pairing link asks
            // the user to confirm in the app; anything else shows the same hint as the scanner). Never logged.
            //   adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind scan --es text "coucou://pair?..."
            "scan" -> {
                val accepted = model.requestPairing(intent.getStringExtra("text"))
                if (!accepted) model.message = context.getString(com.coucou.android.R.string.scan_not_pairing)
                android.util.Log.d("CoucouScan", "debug scan accepted=$accepted")
            }
            // Pretend the computer's address changed: the saved address is replaced by one that never answers, so the
            // phone has to find the computer by itself (needs a paired computer with the phone link on).
            //   adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind addrchange
            "addrchange" -> model.debugAddressChanged()
            // Each state of the Chat tab without a computer, then open the Chat tab:
            //   adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind chatstate --es state offline
            // state = notpaired | offline | off | nomodels | ready | real (back to the real state)
            "chatstate" -> model.debugChatState(
                when (intent.getStringExtra("state")) {
                    "notpaired" -> com.coucou.android.core.ChatTabState.NOT_PAIRED
                    "offline" -> com.coucou.android.core.ChatTabState.NOT_CONNECTED
                    "off" -> com.coucou.android.core.ChatTabState.CHAT_OFF
                    "nomodels" -> com.coucou.android.core.ChatTabState.NO_MODELS
                    "ready" -> com.coucou.android.core.ChatTabState.READY
                    else -> null
                },
            )
            "clear" -> {
                model.onSessions(emptyList())
                model.approvals.forEach { model.onApprovalResolved(it.fingerprint) }
            }
            "diff" -> {
                val big = intent.getStringExtra("size") == "big"
                model.onCaps(setOf("details", "diffs"))
                model.onSessions(
                    listOf(
                        SessionInfo(
                            "integration_claude", "Claude Code", BotState.WORKING, "Editing files", 2, 5, now,
                            steps = listOf("Read · README.md", "Edit · src/app.ts"), project = "coucou", color = "#2DD4BF",
                            files = listOf(
                                com.coucou.android.link.FileChange(1, "app.ts", 3, 1),
                                com.coucou.android.link.FileChange(2, "notes.md", 12, 0, isNew = true),
                                com.coucou.android.link.FileChange(3, "huge-generated-file.json", 5400, 0, tooLarge = true),
                            ),
                        ),
                    ),
                )
                model.setDebugDiff { f ->
                    val rows = when {
                        f.tooLarge -> emptyList()
                        big -> (0 until 200).map { i -> com.coucou.android.link.DiffRow(if (i % 7 == 0) '+' else if (i % 7 == 1) '-' else ' ', "line $i of a long file") }
                        else -> listOf(
                            com.coucou.android.link.DiffRow('@', "@@ 10"),
                            com.coucou.android.link.DiffRow(' ', "function greet(name: string) {"),
                            com.coucou.android.link.DiffRow('-', "  return 'Hello ' + name;"),
                            com.coucou.android.link.DiffRow('+', "  return `Hello, \${name}!`;"),
                            com.coucou.android.link.DiffRow(' ', "}"),
                        )
                    }
                    com.coucou.android.core.FileDiffView("integration_claude", f.id, f.name, f.added, f.removed, f.tooLarge, false, big, rows)
                }
            }
            "outfit" -> {
                intent.getStringExtra("value")?.let { model.applyComputerOutfit(it) }
                intent.getStringExtra("local")?.let { model.updateSettings(model.settings.copy(outfit = com.coucou.android.mochi.outfit.Wardrobe.parseLocal(it))) }
            }
            "history" -> {
                val names = listOf("Gemini CLI" to "npm test", "Claude Code" to "git push origin main", "Codex" to "rm -rf build")
                names.forEachIndexed { i, (agent, cmd) ->
                    model.recordDecision(com.coucou.android.core.Decision(agent, "Bash", cmd, allowed = i != 2, atMs = now - i * 3_600_000L))
                }
            }
            // A question with two parts that can be answered here, with the screen lock, without a computer:
            //   adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind askquestion
            "askquestion" -> {
                model.onSessions(listOf(session(BotState.WORKING, "Working")))
                model.onSessions(listOf(session(BotState.QUESTION, "Which search engine?")))
                model.debugQuestion(
                    "agent_gemini",
                    com.coucou.android.link.QuestionRequest(
                        "agent_gemini", "f".repeat(64),
                        listOf(
                            com.coucou.android.link.AskedQuestion(
                                "Which search engine?",
                                listOf(
                                    com.coucou.android.link.AskedOption("Postgres full-text", "Built in, no new service"),
                                    com.coucou.android.link.AskedOption("Meilisearch", "Typo tolerant, one more thing to run"),
                                    com.coucou.android.link.AskedOption("Algolia", "Hosted"),
                                ),
                                multiSelect = false,
                            ),
                            com.coucou.android.link.AskedQuestion(
                                "Which extras do you want?",
                                listOf(
                                    com.coucou.android.link.AskedOption("Typo tolerance", ""),
                                    com.coucou.android.link.AskedOption("Facets", "Filter by category"),
                                    com.coucou.android.link.AskedOption("Synonyms", ""),
                                ),
                                multiSelect = true,
                            ),
                        ),
                        now,
                    ),
                )
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
