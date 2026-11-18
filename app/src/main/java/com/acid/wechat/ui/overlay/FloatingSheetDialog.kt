package com.acid.wechat.ui.overlay

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.ui.WebViewHost

/**
 * Reusable pure-white floating bottom-sheet WebView container invoked by external floating triggers.
 */
object FloatingSheetDialog {
    private const val TAG = "FloatingSheetDialog"

    fun show(
        activity: Activity,
        assetPath: String,
        bridgeName: String,
        heightRatio: Float = 0.82f,
        bridgeFactory: (Dialog, WebView) -> Any,
    ): Dialog? {
        return runCatching {
            val density = activity.resources.displayMetrics.density
            val screenH = activity.resources.displayMetrics.heightPixels
            val cardHeight = (screenH * heightRatio.coerceIn(0.45f, 0.92f)).toInt()

            WebViewHost.showHtmlDialog(
                activity = activity,
                assetPath = assetPath,
                bridgeName = bridgeName,
                styleResId = android.R.style.Theme_Translucent_NoTitleBar,
                windowSetup = { dialog ->
                    dialog.window?.apply {
                        setBackgroundDrawable(ColorDrawable(Color.parseColor("#66000000")))
                        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    dialog.setCanceledOnTouchOutside(true)
                },
                bridgeFactory = bridgeFactory,
                containerFactory = { webView, dialog ->
                    buildSheetContainer(activity, webView, dialog, density, cardHeight)
                },
            )
        }.onFailure {
            AcidLogger.e(TAG, "show FloatingSheetDialog failed", it)
        }.getOrNull()
    }

    private fun buildSheetContainer(
        activity: Activity,
        webView: WebView,
        dialog: Dialog,
        density: Float,
        cardHeight: Int,
    ): View {
        val backdrop = FrameLayout(activity).apply {
            setOnClickListener { if (dialog.isShowing) dialog.dismiss() }
        }

        val radiusPx = 18f * density
        val card = FrameLayout(activity).apply {
            clipToOutline = true
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
                setColor(Color.WHITE)
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
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                cardHeight,
                Gravity.BOTTOM,
            ),
        )
        return backdrop
    }
}
