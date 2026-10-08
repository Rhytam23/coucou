package com.coucou.android.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the desktop link alive while the app is in the background, so approvals arrive as
 * notifications. Only runs while a desktop is paired; stops when the user unpairs.
 */
class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as CoucouApp).model
        startForeground(Notifications.ONGOING_ID, model.notifier.ongoing(model), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        model.resume()
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, LinkService::class.java)) }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
