// 手柄事件回调（后续卡片使用，当前仅定义接口）。
package com.fseven.padmouse;

oneway interface IGamepadListener {
    void onEvent(int type, int code, int value);
}
