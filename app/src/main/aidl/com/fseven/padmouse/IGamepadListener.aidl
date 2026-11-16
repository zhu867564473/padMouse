// 手柄事件回调（后续卡片使用，当前仅定义接口）。
package com.fseven.padmouse;

oneway interface IGamepadListener {
    // 原始事件：type 为 EV_KEY(1)/EV_ABS(3)，EV_SYN 不上报。
    void onEvent(int type, int code, int value);

    // 摇杆/轴归一化值：按 getevent -pl 的 min/max 线性映射到 -1.0..1.0。
    void onAxis(int code, float normalized);

    // 设备摘要：识别到的设备名称、能力，以及热插拔变化。
    void onDeviceInfo(String summary);
}
