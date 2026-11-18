package com.acid.wechat.ui.overlay

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.ui.WebViewHost

/**
 * Reusable centered pure-white floating card WebView container with fixed position during IME input.
 */
object FloatingSheetDialog {
    private const val TAG = "FloatingSheetDialog"

    fun show(
        activity: Activity,
        assetPath: String,
        bridgeName: String,
        widthRatio: Float = 0.90f,
        heightRatio: Float = 0.68f,
        bridgeFactory: (Dialog, WebView) -> Any,
    ): Dialog? {
        return runCatching {
            val dm = activity.resources.displayMetrics
            val density = dm.density
            val maxW = (420f * density).toInt()
            val cardWidth = (dm.widthPixels * widthRatio.coerceIn(0.65f, 0.96f)).toInt().coerceAtMost(maxW)
            val cardHeight = (dm.heightPixels * heightRatio.coerceIn(0.42f, 0.85f)).toInt()

            WebViewHost.showHtmlDialog(
                activity = activity,
                assetPath = assetPath,
                bridgeName = bridgeName,
                styleResId = android.R.style.Theme_Translucent_NoTitleBar,
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
                windowSetup = { dialog ->
                    dialog.window?.apply {
                        setBackgroundDrawable(ColorDrawable(Color.parseColor("#59000000")))
                        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    dialog.setCanceledOnTouchOutside(true)
                },
                bridgeFactory = bridgeFactory,
                containerFactory = { webView, dialog ->
                    buildFloatingContainer(activity, webView, dialog, density, cardWidth, cardHeight)
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
        cardHeight: Int,
    ): View {
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

        backdrop.addView(
            card,
            FrameLayout.LayoutParams(cardWidth, cardHeight, Gravity.CENTER),
        )
        return backdrop
    }
}
