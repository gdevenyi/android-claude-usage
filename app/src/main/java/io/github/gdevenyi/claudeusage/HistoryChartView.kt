package io.github.gdevenyi.claudeusage

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Percent-over-time line chart: history as solid polylines (split at window
 * resets, so saw-teeth show as separate segments), plus each confident trend
 * as a dashed extension to its reset, with the 100% crossing marked when it
 * lands before the reset. Dumb by design — callers hand it prepared series.
 */
class HistoryChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    class Series(
        val points: List<History.Point>,
        val pred: History.Prediction?,
        val color: Int,
    )

    private var series: List<Series> = emptyList()
    private var resetMarks: List<Long> = emptyList()
    private var xMin = 0L
    private var xMax = 0L
    private var now = 0L
    private var startLabel = ""

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dash = Paint(line).apply {
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = DashPathEffect(
            floatArrayOf(4f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f
        )
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
    }

    private var gridColor = 0
    private var labelColor = 0
    private var critColor = 0

    fun show(
        series: List<Series>,
        rangeMs: Long,
        now: Long,
        startLabel: String,
        resetMarks: List<Long>,
        gridColor: Int,
        labelColor: Int,
    ) {
        this.series = series
        this.now = now
        this.xMin = now - rangeMs
        // The dashed extensions run into the future: stretch the axis to the
        // furthest one so predictions are never drawn off-canvas.
        this.xMax = series.mapNotNull { s -> s.pred?.let { minOf(it.runOutAt ?: it.resetsAt, it.resetsAt) } }
            .plus(now).max()
        this.startLabel = startLabel
        this.resetMarks = resetMarks
        this.gridColor = gridColor
        this.labelColor = labelColor
        this.critColor = context.getColor(R.color.usage_crit)
        invalidate()
    }

    private fun x(t: Long, w: Float) = (t - xMin).toFloat() / (xMax - xMin).toFloat() * w
    private fun y(p: Double, h: Float) = h - (p.coerceIn(0.0, 100.0) / 100.0).toFloat() * h

    override fun onDraw(canvas: Canvas) {
        val labelBand = text.textSize * 1.6f
        val w = width.toFloat()
        val h = height.toFloat() - labelBand
        if (w <= 0 || h <= 0 || xMax <= xMin) return

        text.color = labelColor
        if (series.all { it.points.none { p -> p.t >= xMin } }) {
            canvas.drawText("No data yet", (w - text.measureText("No data yet")) / 2, h / 2, text)
            return
        }

        // Grid: 0 / 50 / 100%, plus reset marks and a hairline at "now".
        thin.color = gridColor
        for (pct in intArrayOf(0, 50, 100)) {
            val gy = y(pct.toDouble(), h)
            canvas.drawLine(0f, gy, w, gy, thin)
        }
        for (m in resetMarks) if (m in xMin..xMax) {
            canvas.drawLine(x(m, w), 0f, x(m, w), h, thin)
        }
        canvas.drawLine(x(now, w), 0f, x(now, w), h, thin)

        for (s in series) {
            // History: one path per cycle, so resets break the line. Each
            // sample also gets a dot — a one-sample cycle has no line at all.
            line.color = s.color
            fill.color = s.color
            val dot = line.strokeWidth * 0.8f
            var path: Path? = null
            var prevR = 0L
            for (p in s.points) {
                if (p.t < xMin) continue
                if (path == null || p.r != prevR) {
                    path?.let { canvas.drawPath(it, line) }
                    path = Path().apply { moveTo(x(p.t, w), y(p.p, h)) }
                } else {
                    path.lineTo(x(p.t, w), y(p.p, h))
                }
                canvas.drawCircle(x(p.t, w), y(p.p, h), dot, fill)
                prevR = p.r
            }
            path?.let { canvas.drawPath(it, line) }

            // Confident trend: dashed from the fit's endpoint to the reset —
            // or to the 100% crossing, marked, when it lands before the reset.
            val pred = s.pred ?: continue
            val endT = if (pred.atRisk) pred.runOutAt!! else pred.resetsAt
            val endP = pred.lastFitP + pred.slopePerMs * (endT - pred.lastT)
            dash.color = s.color
            canvas.drawLine(
                x(pred.lastT, w), y(pred.lastFitP, h),
                x(endT, w), y(endP, h), dash,
            )
            if (pred.atRisk) {
                fill.color = critColor
                canvas.drawCircle(x(endT, w), y(100.0, h), 4f * resources.displayMetrics.density, fill)
            }
        }

        // Y labels ride the top gridline; x labels sit in the bottom band.
        canvas.drawText("100%", 2f, y(100.0, h) + text.textSize, text)
        canvas.drawText(startLabel, 0f, height.toFloat() - text.textSize * 0.3f, text)
        // Clamped: with no future extension "now" sits on the right edge.
        val nowW = text.measureText("now")
        canvas.drawText(
            "now",
            (x(now, w) - nowW / 2).coerceIn(0f, w - nowW),
            height.toFloat() - text.textSize * 0.3f, text,
        )
    }
}
