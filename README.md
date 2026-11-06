# NavWake · 导航条唤醒重定向

把荣耀 MagicOS 的「**长按导航条唤醒 YOYO**」改成唤醒你自己选的第三方 AI —— 长按后弹一个小选择框，
在你**自己指定的任意应用**之间选，点谁开谁；也可以设成默认直达。

> 只做展示层的拉起替换，不碰 YOYO 的账号、数据与服务端交互。

---

## 原理（真机实测，非推测）

这条长按**不走**安卓标准的「默认数字助理」通道：改系统设置里的助理应用影响不到它。
真机抓日志确认的实际链路是：

```
长按导航条（SystemUI 手势）
   → ContextImpl.startService(
         com.hihonor.magicvoice/.startup.StartAssistantService)
   → magicvoice 弹出悬浮窗 FloatContentLayout
```

所以模块**只在 SystemUI 进程里拦截这一条 startService 调用**，命中后替换成打开你选的应用。
别的一概不碰。

几处关键事实（都是真机上量的）：

| 事实 | 值 |
|---|---|
| SystemUI 包名 | `com.android.systemui`（**不是** `com.hihonor.systemui`，后者真机不存在） |
| 目标服务 | `com.hihonor.magicvoice/.startup.StartAssistantService` |
| 调用方 | 只有 `com.android.systemui` 持有该服务的签名级权限 |
| YOYO 形态 | 悬浮窗（`APPLICATION_OVERLAY`），不是 Activity |
| 默认助理通道 | `voice_interaction_service` 虽指向 magicvoice，但长按**没走**它 |

> 模块**源码里不内置任何应用包名**：可选目标由你在设置页里勾选，存在本机配置里。
> 这样代码可以公开而不暴露作者装了什么应用。

## 环境

| 项 | 要求 |
|---|---|
| 手机 | 荣耀 MagicOS 11 机型（真机实测） |
| 系统 | MagicOS 11 / Android 17 |
| 框架 | LSPosed 2.2.1（libxposed API 102） |
| Root | KernelSU（临时 root 亦可） |
| 前置 | 设置 → 荣耀 AI & YOYO → YOYO 助理 → **导航条唤醒保持开启**（导航条得先存在） |

## 安装

1. 安装 `NavWake-0.4.1-release.apk`
2. 打开模块 → **选择应用**：勾选你希望长按唤起的应用（可多选）
3. LSPosed 管理器 → 模块 → 启用「导航条唤醒重定向」
3. 作用域确认勾选：
   - `系统用户界面`（`com.android.systemui`）← **最关键**
   - `YOYO 智能体`（`com.hihonor.magicvoice`）
   - `智慧决策`（`com.hihonor.brain`，仅探针模式用）
4. 重启 SystemUI 让 hook 生效：

```bash
adb shell su -c 'kill $(pidof com.android.systemui)'
```

> 重启整机也能生效，但临时 root 会失效、模块随之停用 —— 这是 KernelSU 临时 root 的已知前提。

## 使用

| 想做什么 | 怎么做 |
|---|---|
| 增减可唤起的应用 | 设置页 → 选择应用（列出本机所有可启动应用） |
| 换成别的应用 | 设置页 → 默认目标 → 点另一个应用 |
| 每次长按都弹选择框 | 默认目标 → 清除默认 |
| 临时完全不用 | 关掉总开关，立刻恢复原 YOYO 行为 |
| 单击导航条 = 返回（替代 mback） | 设置页 → 打开「单击导航条返回」（默认关，与总开关独立，改完立即生效） |

## 熔断设计（长按绝不能变成死键）

任何一步失败都回落原 YOYO：

- 总开关关闭 → 放行
- 锁屏状态 → 放行（v1 不拦锁屏）
- 三个目标都没装 → 放行
- 选择框拉起失败 → 放行
- hook 安装失败 / 回调内异常 → 放行

## 已知限制

- 只适配实测的 MagicOS 11 版本。系统升级后点位若变化，模块会熔断放行，不会崩。
- 锁屏状态不拦截。
- 语音唤醒、气息唤醒、电源键长按唤醒不在拦截范围（导航条这条手势与其他路径是分开的）。

## 构建

```bash
# 需要 JDK 21 与 Android SDK 34
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleRelease
```

可选脚本：

```bash
scripts/setup-gradle-home.sh   # 受限沙箱环境下把依赖缓存复制进工作区
scripts/build.sh               # 编译 + 校验 APK 内 LSPosed 声明三件套
scripts/logs.sh lspd           # 实时看 LSPosed 框架日志
```

构建脚本会做两项强制校验：

- `verifyXposedModule`：解包 APK 检查 `META-INF/xposed/` 三件套是否完整（缺了 LSPosed 就看不到模块，而构建仍然"成功"）
- release 关闭混淆（LSPosed 靠类全名反射加载入口，混淆 = 静默失效）

## 目录

```
app/src/main/java/io/github/yuxiximyxx/navwake/
├── HookEntry.kt              LSPosed 入口（java_init.list 指定）
├── MainActivity.kt           设置页
├── ChooserActivity.kt        长按弹出的底部选择框
├── config/                   包名常量、目标应用、配置读写
├── core/                     日志通道、熔断式 hook 封装
├── hook/RedirectHook.kt      唯一改行为的拦截点
└── probe/                    诊断探针（默认关闭）
```

## License

GPL-3.0
