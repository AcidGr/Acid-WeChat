package com.acid.wechat.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ReflectHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Launches Android's system file manager (`ACTION_OPEN_DOCUMENT` / `ACTION_GET_CONTENT`)
 * directly from a hooked WeChat `Activity`, intercepts `onActivityResult` by 16-bit request code,
 * and copies the picked audio `Uri` into an accessible cache file.
 */
object AudioFilePicker {
    private const val TAG = "AudioFilePicker"
    private const val REQUEST_CODE_PICK_AUDIO = 0x5643 // 16-bit safe for FragmentActivity

    data class PickedAudio(
        val file: File,
        val displayName: String,
    )

    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    private val pendingCallback = AtomicReference<((Result<PickedAudio?>) -> Unit)?>(null)

    fun pickAudio(activity: Activity, onPicked: (Result<PickedAudio?>) -> Unit) {
        if (!ensureActivityResultHooked(activity)) {
            onPicked(Result.failure(IllegalStateException("无法挂载系统文件选择器回调")))
            return
        }
        pendingCallback.set(onPicked)

        val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "audio/mpeg",
                    "audio/mp3",
                    "audio/wav",
                    "audio/x-wav",
                    "audio/flac",
                    "audio/ogg",
                    "audio/mp4",
                    "audio/x-m4a",
                    "audio/aac",
                    "audio/amr",
                    "application/ogg",
                ),
            )
        }
        val launched = runCatching {
            activity.startActivityForResult(openDoc, REQUEST_CODE_PICK_AUDIO)
            true
        }.getOrElse {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            runCatching {
                activity.startActivityForResult(Intent.createChooser(fallback, "选择音频文件"), REQUEST_CODE_PICK_AUDIO)
                true
            }.onFailure { err ->
                pendingCallback.set(null)
                AcidLogger.w(TAG, "launch audio picker failed", err)
                onPicked(Result.failure(err))
            }.getOrDefault(false)
        }
        if (launched) {
            AcidLogger.i(TAG, "opened system audio picker on ${activity.javaClass.simpleName}")
        }
    }

    private fun ensureActivityResultHooked(activity: Activity): Boolean {
        val method = ReflectHelper.findMethod(
            activity.javaClass,
            "onActivityResult",
            listOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java),
        ) ?: return false

        if (!hookedMethods.add(method)) return true

        return runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val reqCode = param.args?.getOrNull(0) as? Int ?: return
                    if (reqCode != REQUEST_CODE_PICK_AUDIO) return
                    param.result = null

                    val act = param.thisObject as? Activity ?: return
                    val resCode = param.args?.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
                    val uri = (param.args?.getOrNull(2) as? Intent)?.data
                    val cb = pendingCallback.getAndSet(null) ?: return

                    if (resCode != Activity.RESULT_OK || uri == null) {
                        cb(Result.success(null))
                        return
                    }

                    Thread({
                        val copied = runCatching { copyUriToTempFile(act, uri) }
                        cb(copied)
                    }, "AcidAudioPickCopy").apply { isDaemon = true; start() }
                }
            })
            AcidLogger.i(TAG, "hooked ${method.declaringClass.name}.onActivityResult")
            true
        }.onFailure { err ->
            hookedMethods.remove(method)
            AcidLogger.w(TAG, "hook onActivityResult failed", err)
        }.getOrDefault(false)
    }

    private fun copyUriToTempFile(activity: Activity, uri: Uri): PickedAudio {
        val displayName = queryDisplayName(activity, uri) ?: "audio_${System.currentTimeMillis()}.mp3"
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            .take(80)
            .ifBlank { "audio.mp3" }
        val dir = File(activity.cacheDir, "acid_voice_pick").apply { mkdirs() }
        val cutoffMs = System.currentTimeMillis() - 3_600_000L
        dir.listFiles()?.forEach { old ->
            if (old.isFile && old.lastModified() < cutoffMs) runCatching { old.delete() }
        }
        val dst = File(dir, "${System.currentTimeMillis()}_$safeName")

        val stream = activity.contentResolver.openInputStream(uri)
            ?: error("无法打开所选音频文件流")
        stream.use { ins ->
            FileOutputStream(dst).use { fos ->
                ins.copyTo(fos)
                fos.flush()
            }
        }
        if (!dst.isFile || dst.length() <= 0L) {
            runCatching { dst.delete() }
            error("所选音频文件为空")
        }
        return PickedAudio(file = dst, displayName = displayName)
    }

    private fun queryDisplayName(activity: Activity, uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
}
