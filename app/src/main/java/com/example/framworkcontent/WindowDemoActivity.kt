package com.example.framworkcontent

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * 演示：先准备好弹窗，再根据用户操作把它挂到 WindowManager 上，
 * 并且让弹窗在 Activity 结束之后继续显示。
 *
 * 流程：
 * 1. onCreate 里 initPopup()：inflate 弹窗布局、配置 WindowManager.LayoutParams，但**不 addView**；
 * 2. Activity 上只有一个按钮：点击 -> showPopup() 添加弹窗 -> finish() 结束 Activity；
 * 3. 弹窗里的按钮：点击 -> removePopup() 移除弹窗，并 finish() 兜底。
 *
 * 为什么必须用 TYPE_APPLICATION_OVERLAY：
 * - TYPE_APPLICATION 的窗口挂在 Activity 的 app token 上，Activity finish() 时 WMS 会把该 token
 *   下的所有窗口一起移除（实测：日志出现 "Input channel object 'DragPopup' was disposed"，
 *   弹窗随之消失），所以它无法“显示弹窗 -> finish -> 弹窗还在”。
 * - TYPE_APPLICATION_OVERLAY 是应用级系统窗口，只依赖进程与 SYSTEM_ALERT_WINDOW 授权，
 *   与 Activity 生命周期无关，Activity 销毁后仍然存在。
 */
class WindowDemoActivity : AppCompatActivity() {

    // 应用级 WindowManager：不绑定任何 Window，overlay 窗口由 WMS 用 Session 建 token
    private val wm: WindowManager by lazy { getSystemService(WindowManager::class.java) }

    private lateinit var popupView: View
    private lateinit var popupParams: WindowManager.LayoutParams
    private var popupShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_window_demo)

        // 步骤 1：初始化弹窗与参数（只准备，不显示）
        initPopup()

        // 步骤 2：唯一按钮——显示弹窗，然后结束 Activity
        findViewById<MaterialButton>(R.id.btn_show_popup).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                // 没有“显示在其他应用上层”授权：跳系统设置页，不 finish，回来还能再点
                Toast.makeText(this, "请先允许“显示在其他应用上层”", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            showPopup()
            finish()
        }
    }

    /**
     * 步骤 1：把弹窗 View 和 LayoutParams 都准备好，等按钮点击时再 addView。
     */
    private fun initPopup() {
        popupView = layoutInflater.inflate(R.layout.window_transparent, null)
        popupView.findViewById<TextView>(R.id.tv_window_hint).text =
            getString(R.string.popup_hint, 1)

        popupParams = WindowManager.LayoutParams(
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
            title = "DragPopup"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        val dragHandle = popupView.findViewById<View>(R.id.drag_handle)

        // 拖动：只挂在拖动手柄上，避免跟“移除弹窗”按钮抢点击事件
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = popupParams.x
                        startY = popupParams.y
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        popupParams.x = startX + (event.rawX - downRawX).toInt()
                        popupParams.y = startY + (event.rawY - downRawY).toInt()
                        if (popupShown) {
                            wm.updateViewLayout(popupView, popupParams)
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })

        // 步骤 3：弹窗自带的按钮——移除弹窗
        popupView.findViewById<MaterialButton>(R.id.btn_remove_popup).setOnClickListener {
            removePopup()
            // Activity 此刻多半已经 finish，这里只是兜底：确保它不会再回来
            if (!isFinishing) finish()
        }
    }

    /** 步骤 2：把准备好的弹窗挂到 WindowManager 上。 */
    private fun showPopup() {
        if (popupShown) return
        try {
            wm.addView(popupView, popupParams)
            popupShown = true
        } catch (e: RuntimeException) {
            Toast.makeText(this, "添加窗口失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 步骤 3：移除弹窗。 */
    private fun removePopup() {
        if (!popupShown) return
        try {
            wm.removeViewImmediate(popupView)
        } catch (_: RuntimeException) {
            // 窗口可能已被系统移除，忽略即可
        }
        popupShown = false
    }

    override fun onDestroy() {
        // 注意：这里不能无条件移除！overlay 就是要在 Activity 结束后继续显示，
        // 所以只在“用户明确移除”时清理（removePopup 由弹窗按钮触发）。
        super.onDestroy()
    }
}
