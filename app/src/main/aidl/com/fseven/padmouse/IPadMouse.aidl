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
    void startGamepadCapture(IGamepadListener listener);
    void stopGamepadCapture();
    String describeGamepads();
}
