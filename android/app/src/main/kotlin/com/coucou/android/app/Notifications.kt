package com.coucou.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import com.coucou.android.MainActivity
import com.coucou.android.R
import com.coucou.android.core.Pills
import com.coucou.android.link.ApprovalRequest
import com.coucou.android.link.LinkState

/**
 * Notifications: one quiet ongoing notification for the link, and a loud one per approval.
 * Deny acts straight from the notification. Allow always opens the app for the biometric
 * check first, and the command is hidden on the lock screen.
 */
class Notifications(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CH_LINK, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that Coucou is connected to your computer"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_APPROVAL, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent is waiting for your permission"
                // Mochi's own approval sound, bundled in res/raw.
                setSound(
                    Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.approval}"),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            },
        )
    }

    private fun open(fp: String?, allow: Boolean, code: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (fp != null) { putExtra(EXTRA_FP, fp); putExtra(EXTRA_ALLOW, allow) } }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun ongoing(model: AppModel?): Notification {
        val text = when {
            model == null -> "Starting…"
            model.linkState == LinkState.CONNECTED ->
                "Connected to ${model.desktopName ?: "your computer"} · ${model.sessions.size} agent" + if (model.sessions.size == 1) "" else "s"
            model.linkState == LinkState.CONNECTING -> "Connecting…"
            else -> "Reconnecting…"
        }
        return Notification.Builder(context, CH_LINK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(null, false, 0))
            .build()
    }

    fun updateOngoing(model: AppModel) {
        if (model.mode == Mode.PAIRED) runCatching { nm.notify(ONGOING_ID, ongoing(model)) }
    }

    fun showApproval(r: ApprovalRequest) {
        val id = idFor(r.fingerprint)
        val pill = Pills.byId(r.pillId)?.name ?: "An agent"
        val deny = PendingIntent.getBroadcast(
            context, id, Intent(context, ActionReceiver::class.java).setAction(ACTION_DENY).putExtra(EXTRA_FP, r.fingerprint),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(context, CH_APPROVAL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Approval needed").setContentText("Unlock to see the request").build()
        val n = Notification.Builder(context, CH_APPROVAL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$pill needs your approval")
            .setContentText("${r.tool}: ${r.command}")
            .setStyle(Notification.BigTextStyle().bigText("${r.tool}: ${r.command}"))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setTimeoutAfter(com.coucou.android.link.Protocol.APPROVAL_TTL_MS)
            .setContentIntent(open(r.fingerprint, false, id))
            .addAction(Notification.Action.Builder(null, "Deny", deny).build())
            .addAction(Notification.Action.Builder(null, "Allow", open(r.fingerprint, true, id + 1)).build())
            .build()
        runCatching { nm.notify(id, n) } // POST_NOTIFICATIONS may be denied: the in-app card still works
    }

    fun cancelApproval(fp: String) = nm.cancel(idFor(fp))

    companion object {
        const val CH_LINK = "link"
        const val CH_APPROVAL = "approvals"
        const val ONGOING_ID = 1
        const val ACTION_DENY = "com.coucou.android.DENY"
        const val EXTRA_FP = "fingerprint"
        const val EXTRA_ALLOW = "allow"
        /** Even ids for the request, +1 for its Allow action; never collides with ONGOING_ID. */
        fun idFor(fp: String) = (fp.hashCode() and 0x3fffffff) * 2 + 2
    }
}

/** Handles the Deny button. Denying needs no biometric check: it can only make things safer. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_DENY) return
        val fp = intent.getStringExtra(Notifications.EXTRA_FP) ?: return
        (context.applicationContext as CoucouApp).model.decide(fp, allow = false)
    }
}
