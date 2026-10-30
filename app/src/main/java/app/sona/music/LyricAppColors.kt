package app.sona.music

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.text.Collator

internal fun hasLyricUsageAccess(context: Context): Boolean = runCatching {
    context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

/** Usage signals are read off the UI thread; interaction also covers already-resumed small windows. */
internal class LyricForegroundApp(context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = 0L
    private var lastSignal = 0L
    private var current: String? = null
    // Public usage events expose class names, not activity instance IDs.
    private val visible = mutableMapOf<Pair<String, String?>, Long>()
    private val resumed = mutableSetOf<Pair<String, String?>>()

    private fun fallback(): String? = visible.filterKeys { it in resumed }.maxByOrNull { it.value }?.key?.first
        ?: visible.maxByOrNull { it.value }?.key?.first

    fun read(): String? {
        val now = System.currentTimeMillis()
        val start = if (lastQuery == 0L || now < lastQuery) now - 86_400_000 else lastQuery - 1000
        if (now < lastQuery) { lastSignal = 0; current = null; visible.clear(); resumed.clear() }
        val events = usage.queryEvents(start, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val activity = pkg to event.className
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    visible[activity] = event.timeStamp
                    resumed += activity
                    if (event.timeStamp >= lastSignal) { current = pkg; lastSignal = event.timeStamp }
                }
                UsageEvents.Event.USER_INTERACTION -> {
                    // Notifications can report interaction for a background app; only follow visible activities.
                    if (visible.keys.any { it.first == pkg } && event.timeStamp >= lastSignal) {
                        visible.keys.filter { it.first == pkg }.forEach { visible[it] = event.timeStamp }
                        current = pkg; lastSignal = event.timeStamp
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    resumed -= activity
                    if (event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) visible -= activity
                    if (current == pkg && resumed.none { it.first == pkg } && event.timeStamp >= lastSignal) {
                        current = fallback(); lastSignal = event.timeStamp
                    }
                }
            }
        }
        lastQuery = now
        return current
    }
}

internal data class LyricColorApp(val packageName: String, val label: String)

@Suppress("DEPRECATION")
internal fun lyricColorApps(context: Context): List<LyricColorApp> {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val order = Collator.getInstance()
    return manager.queryIntentActivities(intent, 0).map {
        LyricColorApp(it.activityInfo.packageName, it.loadLabel(manager).toString())
    }.filter { it.packageName != context.packageName }.distinctBy { it.packageName }
        .sortedWith { a, b -> order.compare(a.label, b.label) }
}
