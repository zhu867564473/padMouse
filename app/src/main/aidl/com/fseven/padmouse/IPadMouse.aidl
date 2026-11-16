// PadMouse UserService 对外接口：运行在 Shizuku shell 进程内，负责实际输入注入。
package com.fseven.padmouse;

import com.fseven.padmouse.IGamepadListener;

interface IPadMouse {
    void tap(float x, float y);
    void down(float x, float y);
    void move(float x, float y);
    void up(float x, float y);
    void scroll(float x, float y, float h, float v);
    void sendKey(int keyCode);
    String shellExec(String cmd);
    String selfTest();

    // 手柄捕获：读 /dev/input，不抢前台焦点。
    void startGamepadCapture(IGamepadListener listener);
    void stopGamepadCapture();
    String describeGamepads();
}
