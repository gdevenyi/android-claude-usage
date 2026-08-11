package io.github.gdevenyi.claudeusage

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Material 3 type scale, in sp at scale 1.0. M3 uses weight 400 for body and
 * 500 for title/label — there is no bold step, so hierarchy comes from size.
 */
private object Type {
    const val TITLE_LARGE = 19f
    const val TITLE_MEDIUM = 16f
    const val TITLE_SMALL = 13f
    const val BODY_MEDIUM = 13f
    const val BODY_SMALL = 11f
    const val LABEL_LARGE = 13f
    const val LABEL_MEDIUM = 11f
    const val LABEL_SMALL = 10f
}

/** M3 4dp spacing grid. */
private object Space {
    const val XS = 4f
    const val SM = 8f
    const val MD = 16f
}

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = UsageWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)
        RefreshWorker.refreshNow(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
    }
}

class UsageWidget : GlanceAppWidget() {

    // Exact sizing: the widget scales continuously instead of snapping to a
    // few layouts, so a tall placement doesn't render at 2x1 text sizes.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read state inside the composition: provideGlance runs once per
        // session, so anything captured out here never changes on update.
        provideContent {
            val store = Store(context)
            GlanceTheme {
                Body(
                    needsLogin = !store.loggedIn || store.authBroken,
                    d = Usage.cached(context),
                    plan = store.plan,
                    preds = History.predictions(context),
                )
            }
        }
    }

    @Composable
    private fun Body(
        needsLogin: Boolean,
        d: Usage.Data?,
        plan: String,
        preds: Map<String, History.Prediction>,
    ) {
        val size = LocalSize.current
        // Scale from width only. Scaling with height too would grow the
        // content faster than the space it has to fit in, so a taller widget
        // would clip rather than show more.
        val scale = (size.width.value / 200f).coerceIn(1f, 1.5f)

        // Height available after padding, against what each tier needs at
        // scale 1 (measured on device): ~25dp compact, ~77 bars, ~112 with
        // resets, ~195 with header and footer. A block's height comes from the
        // large percentage, not the label, and nothing here shrinks to fit —
        // an over-generous threshold clips rather than degrades, so these are
        // deliberately conservative.
        val usable = size.height.value - Space.MD * 2
        val detailed = usable >= 215f * scale
        val showResets = usable >= 125f * scale

        // The body always opens the app (that's where the charts are); the
        // header's refresh icon keeps an in-place refresh at the detailed
        // tier, and opening the app refreshes anyway.
        val root = GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(Space.MD.dp)
            .clickable(actionStartActivity<MainActivity>())

        Column(modifier = root, verticalAlignment = Alignment.CenterVertically) {
            when {
                needsLogin -> Hint("Tap to log in", scale)
                d == null -> Hint("Tap to open", scale)
                usable < 80f * scale -> CompactRow(d, scale)
                else -> Full(d, plan, scale, detailed, showResets, preds)
            }
        }
    }

    // Glance containers hold at most 10 direct children and silently drop the
    // rest, so every section below emits exactly one child of the root Column.
    @Composable
    private fun ColumnScope.Full(
        d: Usage.Data,
        plan: String,
        scale: Float,
        detailed: Boolean,
        showResets: Boolean,
        preds: Map<String, History.Prediction>,
    ) {
        if (detailed) {
            Header(plan, scale)
            Spacer(GlanceModifier.defaultWeight())
        }

        BarBlock("Session (5h)", d.session, false, scale, detailed, showResets, preds["session"])
        if (detailed) Spacer(GlanceModifier.defaultWeight())
        else Spacer(GlanceModifier.height((Space.MD * scale).dp))
        BarBlock("Weekly (7d)", d.weekly, true, scale, detailed, showResets, preds["weekly"])

        if (detailed) {
            Spacer(GlanceModifier.defaultWeight())
            Footer(d, scale, preds)
        }
    }

    @Composable
    private fun Header(plan: String, scale: Float) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(scale)
            Spacer(GlanceModifier.width((Space.SM * scale).dp))
            Text(
                "Claude Usage",
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = (Type.TITLE_MEDIUM * scale).sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (plan.isNotEmpty()) {
                // Keeps the chip off the title when the weighted gap collapses.
                Spacer(GlanceModifier.width((Space.SM * scale).dp))
                Chip(plan, scale)
            }
            Spacer(GlanceModifier.width((Space.SM * scale).dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier
                    .size((Type.TITLE_MEDIUM * scale).dp)
                    .clickable(actionRunCallback<RefreshAction>()),
            )
        }
    }

    /** M3 assist chip: tonal container, pill shape, label type. */
    @Composable
    private fun Chip(text: String, scale: Float) {
        Text(
            text,
            modifier = GlanceModifier
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius((Space.MD * scale).dp)
                .padding(horizontal = (Space.SM * scale).dp, vertical = (Space.XS * scale).dp),
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = (Type.LABEL_MEDIUM * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }

    /** Label + right-aligned percent, the bar, and the reset countdown. */
    @Composable
    private fun BarBlock(
        label: String,
        w: Usage.Window?,
        withDay: Boolean,
        scale: Float,
        detailed: Boolean,
        showResets: Boolean,
        pred: History.Prediction?,
    ) {
        val pct = w?.pctInt ?: 0
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = (Type.TITLE_SMALL * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    "$pct%",
                    style = TextStyle(
                        color = severity(pct),
                        fontSize = (Type.TITLE_LARGE * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(GlanceModifier.height((Space.XS * scale).dp))
            LinearProgressIndicator(
                progress = pct / 100f,
                // M3 linear progress is a 4dp track.
                modifier = GlanceModifier.fillMaxWidth().height((Space.XS * scale).dp),
                color = severity(pct),
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
            if (showResets) {
                Spacer(GlanceModifier.height((Space.XS * scale).dp))
                Text(
                    Fmt.resetLine(w?.resetsAt, withDay, detailed, Fmt.outMark(pred, withDay)),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = (Type.BODY_SMALL * scale).sp,
                    ),
                )
            }
        }
    }

    @Composable
    private fun Footer(d: Usage.Data, scale: Float, preds: Map<String, History.Prediction>) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            if (d.scoped.isNotEmpty()) {
                Text(
                    "By model (7d)",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = (Type.TITLE_SMALL * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(GlanceModifier.height((Space.SM * scale).dp))
                d.scoped.take(2).forEach { s ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            s.name,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = (Type.BODY_MEDIUM * scale).sp,
                            ),
                        )
                        Spacer(GlanceModifier.defaultWeight())
                        LinearProgressIndicator(
                            progress = s.window.pctInt / 100f,
                            modifier = GlanceModifier
                                .width((72f * scale).dp)
                                .height((Space.XS * scale).dp),
                            color = severity(s.window.pctInt),
                            backgroundColor = GlanceTheme.colors.surfaceVariant,
                        )
                        Spacer(GlanceModifier.width((Space.SM * scale).dp))
                        Text(
                            "${s.window.pctInt}%",
                            style = TextStyle(
                                color = severity(s.window.pctInt),
                                fontSize = (Type.LABEL_LARGE * scale).sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
                // A model row has no width left for a reset line, so the
                // forecast gets its own — and only when there is one to show,
                // which keeps the tier's height budget intact the rest of the
                // time. Rows above are flush, so no spacer.
                val risk = d.scoped.firstOrNull { preds[it.name]?.atRisk == true }
                if (risk != null) {
                    Text(
                        risk.name + Fmt.outMark(preds[risk.name], true),
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = (Type.BODY_SMALL * scale).sp,
                        ),
                    )
                }
                Spacer(GlanceModifier.height((Space.SM * scale).dp))
            }
            Text(
                if (Fmt.stale(d.fetchedAt)) "Stale — updated ${Fmt.age(d.fetchedAt)}"
                else "Updated ${Fmt.age(d.fetchedAt)}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = (Type.LABEL_SMALL * scale).sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    /** Shortest form: logo + the two headline numbers. */
    @Composable
    private fun CompactRow(d: Usage.Data, scale: Float) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(scale)
            Spacer(GlanceModifier.width((Space.SM * scale).dp))
            Pct("S", d.session?.pctInt ?: 0, scale)
            Spacer(GlanceModifier.width((Space.MD * scale).dp))
            Pct("W", d.weekly?.pctInt ?: 0, scale)
        }
    }

    @Composable
    private fun Pct(label: String, pct: Int, scale: Float) {
        Text(
            "$label ",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = (Type.LABEL_MEDIUM * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            "$pct%",
            style = TextStyle(
                color = severity(pct),
                fontSize = (Type.TITLE_LARGE * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }

    @Composable
    private fun Hint(text: String, scale: Float) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(scale)
            Spacer(GlanceModifier.width((Space.SM * scale).dp))
            Text(
                text,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = (Type.TITLE_SMALL * scale).sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }

    @Composable
    private fun Logo(scale: Float) {
        Image(
            provider = ImageProvider(R.drawable.ic_stat),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size((Type.TITLE_MEDIUM * scale).dp),
        )
    }

    /**
     * Traffic lights: dynamic color can't express "healthy vs nearly spent",
     * and that reading is the point of the widget. Tones are picked per theme
     * so they stay legible on both a light and a dark widget background.
     */
    private fun severity(pct: Int): ColorProvider = ColorProvider(
        when {
            pct < 50 -> R.color.usage_ok
            pct < 80 -> R.color.usage_warn
            else -> R.color.usage_crit
        }
    )
}
