package com.davideloconte.screenlet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    private const val SERVICE = "service"
    private const val ASK = "ask"
    const val SERVICE_ID = 1

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(SERVICE,
            context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN))
        manager.createNotificationChannel(NotificationChannel(ASK,
            context.getString(R.string.channel_ask), NotificationManager.IMPORTANCE_HIGH))
    }

    fun ready(context: Context): Notification =
        Notification.Builder(context, SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.ready_title))
            .setContentText(context.getString(R.string.ready_text))
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()

    /** The service's notification while a PC's desktop is shown here: back
     * to it with a tap, or Stop. */
    fun displaying(context: Context, pc: String): Notification {
        val stop = PendingIntent.getBroadcast(context, 0,
            Intent(context, DisplayStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.display_title, pc))
            .setContentText(context.getString(R.string.display_text))
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, DisplayActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.display_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    /** One per code (several PCs may pair at once); 1000 and up, clear of SERVICE_ID. */
    private fun pairingId(code: String) = 1000 + code.toInt()

    fun pairing(context: Context, pc: String, code: String, open: Intent) {
        fun action(action: String) = PendingIntent.getBroadcast(context, pairingId(code),
            Intent(context, PairingReceiver::class.java).setAction(action).putExtra(PairActivity.EXTRA_CODE, code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, ASK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.pair_title, pc))
            .setContentText(context.getString(R.string.pair_message, code.chunked(3).joinToString(" ")))
            .setContentIntent(PendingIntent.getActivity(context, pairingId(code), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            // Not from the lock screen: whoever holds the tablet, locked, does not pair it.
            .addAction(Notification.Action.Builder(null, context.getString(R.string.pair),
                action(PairingReceiver.ACTION_ACCEPT)).setAuthenticationRequired(true).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.cancel),
                action(PairingReceiver.ACTION_REJECT)).build())
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(pairingId(code), notification)
    }

    fun cancelPairing(context: Context, code: String) =
        context.getSystemService(NotificationManager::class.java).cancel(pairingId(code))

}
