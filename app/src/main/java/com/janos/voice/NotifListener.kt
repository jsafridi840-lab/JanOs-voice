package com.janos.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

class NotifListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onListenerConnected() {
        Speaker.init(this)
        Notifier.ensureChannel(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w("JanOS", "notif error", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val prefs = Prefs(this)
        if (sbn.packageName !in prefs.enabledApps) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val convTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()

        var sender = title
        var text = ""
        val last = lastMessage(ex)
        if (last != null) {
            text = last.first
            if (!last.second.isNullOrBlank()) {
                sender = if (convTitle.isNullOrBlank()) last.second!! else "${last.second} ($convTitle)"
            }
        }
        if (text.isBlank()) {
            text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        }
        text = text.trim()
        if (text.isEmpty() || sender.isEmpty()) return

        val sig = sbn.packageName + "|" + sender + "|" + text
        if (MessageStore.seenRecently(sig)) return

        val appName = Prefs.DEFAULT_APPS[sbn.packageName] ?: sbn.packageName
        val action = findReplyAction(n)
        val key = sbn.packageName + "|" + (convTitle ?: title)
        val convo = MessageStore.put(key, sbn.packageName, appName, sender, text, action, n.contentIntent)

        if (!prefs.autoRead) {
            Notifier.prompt(this, convo)
            return
        }
        worker.execute {
            val spoken = try {
                Gemini(prefs).prepareIncoming(appName, sender, text)
            } catch (e: Exception) {
                Log.w("JanOS", "gemini", e)
                Spoken("other", "$appName. $sender: $text")
            }
            Notifier.prompt(this, convo)
            Speaker.speak(this, spoken.lang, spoken.speak)
        }
    }

    /** Returns (text, sender) of the newest message from a MessagingStyle notification. */
    @Suppress("DEPRECATION")
    private fun lastMessage(ex: Bundle): Pair<String, String?>? {
        val arr: Array<Parcelable> = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val b = arr.lastOrNull() as? Bundle ?: return null
        val text = b.getCharSequence("text")?.toString() ?: return null
        var sender = b.getCharSequence("sender")?.toString()
        if (sender.isNullOrBlank()) {
            val p = b.getParcelable<Person>("sender_person")
            sender = p?.name?.toString()
        }
        return text to sender
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val all = mutableListOf<Notification.Action>()
        n.actions?.let { all.addAll(it) }
        try {
            all.addAll(Notification.WearableExtender(n).actions)
        } catch (_: Exception) {}
        return all.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
    }
}

/** Our own small notification: "Jawab do (voice)". */
object Notifier {
    const val CH = "janos_reply"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Voice reply", NotificationManager.IMPORTANCE_DEFAULT)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    fun prompt(ctx: Context, c: Convo) {
        ensureChannel(ctx)
        val i = Intent(ctx, ReplyActivity::class.java)
            .putExtra("id", c.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, c.id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = if (c.replyAction != null) "🎤 Jawab do" else "Dekho"
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${c.sender} • ${c.appName}")
            .setContentText(c.text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, label, pi).build())
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(1000 + c.id, n)
        } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(1000 + id)
    }
}
