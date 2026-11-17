/*
 * PadMouse 原生 ioctl 桥（CMake 构建，输出 libnative_ioctl.so）。
 *
 * Android 16（API 36）移除了 android.system.Os / libcore.io.* 的三参
 * ioctlInt(FileDescriptor, int, int)，只剩两参版本（arg 固定 0），
 * 无法下发 EVIOCGRAB(1)。这里用 JNI 直接调用 libc ioctl。
 *
 * JNI 符号必须与顶层对象 com.fseven.padmouse.NativeIoctl 对应：
 *   Java_com_fseven_padmouse_NativeIoctl_nativeIoctl
 *
 * 返回 0 表示成功；失败返回 errno（>0），由 Kotlin 侧转成可读原因。
 */
#include <jni.h>
#include <errno.h>
#include <linux/input.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#ifndef EVIOCGRAB
#error "linux/input.h 未定义 EVIOCGRAB"
#endif
#ifndef EVIOCSFF
#error "linux/input.h 未定义 EVIOCSFF"
#endif
#ifndef EVIOCRMFF
#error "linux/input.h 未定义 EVIOCRMFF"
#endif

JNIEXPORT jint JNICALL
Java_com_fseven_padmouse_NativeIoctl_nativeIoctl(
    JNIEnv *env, jobject thiz, jint fd, jint request, jint value) {
    (void) env;
    (void) thiz;
    if (ioctl((int) fd, (unsigned long) (unsigned int) request,
              (unsigned long) (unsigned int) value) == -1) {
        return errno;
    }
    return 0;
}

/*
 * M13 手柄震动：FF_RUMBLE 直接下发。
 *
 * 手柄没有注册为系统 vibrator，唯一路径是写 /dev/input/eventX：
 *   1. 构造 struct ff_effect（FF_RUMBLE），ioctl(fd, EVIOCSFF, &effect) 让内核分配 effect id；
 *   2. write 一个 EV_FF 事件（code = effect.id, value = 1）启动震动；
 *   3. 到时写 EV_FF value=0，再 ioctl(fd, EVIOCRMFF, id) 释放 effect。
 *
 * 成功返回 effect id（>= 0）；失败返回 -errno（< 0），由 Kotlin 侧转成可读原因。
 * 宏与结构体大小均来自 <linux/input.h>，不硬编码 ioctl 数值。
 */
JNIEXPORT jint JNICALL
Java_com_fseven_padmouse_NativeIoctl_nativeRumble(
    JNIEnv *env, jobject thiz, jint fd, jint duration_ms, jint strength) {
    (void) env;
    (void) thiz;
    if (fd < 0) return -EBADF;
    if (duration_ms <= 0 || strength <= 0) return -EINVAL;

    struct ff_effect effect;
    memset(&effect, 0, sizeof(effect));
    effect.type = FF_RUMBLE;
    effect.id = -1;
    effect.replay.length = (uint16_t) duration_ms;
    effect.replay.delay = 0;
    effect.u.rumble.strong_magnitude = (uint16_t) strength;
    effect.u.rumble.weak_magnitude = (uint16_t) strength;

    if (ioctl((int) fd, EVIOCSFF, &effect) == -1) {
        return -errno;
    }

    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = EV_FF;
    event.code = (uint16_t) effect.id;
    event.value = 1;
    if (write((int) fd, &event, sizeof(event)) == -1) {
        int err = errno;
        ioctl((int) fd, EVIOCRMFF, effect.id);
        return -err;
    }
    return effect.id;
}

JNIEXPORT jint JNICALL
Java_com_fseven_padmouse_NativeIoctl_nativeRumbleStop(
    JNIEnv *env, jobject thiz, jint fd, jint effect_id) {
    (void) env;
    (void) thiz;
    if (fd < 0 || effect_id < 0) return EBADF;

    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = EV_FF;
    event.code = (uint16_t) effect_id;
    event.value = 0;
    if (write((int) fd, &event, sizeof(event)) == -1) {
        return errno;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_fseven_padmouse_NativeIoctl_nativeRumbleDestroy(
    JNIEnv *env, jobject thiz, jint fd, jint effect_id) {
    (void) env;
    (void) thiz;
    if (fd < 0 || effect_id < 0) return EBADF;
    if (ioctl((int) fd, EVIOCRMFF, effect_id) == -1) {
        return errno;
    }
    return 0;
}
