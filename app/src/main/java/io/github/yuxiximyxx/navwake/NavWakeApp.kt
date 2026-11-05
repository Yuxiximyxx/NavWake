package io.github.yuxiximyxx.navwake

import android.app.Application
import io.github.yuxiximyxx.navwake.config.ServiceBridge

/**
 * 模块设置页所在 App 的 Application。
 *
 * 唯一职责：注册 LSPosed 服务监听，让设置改动能即时推给已运行的 SystemUI。
 * 连不上服务不影响任何功能。
 */
class NavWakeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceBridge.install(this)
    }
}
