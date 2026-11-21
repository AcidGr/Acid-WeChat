package com.acid.wechat

import android.app.Application
import com.acid.wechat.agent.AgentConfigStore
import com.acid.wechat.core.AcidLogger
import com.acid.wechat.core.ConfigStore
import com.acid.wechat.core.DexKitLocator
import com.acid.wechat.core.FeatureRegistry
import com.acid.wechat.core.HostContext
import com.acid.wechat.core.MsgNoticeHelper
import com.acid.wechat.core.WcdbBridge
import com.acid.wechat.debug.DebugServer
import com.acid.wechat.features.AntiMomentsPostFeature
import com.acid.wechat.features.AntiRecallFeature
import com.acid.wechat.features.AutoVoiceTransFeature
import com.acid.wechat.features.BlockHotUpdateFeature
import com.acid.wechat.features.BlockTypingStatusFeature
import com.acid.wechat.features.ChatAgentFeature
import com.acid.wechat.features.CustomGameEmojiFeature
import com.acid.wechat.features.FakeLocationFeature
import com.acid.wechat.features.FakeSnsCommentFeature
import com.acid.wechat.features.FakeSnsLikeFeature
import com.acid.wechat.features.ForceTabletModeFeature
import com.acid.wechat.features.SavePathFeature
import com.acid.wechat.features.SnsTailFeature
import com.acid.wechat.ui.HomeHeaderAiHook
import com.acid.wechat.ui.SettingsHook
import com.acid.wechat.wechat.WeChatImageDownloader
import com.acid.wechat.wechat.WeChatSender
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed/LSPosed entry point for Acid-WeChat.
 * Waits for Application.onCreate so WeChat's Tinker DelegateLastClassLoader is active before hooking.
 */
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    companion object {
        private const val TAG = "HookEntry"
        private val hookedAppCreate = AtomicBoolean(false)
        private val initialized = AtomicBoolean(false)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        HostContext.setModulePath(startupParam.modulePath)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (!HostContext.isSupportedPackage(pkg)) return
        HostContext.setHostPackage(pkg)
        val proc = lpparam.processName ?: return
        BlockHotUpdateFeature.installEarlyHooks(lpparam.classLoader, proc)
        val isMain = proc == pkg
        val isExportProc = proc == "$pkg:tools" ||
            proc.startsWith("$pkg:appbrand")
        if (!isMain && !isExportProc) return
        if (!hookedAppCreate.compareAndSet(false, true)) return

        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        if (!HostContext.isSupportedPackage(app.packageName)) return
                        if (isMain) bootstrap(app) else bootstrapSubprocess(app, proc)
                    }
                },
            )
        }.onFailure {
            AcidLogger.e(TAG, "failed hooking Application.onCreate ($proc)", it)
        }
    }

    private fun bootstrapSubprocess(app: Application, processName: String) {
        if (!initialized.compareAndSet(false, true)) return

        runCatching {
            HostContext.init(app, isMainProcess = false)
            ConfigStore.load(syncSchemaFile = false)
            FeatureRegistry.register(SavePathFeature)
            FeatureRegistry.register(FakeLocationFeature)
            FeatureRegistry.registerAllDexRules()
            DexKitLocator.resolveAll(forceRescan = false, saveCache = false)
            FeatureRegistry.initAll()
            AcidLogger.i(TAG, "Acid-WeChat subprocess ready: $processName")
        }.onFailure {
            AcidLogger.e(TAG, "subprocess bootstrap failed ($processName)", it)
        }
    }

    private fun bootstrap(app: Application) {
        if (!initialized.compareAndSet(false, true)) return

        runCatching {
            HostContext.init(app, isMainProcess = true)
            ConfigStore.load()
            AgentConfigStore.ensureInitialized()
            AcidLogger.i(
                TAG,
                "Acid-WeChat ${HostContext.MODULE_VERSION_NAME} attached to ${HostContext.hostVersionName} (${HostContext.hostVersionCode})",
            )

            // Initialize version-agnostic bridges & Settings UI first
            WcdbBridge.init()
            SettingsHook.init()
            HomeHeaderAiHook.init()
            DebugServer.startIfEnabled()

            // Register features & DexKit rules
            MsgNoticeHelper.registerDexRules()
            WeChatSender.registerDexRules()
            WeChatImageDownloader.registerDexRules()
            FeatureRegistry.register(AntiRecallFeature)
            FeatureRegistry.register(CustomGameEmojiFeature)
            FeatureRegistry.register(AutoVoiceTransFeature)
            FeatureRegistry.register(BlockTypingStatusFeature)
            FeatureRegistry.register(ChatAgentFeature)
            FeatureRegistry.register(AntiMomentsPostFeature)
            FeatureRegistry.register(FakeSnsLikeFeature)
            FeatureRegistry.register(FakeSnsCommentFeature)
            FeatureRegistry.register(SnsTailFeature)
            FeatureRegistry.register(SavePathFeature)
            FeatureRegistry.register(FakeLocationFeature)
            FeatureRegistry.register(ForceTabletModeFeature)
            FeatureRegistry.register(BlockHotUpdateFeature)
            FeatureRegistry.registerAllDexRules()

            // Resolve DexKit (instant on cache hit; scans once per WeChat version/Tinker change)
            DexKitLocator.resolveAll(forceRescan = false)

            MsgNoticeHelper.init()
            WeChatSender.init()
            WeChatImageDownloader.init()
            FeatureRegistry.initAll()
            AcidLogger.i(TAG, "Acid-WeChat bootstrap completed")
        }.onFailure {
            AcidLogger.e(TAG, "bootstrap crashed unexpectedly (isolated)", it)
        }
    }
}
