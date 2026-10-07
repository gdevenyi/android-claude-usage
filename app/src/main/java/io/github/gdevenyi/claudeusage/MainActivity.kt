package io.github.gdevenyi.claudeusage

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val intervals = listOf(15, 30, 60)

    // State changes from off-screen writers (the refresh worker, a code
    // exchange finishing after a rotation destroyed its activity) repaint
    // whichever instance is currently visible. Field: prefs hold listeners
    // weakly, so an inline lambda would be collected.
    private val onPrefChange =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateUi() }

    // Fields, not inline: updateUi() detaches them while it syncs the
    // switches, so only a real user toggle — never a programmatic
    // setChecked — asks for the notification permission.
    private val onNotifToggle = CompoundButton.OnCheckedChangeListener { _, checked ->
        Store(this).notifEnabled = checked
        if (checked) askNotifPermission()
        Notif.update(this)
    }

    private val onAlertToggle = CompoundButton.OnCheckedChangeListener { _, checked ->
        Store(this).alertsEnabled = checked
        if (checked) askNotifPermission()
    }

    private fun askNotifPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        Store(this).prefs.registerOnSharedPreferenceChangeListener(onPrefChange)
        updateUi()
    }

    override fun onStop() {
        super.onStop()
        Store(this).prefs.unregisterOnSharedPreferenceChangeListener(onPrefChange)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Edge-to-edge is mandatory since Android 15: inset the content ourselves.
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // ime() too, or the keyboard covers the code field with no way
            // to scroll to it (edge-to-edge stops the system resizing us).
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val store = Store(this)

        findViewById<MaterialButton>(R.id.loginBtn).setOnClickListener {
            val verifier = Auth.newVerifier()
            store.pendingVerifier = verifier
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Auth.authorizeUrl(verifier))))
            updateUi()
        }

        findViewById<MaterialButton>(R.id.submitBtn).setOnClickListener {
            val input = findViewById<TextView>(R.id.codeInput)
            val code = input.text.toString()
            val verifier = store.pendingVerifier
            if (code.isBlank() || verifier == null) {
                Toast.makeText(this, "Start login first, then paste the code", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            it.isEnabled = false
            Thread {
                val err = try {
                    Auth.exchangeCode(this, code, verifier)
                    store.pendingVerifier = null
                    null
                } catch (e: Exception) {
                    e.message
                }
                runOnUiThread {
                    it.isEnabled = true
                    if (err == null) {
                        Toast.makeText(this, "Logged in", Toast.LENGTH_SHORT).show()
                        input.text = ""
                        getSystemService(InputMethodManager::class.java)
                            .hideSoftInputFromWindow(input.windowToken, 0)
                        RefreshWorker.schedule(this)
                        RefreshWorker.refreshNow(this)
                    } else {
                        Toast.makeText(this, "Login failed: $err", Toast.LENGTH_LONG).show()
                    }
                    updateUi()
                }
            }.start()
        }

        findViewById<MaterialButton>(R.id.logoutBtn).setOnClickListener {
            store.clearTokens()
            store.cachedUsage = null
            store.plan = ""
            History.clear(this)
            Notif.update(this)
            RefreshWorker.refreshNow(this) // repaints widget to logged-out state
            updateUi()
        }

        findViewById<MaterialButton>(R.id.releasesBtn).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/gdevenyi/android-claude-usage/releases"),
                )
            )
        }

        findViewById<MaterialSwitch>(R.id.notifSwitch).setOnCheckedChangeListener(onNotifToggle)
        findViewById<MaterialSwitch>(R.id.alertSwitch).setOnCheckedChangeListener(onAlertToggle)
        // One automatic ask, on first launch only. Android 13 stops showing
        // the dialog after two denials, so repeated surprise prompts on every
        // start would burn that quota with the switch still reading "on".
        if ((store.notifEnabled || store.alertsEnabled) && !store.notifAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            store.notifAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val interval = findViewById<MaterialAutoCompleteTextView>(R.id.intervalInput)
        interval.setText("${store.intervalMin} min", false)
        interval.setOnItemClickListener { _, _, pos, _ ->
            if (store.intervalMin != intervals[pos]) {
                store.intervalMin = intervals[pos]
                RefreshWorker.schedule(this)
            }
        }

        RefreshWorker.schedule(this)
        // An app upgrade kills the Glance session and leaves the widget stuck
        // on its loading view; this repaints it. Sideloading makes that common.
        RefreshWorker.refreshNow(this)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Quota exhausted: the dialog will never show again on its own.
            Toast.makeText(
                this, "Notifications are blocked — allow them in system settings",
                Toast.LENGTH_LONG,
            ).show()
        }
        Notif.update(this)
    }

    private fun updateUi() {
        val store = Store(this)
        findViewById<TextView>(R.id.status).text = when {
            store.authBroken -> "Session expired — log in again"
            store.loggedIn -> listOfNotNull(
                "Logged in",
                store.plan.ifEmpty { null },
                Usage.cached(this)
                    ?.let { "session ${it.session?.pctInt ?: 0}%, weekly ${it.weekly?.pctInt ?: 0}%" },
            ).joinToString(" · ")
            else -> "Not logged in"
        }
        val needsLogin = !store.loggedIn || store.authBroken
        val pasteVis = if (needsLogin && store.pendingVerifier != null) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.loginBtn).visibility =
            if (needsLogin) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.logoutBtn).visibility =
            if (store.loggedIn) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.codeHint).visibility = pasteVis
        findViewById<TextInputLayout>(R.id.codeField).visibility = pasteVis
        findViewById<MaterialButton>(R.id.submitBtn).visibility = pasteVis
        val sw = findViewById<MaterialSwitch>(R.id.notifSwitch)
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = store.notifEnabled
        sw.setOnCheckedChangeListener(onNotifToggle)
        val alertSw = findViewById<MaterialSwitch>(R.id.alertSwitch)
        alertSw.setOnCheckedChangeListener(null)
        alertSw.isChecked = store.alertsEnabled
        alertSw.setOnCheckedChangeListener(onAlertToggle)
        updateDetails()
        updateCharts()
    }

    /** What the widget has no room for. The section hides while empty. */
    private fun updateDetails() {
        val d = if (Store(this).loggedIn) Usage.cached(this) else null
        val shares = d?.breakdown.orEmpty()
        findViewById<TextView>(R.id.productTitle).visibility =
            if (shares.isEmpty()) View.GONE else View.VISIBLE
        val rows = findViewById<LinearLayout>(R.id.productRows)
        rows.removeAllViews()
        shares.forEach { sh ->
            // A share is not a risk: the bar keeps the default primary
            // colour, not the traffic lights.
            val row = layoutInflater.inflate(R.layout.share_row, rows, false)
            row.findViewById<TextView>(R.id.shareName).text = sh.name
            row.findViewById<LinearProgressIndicator>(R.id.shareBar).progress = sh.pct.roundToInt()
            row.findViewById<TextView>(R.id.sharePct).text = "${sh.pct.roundToInt()}%"
            rows.addView(row)
        }

        val extra = d?.extra
        listOf(R.id.extraTitle, R.id.extraRow, R.id.extraBar).forEach {
            findViewById<View>(it).visibility = if (extra == null) View.GONE else View.VISIBLE
        }
        val reason = findViewById<TextView>(R.id.extraReason)
        reason.visibility = if (extra?.disabledReason.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (extra != null) {
            findViewById<TextView>(R.id.extraSpent).text =
                "${extra.money(extra.used)} / ${extra.money(extra.limit)} spent"
            // Money spent is a risk, so this one does take the traffic lights.
            val color = getColor(Severity.colorForLevel(Severity.level(extra.pct, "")))
            findViewById<TextView>(R.id.extraPct).apply {
                text = "${extra.pct}%"
                setTextColor(color)
            }
            findViewById<LinearProgressIndicator>(R.id.extraBar).apply {
                progress = extra.pct.coerceAtMost(100)
                setIndicatorColor(color)
            }
            reason.text = "Disabled: ${extra.disabledReason.replace('_', ' ')}"
        }

        findViewById<View>(R.id.details).visibility =
            if (shares.isEmpty() && extra == null) View.GONE else View.VISIBLE
    }

    private fun updateCharts() {
        val now = System.currentTimeMillis()
        val hist = History.readAll(this)
        val preds = History.predictions(this, now)
        val root = findViewById<View>(R.id.root)
        val primary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary)
        val tertiary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorTertiary)
        val grid = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(root, com.google.android.material.R.attr.colorOutline), 70
        )
        val label = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        val weeklyMarks = hist["weekly"].orEmpty().map { it.r }.distinct()
        val modelKey = hist.keys.firstOrNull { it.contains("fable", ignoreCase = true) }
            ?: (hist.keys - setOf("session", "weekly")).firstOrNull()
        // Titles carry the " •" of the limit that binds right now.
        val d = Usage.cached(this)
        val modelLabel = modelKey?.let { k -> Fmt.label(k, d?.scoped?.firstOrNull { it.name == k }?.window) }

        // The window figures span the full current quota window (start to
        // reset), so the dashed trend visibly races the right edge. Between
        // sessions the newest cycle's window is shown as-is.
        fun window(key: String, ms: Long, withDay: Boolean): Triple<Long, Long, String> {
            val r = hist[key]?.lastOrNull()?.r
            return if (r != null) Triple(r - ms, r, "resets ${Fmt.clock(r, withDay)}")
            else Triple(now - ms, now, "now")
        }

        findViewById<TextView>(R.id.sessionTitle).text =
            Fmt.label("Session", d?.session) + " — current 5 h window"
        val (sMin, sMax, sEnd) = window("session", History.SESSION_MS, false)
        findViewById<HistoryChartView>(R.id.sessionChart).show(
            listOf(HistoryChartView.Series(hist["session"].orEmpty(), preds["session"], primary)),
            sMin, sMax, now, Fmt.clock(sMin, false), sEnd, emptyList(), grid, label,
        )

        // The model rides the weekly figure too: it has a weekly limit of
        // its own, and either can be the one that runs out first.
        val weeklySeries = mutableListOf(
            HistoryChartView.Series(hist["weekly"].orEmpty(), preds["weekly"], primary)
        )
        modelKey?.let { weeklySeries += HistoryChartView.Series(hist[it].orEmpty(), preds[it], tertiary) }
        val weeklyLabel = Fmt.label("Weekly", d?.weekly)
        findViewById<TextView>(R.id.weeklyTitle).text =
            if (modelLabel != null) "$weeklyLabel & $modelLabel — current 7 d window"
            else "$weeklyLabel — current 7 d window"
        val (wMin, wMax, wEnd) = window("weekly", History.WEEKLY_MS, true)
        findViewById<HistoryChartView>(R.id.weeklyChart).show(
            weeklySeries, wMin, wMax, now, Fmt.clock(wMin, true), wEnd, weeklyMarks, grid, label,
        )

        // The 3-week figure is history only: total plus per-model, no trend.
        val historySeries = mutableListOf(
            HistoryChartView.Series(hist["weekly"].orEmpty(), null, primary)
        )
        modelKey?.let { historySeries += HistoryChartView.Series(hist[it].orEmpty(), null, tertiary) }
        findViewById<TextView>(R.id.historyTitle).text =
            if (modelKey != null) "History, weekly & $modelKey — last 3 weeks"
            else "History, weekly — last 3 weeks"
        findViewById<HistoryChartView>(R.id.historyChart).show(
            historySeries, now - History.KEEP_MS, now, now, "21 d ago", "now", weeklyMarks, grid, label,
        )
    }
}
