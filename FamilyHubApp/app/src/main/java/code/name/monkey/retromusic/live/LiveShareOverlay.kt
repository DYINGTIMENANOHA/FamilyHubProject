package code.name.monkey.retromusic.live

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small draggable floating bubble shown while screen-share hosting, so the host can
 * toggle mic/system sound or end the live without switching back to the app. Requires
 * the "display over other apps" permission ([Settings.canDrawOverlays]).
 */
class LiveShareOverlay(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onToggleMic()
        fun onToggleSystemAudio()
        fun onOpenRoom()
        fun onEndLive()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var micButton: TextView? = null
    private var soundButton: TextView? = null
    private var panel: LinearLayout? = null
    private var expanded = false

    fun isShowing(): Boolean = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(showSystemAudioToggle: Boolean, micOn: Boolean, soundOn: Boolean) {
        if (!Settings.canDrawOverlays(context) || root != null) return

        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        fun pillButton(label: String, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#494949"))
                }
                setOnClickListener { onClick() }
            }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        val bubbleView = TextView(context).apply {
            text = "🔴 LIVE"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#DD202020"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
        }

        val panelView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#EE202020"))
                setStroke(dp(1), Color.parseColor("#44FFFFFF"))
            }
        }

        val spacing = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }

        val mic = pillButton(micLabel(micOn)) { listener.onToggleMic() }
        micButton = mic
        panelView.addView(mic, LinearLayout.LayoutParams(spacing).apply { topMargin = 0 })

        if (showSystemAudioToggle) {
            val sound = pillButton(soundLabel(soundOn)) { listener.onToggleSystemAudio() }
            soundButton = sound
            panelView.addView(sound, LinearLayout.LayoutParams(spacing))
        }

        panelView.addView(pillButton("Open Room") { listener.onOpenRoom() }, LinearLayout.LayoutParams(spacing))
        panelView.addView(
            pillButton("End Live") { listener.onEndLive() }.apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#CC3333"))
                }
            },
            LinearLayout.LayoutParams(spacing),
        )

        container.addView(bubbleView)
        container.addView(
            panelView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(120)
        }

        // Drag anywhere on the bubble; a short press without movement toggles the panel.
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        bubbleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging || dx * dx + dy * dy > touchSlop * touchSlop) {
                        dragging = true
                        // gravity END: x grows leftwards
                        params.x = (startX - dx.toInt())
                        params.y = (startY + dy.toInt())
                        root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) togglePanel()
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(container, params) }
            .onSuccess {
                root = container
                layoutParams = params
                bubble = bubbleView
                panel = panelView
            }
    }

    private fun togglePanel() {
        expanded = !expanded
        panel?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    fun updateState(micOn: Boolean, soundOn: Boolean) {
        micButton?.text = micLabel(micOn)
        soundButton?.text = soundLabel(soundOn)
    }

    private fun micLabel(on: Boolean) = if (on) "Mic: ON" else "Mic: OFF"
    private fun soundLabel(on: Boolean) = if (on) "Sound: ON" else "Sound: OFF"

    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        layoutParams = null
        bubble = null
        micButton = null
        soundButton = null
        panel = null
        expanded = false
    }
}
