package com.acid.wechat.core

import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages DexKit rule registration, rule resolution via [DexKitCache] + [DexKitBridge],
 * and diagnostic status reporting.
 */
object DexKitLocator {
    private const val TAG = "DexKitLocator"

    enum class RuleKind { CLASS, METHOD, CTOR }

    enum class RuleStatus { PENDING, CACHED, RESOLVED, FAILED }

    data class ResolvedTarget(
        val kind: RuleKind,
        val className: String,
        val memberName: String = "",
        val paramTypes: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("kind", kind.name)
            put("className", className)
            put("memberName", memberName)
            put("paramTypes", JSONArray(paramTypes))
        }

        companion object {
            fun fromJson(obj: JSONObject): ResolvedTarget {
                val kind = RuleKind.valueOf(obj.getString("kind"))
                val cls = obj.getString("className")
                val name = obj.optString("memberName", "")
                val arr = obj.optJSONArray("paramTypes") ?: JSONArray()
                val params = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    params.add(arr.getString(i))
                }
                return ResolvedTarget(kind, cls, name, params)
            }
        }
    }

    class DexRule(
        val id: String,
        val description: String,
        val kind: RuleKind,
        val classMatcher: (FindClass.() -> Unit)? = null,
        val methodMatcher: (FindMethod.() -> Unit)? = null,
    ) {
        @Volatile var status: RuleStatus = RuleStatus.PENDING
        @Volatile var target: ResolvedTarget? = null
        @Volatile var error: String? = null
        @Volatile var costMs: Long = 0L

        fun resolveClass(): Class<*>? {
            val t = target ?: return null
            return runCatching {
                Class.forName(t.className, false, HostContext.classLoader)
            }.onFailure {
                error = "Class.forName failed: ${it.message}"
            }.getOrNull()
        }

        fun resolveMethod(): Method? {
            val t = target ?: return null
            if (t.kind != RuleKind.METHOD) return null
            return runCatching {
                val cls = Class.forName(t.className, false, HostContext.classLoader)
                ReflectHelper.findMethodByNames(cls, t.memberName, t.paramTypes)
            }.onFailure {
                error = "resolveMethod failed: ${it.message}"
            }.getOrNull()
        }

        fun resolveConstructor(): Constructor<*>? {
            val t = target ?: return null
            if (t.kind != RuleKind.CTOR) return null
            return runCatching {
                val cls = Class.forName(t.className, false, HostContext.classLoader)
                ReflectHelper.findConstructorByNames(cls, t.paramTypes)
            }.onFailure {
                error = "resolveConstructor failed: ${it.message}"
            }.getOrNull()
        }
    }

    private val rules = ConcurrentHashMap<String, DexRule>()
    private val ruleOrder = mutableListOf<String>()

    @Volatile var lastFingerprint: String = ""
        private set
    @Volatile var lastScanCostMs: Long = 0L
        private set
    @Volatile var cacheHitCount: Int = 0
        private set

    fun registerClass(id: String, description: String, block: FindClass.() -> Unit): DexRule =
        DexRule(id, description, RuleKind.CLASS, classMatcher = block).also(::register)

    fun registerMethod(id: String, description: String, block: FindMethod.() -> Unit): DexRule =
        DexRule(id, description, RuleKind.METHOD, methodMatcher = block).also(::register)

    private fun register(rule: DexRule) {
        synchronized(ruleOrder) {
            if (!rules.containsKey(rule.id)) {
                ruleOrder.add(rule.id)
            }
            rules[rule.id] = rule
        }
    }

    fun getRule(id: String): DexRule? = rules[id]

    fun clearCache(): Boolean = DexKitCache.clear()

    fun resolveAll(forceRescan: Boolean = false) {
        val fp = DexKitCache.computeHostFingerprint()
        lastFingerprint = fp

        if (!forceRescan) {
            val hits = DexKitCache.loadIfValid(rules, ruleOrder, fp)
            if (hits >= 0) {
                cacheHitCount = hits
                AcidLogger.i(TAG, "DexKit cache hit ($hits/${rules.size} rules), fingerprint=$fp")
                return
            }
        }

        val startAll = System.currentTimeMillis()
        if (!DexKitCache.ensureNativeLoaded()) {
            for (id in ruleOrder) {
                rules[id]?.apply {
                    status = RuleStatus.FAILED
                    error = "libdexkit.so load failed"
                }
            }
            return
        }

        val apkPath = HostContext.hostApkPath
        if (apkPath.isBlank()) {
            AcidLogger.e(TAG, "hostApkPath is empty, cannot open DexKitBridge")
            return
        }

        val bridge = runCatching { DexKitBridge.create(apkPath) }.onFailure {
            AcidLogger.e(TAG, "DexKitBridge.create($apkPath) failed", it)
        }.getOrNull() ?: return

        try {
            for (id in ruleOrder) {
                val rule = rules[id] ?: continue
                resolveSingleRule(bridge, rule)
            }
        } finally {
            runCatching { bridge.close() }
        }

        lastScanCostMs = System.currentTimeMillis() - startAll
        DexKitCache.save(rules, ruleOrder, fp)
        val okCount = rules.values.count { it.status == RuleStatus.RESOLVED || it.status == RuleStatus.CACHED }
        AcidLogger.i(TAG, "DexKit scan complete: $okCount/${rules.size} resolved in ${lastScanCostMs}ms")
    }

    private fun resolveSingleRule(bridge: DexKitBridge, rule: DexRule) {
        val t0 = System.currentTimeMillis()
        runCatching {
            when (rule.kind) {
                RuleKind.CLASS -> {
                    val matcher = rule.classMatcher ?: error("missing classMatcher")
                    val first = bridge.findClass { matcher() }.firstOrNull() ?: error("no class matched")
                    rule.target = ResolvedTarget(RuleKind.CLASS, first.name)
                }
                RuleKind.METHOD -> {
                    val matcher = rule.methodMatcher ?: error("missing methodMatcher")
                    val first = bridge.findMethod { matcher() }.firstOrNull() ?: error("no method matched")
                    rule.target = if (first.isConstructor) {
                        ResolvedTarget(RuleKind.CTOR, first.className, "<init>", first.paramTypeNames)
                    } else {
                        ResolvedTarget(RuleKind.METHOD, first.className, first.methodName, first.paramTypeNames)
                    }
                }
                RuleKind.CTOR -> {
                    val matcher = rule.methodMatcher ?: error("missing methodMatcher")
                    val first = bridge.findMethod { matcher() }.firstOrNull() ?: error("no ctor matched")
                    rule.target = ResolvedTarget(RuleKind.CTOR, first.className, "<init>", first.paramTypeNames)
                }
            }
            rule.status = RuleStatus.RESOLVED
            rule.error = null
        }.onFailure {
            rule.status = RuleStatus.FAILED
            rule.target = null
            rule.error = "${it.javaClass.simpleName}: ${it.message}"
            AcidLogger.w(TAG, "rule [${rule.id}] failed: ${rule.error}")
        }
        rule.costMs = System.currentTimeMillis() - t0
    }

    fun snapshotJson(): JSONObject {
        val arr = JSONArray()
        var ok = 0
        var fail = 0
        for (id in ruleOrder) {
            val r = rules[id] ?: continue
            if (r.status == RuleStatus.RESOLVED || r.status == RuleStatus.CACHED) ok++ else fail++
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("description", r.description)
                put("kind", r.kind.name)
                put("status", r.status.name)
                put("costMs", r.costMs)
                put("target", r.target?.toJson() ?: JSONObject.NULL)
                put("error", r.error ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("fingerprint", lastFingerprint)
            put("lastScanCostMs", lastScanCostMs)
            put("okCount", ok)
            put("failCount", fail)
            put("total", rules.size)
            put("rules", arr)
        }
    }
}
