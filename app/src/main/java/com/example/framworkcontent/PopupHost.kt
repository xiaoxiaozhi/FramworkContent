package com.example.framworkcontent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton

/**
 * 弹窗宿主：持有 WindowManager 和当前挂上去的窗口。
 *
 * 由 [DemoApp] 实例持有（不是 object、不是 Activity 字段）：
 *   - Activity finish 之后弹窗还在屏幕上，因为引用在 Application 上；
 *   - 静态区没有任何 View/Context 引用，不会触发 lint 的 StaticFieldLeak；
 *   - 同一个宿主里只有一份同类弹窗，重进 Activity 不会叠加。
 *
 * 充气用 [ContextThemeWrapper] 包 applicationContext，View 只认识应用级 Context，
 * 即使 Activity 被销毁重建也不会被这些弹窗拖住。
 */
@SuppressLint("InflateParams") // 弹窗根布局没有父容器，null root 是刻意的
class PopupHost(private val appContext: Context) {

    private val wm: WindowManager =
        appContext.getSystemService(WindowManager::class.java)

    // 带主题的应用级 Context：MaterialButton 需要 AppCompat/Material 主题
    private val themedContext: Context =
        ContextThemeWrapper(appContext, R.style.Theme_FramworkContent_WindowDemo)

    private val inflater: LayoutInflater = LayoutInflater.from(themedContext)

    /** 挖孔屏铺满模式：API 30 才支持 ALWAYS，低版本用 SHORT_EDGES。 */
    private val cutoutMode: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    private var smallView: View? = null
    private var smallParams: WindowManager.LayoutParams? = null

    private var fullView: View? = null
    private var fullParams: WindowManager.LayoutParams? = null

    // ------------------------------------------------------------ A. 可拖动小弹窗

    /** 步骤 1：只 inflate + 配 LayoutParams，不 addView。 */
    fun prepareSmall() {
        if (smallView != null) return
        val view = inflater.inflate(R.layout.window_transparent, null)

        view.findViewById<TextView>(R.id.tv_window_hint).text =
            themedContext.getString(R.string.popup_hint, 1)

        smallParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不要 FLAG_NOT_TOUCHABLE，否则收不到手指事件；NOT_FOCUSABLE 让弹窗不抢焦点
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        ).apply {
            // 水平居中起步：CENTER_HORIZONTAL 下 x 是相对中心的偏移量
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 360
            title = TAG_SMALL
            layoutInDisplayCutoutMode = cutoutMode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        // 拖动：只挂在拖动手柄上，避免跟“关闭”按钮抢点击事件
        view.findViewById<View>(R.id.drag_handle).setOnTouchListener(makeDragListener())

        // 小弹窗的 close：关掉当前所有弹窗（含全屏弹窗）
        view.findViewById<MaterialButton>(R.id.btn_remove_popup).setOnClickListener {
            removeAll()
        }

        smallView = view
    }

    @SuppressLint("ClickableViewAccessibility") // 手柄只做拖动，没有 click 语义
    private fun makeDragListener(): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = smallParams ?: return false
                val view = smallView ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - downRawX).toInt()
                        params.y = startY + (event.rawY - downRawY).toInt()
                        if (view.isAttachedToWindow) {
                            runCatching { wm.updateViewLayout(view, params) }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        }
    }

    fun showSmall() {
        prepareSmall()
        val view = smallView ?: return
        val params = smallParams ?: return
        if (view.isAttachedToWindow) return
        runCatching { wm.addView(view, params) }
            .onFailure { toast("添加窗口失败：${it.message}") }
    }

    fun removeSmall() {
        val view = smallView ?: return
        if (!view.isAttachedToWindow) return
        runCatching { wm.removeViewImmediate(view) }
    }

    // ------------------------------------------------------- B. 全屏透明弹窗

    /**
     * 步骤 1：准备全屏弹窗。
     * 参数和小弹窗一致（TYPE_APPLICATION_OVERLAY + NOT_FOCUSABLE + 透明 PixelFormat），
     * 只把尺寸换成 MATCH_PARENT，并靠 fitInsetsTypes=0 + LAYOUT_NO_LIMITS
     * 把窗口铺到状态栏、导航栏下面。
     */
    fun prepareFullScreen() {
        if (fullView != null) return
        val view = inflater.inflate(R.layout.window_fullscreen, null)

        fullParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = TAG_FULL
            layoutInDisplayCutoutMode = cutoutMode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 关键：不吃 insets，窗口才会真正铺满到系统栏下面
                fitInsetsTypes = 0
            }
        }

        // 全屏弹窗自己的 close：同样关掉所有弹窗
        view.findViewById<MaterialButton>(R.id.btn_close_fullscreen)
            .setOnClickListener { removeAll() }

        fullView = view
    }

    fun showFullScreen() {
        prepareFullScreen()
        val view = fullView ?: return
        val params = fullParams ?: return
        if (!view.isAttachedToWindow) {
            runCatching { wm.addView(view, params) }
                .onFailure { toast("添加全屏窗口失败：${it.message}") }
        }
        // 全屏窗口铺满屏幕，会盖住小弹窗的点击区域；
        // 把已经在屏幕上的小弹窗重新 add 一次，抬到全屏窗口上面，
        // 这样它的 close 按钮仍然点得到，也就能关掉这个全屏弹窗。
        raiseSmallAbove()
    }

    fun removeFullScreen() {
        val view = fullView ?: return
        if (!view.isAttachedToWindow) return
        runCatching { wm.removeViewImmediate(view) }
    }

    /** 关掉当前所有弹窗：小弹窗和全屏弹窗都算。任意 close 按钮都走这里。 */
    fun removeAll() {
        removeFullScreen()
        removeSmall()
    }

    // ------------------------------------------------------------------ 工具

    private fun raiseSmallAbove() {
        val view = smallView ?: return
        val params = smallParams ?: return
        if (!view.isAttachedToWindow) return
        runCatching {
            wm.removeViewImmediate(view)
            wm.addView(view, params)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG_SMALL = "DragPopup"
        const val TAG_FULL = "FullScreenPopup"
    }
}
