package com.example.framworkcontent

import android.app.Application

/**
 * 只为了给弹窗宿主一个非静态的归宿。
 *
 * 弹窗需要跨 Activity finish 存活，所以不能放在 Activity 字段里；
 * 但如果放 `object`（静态区），lint 的 StaticFieldLeak 会判定
 * “静态字段间接持有 View → 持有 Context → 泄漏”。
 *
 * 放在 Application 实例上，生命周期等于进程，且不是静态字段：
 * 既满足“跨 finish 存活”，也不会触发该检查。
 */
class DemoApp : Application() {

    val popups: PopupHost by lazy { PopupHost(this) }
}
