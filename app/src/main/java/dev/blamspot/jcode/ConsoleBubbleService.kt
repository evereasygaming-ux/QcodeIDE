package dev.blamspot.jcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.blamspot.jcode.core.term.visibleScreenText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The floating console bubble: a draggable `<Q/>` icon that lives ABOVE every app.
 *
 * A video render or a build can run for many minutes, and the phone does not have to stay inside
 * QCODE while it does. With this service on, the console follows the user onto the home screen and
 * into other apps as a small bubble; tapping it expands the live terminal mirror in place. The
 * terminal sessions themselves are process-lifetime ([TerminalSessionHost]) PTYs, so the mirror
 * keeps updating with the app closed as long as this service — and with it the process — is alive.
 *
 * Requires the "display over other apps" grant ([android.provider.Settings.canDrawOverlays]); the
 * in-app toggle routes the user to that Settings page before starting the service.
 */
class ConsoleBubbleService : Service() {

    companion object {
        private const val ACTION_START = "dev.blamspot.jcode.bubble.START"
        private const val ACTION_STOP = "dev.blamspot.jcode.bubble.STOP"
        private const val CHANNEL_ID = "console_bubble"
        private const val NOTIFICATION_ID = 7102

        /** CoreX palette: the bubble's shell and accent (kept in sync with the design tokens). */
        private const val COLOR_SHELL = 0xF00B0B0E.toInt()
        private const val COLOR_SURFACE = 0xF0131317.toInt()
        private const val COLOR_ACCENT = 0xFFFF4D2E.toInt()
        private const val COLOR_TEXT = 0xFFF4F0EC.toInt()
        private const val COLOR_DIM = 0xFF9A98A3.toInt()

        /** Observable so the in-app toggle chip reflects the service without a broadcast. */
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** Screen-poll cadence: fast enough to read live output, slow enough to be free. */
        const val POLL_INTERVAL_MS = 700L

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ConsoleBubbleService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            // A started service is already foreground: a plain startService needs no reschedule.
            context.startService(
                Intent(context, ConsoleBubbleService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var overlayRoot: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var card: LinearLayout? = null
    private var cardTitle: TextView? = null
    private var consoleText: TextView? = null
    private var consoleScroll: ScrollView? = null

    private val poll = object : Runnable {
        override fun run() {
            updateConsole()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        attachOverlay()
        _running.value = true
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        // Sticky: a user swipe of the recents card must not take the bubble with it.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        overlayRoot?.let { root ->
            runCatching { windowManager.removeView(root) }
        }
        overlayRoot = null
        _running.value = false
        super.onDestroy()
    }

    // ---- overlay --------------------------------------------------------------

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    private fun attachOverlay() {
        if (overlayRoot != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // The toggle checks this too; a revoked grant between tap and start lands here.
            stopSelf()
            return
        }

        val metrics = resources.displayMetrics
        val root = FrameLayout(this)

        // The bubble: a small CoreX mark that is always tappable.
        val bubbleView = TextView(this).apply {
            text = "<Q/>"
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_ACCENT)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(COLOR_SHELL)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), COLOR_ACCENT)
            }
        }

        // The expanded console card, hidden until the bubble is tapped.
        val titleView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT)
            textSize = 12f
            maxLines = 1
        }
        val openApp = TextView(this).apply {
            text = "OPEN"
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_ACCENT)
            textSize = 11f
            setOnClickListener { returnToApp() }
        }
        val collapse = TextView(this).apply {
            text = "–"
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_DIM)
            textSize = 14f
            setOnClickListener { setExpanded(false) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                openApp,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(collapse, LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9.5f
            setTextColor(COLOR_TEXT)
            setPadding(dp(10), 0, dp(10), dp(10))
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        // Horizontal panning so wide build output stays readable instead of hard-wrapping mid-token.
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(COLOR_SURFACE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF3A3A45.toInt())
            }
            addView(header)
            addView(hScroll)
        }

        root.addView(
            bubbleView,
            FrameLayout.LayoutParams(dp(46), dp(46)),
        )
        root.addView(
            cardView,
            FrameLayout.LayoutParams((metrics.widthPixels * 0.92f).toInt(), dp(220)),
        )

        // One gesture handler drives both drag and tap: move beyond the slop drags the overlay, a
        // tap (movement within the slop) toggles bubble <-> card.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }
        overlayParams = params

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragging = false
        bubbleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (dragging || dx * dx + dy * dy > dp(6) * dp(6)) {
                        dragging = true
                        params.x = downX + dx
                        params.y = downY + dy
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) setExpanded(true)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(root, params)
        overlayRoot = root
        bubble = bubbleView
        card = cardView
        cardTitle = titleView
        consoleText = text
        consoleScroll = scroll
    }

    private fun setExpanded(expanded: Boolean) {
        bubble?.visibility = if (expanded) View.GONE else View.VISIBLE
        card?.visibility = if (expanded) View.VISIBLE else View.GONE
        // A visibility swap changes the root's size; re-measure or the window keeps the old bounds
        // and the card is either clipped or floats far past its content.
        val root = overlayRoot
        val params = overlayParams
        if (root != null && params != null) runCatching { windowManager.updateViewLayout(root, params) }
        if (expanded) {
            consoleText?.text = currentText().second
            consoleScroll?.post { consoleScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun returnToApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        runCatching { startActivity(launch) }
        setExpanded(false)
    }

    // ---- live mirror ----------------------------------------------------------

    /** The most recently active session (id, screen text), or null when nothing is running. */
    private fun currentText(): Pair<String, String> {
        val manager = TerminalSessionHost.existingManager() ?: return "" to ""
        val sessions = manager.sessions.values
        val latest = sessions.maxByOrNull { it.lastActivityAt } ?: return "" to ""
        val text = runCatching { latest.visibleScreenText() }.getOrDefault("")
        return latest.label to text
    }

    private fun updateConsole() {
        val (label, text) = currentText()
        cardTitle?.text = if (label.isBlank()) "console — no terminal running" else "console — $label"
        consoleText?.text = when {
            text.isNotBlank() -> text
            TerminalSessionHost.existingManager()?.sessions?.isNotEmpty() == true ->
                "(no output yet)"
            else -> "No terminal is running.\n\nOpen QCODE and start one — the console follows it " +
                "here, even after you leave the app."
        }
        consoleScroll?.post { consoleScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- notification ---------------------------------------------------------

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating console",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Shows the terminal console bubble over other apps."
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ConsoleBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Floating console")
            .setContentText("The terminal console is available over other apps.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()
    }
}
