package io.github.yuxiximyxx.navwake.config

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.yuxiximyxx.navwake.core.ModuleLog

/**
 * ServiceBridge —— 设置页与 LSPosed 框架之间的连接。
 *
 * 为什么需要它：hook 侧用 `getRemotePreferences()` 读配置，但那份数据在
 * 被注入的 SystemUI 进程里是**带缓存的**。设置页只改本地文件的话，
 * SystemUI 读到的还是旧值（真机实测：设了默认目标，长按仍然弹框）。
 *
 * 连上框架服务后用 `service.getRemotePreferences()` 写一份，框架会把变更
 * 推给所有已注入进程，缓存随之失效。
 *
 * 拿不到服务也不影响基本使用 —— 本地照常写，最坏情况是「改完要等一会儿/
 * 重启 SystemUI 才生效」，功能不会坏。
 */
object ServiceBridge {

    private const val TAG = "ServiceBridge"

    private const val LSPOSED_PACKAGE = "org.lsposed.manager"

    @Volatile
    private var service: XposedService? = null

    @Volatile
    var bound: Boolean = false
        private set

    fun install(application: Application) {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(value: XposedService) {
                    service = value
                    bound = true
                    ModuleLog.i(TAG, "LSPosed 服务已连接 api=${value.apiVersion}")
                }

                override fun onServiceDied(value: XposedService) {
                    service = null
                    bound = false
                    ModuleLog.w(TAG, "LSPosed 服务断开")
                }
            })
        }.onFailure {
            ModuleLog.e(TAG, "注册 LSPosed 服务监听失败（不影响本地设置）", it)
        }
    }

    /** 拿远程 prefs（只在连上服务时非 null）。 */
    fun remotePreferences(name: String): SharedPreferences? = runCatching {
        service?.getRemotePreferences(name)
    }.getOrNull()

    /**
     * 把一组改动同时写进远程 prefs。
     * 本地那一份由调用方自己写，这里只负责「通知已运行的进程」。
     */
    fun pushRemote(name: String, block: (SharedPreferences.Editor) -> Unit) {
        val remote = remotePreferences(name) ?: return
        runCatching {
            val editor = remote.edit()
            block(editor)
            editor.apply()
        }.onFailure { ModuleLog.w(TAG, "推送远程 prefs 失败: ${it.message}") }
    }
}
