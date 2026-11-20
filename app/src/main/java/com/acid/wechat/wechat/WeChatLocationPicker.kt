package com.acid.wechat.wechat

import android.app.Activity
import android.content.Intent
import android.os.Parcelable
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Bridges WeChat's native map picker (`RedirectUI` + `KLocationIntent`) so callers in the main
 * process can let the user pick GCJ-02 coordinates without bundling a third-party map SDK.
 */
object WeChatLocationPicker {
    private const val TAG = "WeChatLocationPicker"
    private const val REDIRECT_UI_CLS = "com.tencent.mm.plugin.location.ui.RedirectUI"
    private const val EXTRA_ACID_PICK = "acid_fake_location_pick"

    private val LAT_LNG_PATTERN = Pattern.compile("lat\\s+(-?\\d+(?:\\.\\d+)?);lng\\s+(-?\\d+(?:\\.\\d+)?);")
    private val POI_NAME_PATTERN = Pattern.compile("poiname\\s+([^;]*);")
    private val LABEL_PATTERN = Pattern.compile("label\\s+([^;]*);")

    private val hooked = AtomicBoolean(false)
    private val pendingCallback = AtomicReference<((PickedLocation) -> Unit)?>(null)

    data class PickedLocation(
        val lat: Double,
        val lng: Double,
        val label: String,
    ) {
        val latText: String get() = String.format(Locale.US, "%.6f", lat)
        val lngText: String get() = String.format(Locale.US, "%.6f", lng)
    }

    fun isValidCoord(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    fun init(): Boolean {
        if (!hooked.compareAndSet(false, true)) return true
        val ok = runCatching {
            val redirectCls = Class.forName(REDIRECT_UI_CLS, false, HostContext.classLoader)
            val onActivityResult = ReflectHelper.findMethod(
                redirectCls,
                "onActivityResult",
                listOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java),
            ) ?: error("RedirectUI.onActivityResult not found")

            XposedBridge.hookMethod(onActivityResult, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val act = param.thisObject as? Activity ?: return
                    if (act.intent?.getBooleanExtra(EXTRA_ACID_PICK, false) != true) return

                    val resultCode = param.args?.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
                    val data = param.args?.getOrNull(2) as? Intent
                    val cb = pendingCallback.getAndSet(null)

                    if (resultCode == Activity.RESULT_OK && data != null) {
                        val picked = extractPickedLocation(data)
                        if (picked != null) {
                            cb?.invoke(picked)
                        } else {
                            AcidLogger.w(TAG, "map picker returned RESULT_OK but coordinates could not be parsed")
                        }
                    }

                    act.finish()
                    param.result = null
                }
            })
            AcidLogger.i(TAG, "hooked RedirectUI.onActivityResult for map picker")
            true
        }.onFailure {
            AcidLogger.w(TAG, "init RedirectUI picker hook failed", it)
        }.getOrDefault(false)

        if (!ok) hooked.set(false)
        return ok
    }

    fun launch(activity: Activity, onPicked: (PickedLocation) -> Unit): Boolean {
        if (!init()) return false
        return runCatching {
            val redirectCls = Class.forName(REDIRECT_UI_CLS, false, HostContext.classLoader)
            pendingCallback.set(onPicked)
            val intent = Intent(activity, redirectCls).apply {
                putExtra("map_view_type", 8)
                putExtra(EXTRA_ACID_PICK, true)
                putExtra("wx_misclick_entry_name", "")
            }
            activity.startActivity(intent)
            true
        }.onFailure {
            pendingCallback.set(null)
            AcidLogger.e(TAG, "launch RedirectUI failed", it)
        }.getOrDefault(false)
    }

    private fun extractPickedLocation(data: Intent): PickedLocation? {
        @Suppress("DEPRECATION")
        val locIntent = data.getParcelableExtra<Parcelable>("KLocationIntent")
        if (locIntent != null) {
            extractFromLocationIntentString(locIntent)?.let { return it }
            extractFromLocationIntentFields(locIntent, data)?.let { return it }
        }
        val slat = data.getDoubleExtra("kwebmap_slat", Double.NaN)
        val slng = data.getDoubleExtra("kwebmap_lng", Double.NaN)
        if (nonZeroValid(slat, slng)) {
            return PickedLocation(slat, slng, fallbackLabel(data))
        }
        return null
    }

    private fun extractFromLocationIntentString(locIntent: Parcelable): PickedLocation? {
        val formattedTexts = buildList {
            add(locIntent.toString())
            for (m in locIntent.javaClass.declaredMethods) {
                if (m.parameterTypes.isEmpty() && m.returnType == String::class.java) {
                    runCatching {
                        m.isAccessible = true
                        m.invoke(locIntent) as? String
                    }.getOrNull()?.let { add(it) }
                }
            }
        }
        for (text in formattedTexts) {
            val matcher = LAT_LNG_PATTERN.matcher(text)
            if (!matcher.find()) continue
            val lat = matcher.group(1)?.toDoubleOrNull() ?: continue
            val lng = matcher.group(2)?.toDoubleOrNull() ?: continue
            if (!nonZeroValid(lat, lng)) continue
            val poiName = extractGroup(POI_NAME_PATTERN, text)
            val addrLabel = extractGroup(LABEL_PATTERN, text)
            return PickedLocation(lat, lng, poiName ?: addrLabel ?: "")
        }
        return null
    }

    private fun extractFromLocationIntentFields(locIntent: Parcelable, data: Intent): PickedLocation? {
        // Fallback: LocationIntent stores latitude and longitude in its first two primitive double fields.
        val doubles = ArrayList<Double>(2)
        for (f in locIntent.javaClass.declaredFields) {
            if (f.type == Double::class.javaPrimitiveType) {
                f.isAccessible = true
                (f.get(locIntent) as? Double)?.let { doubles.add(it) }
                if (doubles.size >= 2) break
            }
        }
        if (doubles.size >= 2 && nonZeroValid(doubles[0], doubles[1])) {
            return PickedLocation(doubles[0], doubles[1], fallbackLabel(data))
        }
        return null
    }

    private fun extractGroup(pattern: Pattern, text: String): String? =
        pattern.matcher(text)
            .takeIf { it.find() }
            ?.group(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun nonZeroValid(lat: Double, lng: Double): Boolean =
        isValidCoord(lat, lng) && !(lat == 0.0 && lng == 0.0)

    private fun fallbackLabel(data: Intent): String =
        data.getStringExtra("kPoiName")?.takeIf { it.isNotBlank() }
            ?: data.getStringExtra("Kwebmap_locaion")?.takeIf { it.isNotBlank() }
            ?: ""
}
