package io.github.yuxiximyxx.navwake.hook

import android.content.Context
import android.content.res.Resources
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.core.argOrNull
import io.github.yuxiximyxx.navwake.core.thisOrNull
import java.lang.reflect.Method
import kotlin.math.hypot

/**
 * TapBackHook —— 「单击导航条返回」（v0.4.0 新增，v0.4.1 修点位）。
 *
 * 机主原本单装 mback 实现这个功能，本类把它并进 NavWake，少装一个模块。
 *
 * ## 原理
 *
 * 观察 SystemUI 里导航条视图（NavigationBarView）的触摸流：
 * 一次「按下 → 抬起」若同时满足【间隔 ≤ 250ms】【位移 ≤ 10dp】【单指】
 * 【期间没有第二根手指】，判定为单击，在原事件处理完后注入一次 KEYCODE_BACK。
 *
 * 与另外两条手势天然不冲突：
 * - 长按：间隔超限，不触发（长按仍归 RedirectHook 管）；
 * - 上滑回桌面 / 多任务：位移超限，不触发。
 *
 * ## 红线（与需求补充单一致）
 *
 * - **纯观察，永不消费事件**：任何情况下都先 `chain.proceed()` 让系统原样处理，
 *   本类的判断只决定「事后要不要补一发返回键」。判断逻辑全错也只是不发/误发
 *   一次返回，绝不会让导航条变死、变卡或吞手势；
 * - 功能开关默认关；开关状态在每次按下时现读远程配置（绕过进程内缓存，
 *   与 RedirectHook 同款做法），设置页改完立即生效，不用重启 SystemUI；
 * - 注入失败只记日志，不重试、不抛异常。
 *
 * ## hook 点位的选择（v0.4.1 的教训）
 *
 * v0.4.0 挂的是 NavigationBarView 本类声明的 onTouchEvent，真机日志实证在
 * MagicOS 上**一次单击都没判定到**（两次开机日志里「命中单击」均为零，而同期
 * 长按命中多条）：小白条的触摸在到达导航条自己的 onTouchEvent 之前，就被子
 * 控件/手势通道拿走了 —— onTouchEvent 只收「没人消费」的事件，天然看不见它。
 *
 * v0.4.1 改挂 **dispatchTouchEvent 层**：那是触摸进入导航条视图树的第一站，
 * 不管最后谁消费，事件都先从这里过。NavigationBarView 自己不声明它，所以挂
 * ViewGroup 声明的那个 Method，并在回调里按 thisObject 类名严格过滤，只认
 * NavigationBarView 本尊。挂上哪个点位都会打进日志。
 */
object TapBackHook {

    private const val TAG = "TapBackHook"

    private const val NAV_BAR_VIEW = "com.android.systemui.navigationbar.views.NavigationBarView"

    /** 单击判定：最长按下时长。 */
    private const val MAX_TAP_DURATION_MS = 250L

    /** 单击判定：按下到抬起的最大位移（dp）。 */
    private const val MAX_MOVE_DP = 10f

    /** injectInputEvent 的异步模式（不阻塞 SystemUI 主线程）。 */
    private const val INJECT_MODE_ASYNC = 0

    // ── 手势跟踪状态 ──────────────────────────────────────────────
    // 触摸分发在 SystemUI 主线程串行执行，单候选跟踪足够；字段加 Volatile 防御即可。

    @Volatile
    private var candidate = false

    @Volatile
    private var downTimeMs = 0L

    @Volatile
    private var downX = 0f

    @Volatile
    private var downY = 0f

    fun install(context: HookContext) {
        val navBarClass = context.findClass(NAV_BAR_VIEW) ?: run {
            ModuleLog.w(TAG, "找不到 $NAV_BAR_VIEW，单击返回不安装（不影响长按重定向）")
            return
        }

        // ① 本类声明的 dispatchTouchEvent（最理想：只有导航条的触摸流经这里）
        declaredTouchMethod(context, navBarClass, "dispatchTouchEvent")?.let { method ->
            if (context.hook(method, "tapback#declared-dispatch") { chain -> observe(chain) }) {
                ModuleLog.i(TAG, "单击返回就绪（点位：NavigationBarView.dispatchTouchEvent 本类声明）")
                return
            }
        }

        // ② 继承的 dispatchTouchEvent（声明在 ViewGroup 上）+ 类名过滤 —— v0.4.1 主点位
        inheritedTouchMethod(navBarClass, "dispatchTouchEvent")?.let { method ->
            if (context.hook(method, "tapback#inherited-dispatch") { chain ->
                observeFiltered(chain, navBarClass)
            }) {
                ModuleLog.i(
                    TAG,
                    "单击返回就绪（点位：${method.declaringClass.name}.dispatchTouchEvent 继承点 + 类名过滤）"
                )
                return
            }
        }

        // ③ 本类声明的 onTouchEvent（兜底；MagicOS 实测收不到小白条触摸，见类注释）
        declaredTouchMethod(context, navBarClass, "onTouchEvent")?.let { method ->
            if (context.hook(method, "tapback#declared-ontouch") { chain -> observe(chain) }) {
                ModuleLog.i(TAG, "单击返回就绪（点位：NavigationBarView.onTouchEvent 本类声明）")
                return
            }
        }

        // ④ 继承的 onTouchEvent + 类名过滤（最后兜底）
        inheritedTouchMethod(navBarClass, "onTouchEvent")?.let { method ->
            if (context.hook(method, "tapback#inherited-ontouch") { chain ->
                observeFiltered(chain, navBarClass)
            }) {
                ModuleLog.i(
                    TAG,
                    "单击返回就绪（点位：${method.declaringClass.name}.onTouchEvent 继承点 + 类名过滤）"
                )
                return
            }
        }

        ModuleLog.w(TAG, "导航条触摸点位一个都没挂上 —— 单击返回不生效，其余功能不受影响")
    }

    /** 在类**本层**找声明了的单参数 MotionEvent 方法（dispatchTouchEvent / onTouchEvent）。 */
    private fun declaredTouchMethod(
        context: HookContext,
        clazz: Class<*>,
        name: String
    ): Method? = context.methodsNamed(clazz, name).firstOrNull { method ->
        method.parameterTypes.size == 1 &&
            MotionEvent::class.java.isAssignableFrom(method.parameterTypes[0])
    }

    /** 沿继承链向上找第一个声明该方法的类，返回其 Method（用于兜底 hook）。 */
    private fun inheritedTouchMethod(clazz: Class<*>, name: String): Method? {
        var current: Class<*>? = clazz.superclass
        while (current != null && current != Any::class.java) {
            val found = runCatching { current.declaredMethods.toList() }.getOrDefault(emptyList())
                .firstOrNull { method ->
                    method.name == name &&
                        method.parameterTypes.size == 1 &&
                        MotionEvent::class.java.isAssignableFrom(method.parameterTypes[0])
                }
            if (found != null) {
                found.isAccessible = true
                return found
            }
            current = current.superclass
        }
        return null
    }

    /** 继承点位用：thisObject 不是导航条本尊就立刻放行。 */
    private fun observeFiltered(chain: XposedInterface.Chain, navBarClass: Class<*>): Any? {
        val self = chain.thisOrNull()
        if (self == null || !navBarClass.isInstance(self)) return chain.proceed()
        return observe(chain)
    }

    /**
     * 观察一条触摸事件。返回值恒为 proceed 的结果，本方法永不拦截、永不改事件。
     *
     * 顺序刻意为之：先从事件里把要用的值**快照**出来（事件对象在分发结束后可能被
     * 回收复用），再 proceed 让系统原样处理，最后才按快照结论决定补不补返回键。
     */
    private fun observe(chain: XposedInterface.Chain): Any? {
        val tapCompleted = runCatching { inspect(chain) }.getOrDefault(false)
        val result = chain.proceed()
        if (tapCompleted) {
            runCatching { injectBack() }
                .onFailure { ModuleLog.e(TAG, "注入返回键失败（已忽略）", it) }
        }
        return result
    }

    /**
     * 看一眼事件、维护候选状态。
     *
     * @return true 仅当**本条事件是 ACTION_UP 且刚完成了一次合格单击**。
     */
    private fun inspect(chain: XposedInterface.Chain): Boolean {
        val event = chain.argOrNull(0) as? MotionEvent ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 每次按下现读一次开关：设置页改完立刻生效（绕过远程 prefs 缓存）
                NavConfig.refresh {
                    HookEntry.moduleInstance?.getRemotePreferences(NavConfig.PREFS_NAME)
                }
                candidate = NavConfig.tapBackEnabled()
                downTimeMs = event.eventTime
                downX = event.x
                downY = event.y
                false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                candidate = false // 第二根手指落下 → 不是单击
                false
            }

            MotionEvent.ACTION_MOVE -> {
                if (candidate && movedTooFar(event)) candidate = false
                false
            }

            MotionEvent.ACTION_UP -> {
                val ok = candidate &&
                    event.eventTime - downTimeMs <= MAX_TAP_DURATION_MS &&
                    !movedTooFar(event)
                candidate = false
                if (ok) ModuleLog.d(TAG, "命中单击（${event.eventTime - downTimeMs}ms），注入返回")
                ok
            }

            MotionEvent.ACTION_CANCEL -> {
                candidate = false
                false
            }

            else -> false
        }
    }

    private fun movedTooFar(event: MotionEvent): Boolean {
        val density = runCatching { Resources.getSystem().displayMetrics.density }
            .getOrDefault(3f)
        val maxPx = MAX_MOVE_DP * density
        return hypot(event.x - downX, event.y - downY) > maxPx
    }

    /**
     * 注入一次返回键（按下+抬起）。
     *
     * 两条路依次尝试：InputManager.getInstance() → ServiceManager 拿 IInputManager。
     * 都在 SystemUI 进程里执行，它持有 INJECT_EVENTS 权限。任一通了即算成功。
     */
    private fun injectBack() {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD
        )
        val up = KeyEvent(
            now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD
        )
        if (injectViaInputManager(down) && injectViaInputManager(up)) return
        if (injectViaServiceManager(down) && injectViaServiceManager(up)) return
        ModuleLog.w(TAG, "两条注入路径都失败，本次单击不产生返回")
    }

    private fun injectViaInputManager(event: InputEvent): Boolean = runCatching {
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        val instance = inputManagerClass.getMethod("getInstance").invoke(null)
            ?: return@runCatching false
        val method = inputManagerClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
        method.invoke(instance, event, INJECT_MODE_ASYNC) as? Boolean ?: false
    }.getOrDefault(false)

    private fun injectViaServiceManager(event: InputEvent): Boolean = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, Context.INPUT_SERVICE) as? IBinder ?: return@runCatching false
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val manager = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: return@runCatching false
        val method = manager.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
        method.invoke(manager, event, INJECT_MODE_ASYNC) as? Boolean ?: false
    }.getOrDefault(false)
}
