package com.acid.wechat.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListAdapter
import android.widget.TextView
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Injects a native-styled "MP3语音" tile into WeChat's chat "+" panel (`AppPanel` / `AppGrid`).
 */
object ChatPlusPanelHook {
    private const val TAG = "ChatPlusPanelHook"
    private const val TILE_LABEL = "MP3语音"
    private const val MAX_ITEMS_PER_PAGE = 8
    private const val TAG_VOICE_TILE = 0x56435449 // "VCTI"

    private lateinit var ruleAppPanelInitGrid: DexKitLocator.DexRule
    private val rulesRegistered = AtomicBoolean(false)
    private val hookInstalled = AtomicBoolean(false)

    private val hookedAdapterClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val activeAdapters = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<GridView>>())
    private val bypassHooks = ThreadLocal<Boolean>()

    @Volatile private var enabledProvider: () -> Boolean = { false }
    @Volatile private var clickHandler: ((Activity) -> Unit)? = null

    fun registerDexRules() {
        if (!rulesRegistered.compareAndSet(false, true)) return
        ruleAppPanelInitGrid = DexKitLocator.registerMethod(
            id = "voice_app_panel_init_grid_v1",
            description = "AppPanel.initAppGrid() for chat '+' panel injection",
        ) {
            matcher {
                declaredClass = "com.tencent.mm.pluginsdk.ui.chat.AppPanel"
                usingEqStrings("MicroMsg.AppPanel", "initAppGrid()")
            }
        }
    }

    fun init(isEnabled: () -> Boolean, onClick: (Activity) -> Unit) {
        enabledProvider = isEnabled
        clickHandler = onClick
        if (!hookInstalled.compareAndSet(false, true)) return

        val initMethod = if (::ruleAppPanelInitGrid.isInitialized) ruleAppPanelInitGrid.resolveMethod() else null
        if (initMethod == null) {
            AcidLogger.w(TAG, "AppPanel.initAppGrid not resolved; '+' panel button skipped")
            return
        }

        runCatching {
            XposedBridge.hookMethod(initMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val appPanel = (param.thisObject ?: param.args?.getOrNull(0)) as? ViewGroup ?: return
                        attachTileToPanel(appPanel)
                    }.onFailure { err ->
                        AcidLogger.w(TAG, "attachTileToPanel failed", err)
                    }
                }
            })
            AcidLogger.i(TAG, "hooked ${initMethod.declaringClass.name}.${initMethod.name} for '+' panel")
        }.onFailure { err ->
            AcidLogger.w(TAG, "failed hooking AppPanel.initAppGrid", err)
        }
    }

    private fun attachTileToPanel(appPanel: ViewGroup) {
        val grids = collectGrids(appPanel)
        if (grids.isEmpty()) {
            AcidLogger.i(TAG, "attachTileToPanel: no GridViews in AppPanel yet")
            return
        }

        for (g in grids) {
            g.adapter?.let { activeAdapters.remove(it) }
        }

        if (!enabledProvider()) {
            for (g in grids) (g.adapter as? BaseAdapter)?.notifyDataSetChanged()
            return
        }

        val targetGrid = grids.lastOrNull { g ->
            val a = g.adapter ?: return@lastOrNull false
            ensureAdapterHooked(a)
            queryRealCount(a) in 1 until MAX_ITEMS_PER_PAGE
        } ?: run {
            val counts = grids.joinToString(",") { g -> g.adapter?.let { queryRealCount(it).toString() } ?: "null" }
            AcidLogger.w(TAG, "attachTileToPanel: no grid with empty slot (grids=${grids.size}, counts=[$counts])")
            return
        }

        val adapter = targetGrid.adapter ?: return
        val baseCount = queryRealCount(adapter)
        activeAdapters[adapter] = WeakReference(targetGrid)
        wrapGridClickListener(targetGrid)
        (adapter as? BaseAdapter)?.notifyDataSetChanged()
        AcidLogger.i(TAG, "attached '$TILE_LABEL' tile to '+' panel grid (grids=${grids.size}, baseCount=$baseCount)")
    }

    private fun collectGrids(root: View): List<GridView> {
        val out = ArrayList<GridView>(4)
        fun walk(v: View) {
            if (v is GridView) {
                out.add(v)
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun ensureAdapterHooked(adapter: ListAdapter) {
        val cls = adapter.javaClass
        if (!hookedAdapterClasses.add(cls)) return

        val getCountM = findMethod(cls, "getCount")
        val getItemM = findMethod(cls, "getItem", Int::class.javaPrimitiveType!!)
        val getItemIdM = findMethod(cls, "getItemId", Int::class.javaPrimitiveType!!)
        val getViewM = findMethod(cls, "getView", Int::class.javaPrimitiveType!!, View::class.java, ViewGroup::class.java)

        if (getCountM == null || getViewM == null) {
            hookedAdapterClasses.remove(cls)
            AcidLogger.w(TAG, "AppGrid adapter ${cls.name} missing getCount/getView")
            return
        }

        XposedBridge.hookMethod(getCountM, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (bypassHooks.get() == true || !enabledProvider()) return
                if (!activeAdapters.containsKey(param.thisObject)) return
                val real = param.result as? Int ?: return
                if (real in 1 until MAX_ITEMS_PER_PAGE) {
                    param.result = real + 1
                }
            }
        })

        getItemM?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (bypassHooks.get() == true || !enabledProvider()) return
                    val adp = param.thisObject ?: return
                    if (!activeAdapters.containsKey(adp)) return
                    val pos = param.args?.getOrNull(0) as? Int ?: return
                    val real = queryRealCount(adp)
                    if (real in 1 until MAX_ITEMS_PER_PAGE && pos == real) {
                        param.result = null
                    }
                }
            })
        }

        getItemIdM?.let { m ->
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (bypassHooks.get() == true || !enabledProvider()) return
                    val adp = param.thisObject ?: return
                    if (!activeAdapters.containsKey(adp)) return
                    val pos = param.args?.getOrNull(0) as? Int ?: return
                    val real = queryRealCount(adp)
                    if (real in 1 until MAX_ITEMS_PER_PAGE && pos == real) {
                        param.result = -99L
                    }
                }
            })
        }

        XposedBridge.hookMethod(getViewM, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (bypassHooks.get() == true || !enabledProvider()) return
                val adp = param.thisObject ?: return
                val grid = activeAdapters[adp]?.get() ?: return
                val pos = param.args?.getOrNull(0) as? Int ?: return
                val convertView = param.args?.getOrNull(1) as? View
                val parent = param.args?.getOrNull(2) as? ViewGroup ?: return
                val real = queryRealCount(adp)
                if (real !in 1 until MAX_ITEMS_PER_PAGE) return

                wrapGridClickListener(grid)
                if (pos == real) {
                    val custom = buildVoiceTileView(adp as ListAdapter, convertView, parent) ?: return
                    param.result = custom
                } else if (convertView?.getTag(TAG_VOICE_TILE) == true) {
                    param.args[1] = null
                }
            }
        })
        AcidLogger.i(TAG, "hooked AppGrid adapter ${cls.name}")
    }

    private fun wrapGridClickListener(grid: GridView) {
        val current = grid.onItemClickListener
        if (current is VoiceClickListener) return
        grid.onItemClickListener = VoiceClickListener(current)
    }

    private class VoiceClickListener(
        private val delegate: AdapterView.OnItemClickListener?,
    ) : AdapterView.OnItemClickListener {
        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val adapter = parent?.adapter
            val isVoiceTile = enabledProvider() && (
                view?.getTag(TAG_VOICE_TILE) == true ||
                    (adapter != null && activeAdapters.containsKey(adapter) && position == queryRealCount(adapter))
                )
            if (isVoiceTile) {
                val activity = ReflectHelper.unwrapActivity(view?.context ?: parent?.context)
                if (activity != null) {
                    clickHandler?.invoke(activity)
                }
                return
            }
            delegate?.onItemClick(parent, view, position, id)
        }
    }

    private fun queryRealCount(adapter: Any): Int {
        bypassHooks.set(true)
        return try {
            (adapter as? ListAdapter)?.count ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            bypassHooks.remove()
        }
    }

    private fun buildVoiceTileView(adapter: ListAdapter, convertView: View?, parent: ViewGroup): View? {
        if (convertView != null && convertView.getTag(TAG_VOICE_TILE) == true) {
            return convertView
        }
        val cloned = runCatching {
            bypassHooks.set(true)
            adapter.getView(0, null, parent)
        }.also {
            bypassHooks.remove()
        }.getOrNull() ?: return null

        cloned.setTag(TAG_VOICE_TILE, true)
        val root = cloned as? ViewGroup ?: return cloned
        customizeTileContent(root)
        return cloned
    }

    private fun customizeTileContent(root: ViewGroup) {
        val iconViews = ArrayList<ImageView>(3)
        val textViews = ArrayList<TextView>(3)
        collectViews(root, iconViews, textViews)

        val labelTv = textViews.lastOrNull { it.visibility == View.VISIBLE && !it.text.isNullOrBlank() }
            ?: textViews.lastOrNull()
        if (labelTv != null) {
            labelTv.text = TILE_LABEL
            labelTv.visibility = View.VISIBLE
        }
        for (tv in textViews) {
            if (tv !== labelTv) tv.visibility = View.GONE
        }

        val density = root.resources.displayMetrics.density
        val dotMaxPx = (14f * density).toInt()
        val targetIv = iconViews.firstOrNull {
            it.visibility == View.VISIBLE && it.drawable != null && (it.width == 0 || it.width > dotMaxPx)
        } ?: iconViews.firstOrNull()
        for (iv in iconViews) {
            if (iv === targetIv) {
                iv.visibility = View.VISIBLE
                iv.alpha = 1f
                iv.imageTintList = null
                iv.clearColorFilter()
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setImageDrawable(VoiceMicDrawable(root.context))
            } else if (iv.width in 1..dotMaxPx) {
                iv.visibility = View.GONE
            }
        }
    }

    private fun collectViews(v: View, images: MutableList<ImageView>, texts: MutableList<TextView>) {
        when (v) {
            is ImageView -> images.add(v)
            is TextView -> texts.add(v)
            is ViewGroup -> for (i in 0 until v.childCount) collectViews(v.getChildAt(i), images, texts)
        }
    }

    private fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method? {
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            runCatching {
                return cur.getDeclaredMethod(name, *params).apply { isAccessible = true }
            }
            cur = cur.superclass
        }
        return null
    }

    private class VoiceMicDrawable(context: android.content.Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = if (isDark) Color.parseColor("#E6FFFFFF") else Color.parseColor("#E6181818")
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat().coerceAtLeast(28f * density)
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val u = size / 24f
            strokePaint.strokeWidth = (2.1f * u).coerceAtLeast(2.2f * density)

            // Microphone capsule
            val micRect = RectF(cx - 3.3f * u, cy - 9.0f * u, cx + 3.3f * u, cy + 1.0f * u)
            canvas.drawRoundRect(micRect, 3.3f * u, 3.3f * u, strokePaint)

            // Cradle arc around microphone
            val arcRect = RectF(cx - 6.8f * u, cy - 5.4f * u, cx + 6.8f * u, cy + 4.8f * u)
            canvas.drawArc(arcRect, 12f, 156f, false, strokePaint)

            // Stem and base
            canvas.drawLine(cx, cy + 4.8f * u, cx, cy + 8.8f * u, strokePaint)
            canvas.drawLine(cx - 4.2f * u, cy + 8.8f * u, cx + 4.2f * u, cy + 8.8f * u, strokePaint)

            // Sound wave arc on top-right
            val waveRect = RectF(cx + 5.2f * u, cy - 8.6f * u, cx + 9.4f * u, cy - 3.6f * u)
            canvas.drawArc(waveRect, -55f, 110f, false, strokePaint)
        }

        override fun setAlpha(alpha: Int) { strokePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { strokePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = (32f * density).toInt()
        override fun getIntrinsicHeight(): Int = (32f * density).toInt()
    }
}
