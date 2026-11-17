<div align="center">

# 🎮 手柄鼠标 · PadMouse

**把手柄变成手机上的鼠标**

左摇杆移动光标 · A 键点击 · 右摇杆滚轮 · 全离线 · 不要无障碍

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2011%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.x-purple.svg)](https://kotlinlang.org)
[![No Internet](https://img.shields.io/badge/internet-not%20required-lightgrey.svg)](#-安全说明)

</div>

---

## ✨ 为什么做这个

市面上「手柄当鼠标」的 App 大多依赖**无障碍服务**，而 Android 会在各种场景下把无障碍权限悄悄回收，表现为「用着用着就失灵了，还得去设置里重新打开」。有些还强制联网、带广告。

PadMouse 换了一条路：

- **Shizuku 授权，不要无障碍** —— 授权一次长期有效，不会莫名其妙被回收
- **UHID 虚拟鼠标** —— 在内核层注册一个真实的鼠标设备，系统自己画光标、自己处理滚轮，阅读类 App 也能正常翻页
- **零网络权限** —— APK 里没有 `android.permission.INTERNET`，物理上无法联网
- **开源** —— 全部代码在这里，可自行编译、审计

## 🕹 功能

| 模块 | 能力 |
| --- | --- |
| **光标** | 系统原生光标（UHID 模式），或自绘悬浮箭头（兼容模式） |
| **移动** | 左摇杆控制，死区 / 速度 / 加速曲线可调 |
| **滚轮** | 右摇杆上下滚动，阅读器翻页可用 |
| **鼠标按键** | A/B/X/LB/RB 可自由绑定：左键 / 右键 / 中键 / 滚轮上 / 滚轮下 / 按住左键 / 按住右键 |
| **系统动作** | Back · Home · 最近任务 · 音量± · 亮度± · 截图，每个都可绑任意手柄键 |
| **独占模式** | 内核级 `EVIOCGRAB`，手柄事件只给本 App，不与游戏抢输入 |
| **模式切换** | 长按 L3（可改键）在「鼠标模式 / 游戏模式」间切换，游戏模式完全交还手柄 |
| **断连恢复** | Shizuku 被杀自动重连；权限被撤销自动停止 |

## 🏗 架构

```
┌──────────────────────────────────────────────┐
│  MainActivity                                │
│  授权引导 · 按键绑定 · 手感调参 · 状态显示      │
└───────────────┬──────────────────────────────┘
                │ 启动 / 停止
┌───────────────▼──────────────────────────────┐
│  MouseService（前台服务，常驻）                 │
│                                              │
│   ┌─────────────┐     ┌──────────────────┐   │
│   │ GamepadReader│────▶│  Mapper 映射引擎  │   │
│   │ 读 /dev/input │     │ 摇杆 → 光标/滚轮  │   │
│   └─────────────┘     └────────┬─────────┘   │
│                                │             │
└────────────────────────────────┼─────────────┘
                                 │ AIDL（shell uid）
                    ┌────────────▼────────────┐
                    │ PadMouseUserService      │
                    │ Shizuku UserService      │
                    │                          │
                    │  UhidMouse  ── /dev/uhid │
                    │  injectInputEvent        │
                    │  EVIOCGRAB（JNI ioctl）  │
                    └──────────────────────────┘
```

> **为什么映射引擎在服务里、不放在 Activity**：放 Activity 会随界面生命周期停摆——一退后台光标就不动，而「切到别的 App 用手柄」正是核心用法。

## 🚀 快速开始

### 前置条件

- Android 11+（API 30+），建议 Android 13+
- 已安装 [Shizuku](https://shizuku.rikka.app/) 并启动服务
- 支持的手柄（Xbox / PS / 通用 HID，蓝牙或 USB 均可）

### 使用步骤

1. 安装 APK，打开 App
2. 点 **「一键完成全部授权」** → 在 Shizuku 弹窗点一次「允许」
   - App 会用 Shizuku 自己打开悬浮窗和通知权限，不用进系统设置
3. **（MIUI / HyperOS 用户必做）** 点「打开自启动管理」→ 允许自启动
   - 再到 **设置 → 电池与性能 → 应用配置 → 手柄鼠标 → 无限制**
   - 不做这步，切到其他 App 后服务会被系统冻结，表现为「光标消失、按键无反应」
4. 连接手柄，点 **「启动手柄鼠标」**
5. 推左摇杆，光标出现

### 默认按键

| 手柄 | 动作 |
| --- | --- |
| 左摇杆 | 移动光标 |
| 右摇杆 | 滚轮上下 |
| **A** | 左键点击（长按 350ms 进入拖拽） |
| **B** | 右键点击 |
| **X** | 中键点击 |
| **LB / RB** | 滚轮上 / 下 |
| **LT / RT** | 按住左键 / 右键 |
| **Start** | 返回 |
| **Select** | 主页 |
| **Xbox 键** | 最近任务 |
| **十字键 ↑ ↓** | 亮度 ± |
| **十字键 ← →** | 音量 ± |
| **长按 L3** | 切换鼠标 / 游戏模式 |
| **LB + RB** | 截图 |

所有绑定都能在 App 设置区改；截图还支持组合键，单独按 LB/RB 仍是滚轮（组合判定窗口 90ms）。

## 🔐 权限说明

| 权限 | 用途 | 授予方式 |
| --- | --- | --- |
| Shizuku | 注入输入事件、读写 `/dev/input`、创建 UHID 设备 | 用户点一次「允许」，长期有效 |
| 悬浮窗 | 兼容模式下绘制光标 | Shizuku 自动 `appops` 授予 |
| 通知 | 前台服务常驻通知（含停止按钮） | Shizuku 自动 `pm grant` |
| **自启动 / 省电白名单** | **防止后台被冻结** | **只能手动设置** |

> ⚠️ 自启动和省电策略是厂商私有机制，Shizuku 也代劳不了（实测 `pm grant miui.permission.AUTO_START` 抛异常、`appops AUTO_START` 未定义）。这两个开关设一次就不用再动。

## 🛡 安全说明

- **APK 不申请 `android.permission.INTERNET`** —— 没有网络能力，不存在上传数据的可能
- **不使用无障碍服务** —— 没有 `BIND_ACCESSIBILITY_SERVICE` 声明
- **不上传任何数据**，无广告、无账号、无统计
- 所有权限在应用内明示，代码可审计

## ⚠️ 已知限制

### 1. 系统光标在部分 App 里不显示

某些厂商 ROM（实测 HyperOS）的系统组件 `PointerChoreographer` 在切换窗口时拿不到有效的输入设备 ID，会拒绝绘制光标：

```
W PointerChoreographer: Invalid device id -1. Cannot set pointer icon.
```

这是系统侧行为，App 无法绕过。表现：切到微信、微信读书等 App 后光标消失，下拉通知栏后恢复。

**默认已提供两种光标后端**，可在设置里切换：

- **UHID 虚拟鼠标**（推荐）：系统原生光标 + 原生滚轮，绝大多数场景正常
- **触摸注入 + 自绘光标**：兼容性 100%，但滚轮在部分 App 里效果有限

### 2. 手柄震动不可用

`EVIOCSFF` 在 Android 11+ 被 SELinux 拦截（`EPERM`），无 root 无法实现，功能已移除。

### 3. 手柄轴定义因设备而异

同样叫 "Xbox Wireless Controller"，蓝牙款的右摇杆是 `ABS_Z`/`ABS_RZ`、扳机是 `ABS_GAS`/`ABS_BRAKE`，**没有** `ABS_RX`/`ABS_RY`。本 App 按设备实际能力自适应重映射；新增手柄型号若表现异常，先看 `getevent -pl` 的轴定义。

## ❓ 常见问题

**手柄没反应 / 认不到？**
- 确认手柄处于 **X-input 模式**（部分手柄默认 DirectInput/Switch 模式，需按组合键切换）
- 先在系统设置里确认手柄已连接，再打开 App 的「手柄」卡片刷新
- 部分手柄把十字键上报为按键（`BTN_DPAD_*`）而不是数字轴（`ABS_HAT0X/Y`），两种绑定都支持

**切到别的 App 后光标消失、按键也没反应？**
- 九成是**后台被系统冻结**。先做「自启动 + 省电无限制」
- 验证方法：切到目标 App 后，连续两次采样主进程 CPU 时间（`/proc/<pid>/stat` 第 14/15 列），若完全不变即为冻结

**Shizuku 被系统杀掉了？**
- 给 Shizuku 也开「自启动」「后台运行」并关闭电池优化
- 本 App 会自动重绑（最多 5 次，1s 起退避）；仍失败时点「刷新状态」或重新授权

**光标出现但点击没反应？**
- 看「实时状态」里映射是否运行；「注入诊断 → 测试点击屏幕中心」可确认注入链路

## 🔨 从源码构建

```bash
git clone <repo-url>
cd pad-mouse

# 需要 Android SDK（compileSdk 36）与 JDK 17
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew assembleDebug --console=plain
# 产物：app/build/outputs/apk/debug/app-debug.apk

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**技术栈**：Kotlin 2.x · AGP 9.5 · Gradle 9.6 · compileSdk 36 · minSdk 30 · NDK（JNI ioctl）

## 📄 许可证

[Apache License 2.0](LICENSE)

本项目依赖 [Shizuku API](https://github.com/RikkaApps/Shizuku-API)（Apache-2.0）。

---

<div align="center">

**如果这个项目帮到了你，点个 ⭐ 支持一下**

</div>
