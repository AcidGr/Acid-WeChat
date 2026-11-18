package com.acid.wechat.ui.overlay

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.ui.WebViewHost

/**
 * Reusable centered pure-white floating card WebView container that adapts cleanly
 * once per IME state change via `WindowInsets` without per-frame resize jitter.
 */
object FloatingSheetDialog {
    private const val TAG = "FloatingSheetDialog"

    fun show(
        activity: Activity,
        assetPath: String,
        bridgeName: String,
        widthRatio: Float = 0.90f,
        heightRatio: Float = 0.68f,
        requestInterceptor: ((Uri) -> WebResourceResponse?)? = null,
        bridgeFactory: (Dialog, WebView) -> Any,
    ): Dialog? {
        return runCatching {
            val dm = activity.resources.displayMetrics
            val density = dm.density
            val maxW = (420f * density).toInt()
            val cardWidth = (dm.widthPixels * widthRatio.coerceIn(0.65f, 0.96f)).toInt().coerceAtMost(maxW)
            val desiredCardHeight = (dm.heightPixels * heightRatio.coerceIn(0.42f, 0.88f)).toInt()

            @Suppress("DEPRECATION")
            val softInput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            }

            WebViewHost.showHtmlDialog(
                activity = activity,
                assetPath = assetPath,
                bridgeName = bridgeName,
                styleResId = android.R.style.Theme_Translucent_NoTitleBar,
                softInputMode = softInput,
                windowSetup = { dialog ->
                    dialog.window?.apply {
                        setBackgroundDrawable(ColorDrawable(Color.parseColor("#59000000")))
                        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setDecorFitsSystemWindows(false)
                        }
                    }
                    dialog.setCanceledOnTouchOutside(true)
                },
                bridgeFactory = bridgeFactory,
                requestInterceptor = requestInterceptor,
                containerFactory = { webView, dialog ->
                    buildFloatingContainer(activity, webView, dialog, density, cardWidth, desiredCardHeight)
                },
            )
        }.onFailure {
            AcidLogger.e(TAG, "show FloatingSheetDialog failed", it)
        }.getOrNull()
    }

    private fun buildFloatingContainer(
        activity: Activity,
        webView: WebView,
        dialog: Dialog,
        density: Float,
        cardWidth: Int,
        desiredCardHeight: Int,
    ): View {
        val marginPx = (12f * density).toInt()
        val backdrop = FrameLayout(activity).apply {
            setOnClickListener { if (dialog.isShowing) dialog.dismiss() }
        }

        val radiusPx = 18f * density
        val card = FrameLayout(activity).apply {
            clipToOutline = true
            isClickable = true
            elevation = 14f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(Color.WHITE)
                setStroke((0.8f * density).toInt().coerceAtLeast(1), Color.parseColor("#ECEEF2"))
            }
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val cardLp = FrameLayout.LayoutParams(cardWidth, desiredCardHeight, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        backdrop.addView(card, cardLp)

        fun applySafeBounds(safeTop: Int, safeBottom: Int) {
            val totalH = backdrop.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val minH = (180f * density).toInt().coerceAtMost(totalH)
            val availH = (totalH - safeTop - safeBottom).coerceAtLeast(minH)
            val targetH = desiredCardHeight.coerceAtMost(availH)
            val targetTop = safeTop + ((availH - targetH) / 2).coerceAtLeast(0)
            if (cardLp.topMargin != targetTop || cardLp.height != targetH) {
                cardLp.topMargin = targetTop
                cardLp.height = targetH
                card.layoutParams = cardLp
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            backdrop.setOnApplyWindowInsetsListener { _, insets ->
                val sys = insets.getInsets(WindowInsets.Type.systemBars())
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (imeVisible && imeBottom == 0) {
                    return@setOnApplyWindowInsetsListener WindowInsets.CONSUMED
                }
                val effectiveIme = if (imeVisible) imeBottom else 0
                applySafeBounds(
                    safeTop = sys.top + marginPx,
                    safeBottom = maxOf(sys.bottom, effectiveIme) + marginPx,
                )
                WindowInsets.CONSUMED
            }
            backdrop.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets() }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        } else {
            val rect = Rect()
            val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                backdrop.getWindowVisibleDisplayFrame(rect)
                if (rect.height() > 0) {
                    val safeTop = rect.top + marginPx
                    val bottomInset = (activity.resources.displayMetrics.heightPixels - rect.bottom).coerceAtLeast(0)
                    applySafeBounds(safeTop = safeTop, safeBottom = bottomInset + marginPx)
                }
            }
            backdrop.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }

        return backdrop
    }
}
