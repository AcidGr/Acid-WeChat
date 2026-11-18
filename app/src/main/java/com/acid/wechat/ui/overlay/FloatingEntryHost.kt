package com.acid.wechat.ui.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Attaches a permission-free, draggable floating pill inside matching WeChat activities
 * so features can be invoked externally in context without leaving the current screen.
 */
object FloatingEntryHost {
    private const val TAG = "FloatingEntryHost"
    private const val VIEW_TAG = "acid_floating_entry_view"

    private val attached = AtomicBoolean(false)

    fun attach(app: Application) {
        if (!attached.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                syncForActivity(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                removeFrom(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                removeFrom(activity)
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
        })
        AcidLogger.i(TAG, "attached ActivityLifecycleCallbacks for floating entries")
    }

    private fun syncForActivity(activity: Activity) {
        runCatching {
            val actions = FloatingActionRegistry.matching(activity)
            if (actions.isEmpty()) {
                removeFrom(activity)
                return
            }
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            root.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                removeFrom(activity)
                val pill = createFloatingPill(activity, root, actions)
                root.addView(pill)
            }
        }.onFailure {
            AcidLogger.w(TAG, "syncForActivity failed on ${activity.javaClass.simpleName}", it)
        }
    }

    private fun removeFrom(activity: Activity) {
        runCatching {
            val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val existing = root.findViewWithTag<View>(VIEW_TAG) ?: return
            root.removeView(existing)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingPill(
        activity: Activity,
        root: ViewGroup,
        actions: List<FloatingActionSpec>,
    ): View {
        val density = activity.resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()
        val marginEdge = (12 * density).toInt()
        val dotSize = (7 * density).toInt()

        val pill = LinearLayout(activity).apply {
            tag = VIEW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            elevation = 6 * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(Color.WHITE)
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#E5E7EB"))
            }
        }

        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#07C160"))
            }
        }
        pill.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginEnd = (6 * density).toInt()
        })

        val labelText = if (actions.size == 1) actions[0].label else "Acid"
        val label = TextView(activity).apply {
            text = labelText
            setTextColor(Color.parseColor("#111827"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        pill.addView(label)

        val rightSide = ConfigStore.getBoolean(ConfigStore.KEY_FLOAT_BTN_RIGHT_SIDE, true)
        val yRatio = ConfigStore.getString(ConfigStore.KEY_FLOAT_BTN_Y_RATIO, "0.62")
            .toFloatOrNull()?.coerceIn(0.15f, 0.85f) ?: 0.62f

        val rootH = root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val rootW = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = marginEdge
            topMargin = (rootH * yRatio).toInt()
        }
        pill.layoutParams = lp

        pill.post {
            val targetX = if (rightSide) (rootW - pill.width - marginEdge).toFloat() else marginEdge.toFloat()
            pill.x = targetX.coerceAtLeast(marginEdge.toFloat())
            val maxY = (root.height - pill.height - marginEdge).coerceAtLeast(marginEdge).toFloat()
            pill.y = (root.height * yRatio).coerceIn(marginEdge.toFloat(), maxY)
        }

        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false

        pill.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = v.x
                    startY = v.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxX = (root.width - v.width - marginEdge).coerceAtLeast(marginEdge).toFloat()
                        val maxY = (root.height - v.height - marginEdge).coerceAtLeast(marginEdge).toFloat()
                        v.x = (startX + dx).coerceIn(marginEdge.toFloat(), maxX)
                        v.y = (startY + dy).coerceIn(marginEdge.toFloat(), maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val mid = root.width / 2f
                        val snapRight = (v.x + v.width / 2f) >= mid
                        val targetX = if (snapRight) {
                            (root.width - v.width - marginEdge).coerceAtLeast(marginEdge).toFloat()
                        } else {
                            marginEdge.toFloat()
                        }
                        v.animate().x(targetX).setDuration(150).start()
                        val ratio = (v.y / root.height.coerceAtLeast(1)).coerceIn(0.12f, 0.88f)
                        ConfigStore.putBoolean(ConfigStore.KEY_FLOAT_BTN_RIGHT_SIDE, snapRight)
                        ConfigStore.putString(ConfigStore.KEY_FLOAT_BTN_Y_RATIO, "%.3f".format(ratio))
                    } else {
                        triggerActions(activity, actions)
                    }
                    true
                }
                else -> false
            }
        }
        return pill
    }

    private fun triggerActions(activity: Activity, actions: List<FloatingActionSpec>) {
        if (actions.size == 1) {
            runCatching { actions[0].onTrigger(activity) }
                .onFailure { AcidLogger.w(TAG, "action ${actions[0].id} trigger failed", it) }
            return
        }
        val labels = actions.map { it.label }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Acid-WeChat")
            .setItems(labels) { _, which ->
                runCatching { actions[which].onTrigger(activity) }
                    .onFailure { AcidLogger.w(TAG, "action ${actions[which].id} failed", it) }
            }
            .show()
    }
}
