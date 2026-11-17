/*
 * PadMouse 原生 ioctl 桥（CMake 构建，输出 libpadmouse_evioctl.so）。
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
#include <sys/ioctl.h>

#ifndef EVIOCGRAB
#error "linux/input.h 未定义 EVIOCGRAB"
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
