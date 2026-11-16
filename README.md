# 手柄鼠标（PadMouse）

把手柄当作鼠标用：左摇杆移动光标，A 键点击，B/X 右键中键，还能把 Back/Home/音量/亮度/截图等系统动作绑到手柄按键上。

- 通过 **Shizuku** 获得注入权限，反射调用 `InputManager.injectInputEvent`，不使用无障碍服务；
- 光标是自绘的悬浮窗箭头，静止后自动变淡，不挡触摸；
- 不需要 root（用无线调试启动 Shizuku 即可）；
- **没有 INTERNET 权限**，不联网、不上传任何数据。

## 功能

| 模块 | 说明 |
| --- | --- |
| 光标 | 大小 20–48dp、颜色（白/黑/黄）、静止 1–10 秒后变淡 |
| 摇杆移动 | 左摇杆控制光标，带死区与加速曲线，速度/死区/指数可调 |
| 点击 | A 点击、长按进入拖拽，B 右键，X 中键 |
| 滚动 | 右摇杆上下滚动；LB/RB 单键滚轮上/下 |
| 扳机 | LT 按住左键、RT 按住右键 |
| 系统动作 | Back / Home / Recents / 音量± / 亮度± / 截图，可逐个改绑 |
| 设置持久化 | 外观、手感、按键绑定全部保存在本地，重启 App 后保留 |
| 启动引导 | 缺 Shizuku 授权或悬浮窗权限时，顶部引导条一键跳转 |
| 通知 | 前台服务常驻通知「手柄鼠标运行中 · 摇杆控制光标」，带停止按钮 |
| 断连恢复 | Shizuku 服务被杀时自动重连并提示；悬浮窗权限被撤销时自动停止 |

## 安装步骤

1. **安装 Shizuku**：从 [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) 或 [Shizuku 官网](https://shizuku.rikka.app/) 安装。
2. **启动 Shizuku 服务**：在 Shizuku 里进入「通过无线调试启动」，按提示在开发者选项中开启「无线调试」，配对并启动。Android 11+ 无需 root；已 root 的设备可直接以 root 启动。
3. **授权本应用**：安装并打开手柄鼠标，顶部引导条或「环境状态」里点「申请授权」，在 Shizuku 弹窗中允许。
4. **授予悬浮窗权限**：点引导条 / 「授予悬浮窗权限」，在系统设置里允许「显示在其他应用上层」。
5. **连接手柄**：蓝牙或 USB 连接 Xbox 布局手柄（X-input），在「手柄」卡片里确认已识别到设备名。
6. **启动**：点「启动手柄鼠标」，光标出现后即可用摇杆操作。通知栏可随时停止。

> Android 13+ 首次启动会请求通知权限；拒绝也能用，但通知栏的「停止」入口不会显示。

## 默认按键表

Xbox 布局（Linux input-event-codes）：

| 手柄输入 | 动作 |
| --- | --- |
| 左摇杆 | 移动光标 |
| 右摇杆上下 | 滚轮 |
| A | 点击（长按 350ms 进入拖拽） |
| B | 右键 |
| X | 中键 |
| LT / RT | 按住左键 / 按住右键 |
| LB / RB（TL/TR） | 滚轮上 / 滚轮下 |
| Start | Home |
| Select / View | Back |
| Xbox 键 | 最近任务 |
| 左摇杆按下 | 音量减 |
| 右摇杆按下 | 音量加 |
| 十字键上 / 下 | 亮度加 / 亮度减 |
| LB + RB 同时按 | 截图（保存到 `/sdcard/DCIM/Screenshots`） |

系统动作可在「设置 → 按键绑定」里逐个改绑，截图还支持组合键。单独按 LB/RB 仍是滚轮，组合判定窗口 90ms。

## 设置说明

- **光标速度**：左摇杆推满时每秒移动的像素数（200–2000）。
- **摇杆死区**：低于该幅度不移动（0.02–0.40），越大越防漂移/误触。
- **加速指数**：`速度 = 基础速度 × 归一幅值^指数`（1.0–3.0），越大越“轻推精细、重推加速”。
- 所有设置改动即时生效，并写入本地 SharedPreferences；重启 App 后保留。

## 常见问题

**手柄没反应 / 认不到？**
- 确认手柄处于 **X-input 模式**（部分手柄默认是 DirectInput/Switch 模式，需要按组合键切换，例如 Xbox 手柄长按 Xbox 键 + 配对键）。
- 先用 USB 或蓝牙在系统设置里确认手柄已连接，再打开 App 的「手柄」卡片刷新。
- 部分手柄把十字键上报为按键（`BTN_DPAD_*`）而不是数字轴（`ABS_HAT0X/Y`），按键绑定里两种都提供。

**Shizuku 被系统杀掉了？**
- 国产 ROM 请在系统设置里给 Shizuku 开「自启动」「后台运行」并关闭电池优化。
- Shizuku 服务重启后，本应用会自动尝试重绑（最多 5 次，1s 起退避）；仍失败时回到 App 点「刷新状态」或重新授权。
- 如果 Shizuku 显示「服务未运行」，重新进入 Shizuku 用无线调试启动服务。

**授权了还是提示 UserService 未连接？**
- 先看「环境状态」里 Shizuku 是否为「已授权」；UserService 绑定失败时点「刷新状态」或重新申请授权。
- 某些系统会限制后台启动前台服务，确保应用在前台时再点启动。

**光标出现但点击没反应？**
- 检查 Shizuku 是否被系统回收，或「实时状态」里映射是否运行。
- 「注入诊断」里的「测试点击屏幕中心」可用于确认注入链路。

**悬浮窗权限被撤销？**
- 服务会在 2 秒内检测到并自动停止，同时提示；重新授权后再启动即可。

## 构建

要求：JDK 17、Android SDK（compileSdk 36）。

```bash
./gradlew assembleDebug --console=plain
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | 显示光标悬浮窗 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 光标常驻前台服务 |
| `POST_NOTIFICATIONS` | 显示前台服务通知（含停止按钮） |
| `VIBRATE` | 预留触感反馈 |
| `moe.shizuku.manager.permission.API` | 通过 Shizuku 绑定 UserService 注入输入事件 |

应用**不申请 INTERNET 权限**。
