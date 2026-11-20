package com.acid.wechat.features

import android.app.Activity
import com.acid.wechat.core.AcidFeature
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigSchema
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureOption
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.ReflectHelper
import com.acid.wechat.wechat.WeChatLocationPicker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spoofs WeChat's `TencentLocation` coordinates (`我的 -> 虚拟定位`) across main and `:appbrand*`/`:tools`
 * subprocesses, and converts GCJ-02 user coordinates to WGS-84 when requested by callers.
 */
object FakeLocationFeature : AcidFeature {
    private const val TAG = "FakeLocation"
    private const val TENCENT_LOC_MGR_CLS = "com.tencent.map.geolocation.sapp.TencentLocationManager"
    private const val EXTRA_PREV_WGS84 = "acid_prev_wgs84"
    private const val EXTRA_TRACKED_WGS84 = "acid_tracked_wgs84"

    private lateinit var ruleSLocationListener: DexKitLocator.DexRule
    private lateinit var ruleSLocationListenerWgs84: DexKitLocator.DexRule
    private lateinit var ruleDefaultTencentLocListener: DexKitLocator.DexRule

    private val hookedLocationClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    private val coordTypeMethodCache = ConcurrentHashMap<Class<*>, CoordMethodSlot>()
    private val inWgs84Callback = ThreadLocal<Boolean>()
    private val interceptedCount = AtomicInteger(0)
    private val pickedCount = AtomicInteger(0)

    private data class CoordMethodSlot(val method: Method?)

    private val coordTypeConstants: Pair<Int, Int> by lazy { resolveCoordTypeConstants() }
    private val coordTypeWgs84: Int get() = coordTypeConstants.first
    private val coordTypeGcj02: Int get() = coordTypeConstants.second

    override val id = "fake_location"
    override val spec = ConfigSchema.FAKE_LOCATION_ENABLED

    override fun extraOptions(): List<FeatureOption> = listOf(
        FeatureOption.from(ConfigSchema.FAKE_LOCATION_LAT),
        FeatureOption.from(ConfigSchema.FAKE_LOCATION_LNG),
    )

    override fun registerDexRules() {
        ruleSLocationListener = DexKitLocator.registerMethod(
            id = "fake_loc_s_listener_v1",
            description = "WeChat SLocationListener.onLocationChanged",
        ) {
            matcher {
                name("onLocationChanged")
                usingEqStrings("MicroMsg.SLocationListener")
            }
        }
        ruleSLocationListenerWgs84 = DexKitLocator.registerMethod(
            id = "fake_loc_s_listener_wgs84_v1",
            description = "WeChat SLocationListenerWgs84.onLocationChanged",
        ) {
            matcher {
                name("onLocationChanged")
                usingEqStrings("MicroMsg.SLocationListenerWgs84")
            }
        }
        ruleDefaultTencentLocListener = DexKitLocator.registerMethod(
            id = "fake_loc_default_tencent_listener_v1",
            description = "DefaultTencentLocationManager listener onLocationChanged",
        ) {
            matcher {
                name("onLocationChanged")
                usingEqStrings(
                    "MicroMsg.DefaultTencentLocationManager",
                    "[mlocationListener]error:%d, reason:%s",
                )
            }
        }
    }

    override fun onInit() {
        hookLocationListener(ruleSLocationListener, defaultWgs84 = false)
        hookLocationListener(ruleSLocationListenerWgs84, defaultWgs84 = true)
        hookLocationListener(ruleDefaultTencentLocListener, defaultWgs84 = true)
        if (HostContext.isMainProcess) {
            WeChatLocationPicker.init()
        }
    }

    /**
     * Launches WeChat's native map picker and atomically persists selected GCJ-02 coordinates.
     */
    fun launchMapPicker(
        activity: Activity,
        onPicked: (WeChatLocationPicker.PickedLocation) -> Unit,
    ): Boolean = WeChatLocationPicker.launch(activity) { picked ->
        runCatching {
            val patch = JSONObject().apply {
                put(ConfigSchema.FAKE_LOCATION_LAT.key, picked.latText)
                put(ConfigSchema.FAKE_LOCATION_LNG.key, picked.lngText)
            }
            ConfigStore.applyPatch(patch)
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "persist picked coordinates failed", it)
        }
        pickedCount.incrementAndGet()
        FeatureRegistry.recordHit(id)
        AcidLogger.i(TAG, "picked native map location: ${picked.latText},${picked.lngText} (${picked.label})")
        onPicked(picked)
    }

    private fun resolveCoordTypeConstants(): Pair<Int, Int> = runCatching {
        val cls = Class.forName(TENCENT_LOC_MGR_CLS, false, HostContext.classLoader)
        val wgs84 = cls.getDeclaredField("COORDINATE_TYPE_WGS84").apply { isAccessible = true }.getInt(null)
        val gcj02 = cls.getDeclaredField("COORDINATE_TYPE_GCJ02").apply { isAccessible = true }.getInt(null)
        wgs84 to gcj02
    }.getOrElse { 0 to 1 }

    private fun hookLocationListener(rule: DexKitLocator.DexRule, defaultWgs84: Boolean) {
        val m = rule.resolveMethod() ?: return
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FeatureRegistry.isEnabled(this@FakeLocationFeature)) return
                    param.setObjectExtra(EXTRA_PREV_WGS84, inWgs84Callback.get())
                    param.setObjectExtra(EXTRA_TRACKED_WGS84, true)
                    inWgs84Callback.set(defaultWgs84)
                    val loc = param.args?.getOrNull(0) ?: return
                    ensureLocationClassHooked(loc.javaClass)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra(EXTRA_TRACKED_WGS84) != true) return
                    val prev = param.getObjectExtra(EXTRA_PREV_WGS84) as? Boolean
                    if (prev == null) inWgs84Callback.remove() else inWgs84Callback.set(prev)
                }
            })
            AcidLogger.i(TAG, "hooked ${rule.id}: ${m.declaringClass.name}.${m.name} (defaultWgs84=$defaultWgs84)")
        }.onFailure {
            FeatureRegistry.recordError(id, it)
            AcidLogger.w(TAG, "hookLocationListener failed for ${rule.id}", it)
        }
    }

    private fun ensureLocationClassHooked(locClass: Class<*>) {
        if (!hookedLocationClasses.add(locClass)) return
        val latMethod = ReflectHelper.findMethod(locClass, "getLatitude", emptyList())
            ?.takeIf { it.returnType == Double::class.javaPrimitiveType }
        val lngMethod = ReflectHelper.findMethod(locClass, "getLongitude", emptyList())
            ?.takeIf { it.returnType == Double::class.javaPrimitiveType }

        if (latMethod == null || lngMethod == null) {
            AcidLogger.w(TAG, "TencentLocation getters missing on ${locClass.name}")
            return
        }

        if (hookedMethods.add(latMethod)) {
            runCatching {
                XposedBridge.hookMethod(latMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FeatureRegistry.isEnabled(this@FakeLocationFeature)) return
                        val (lat, lng) = currentCoords() ?: return
                        param.result = if (expectsWgs84(param.thisObject)) {
                            gcj02ToWgs84(lat, lng).first
                        } else {
                            lat
                        }
                        interceptedCount.incrementAndGet()
                        FeatureRegistry.recordHit(id)
                    }
                })
            }.onFailure {
                FeatureRegistry.recordError(id, it)
                AcidLogger.w(TAG, "hook getLatitude failed on ${locClass.name}", it)
            }
        }

        if (hookedMethods.add(lngMethod)) {
            runCatching {
                XposedBridge.hookMethod(lngMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FeatureRegistry.isEnabled(this@FakeLocationFeature)) return
                        val (lat, lng) = currentCoords() ?: return
                        param.result = if (expectsWgs84(param.thisObject)) {
                            gcj02ToWgs84(lat, lng).second
                        } else {
                            lng
                        }
                    }
                })
            }.onFailure {
                FeatureRegistry.recordError(id, it)
                AcidLogger.w(TAG, "hook getLongitude failed on ${locClass.name}", it)
            }
        }

        AcidLogger.i(TAG, "hooked TencentLocation impl: ${locClass.name}")
    }

    private fun expectsWgs84(locObj: Any?): Boolean {
        if (locObj != null) {
            val slot = coordTypeMethodCache.getOrPut(locObj.javaClass) {
                val m = ReflectHelper.findMethod(locObj.javaClass, "getCoordinateType", emptyList())
                    ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
                CoordMethodSlot(m)
            }
            val type = slot.method?.let { runCatching { it.invoke(locObj) as? Int }.getOrNull() }
            if (type != null) {
                if (type == coordTypeWgs84) return true
                if (type == coordTypeGcj02) return false
            }
        }
        return inWgs84Callback.get() == true
    }

    private fun currentCoords(): Pair<Double, Double>? {
        val lat = ConfigStore.getString(ConfigSchema.FAKE_LOCATION_LAT).trim().toDoubleOrNull() ?: return null
        val lng = ConfigStore.getString(ConfigSchema.FAKE_LOCATION_LNG).trim().toDoubleOrNull() ?: return null
        if (!WeChatLocationPicker.isValidCoord(lat, lng)) return null
        return lat to lng
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun wgs84ToGcj02Delta(lat: Double, lng: Double): Pair<Double, Double> {
        val a = 6378245.0
        val ee = 0.00669342162296594323
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLat = (transformLat(lng - 105.0, lat - 35.0) * 180.0) /
            ((a * (1 - ee)) / (magic * sqrtMagic) * PI)
        val dLng = (transformLng(lng - 105.0, lat - 35.0) * 180.0) /
            (a / sqrtMagic * cos(radLat) * PI)
        return dLat to dLng
    }

    private fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (outOfChina(gcjLat, gcjLng)) return gcjLat to gcjLng
        var wgsLat = gcjLat
        var wgsLng = gcjLng
        repeat(3) {
            val (dLat, dLng) = wgs84ToGcj02Delta(wgsLat, wgsLng)
            wgsLat = gcjLat - dLat
            wgsLng = gcjLng - dLng
        }
        return wgsLat to wgsLng
    }

    override fun statusJson(): JSONObject = JSONObject().apply {
        put("interceptedCount", interceptedCount.get())
        put("pickedCount", pickedCount.get())
        put("hookedLocationClasses", hookedLocationClasses.size)
        put("lat", ConfigStore.getString(ConfigSchema.FAKE_LOCATION_LAT))
        put("lng", ConfigStore.getString(ConfigSchema.FAKE_LOCATION_LNG))
    }
}
