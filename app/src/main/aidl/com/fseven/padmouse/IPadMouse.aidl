// PadMouse UserService 对外接口：运行在 Shizuku shell 进程内，负责实际输入注入。
package com.fseven.padmouse;

import com.fseven.padmouse.IGamepadListener;

interface IPadMouse {
    void tap(float x, float y);
    void down(float x, float y);
    void move(float x, float y);
    void up(float x, float y);
    void scroll(float x, float y, float h, float v);
    // 鼠标键注入（M5）：右键/中键点击与按住/松开/拖拽移动。
    void rightClick(float x, float y);
    void middleClick(float x, float y);
    void mouseDown(float x, float y, int button);
    void mouseUp(float x, float y, int button);
    void mouseMove(float x, float y, int buttonState);
    void sendKey(int keyCode);
    String shellExec(String cmd);
    String selfTest();

    // 手柄捕获：读 /dev/input，不抢前台焦点。
    // App 的 nativeLibraryDir / codeCacheDir / APK 路径：UserService 进程用它显式 System.load 原生 ioctl 库
    // （APK 为 extractNativeLibs=false 时需从 APK 解压到 codeCacheDir）。
    void setNativeLibPaths(String nativeLibraryDir, String codeCacheDir, String apkPath);
    void startGamepadCapture(IGamepadListener listener);
    void stopGamepadCapture();
    String describeGamepads();

    // M10 手柄独占：EVIOCGRAB 抢占设备，系统输入层不再派发该手柄事件。
    // 开关变化会重建读取器（先 ungrab 关闭旧 fd，再按新配置重开）。
    void setExclusiveMode(boolean enabled);
    boolean isExclusiveActive();
    // 独占失败原因；成功/未启用时返回空串。
    String getExclusiveError();

    // 鼠标模式(true) / 游戏模式(false)：游戏模式只 EVIOCGRAB 0 释放设备，
    // fd 保持打开，长按热键（BTN_THUMBL）仍可读到来切回。
    void setMouseMode(boolean mouseMode);
    boolean isGameMode();

    // M12 UHID 虚拟鼠标：在 UserService（shell 进程）内打开 /dev/uhid，
    // 创建系统级 HID 相对鼠标（3 键 + 滚轮，4 字节 report）。
    // createUhidMouse 返回状态串；成功以 "active=true" 开头，失败以 "ERROR:" 开头。
    // uniq 为副屏 uniqueId（可为空 = 无副屏）。
    // 实现侧会同时创建两个设备：主屏设备（uniq 空、BUS_VIRTUAL）与副屏设备
    // （uniq=该值、BUS_USB）。两个设备的 DisplayId 在创建时固定，之后不再重建，
    // 切换光标本屏/副屏只是改「哪个设备在发事件」，避免 descriptor 缓存导致切不回来。
    String createUhidMouse(String uniq);
    /** 切换活跃设备：0 = 主屏设备，1 = 副屏设备（未创建则忽略）。 */
    void setActiveUhid(int which);
    /** 副屏设备是否已就绪。 */
    boolean secondaryUhidReady();
    /**
     * 查出外接屏（副屏）的 uniqueId，用于把光标切到副屏。
     * 返回空串表示没有外接屏。
     * 放在 shell 进程里做：Display.getUniqueId() 是 hidden API，普通 App 反射会被拦。
     */
    String externalDisplayUniqueId();
    void destroyUhidMouse();
    // 相对位移（像素）；实现侧按 HID int8 上限 ±127 拆分多次上报。
    void uhidMouseMove(int dx, int dy);
    // 按键位掩码：bit0 左键 / bit1 右键 / bit2 中键。
    void uhidMouseButton(int buttonMask);
    // 滚轮格数：正数向上、负数向下。
    void uhidMouseWheel(int delta);
    // 当前设备状态（active=true ... / active=false / ERROR: ...）。
    String uhidStatus();

    // M13 手柄震动：FF_RUMBLE 直接写 /dev/input/eventX（手柄未注册为系统 vibrator）。
    // strength 为 0..65535；成功返回 effect id（>= 0），失败返回负值（-errno / 节流 / 不可用）。
    // 到时由 UserService 自动 stop + destroy，调用方无需再回收。
    int rumble(int durationMs, int strength);
    void rumbleStop(int effectId);
    // 空串 = 手柄震动可用；否则为「手柄震动不可用：<原因>」。
    String hapticsStatus();
}
