package com.acid.wechat.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Shared reflection utilities across DexKit resolution, hooks, and notice helpers.
 */
object ReflectHelper {
    fun loadType(name: String, cl: ClassLoader = HostContext.classLoader): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> if (name.endsWith("[]")) {
            val base = name.removeSuffix("[]")
            java.lang.reflect.Array.newInstance(loadType(base, cl), 0).javaClass
        } else {
            Class.forName(name, false, cl)
        }
    }

    fun findMethod(cls: Class<*>, name: String, paramTypes: List<Class<*>>): Method? {
        var cur: Class<*>? = cls
        while (cur != null) {
            for (m in cur.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == paramTypes.size &&
                    m.parameterTypes.indices.all { i -> m.parameterTypes[i] == paramTypes[i] }
                ) {
                    m.isAccessible = true
                    return m
                }
            }
            cur = cur.superclass
        }
        return null
    }

    fun findMethodByNames(cls: Class<*>, name: String, paramTypeNames: List<String>): Method? {
        val cl = cls.classLoader ?: HostContext.classLoader
        val paramClasses = paramTypeNames.map { loadType(it, cl) }
        return findMethod(cls, name, paramClasses)
    }

    fun findConstructorByNames(cls: Class<*>, paramTypeNames: List<String>): Constructor<*> {
        val cl = cls.classLoader ?: HostContext.classLoader
        val paramClasses = paramTypeNames.map { loadType(it, cl) }.toTypedArray()
        return cls.getDeclaredConstructor(*paramClasses).apply { isAccessible = true }
    }

    fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var cur: Class<*>? = cls
        while (cur != null) {
            runCatching {
                return cur.getDeclaredField(name).apply { isAccessible = true }
            }
            cur = cur.superclass
        }
        return null
    }

    fun unwrapActivity(ctx: Context?): Activity? {
        var cur = ctx
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    fun findActivityField(obj: Any): Activity? {
        var cur: Class<*>? = obj.javaClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (Activity::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    (f.get(obj) as? Activity)?.let { return it }
                }
            }
            cur = cur.superclass
        }
        return null
    }
}
