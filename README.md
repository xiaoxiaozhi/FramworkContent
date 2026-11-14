# FramworkContent

Android 窗口（Window / WindowManager）实验工程。用最小可运行的 Demo 验证几个容易搞混的机制，每个功能都能在模拟器/真机上直接点出来看现象。

包名：`com.example.framworkcontent`，入口 Activity：`WindowDemoActivity`。

## 功能

| 功能 | 现象 | 关键实现 |
|---|---|---|
| Edge-to-edge 布局 | 内容延伸到状态栏、导航栏下面，含刘海（`layoutInDisplayCutoutMode=SHORT_EDGES`） | `WindowCompat.setDecorFitsSystemWindows(window, false)` + 透明系统栏 + `applyInsetsToButtonBar()`（必须放在 `setContentView` 之后） |
| 可拖动小窗 | 用 `TYPE_APPLICATION_OVERLAY` 起一个可拖动、可点击的悬浮窗；Activity `finish()` 后窗口依然存在 | `PopupHost` + `DragPopup`，`PixelFormat.TRANSPARENT` + `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` |
| 全屏弹窗 | `MATCH_PARENT` 铺满整屏（含系统栏区域）的 overlay 窗口 | `FullScreenPopup`，`ty=2038`、`fitInsetsTypes=0`；显示后会 `removeViewImmediate` + `addView` 把小窗重新抬到上层，否则小窗的关闭按钮点不到 |
| 透明 + 换壁纸 | 点按钮后 Activity 背景立即透明，露出系统壁纸，并每 3 秒在两张壁纸间轮换 | 主题 `windowIsTranslucent=true` + `window.addFlags(FLAG_SHOW_WALLPAPER)` + `WallpaperManager.setBitmap()`（需 `SET_WALLPAPER` 权限） |
| 画中画（PiP） | 点「进入画中画」，整屏 Activity 收进 16:9 悬浮小窗，按钮列自动隐藏 | `enterPictureInPictureMode(PictureInPictureParams)` + manifest `supportsPictureInPicture="true"` + `onPictureInPictureModeChanged()` |
| 窗口不可获焦 | 点一下后 WMS 不再把 input focus 交给该窗口（`mCurrentFocus=null`），按键/输入法事件不派发；触摸仍能命中，再点一下恢复焦点 | `window.addFlags(FLAG_NOT_FOCUSABLE)` / `clearFlags()`，按钮文案同步切换 |

## 实验

[`LAB.md`](LAB.md) 是一份**故障注入清单**：6 个可以从本工程直接复现的问题（焦点消失、触摸被吞、壁纸不出现、PiP 静默失败、无焦点 ANR、旋转重建），每个都给了「改什么 / 判据命令 / 根因锚点」。没有真实 bug 时，用它制造 bug。

## 几个要点

**`TYPE_APPLICATION` 与 `TYPE_APPLICATION_OVERLAY` 的区别**

- `TYPE_APPLICATION`（2）：必须挂在 Activity 的 window token 上，Activity 一 `finish()` 再加窗口就是 `BadTokenException`。
- `TYPE_APPLICATION_OVERLAY`（2038）：需要 `SYSTEM_ALERT_WINDOW` 权限，不依赖 Activity token，Activity 结束后窗口仍然存在。
- 层级：普通 `BASE_APPLICATION` 的 `mBaseLayer=21000`，`APPLICATION_OVERLAY` 是 `121000`。

**全屏 overlay 会吞掉触摸**

`dumpsys input` 里 `Windows:` 段是 InputDispatcher 实际做 hit-test 的快照（`0:` 最上层），第一个 `touchableRegion` 命中的窗口独占事件。全屏 overlay 的 `touchableRegion` 覆盖整屏，因此它下面的窗口收不到点击。原生机制不支持"既响应事件又穿透给背后"，只能二选一：`FLAG_NOT_TOUCHABLE`（纯视觉）或缩小 `touchableRegion`。

**壁纸透出的两个必要条件**

1. Activity window 的 surface 必须带 alpha —— `windowIsTranslucent=true` + 透明 `windowBackground`，否则壁纸被完全不透明的 surface 盖掉。
2. 必须 `addFlags(FLAG_SHOW_WALLPAPER)`，`WallpaperController` 只会把壁纸窗口挂给声明了该 flag 的窗口。

每次 `setBitmap()` 都会让 `WallpaperManagerService` 重新 `bindWallpaperComponentLocked` 重建 ImageWallpaper，新 surface 出首帧前有短暂黑帧，所以轮换间隔取 3 秒而不是更短。

**`FLAG_NOT_FOCUSABLE` 不等于不可触摸**

`addFlags(FLAG_NOT_FOCUSABLE)` 后 WMS 不再把这个 window 当作焦点候选，`dumpsys window` 里 `mCurrentFocus=null`、该窗口 `mAttrs` 的 flags 多出 `0x00000008`，按键/遥控器/输入法都不会派发过来。但它只是隐含 `FLAG_NOT_TOUCH_MODAL`，不是 `FLAG_NOT_TOUCHABLE`，触摸命中区域不变 —— 所以同一个按钮还能再点一次把 flag 清掉，焦点随即回到 Activity。

**画中画（PiP）**

PiP 不是新开窗口，而是把同一个 Activity 的 task 收进 `mode=pinned` 的容器里显示，Activity 实例不变。三个前提：

1. manifest 的 activity 上 `android:supportsPictureInPicture="true"`；
2. 设备支持 `FEATURE_PICTURE_IN_PICTURE`；
3. Activity 处于 resumed 状态（后台/finish 后调用只会返回 `false`，不抛异常）。

另外必须自己声明 `android:configChanges="keyboard|keyboardHidden|orientation|screenSize|smallestScreenSize|screenLayout|uiMode"`，否则缩窗触发的配置变更会让 Activity 重建。`setAspectRatio(Rational(16,9))` 决定小窗比例；实测 AOSP 12 上窗口 bounds 为 `Rect(533,1206-1038,1490)` = 505×284，正好 16:9。

## 验证截图

点击"透明+换壁纸"前后的实测（`adb shell screencap`，判色确认）：

`1-before-opaque.png` → `2-after-transparent-A.png` → `3-wallpaper-B.png` → `4-rebind-black-frame.png`

| 时刻 | 取色 (60,760) | 判定 |
|---|---|---|
| 点击前 | 14,18,22 | 不透明深色底 |
| 点击后 | 67,143,161 | 透明，壁纸 A 透出 |
| +2s | 214,120,88 | 壁纸 B |
| +4s | 0,0,0 | 重建瞬时黑帧 |

`dumpsys window` 中该窗口：

```
ty=BASE_APPLICATION fmt=TRANSPARENT
fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SHOW_WALLPAPER SPLIT_TOUCH HARDWARE_ACCELERATED
```

## 构建

```bash
# 需要 JDK 17+（本项目用 Android Studio 自带 JBR）
./gradlew assembleDebug
```

- AGP `9.5.0`（内置 Kotlin 支持，无需单独 Kotlin 插件）
- Gradle `9.6.0`
- `compileSdk` / `targetSdk` 37，`minSdk` 28

## 目录

```
app/src/main/java/com/example/framworkcontent/
├── WindowDemoActivity.kt   # 入口：edge-to-edge、insets、透明 + 壁纸轮换
├── PopupHost.kt            # 窗口管理（DragPopup / FullScreenPopup），由 Application 持有
└── DemoApp.kt              # Application，进程级窗口宿主
app/src/main/res/layout/
├── activity_window_demo.xml
├── window_transparent.xml  # 小窗布局
└── window_fullscreen.xml   # 全屏窗布局
```

小窗/全屏窗的 `WindowManager.LayoutParams` 组装和 `View` 管理都集中在 `PopupHost.kt`。
