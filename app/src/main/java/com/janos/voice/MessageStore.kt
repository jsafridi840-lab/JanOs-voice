package com.janos.voice

import android.app.Notification
import android.app.PendingIntent

/** One chat/conversation we can read and (if the app allows) reply to. */
data class Convo(
    val id: Int,
    val pkg: String,
    val appName: String,
    val sender: String,
    val text: String,
    val time: Long,
    val replyAction: Notification.Action?,
    val openIntent: PendingIntent?
)

/** Keeps the latest message per conversation in memory. */
object MessageStore {
    private val convos = LinkedHashMap<String, Convo>()
    private val recentKeys = LinkedHashMap<String, Long>()
    private var nextId = 1

    @Synchronized
    fun put(key: String, pkg: String, appName: String, sender: String, text: String,
            action: Notification.Action?, open: PendingIntent?): Convo {
        val old = convos[key]
        val c = Convo(old?.id ?: nextId++, pkg, appName, sender, text,
            System.currentTimeMillis(), action ?: old?.replyAction, open ?: old?.openIntent)
        convos.remove(key)
        convos[key] = c
        while (convos.size > 40) convos.remove(convos.keys.first())
        return c
    }

    /** True if we already handled this exact message in the last 3 minutes (apps re-post). */
    @Synchronized
    fun seenRecently(sig: String): Boolean {
        val now = System.currentTimeMillis()
        recentKeys.entries.removeAll { now - it.value > 180_000 }
        if (recentKeys.containsKey(sig)) return true
        recentKeys[sig] = now
        return false
    }

    @Synchronized
    fun byId(id: Int): Convo? = convos.values.firstOrNull { it.id == id }

    @Synchronized
    fun all(): List<Convo> = convos.values.toList().reversed()

    @Synchronized
    fun clear() = convos.clear()
}
