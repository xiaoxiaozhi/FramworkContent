package com.example.framworkcontent

import android.content.Intent
import android.app.PictureInPictureParams
import android.app.WallpaperManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton

/**
 * 演示两种 WindowManager 弹窗：
 *
 * A. 可拖动小弹窗：WRAP_CONTENT，按住「拖动手柄」跟着手指走；点「移除弹窗」关闭。
 * B. 全屏透明弹窗：MATCH_PARENT + 透明背景，覆盖到状态栏与导航栏下方；点「关闭全屏弹窗」关闭。
 *
 * Activity 本身也是 edge-to-edge 全屏：布局延伸到状态栏和导航栏下方
 * （WindowCompat.setDecorFitsSystemWindows(false) + 透明系统栏 + 深色系统栏图标），
 * 按钮列用 insets 做底部避让，保证不会被导航栏挡住。
 *
 * 两个弹窗都用 TYPE_APPLICATION_OVERLAY 挂在应用级 WindowManager 上，
 * 由 [DemoApp] 里的 [PopupHost] 持有（不是 Activity 字段，也不是静态字段），所以：
 *   - Activity finish 之后弹窗仍然留在屏幕上；
 *   - 任意一个 close 按钮都能关掉当前所有弹窗（包括另一个弹窗）；
 *   - 重新进入 Activity 不会出现两份同类型弹窗。
 *
 * C. 透明 + 换壁纸：点「透明+换壁纸」后，先把 Activity 的背景立刻清成透明
 * （根布局背景清空 + window 背景清空），再 addFlags(FLAG_SHOW_WALLPAPER)
 * 让 WMS 把系统壁纸窗口挂到本 Activity 下面，于是壁纸直接可见；
 * 随后每 [WALLPAPER_INTERVAL_MS] 在两张壁纸之间切换一次
 * （WallpaperManager.setBitmap，需要 SET_WALLPAPER 权限）。
 *
 * D. 画中画（PiP）：点「进入画中画」调 enterPictureInPictureMode()，
 * 整屏 Activity 被收进一个 16:9 的悬浮小窗。前置条件是 manifest 里
 * supportsPictureInPicture="true"，且 configChanges 自己声明（否则缩窗时会重建）。
 */
class WindowDemoActivity : AppCompatActivity() {

    private val popups: PopupHost
        get() = (application as DemoApp).popups

    /** 两张演示壁纸，按顺序循环 */
    private val wallpaperCycle = intArrayOf(R.drawable.wallpaper_a, R.drawable.wallpaper_b)
    private var wallpaperIndex = 0
    private var wallpaperCycling = false
    private val wallpaperHandler = Handler(Looper.getMainLooper())
    private val wallpaperTask = object : Runnable {
        override fun run() {
            applyNextWallpaper()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏（edge-to-edge）：内容延伸到状态栏和导航栏下方
        setupEdgeToEdge()

        setContentView(R.layout.activity_window_demo)

        // setContentView 之后才能取到 R.id.btn_bar
        applyInsetsToButtonBar()

        // 步骤 1：把两种弹窗的 View 和 LayoutParams 都准备好（只准备，不 addView）
        popups.prepareSmall()
        popups.prepareFullScreen()

        findViewById<MaterialButton>(R.id.btn_show_popup).setOnClickListener {
            if (!ensureOverlayPermission()) return@setOnClickListener
            popups.showSmall()
            finish()
        }

        findViewById<MaterialButton>(R.id.btn_show_fullscreen).setOnClickListener {
            if (!ensureOverlayPermission()) return@setOnClickListener
            popups.showFullScreen()
            finish()
        }

        findViewById<MaterialButton>(R.id.btn_transparent_wallpaper).setOnClickListener {
//            makeWindowTransparent()
            startWallpaperCycle()
        }

        findViewById<MaterialButton>(R.id.btn_pip).setOnClickListener {
            enterPip()
        }
    }

    /**
     * 进入画中画。
     *
     * PiP 的本质不是"新开一个窗口"，而是让 WMS 把这同一个 Activity 的 task
     * 收进一个 PinnedStack / PiP 容器里显示，Activity 实例不变，只收到一次
     * onPictureInPictureModeChanged 和一次 configuration change。
     *
     * 三个必要前提：
     *  1. manifest 的 activity 上 supportsPictureInPicture="true"
     *  2. 设备/系统支持 FEATURE_PICTURE_IN_PICTURE（AOSP 手持设备有）
     *  3. Activity 处于 resumed（在后台或已 finish 时调用会返回 false）
     *
     * setAspectRatio 决定 PiP 窗口比例；不给的话系统用当前窗口比例。
     * 返回 false 通常意味着当前状态不允许（比如权限/多窗口限制），不抛异常。
     */
    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, R.string.pip_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        if (!enterPictureInPictureMode(params)) {
            Toast.makeText(this, R.string.pip_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * PiP 模式切换回调（API 26+）。进 PiP 时窗口只有几百 px，
     * 这里把按钮列收起来、换上一行占位文字，顺便给根布局铺个实底，
     * 否则透明主题 + 壁纸在 PiP 容器里看起来是一片黑，分不清是小窗还是没渲染。
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        findViewById<View>(R.id.btn_bar).visibility =
            if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_pip_hint).visibility =
            if (isInPictureInPictureMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.root).setBackgroundResource(
            if (isInPictureInPictureMode) {
                R.color.demo_backdrop
            } else {
                android.R.color.transparent
            }
        )
    }

    /**
     * 让 Activity 立刻变透明，露出下面的壁纸。
     *
     * 三步缺一不可：
     *  1. 根布局背景清空 —— 下一帧就不再画不透明底色（“立马”变透明的就是这个）；
     *  2. window 背景清空 —— 主题里已是 transparent，这里再兜底一次，
     *     避免有的主题给 windowBackground 塞了 colorSurface；
     *  3. addFlags(FLAG_SHOW_WALLPAPER) —— WMS 的 WallpaperController 只会把
     *     壁纸窗口挂给“声明了要壁纸”的窗口；不声明的话壁纸根本不会被合成进来。
     *     配合主题的 windowIsTranslucent（surface 带 alpha），才能真正透出来。
     */
    private fun makeWindowTransparent() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    }

    /** 开始循环换壁纸；重复点按钮不会叠加多份循环。 */
    private fun startWallpaperCycle() {
        wallpaperHandler.post(wallpaperTask)
    }

    private fun stopWallpaperCycle() {
        wallpaperCycling = false
        wallpaperHandler.removeCallbacks(wallpaperTask)
    }

    /**
     * 解码下一张壁纸并设为系统壁纸。
     * setBitmap 是 binder + 落盘的重活，放到子线程；结果回主线程提示。
     */
    private fun applyNextWallpaper() {
        val resId = wallpaperCycle[wallpaperIndex % wallpaperCycle.size]
        wallpaperIndex++
        val appContext = applicationContext
        Thread {
            val bmp = BitmapFactory.decodeResource(resources, resId)
            var ok = false
            if (bmp != null) {
                try {
                    // 返回新壁纸的整数 ID，0 表示失败
                    ok = WallpaperManager.getInstance(appContext)
                        .setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM) != 0
                } catch (e: Exception) {
                    Log.w(TAG, "setBitmap failed: res=$resId", e)
                } finally {
                    bmp.recycle()
                }
            }
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        // 退到后台就别再换壁纸了
        stopWallpaperCycle()
    }

    override fun onDestroy() {
        super.onDestroy()
        wallpaperHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 让 Activity 内容画到状态栏、导航栏下面（immersive 的 edge-to-edge 形态）。
     * 如果还想把系统栏彻底隐藏，再加：
     *   WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
     *   systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关掉系统给透明系统栏加的半透明遮罩
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // 系统栏变透明后图标颜色要跟着背景走。
        // 这个 Demo 的根布局始终是深色底（@color/demo_backdrop），
        // 点“透明+换壁纸”后露出的两张壁纸也都是中间调，
        // 所以统一用浅色（白）图标；按夜间模式判断会得到“深底 + 深图标”，看不见。
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /** 按钮列避让导航栏，避免被系统栏压住。必须在 setContentView 之后调用。 */
    private fun applyInsetsToButtonBar() {
        val btnBar = findViewById<View>(R.id.btn_bar)
        ViewCompat.setOnApplyWindowInsetsListener(btnBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
            insets
        }
    }

    /** 没有“显示在其他应用上层”授权时跳系统设置页，返回 false 表示先别继续。 */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        Toast.makeText(this, "请先允许“显示在其他应用上层”", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
        )
        return false
    }

    private companion object {
        const val TAG = "WindowDemo"

        /**
         * 换壁纸的间隔。
         * 每次 setBitmap 都会让 WallpaperManagerService 重新 bind ImageWallpaper，
         * 壁纸 surface 重建期间会有一小段黑屏（模拟器软渲染下更明显），
         * 3s 留够它画完，A / B 也能看清。
         */
        const val WALLPAPER_INTERVAL_MS = 3_000L
    }
}
