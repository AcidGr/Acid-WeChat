package com.acid.wechat.core

import android.os.FileObserver
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JSON-backed Single Source of Truth configuration store (`config.json`).
 * Supports atomic writes, schema sanitization, throttled mtime/size checks,
 * and `FileObserver`-backed live hot-reload across main and subprocesses.
 */
object ConfigStore {
    private const val TAG = "ConfigStore"
    private const val FILE_NAME = "config.json"
    private const val SCHEMA_FILE_NAME = "config.schema.json"
    private const val STAT_THROTTLE_MS = 150L

    private val lock = Any()
    private val map = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<(Set<String>) -> Unit>()

    @Volatile private var loadedOnce = false
    @Volatile private var observerDirty = false
    @Volatile private var lastStatCheckMs = 0L
    @Volatile private var lastLoadedMtime = 0L
    @Volatile private var lastLoadedLength = -1L
    @Volatile private var lastLoadedRawText = ""
    @Volatile private var lastParseErrorRaw = ""
    @Volatile private var testDataDir: File? = null

    @Suppress("DEPRECATION")
    @Volatile private var fileObserver: FileObserver? = null

    fun configFile(): File = File(dataDir(), FILE_NAME)
    fun schemaFile(): File = File(dataDir(), SCHEMA_FILE_NAME)
    private fun dataDir(): File = testDataDir ?: HostContext.getDataDir()

    internal fun setDataDirForTest(dir: File?) {
        synchronized(lock) {
            fileObserver?.let { runCatching { it.stopWatching() } }
            fileObserver = null
            testDataDir = dir
            map.clear()
            loadedOnce = false
            observerDirty = false
            lastStatCheckMs = 0L
            lastLoadedMtime = 0L
            lastLoadedLength = -1L
            lastLoadedRawText = ""
            lastParseErrorRaw = ""
        }
    }

    fun addListener(listener: (Set<String>) -> Unit) {
        listeners.add(listener)
    }

    fun load(startObserver: Boolean = true, syncSchemaFile: Boolean = true) {
        val changed: Set<String>
        synchronized(lock) {
            if (!loadedOnce) seedDefaultsLocked()
            val f = configFile()
            changed = if (f.exists() && f.length() > 0L) {
                reloadFromDiskLocked(f)
            } else {
                saveLocked()
                emptySet()
            }
            loadedOnce = true
            if (syncSchemaFile) syncSchemaFileLocked()
            if (startObserver && fileObserver == null) {
                fileObserver = AcidIo.watchFile(dataDir(), FILE_NAME) {
                    observerDirty = true
                    reloadIfModified(force = true)
                }
            }
        }
        notifyListeners(changed)
    }

    fun reloadIfModified(force: Boolean = false) {
        if (!loadedOnce) {
            load(startObserver = false, syncSchemaFile = false)
            return
        }
        val now = System.nanoTime() / 1_000_000L
        if (!force && !observerDirty && now - lastStatCheckMs < STAT_THROTTLE_MS) return
        lastStatCheckMs = now

        val f = configFile()
        val mtime = f.lastModified()
        val len = f.length()
        if (!force && !observerDirty && mtime == lastLoadedMtime && len == lastLoadedLength) return

        val changed = synchronized(lock) { reloadFromDiskLocked(f) }
        if (changed.isNotEmpty()) {
            AcidLogger.i(TAG, "hot-reloaded config.json keys: $changed")
            notifyListeners(changed)
        }
    }

    fun getBoolean(spec: ConfigSchema.BoolSpec): Boolean = getBoolean(spec.key, spec.defaultValue)

    fun getBooleanEarlySafe(spec: ConfigSchema.BoolSpec): Boolean {
        if (HostContext.isInitialized) return getBoolean(spec)
        for (dir in HostContext.candidateDataDirs()) {
            val f = File(dir, FILE_NAME)
            if (!f.isFile || !f.canRead() || f.length() == 0L) continue
            val parsed = runCatching {
                val obj = JSONObject(f.readText(Charsets.UTF_8))
                if (obj.has(spec.key)) spec.resolve(obj.opt(spec.key)) else null
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return spec.defaultValue
    }

    fun getInt(spec: ConfigSchema.IntSpec): Int = getInt(spec.key, spec.defaultValue)
    fun getString(spec: ConfigSchema.StringSpec): String = getString(spec.key, spec.defaultValue)
    fun isEffective(spec: ConfigSchema.BoolSpec): Boolean = isEffective(spec.key, spec.defaultValue, spec.dependsOn)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        reloadIfModified()
        return getBooleanInternal(key, defaultValue)
    }

    fun isEffective(
        key: String,
        defaultValue: Boolean,
        dependsOn: String? = null,
        parentDefault: Boolean = true,
    ): Boolean {
        reloadIfModified()
        var parentKey = dependsOn
        var hops = 0
        while (parentKey != null && hops++ < 8) {
            val parentSpec = ConfigSchema.byKey[parentKey] as? ConfigSchema.BoolSpec
            val pDefault = parentSpec?.defaultValue ?: parentDefault
            if (!getBooleanInternal(parentKey, pDefault)) return false
            parentKey = parentSpec?.dependsOn
        }
        return getBooleanInternal(key, defaultValue)
    }

    fun getInt(key: String, defaultValue: Int): Int {
        reloadIfModified()
        val spec = ConfigSchema.byKey[key] as? ConfigSchema.IntSpec
        return spec?.resolve(map[key]) ?: ConfigSchema.IntSpec(
            key, defaultValue, FeatureCategory.ME, "", "", "",
        ).resolve(map[key])
    }

    fun getString(key: String, defaultValue: String): String {
        reloadIfModified()
        val spec = ConfigSchema.byKey[key] as? ConfigSchema.StringSpec
        return spec?.resolve(map[key]) ?: (map[key] as? String ?: map[key]?.toString() ?: defaultValue)
    }

    fun decodeNewlines(raw: String): String = raw.replace("\\n", "\n")

    fun putBoolean(key: String, value: Boolean) = mutateSingle(key, value)
    fun putInt(key: String, value: Int) = mutateSingle(key, value)
    fun putString(key: String, value: String) = mutateSingle(key, value)

    /**
     * Validates and applies a single setting value against [ConfigSchema].
     * Returns `false` if [key] is unknown or [rawValue] cannot be parsed as the key's type.
     */
    fun setFromSchema(key: String, rawValue: Any?): Boolean {
        val spec = ConfigSchema.byKey[key] ?: return false
        val coerced = parseAndSanitize(spec, rawValue) ?: return false
        mutateLocked {
            val prev = map[key]
            map[key] = coerced
            if (prev != coerced) setOf(key) else emptySet()
        }
        return true
    }

    /**
     * Validates all keys in [patch] before atomically merging and persisting to `config.json`.
     * Throws [IllegalArgumentException] on unknown keys or invalid types.
     */
    fun applyPatch(patch: JSONObject): Set<String> {
        val parsed = LinkedHashMap<String, Any>()
        val keys = patch.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val spec = ConfigSchema.byKey[k] ?: throw IllegalArgumentException("Unknown config key: $k")
            val raw = if (patch.isNull(k)) null else patch.get(k)
            parsed[k] = parseAndSanitize(spec, raw)
                ?: throw IllegalArgumentException("Invalid value for '$k' (expected ${spec.typeName})")
        }
        return mutateLocked {
            val changed = LinkedHashSet<String>()
            for ((k, v) in parsed) {
                if (map[k] != v) {
                    map[k] = v
                    changed.add(k)
                }
            }
            changed
        }
    }

    fun resetToDefaults(): JSONObject {
        mutateLocked {
            val changed = LinkedHashSet<String>()
            for (spec in ConfigSchema.ALL_SPECS) {
                if (map[spec.key] != spec.defaultValue) {
                    map[spec.key] = spec.defaultValue
                    changed.add(spec.key)
                }
            }
            changed
        }
        return snapshot()
    }

    fun snapshot(): JSONObject {
        reloadIfModified(force = true)
        synchronized(lock) { return buildCanonicalJsonLocked() }
    }

    private fun getBooleanInternal(key: String, defaultValue: Boolean): Boolean {
        val spec = ConfigSchema.byKey[key] as? ConfigSchema.BoolSpec
        return spec?.resolve(map[key]) ?: when (val v = map[key]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            is Number -> v.toInt() != 0
            else -> defaultValue
        }
    }

    private fun mutateSingle(key: String, rawValue: Any) {
        val spec = ConfigSchema.byKey[key]
        val coerced = if (spec != null) parseAndSanitize(spec, rawValue) ?: return else rawValue
        mutateLocked {
            val prev = map[key]
            map[key] = coerced
            if (prev != coerced) setOf(key) else emptySet()
        }
    }

    private fun mutateLocked(block: () -> Set<String>): Set<String> {
        val diskChanged: Set<String>
        val writeChanged: Set<String>
        synchronized(lock) {
            if (!loadedOnce) seedDefaultsLocked()
            val f = configFile()
            diskChanged = if (f.exists() && f.length() > 0L) reloadFromDiskLocked(f) else emptySet()
            loadedOnce = true
            writeChanged = block()
            saveLocked()
        }
        notifyListeners(diskChanged + writeChanged)
        return writeChanged
    }

    private fun seedDefaultsLocked() {
        for (spec in ConfigSchema.ALL_SPECS) {
            map[spec.key] = spec.defaultValue
        }
    }

    private fun reloadFromDiskLocked(f: File): Set<String> {
        if (!f.exists() || f.length() == 0L) {
            observerDirty = false
            return emptySet()
        }
        val raw = runCatching { f.readText(Charsets.UTF_8) }.getOrElse {
            AcidLogger.w(TAG, "read config failed, keeping cached snapshot", it)
            observerDirty = false
            return emptySet()
        }
        val mtime = f.lastModified()
        val len = f.length()
        if (raw == lastLoadedRawText) {
            lastLoadedMtime = mtime
            lastLoadedLength = len
            observerDirty = false
            return emptySet()
        }
        val obj = runCatching { JSONObject(raw) }.getOrElse {
            if (raw != lastParseErrorRaw) {
                lastParseErrorRaw = raw
                AcidLogger.w(TAG, "invalid config.json ignored, preserving last valid config", it)
            }
            observerDirty = false
            return emptySet()
        }
        lastParseErrorRaw = ""

        val next = LinkedHashMap<String, Any>(ConfigSchema.ALL_SPECS.size)
        val changed = LinkedHashSet<String>()
        for (spec in ConfigSchema.ALL_SPECS) {
            val resolved = if (obj.has(spec.key) && !obj.isNull(spec.key)) {
                spec.resolve(obj.get(spec.key))
            } else {
                spec.defaultValue
            }
            next[spec.key] = resolved
            if (map[spec.key] != resolved) changed.add(spec.key)
        }
        map.clear()
        map.putAll(next)
        lastLoadedRawText = raw
        lastLoadedMtime = mtime
        lastLoadedLength = len
        observerDirty = false
        return changed
    }

    private fun saveLocked() {
        runCatching {
            val f = configFile()
            val text = buildCanonicalJsonLocked().toString(2)
            AcidIo.writeAtomicUtf8(f, text)
            lastLoadedRawText = text
            lastLoadedMtime = f.lastModified()
            lastLoadedLength = f.length()
            observerDirty = false
        }.onFailure {
            AcidLogger.w(TAG, "save config failed", it)
        }
    }

    private fun syncSchemaFileLocked() {
        runCatching {
            val sf = schemaFile()
            val text = ConfigSchema.schemaSnapshotJson(configFile()).toString(2)
            if (sf.exists() && sf.length() == text.toByteArray(Charsets.UTF_8).size.toLong()) {
                if (runCatching { sf.readText(Charsets.UTF_8) }.getOrNull() == text) return
            }
            AcidIo.writeAtomicUtf8(sf, text)
        }.onFailure {
            AcidLogger.w(TAG, "sync config.schema.json failed", it)
        }
    }

    private fun buildCanonicalJsonLocked(): JSONObject = JSONObject().apply {
        for (spec in ConfigSchema.ALL_SPECS) {
            put(spec.key, map[spec.key] ?: spec.defaultValue)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> parseAndSanitize(spec: ConfigSchema.SettingSpec<T>, raw: Any?): T? =
        spec.parseRaw(raw)?.let { spec.sanitize(it) }

    private fun notifyListeners(changed: Set<String>) {
        if (changed.isEmpty()) return
        for (listener in listeners) runCatching { listener(changed) }
    }
}
