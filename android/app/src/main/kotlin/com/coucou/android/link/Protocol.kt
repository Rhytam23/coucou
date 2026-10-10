package com.coucou.android.link

import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.outfit.Wardrobe
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Wire format of the desktop link (docs/ANDROID_LINK.md): newline-delimited JSON over TLS.
 * Pure Kotlin plus org.json, so it runs in JVM unit tests.
 */
object Protocol {
    const val VERSION = 1
    /** The desktop dismisses an approval after 115 s; the phone stops offering it at 120 s. */
    const val APPROVAL_TTL_MS = 120_000L
    /** One message never exceeds this; a longer line is a protocol error. */
    const val MAX_LINE_BYTES = 64 * 1024
    /** Optional features this app understands; the desktop offers back the ones it has switched on. */
    val CAPABILITIES = listOf("chat", "details", "answers", "prefs")
    const val CAP_CHAT = "chat"
    /** Steps, last line, project folder name and colour of each session. */
    const val CAP_DETAILS = "details"
    /** Answering the questions Claude Code asks, from the phone (each answer confirmed with the screen lock). */
    const val CAP_ANSWERS = "answers"
    /** What Mochi wears on the computer (no switch there: it is no secret). */
    const val CAP_PREFS = "prefs"
    const val MAX_QUESTIONS = 4
    const val MAX_OPTIONS = 8
    const val MAX_STEPS = 20
    const val MAX_STEP_CHARS = 200
    const val MAX_PROJECT_CHARS = 64
    /** What the desktop accepts for one chat message; longer is refused there, so it is refused here first. */
    const val CHAT_MAX_TEXT = 4000
}

/** A model the user allowed on the computer for the phone. [id] is "provider/model". */
data class ChatModel(val id: String, val provider: String, val label: String)

data class SessionInfo(
    val pillId: String,
    val agent: String,
    val state: BotState,
    val statusText: String,
    val stepIndex: Int,
    val stepCount: Int,
    val updatedAtMs: Long,
    // Only with the "details" capability (the user's switch on the computer); empty or null otherwise.
    val steps: List<String> = emptyList(),
    val finalLine: String? = null,
    /** A folder name, never a path. */
    val project: String? = null,
    /** "#RRGGBB" or null. */
    val color: String? = null,
)

data class ApprovalRequest(
    val pillId: String,
    val fingerprint: String,
    val tool: String,
    val command: String,
    val createdAtMs: Long,
)

/** One option of a question Claude Code asked. */
data class AskedOption(val label: String, val description: String)

/** One question of an AskUserQuestion call. */
data class AskedQuestion(val question: String, val options: List<AskedOption>, val multiSelect: Boolean)

/** A question waiting for an answer (cap `answers`). */
data class QuestionRequest(val pillId: String, val fingerprint: String, val questions: List<AskedQuestion>, val createdAtMs: Long)

sealed interface ServerMsg {
    data class Welcome(val version: Int, val desktopName: String, val os: String, val caps: Set<String> = emptySet()) : ServerMsg
    data class Sessions(val sessions: List<SessionInfo>) : ServerMsg
    data class Approval(val request: ApprovalRequest) : ServerMsg
    data class ApprovalResolved(val fingerprint: String) : ServerMsg
    data class Question(val request: QuestionRequest) : ServerMsg
    /** What Mochi wears on the computer: "auto" or an outfit, exactly one of [com.coucou.android.mochi.outfit.Wardrobe.SELECTIONS]. */
    data class Prefs(val outfit: String) : ServerMsg
    data object Pong : ServerMsg
    data class ChatModels(val models: List<ChatModel>) : ServerMsg
    /** [text] is appended to the answer being written. */
    data class ChatDelta(val id: String, val text: String) : ServerMsg
    /** [text], when present, is the full answer and replaces what was streamed. */
    data class ChatDone(val id: String, val text: String?) : ServerMsg
    /** [reason] is a fixed code (off, busy, rate, auth...); [message] a fixed sentence written by the computer. */
    data class ChatError(val id: String, val reason: String, val message: String) : ServerMsg
    data class Error(val code: String, val message: String) : ServerMsg
}

sealed interface ClientMsg {
    data class Hello(val version: Int, val token: String, val deviceName: String, val caps: List<String> = emptyList()) : ClientMsg
    /** Only allow and deny exist from the phone: no "always". */
    data class Decision(val fingerprint: String, val allow: Boolean) : ClientMsg
    data object Ping : ClientMsg
    data object Bye : ClientMsg
    /** [picks]: one list of labels per question, in order (exactly one for a single choice). */
    data class Answer(val fingerprint: String, val picks: List<List<String>>) : ClientMsg
    data object ChatModels : ClientMsg
    data class ChatSend(val id: String, val model: String, val text: String) : ClientMsg
    data class ChatCancel(val id: String) : ClientMsg
    /** New chat: the computer forgets its side of the conversation and stops a running answer. */
    data object ChatReset : ClientMsg
}

object Wire {
    fun encode(m: ClientMsg): String {
        val o = JSONObject()
        when (m) {
            is ClientMsg.Hello -> {
                o.put("type", "hello").put("v", m.version).put("token", m.token).put("device", m.deviceName)
                // Only when there is something to say: an app with no optional features sends the v1 hello unchanged.
                if (m.caps.isNotEmpty()) o.put("caps", JSONArray(m.caps))
            }
            is ClientMsg.Decision -> o.put("type", "decision").put("fingerprint", m.fingerprint)
                .put("decision", if (m.allow) "allow" else "deny")
            ClientMsg.Ping -> o.put("type", "ping")
            ClientMsg.Bye -> o.put("type", "bye")
            is ClientMsg.Answer -> o.put("type", "answer").put("fingerprint", m.fingerprint)
                .put("picks", JSONArray(m.picks.map { JSONArray(it) }))
            ClientMsg.ChatModels -> o.put("type", "chatModels")
            is ClientMsg.ChatSend -> o.put("type", "chatSend").put("id", m.id).put("model", m.model).put("text", m.text)
            is ClientMsg.ChatCancel -> o.put("type", "chatCancel").put("id", m.id)
            ClientMsg.ChatReset -> o.put("type", "chatReset")
        }
        return o.toString()
    }

    /** Returns null for anything malformed or unknown: a bad line never crashes the client. */
    fun decodeServer(line: String): ServerMsg? {
        if (line.length > Protocol.MAX_LINE_BYTES) return null
        return try {
            val o = JSONObject(line)
            when (o.getString("type")) {
                "welcome" -> ServerMsg.Welcome(o.getInt("v"), o.optString("desktop", ""), o.optString("os", ""), strings(o.optJSONArray("caps")))
                "sessions" -> ServerMsg.Sessions(o.getJSONArray("sessions").objects().map(::session))
                "approval" -> ServerMsg.Approval(
                    ApprovalRequest(
                        pillId = o.getString("pillId"),
                        fingerprint = o.getString("fingerprint").also { require(isFingerprint(it)) },
                        tool = o.getString("tool"),
                        command = o.getString("command"),
                        createdAtMs = o.getLong("createdAt"),
                    ),
                )
                "approvalResolved" -> ServerMsg.ApprovalResolved(o.getString("fingerprint"))
                "question" -> question(o)?.let { ServerMsg.Question(it) }
                // A value this build does not know is dropped rather than guessed: the phone keeps what it had.
                "prefs" -> o.optString("outfit", "").takeIf { it in Wardrobe.SELECTIONS }?.let { ServerMsg.Prefs(it) }
                "pong" -> ServerMsg.Pong
                "chatModels" -> ServerMsg.ChatModels(o.getJSONArray("models").objects().mapNotNull(::chatModel))
                "chatDelta" -> ServerMsg.ChatDelta(chatId(o), o.getString("text"))
                "chatDone" -> ServerMsg.ChatDone(chatId(o), if (o.has("text")) o.getString("text") else null)
                "chatError" -> ServerMsg.ChatError(chatId(o), o.optString("reason", "internal"), o.optString("message", ""))
                "error" -> ServerMsg.Error(o.optString("code", ""), o.optString("message", ""))
                else -> null
            }
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun session(o: JSONObject) = SessionInfo(
        pillId = o.getString("pillId"),
        agent = o.optString("agent", ""),
        state = BotState.entries.firstOrNull { it.key == o.optString("state") } ?: BotState.IDLE,
        statusText = o.optString("statusText", ""),
        stepIndex = o.optInt("stepIndex", 0),
        stepCount = o.optInt("stepCount", 0),
        updatedAtMs = o.optLong("updatedAt", 0),
        steps = stringList(o.optJSONArray("steps")).take(Protocol.MAX_STEPS).map { it.take(Protocol.MAX_STEP_CHARS) },
        finalLine = o.optString("finalLine", "").take(Protocol.MAX_STEP_CHARS).ifBlank { null },
        project = folderName(o.optString("project", "")),
        color = o.optString("color", "").takeIf { isColor(it) },
    )

    /**
     * A question as the computer offers it, or null if it breaks the limits (the computer would not send such a
     * one): 1 to 4 questions, each with 1 to 8 options that have different, non-blank labels.
     */
    private fun question(o: JSONObject): QuestionRequest? {
        val fingerprint = o.getString("fingerprint").also { require(isFingerprint(it)) }
        val items = o.getJSONArray("questions").objects().map { q ->
            val options = q.getJSONArray("options").objects().map { AskedOption(it.getString("label"), it.optString("description", "")) }
            AskedQuestion(q.getString("question"), options, q.optBoolean("multiSelect", false))
        }
        val ok = items.isNotEmpty() && items.size <= Protocol.MAX_QUESTIONS && items.all { i ->
            i.question.isNotBlank() && i.options.isNotEmpty() && i.options.size <= Protocol.MAX_OPTIONS &&
                i.options.all { it.label.isNotBlank() } && i.options.map { it.label }.distinct().size == i.options.size
        }
        return if (ok) QuestionRequest(o.getString("pillId"), fingerprint, items, o.optLong("createdAt", 0)) else null
    }

    /** Strings of a JSON array, in order; anything else in it is skipped. */
    private fun stringList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf { s -> s.isNotBlank() } }

    /** Even from a computer that should not send one, never a path: only the last segment is kept. */
    fun folderName(raw: String): String? {
        val last = raw.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() }.trim().take(Protocol.MAX_PROJECT_CHARS)
        return last.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    fun isColor(s: String) = s.length == 7 && s[0] == '#' && s.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun strings(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotEmpty() } }.toSet()

    /** A model without a usable id is dropped; a missing label shows the id. */
    private fun chatModel(o: JSONObject): ChatModel? {
        val id = o.optString("id", "")
        if (id.isBlank() || id.length > 300 || id.indexOf('/') <= 0) return null
        return ChatModel(id, o.optString("provider", id.substringBefore('/')), o.optString("label", "").ifBlank { id })
    }

    private fun chatId(o: JSONObject): String = o.getString("id").also { require(isChatId(it)) }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    /** The id the phone makes for a chat message and the computer echoes: 1 to 64 of letters, digits, - and _. */
    fun isChatId(s: String) = s.isNotEmpty() && s.length <= 64 && s.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }

    fun isFingerprint(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Same derivation as ApprovalRelay.fingerprint on the Mac: SHA-256 of the fields joined by U+001F. */
object Fingerprint {
    fun of(pillId: String, sessionId: String, tool: String, command: String, inputKey: String): String {
        val raw = listOf(pillId, sessionId, tool, command, inputKey).joinToString("\u001F")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
