package com.coucou.android.link

import com.coucou.android.mochi.BotState
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
}

data class SessionInfo(
    val pillId: String,
    val agent: String,
    val state: BotState,
    val statusText: String,
    val stepIndex: Int,
    val stepCount: Int,
    val updatedAtMs: Long,
)

data class ApprovalRequest(
    val pillId: String,
    val fingerprint: String,
    val tool: String,
    val command: String,
    val createdAtMs: Long,
)

sealed interface ServerMsg {
    data class Welcome(val version: Int, val desktopName: String, val os: String) : ServerMsg
    data class Sessions(val sessions: List<SessionInfo>) : ServerMsg
    data class Approval(val request: ApprovalRequest) : ServerMsg
    data class ApprovalResolved(val fingerprint: String) : ServerMsg
    data object Pong : ServerMsg
    data class Error(val code: String, val message: String) : ServerMsg
}

sealed interface ClientMsg {
    data class Hello(val version: Int, val token: String, val deviceName: String) : ClientMsg
    /** Only allow and deny exist from the phone: no "always". */
    data class Decision(val fingerprint: String, val allow: Boolean) : ClientMsg
    data object Ping : ClientMsg
    data object Bye : ClientMsg
}

object Wire {
    fun encode(m: ClientMsg): String {
        val o = JSONObject()
        when (m) {
            is ClientMsg.Hello -> o.put("type", "hello").put("v", m.version).put("token", m.token).put("device", m.deviceName)
            is ClientMsg.Decision -> o.put("type", "decision").put("fingerprint", m.fingerprint)
                .put("decision", if (m.allow) "allow" else "deny")
            ClientMsg.Ping -> o.put("type", "ping")
            ClientMsg.Bye -> o.put("type", "bye")
        }
        return o.toString()
    }

    /** Returns null for anything malformed or unknown: a bad line never crashes the client. */
    fun decodeServer(line: String): ServerMsg? {
        if (line.length > Protocol.MAX_LINE_BYTES) return null
        return try {
            val o = JSONObject(line)
            when (o.getString("type")) {
                "welcome" -> ServerMsg.Welcome(o.getInt("v"), o.optString("desktop", ""), o.optString("os", ""))
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
                "pong" -> ServerMsg.Pong
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
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

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
