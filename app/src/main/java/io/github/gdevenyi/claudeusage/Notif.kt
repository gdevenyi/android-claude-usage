package io.github.gdevenyi.claudeusage

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Fmt {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val dayTime = DateTimeFormatter.ofPattern("EEE HH:mm")

    fun reset(at: Instant?, withDay: Boolean): String {
        at ?: return "—"
        val local = at.atZone(ZoneId.systemDefault())
        return local.format(if (withDay) dayTime else time)
    }

    /** Time left until a window resets, e.g. "3h 43m" or "6d 15h". */
    fun until(at: Instant?): String {
        at ?: return ""
        var s = at.epochSecond - System.currentTimeMillis() / 1000
        if (s <= 0) return "now"
        val d = s / 86400; s %= 86400
        val h = s / 3600; s %= 3600
        val m = s / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    fun age(fetchedAt: Long): String {
        val min = (System.currentTimeMillis() - fetchedAt) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            else -> "${min / 60} h ago"
        }
    }

    fun stale(fetchedAt: Long) = System.currentTimeMillis() - fetchedAt > 60 * 60_000
}

object Notif {
    private const val CHANNEL = "usage"
    private const val ID = 1

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val store = Store(ctx)
        if (!store.notifEnabled || !store.loggedIn) {
            nm.cancel(ID)
            return
        }
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Usage", NotificationManager.IMPORTANCE_LOW)
        )

        val tap = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, RefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val d = Usage.cached(ctx)
        val title: String
        val text: String
        if (store.authBroken) {
            title = "Claude: log in again"
            text = "Open the app to re-authenticate"
        } else if (d == null) {
            title = "Claude usage"
            text = "Waiting for first refresh…"
        } else {
            title = "Session ${d.session?.pct ?: 0}%  ·  Weekly ${d.weekly?.pct ?: 0}%" +
                store.plan.let { if (it.isEmpty()) "" else "  ·  $it" }
            text = "Session resets in ${Fmt.until(d.session?.resetsAt)}" +
                (if (Fmt.stale(d.fetchedAt)) "  (updated ${Fmt.age(d.fetchedAt)})" else "")
        }

        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (d != null && !store.authBroken) {
            // Real progress bars need custom views; the decorated style keeps
            // the system's own icon/name/time header around them.
            builder.setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(collapsedView(ctx, d, title, text))
                .setCustomBigContentView(expandedView(ctx, d))
        }
        val n = builder.build()
        nm.notify(ID, n)
    }

    private fun severityColor(pct: Int) = when {
        pct < 50 -> R.color.usage_ok
        pct < 80 -> R.color.usage_warn
        else -> R.color.usage_crit
    }

    private fun collapsedView(
        ctx: Context,
        d: Usage.Data,
        title: String,
        text: String,
    ): RemoteViews {
        val pct = d.session?.pct ?: 0
        return RemoteViews(ctx.packageName, R.layout.notif_collapsed).apply {
            setTextViewText(R.id.headline, title)
            setTextViewText(R.id.subline, text)
            setProgressBar(R.id.collapsedBar, 100, pct, false)
            setColorStateList(R.id.collapsedBar, "setProgressTintList", severityColor(pct))
        }
    }

    private fun expandedView(ctx: Context, d: Usage.Data): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.notif_expanded)

        fun block(pctId: Int, barId: Int, resetId: Int, w: Usage.Window?, withDay: Boolean) {
            val pct = w?.pct ?: 0
            v.setTextViewText(pctId, "$pct%")
            // Resolve at apply time (like the bar tints) so a light/dark
            // switch re-inflates with the right palette, not a baked-in one.
            v.setColor(pctId, "setTextColor", severityColor(pct))
            v.setProgressBar(barId, 100, pct, false)
            v.setColorStateList(barId, "setProgressTintList", severityColor(pct))
            v.setTextViewText(
                resetId,
                "resets ${Fmt.reset(w?.resetsAt, withDay)} · in ${Fmt.until(w?.resetsAt)}",
            )
        }

        block(R.id.sessionPct, R.id.sessionBar, R.id.sessionReset, d.session, false)
        block(R.id.weeklyPct, R.id.weeklyBar, R.id.weeklyReset, d.weekly, true)

        val model = d.scoped.firstOrNull()
        if (model == null) {
            v.setViewVisibility(R.id.modelRow, View.GONE)
        } else {
            v.setViewVisibility(R.id.modelRow, View.VISIBLE)
            v.setTextViewText(R.id.modelName, "${model.name} (7d)")
            v.setTextViewText(R.id.modelPct, "${model.window.pct}%")
            v.setColor(R.id.modelPct, "setTextColor", severityColor(model.window.pct))
            v.setProgressBar(R.id.modelBar, 100, model.window.pct, false)
            v.setColorStateList(R.id.modelBar, "setProgressTintList", severityColor(model.window.pct))
        }

        v.setTextViewText(
            R.id.updated,
            if (Fmt.stale(d.fetchedAt)) "Stale — updated ${Fmt.age(d.fetchedAt)} · tap to refresh"
            else "Updated ${Fmt.age(d.fetchedAt)} · tap to refresh",
        )
        return v
    }
}
