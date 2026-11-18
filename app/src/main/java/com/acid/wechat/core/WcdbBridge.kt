package com.acid.wechat.core

import android.content.ContentValues
import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unobfuscated WCDB SQLiteDatabase hook bridge.
 * Captures insert/update events across WeChat versions without relying on obfuscated class names.
 */
object WcdbBridge {
    private const val TAG = "WcdbBridge"

    fun interface InsertListener {
        fun onInsert(table: String, values: ContentValues, rowId: Long)
    }

    fun interface UpdateListener {
        fun onUpdate(
            table: String,
            values: ContentValues,
            whereClause: String?,
            whereArgs: Array<String>?,
            conflictAlgorithm: Int,
        )
    }

    private val insertListeners = CopyOnWriteArrayList<InsertListener>()
    private val updateListeners = CopyOnWriteArrayList<UpdateListener>()

    @Volatile
    var lastDb: Any? = null
        private set

    @Volatile
    var mainDb: Any? = null
        private set

    @Volatile
    var snsDb: Any? = null
        private set

    @Volatile
    var hookedInsertCount: Int = 0
        private set

    @Volatile
    var hookedUpdateCount: Int = 0
        private set

    fun addInsertListener(l: InsertListener) {
        insertListeners.addIfAbsent(l)
    }

    fun addUpdateListener(l: UpdateListener) {
        updateListeners.addIfAbsent(l)
    }

    fun init() {
        val dbClasses = listOf(
            "com.tencent.wcdb.database.SQLiteDatabase",
            "com.tencent.wcdb.compat.SQLiteDatabase",
        )
        for (clsName in dbClasses) {
            val cls = runCatching { Class.forName(clsName, false, HostContext.classLoader) }.getOrNull() ?: continue
            runCatching {
                val hooks = XposedBridge.hookAllMethods(cls, "insertWithOnConflict", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        if (args.size < 3) return
                        val table = args[0] as? String ?: return
                        if (clsName == "com.tencent.wcdb.database.SQLiteDatabase") {
                            recordDbInstance(param.thisObject, table)
                        }
                        if (insertListeners.isEmpty()) return
                        val values = args[2] as? ContentValues ?: return
                        val rowId = (param.result as? Number)?.toLong() ?: -1L
                        for (listener in insertListeners) {
                            runCatching { listener.onInsert(table, values, rowId) }
                                .onFailure { AcidLogger.w(TAG, "insert listener error: ${it.message}") }
                        }
                    }
                })
                hookedInsertCount += hooks.size
            }.onFailure {
                AcidLogger.w(TAG, "hook insertWithOnConflict on $clsName failed", it)
            }

            runCatching {
                val hooks = XposedBridge.hookAllMethods(cls, "updateWithOnConflict", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        if (args.size < 5) return
                        val table = args[0] as? String ?: return
                        if (clsName == "com.tencent.wcdb.database.SQLiteDatabase") {
                            recordDbInstance(param.thisObject, table)
                        }
                        if (updateListeners.isEmpty()) return
                        val values = args[1] as? ContentValues ?: return
                        val whereClause = args[2] as? String
                        @Suppress("UNCHECKED_CAST")
                        val whereArgs = when (val wa = args[3]) {
                            is Array<*> -> wa.map { it?.toString() ?: "" }.toTypedArray()
                            else -> null
                        }
                        val conflictAlgo = (args[4] as? Number)?.toInt() ?: 0
                        for (listener in updateListeners) {
                            runCatching {
                                listener.onUpdate(table, values, whereClause, whereArgs, conflictAlgo)
                            }.onFailure {
                                AcidLogger.w(TAG, "update listener error: ${it.message}")
                            }
                        }
                    }
                })
                hookedUpdateCount += hooks.size
            }.onFailure {
                AcidLogger.w(TAG, "hook updateWithOnConflict on $clsName failed", it)
            }
        }
        AcidLogger.i(TAG, "WCDB bridge ready: insertHooks=$hookedInsertCount, updateHooks=$hookedUpdateCount")
    }

    private fun recordDbInstance(db: Any?, table: String) {
        if (db == null) return
        lastDb = db
        when (table) {
            "message", "rconversation", "rcontact" -> {
                mainDb = db
                return
            }
            "SnsInfo", "SnsComment" -> {
                snsDb = db
                return
            }
        }
        val path = runCatching {
            db.javaClass.getMethod("getPath").invoke(db) as? String
        }.getOrNull() ?: return
        if (mainDb == null && path.endsWith("EnMicroMsg.db")) {
            mainDb = db
        } else if (snsDb == null && path.endsWith("SnsMicroMsg.db")) {
            snsDb = db
        }
    }

    private fun resolveDbBySuffix(cached: Any?, suffix: String, cacheSetter: (Any) -> Unit): Any? {
        cached?.let { return it }
        runCatching {
            val cls = Class.forName("com.tencent.wcdb.database.SQLiteDatabase", false, HostContext.classLoader)
            val field = cls.getDeclaredField("sActiveDatabases").apply { isAccessible = true }
            val active = field.get(null) as? Map<*, *>
            if (active != null) {
                for (db in active.keys) {
                    if (db == null) continue
                    val path = runCatching { db.javaClass.getMethod("getPath").invoke(db) as? String }.getOrNull()
                    if (path != null && path.endsWith(suffix)) {
                        cacheSetter(db)
                        return db
                    }
                }
            }
        }
        return if (suffix == "EnMicroMsg.db") lastDb else null
    }

    fun rawQuery(sql: String, selectionArgs: Array<String>? = null): Cursor? =
        rawQueryOn(resolveDbBySuffix(mainDb, "EnMicroMsg.db") { mainDb = it }, sql, selectionArgs)

    fun mainDbPath(): String? {
        val db = resolveDbBySuffix(mainDb, "EnMicroMsg.db") { mainDb = it } ?: return null
        return runCatching { db.javaClass.getMethod("getPath").invoke(db) as? String }.getOrNull()
    }

    fun rawQuerySns(sql: String, selectionArgs: Array<String>? = null): Cursor? =
        rawQueryOn(resolveDbBySuffix(snsDb, "SnsMicroMsg.db") { snsDb = it }, sql, selectionArgs)

    private fun rawQueryOn(db: Any?, sql: String, selectionArgs: Array<String>?): Cursor? {
        if (db == null) return null
        return runCatching {
            val methods = db.javaClass.methods.filter { it.name == "rawQuery" && it.parameterTypes.size == 2 }
            for (m in methods) {
                val p1 = m.parameterTypes[1]
                if (p1 == Array<String>::class.java) {
                    return m.invoke(db, sql, selectionArgs) as? Cursor
                }
                if (p1 == Array<Any>::class.java) {
                    val objArgs: Array<Any>? = selectionArgs?.map { it as Any }?.toTypedArray()
                    return m.invoke(db, sql, objArgs) as? Cursor
                }
            }
            null
        }.onFailure {
            AcidLogger.w(TAG, "rawQuery failed: ${it.message}")
        }.getOrNull()
    }
}
