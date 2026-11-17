/*
 * PadMouse 原生 ioctl 桥（arm64-v8a）。
 *
 * 背景：Android 16（API 36）移除了 android.system.Os / libcore.io.* 的
 * ioctlInt(FileDescriptor, int, int) 三参重载，只剩 ioctlInt(FileDescriptor, int)
 * （arg 固定 0），无法下发 EVIOCGRAB(1)。这里用 JNI 直接调用 libc ioctl。
 *
 * 构建（NDK 28，arm64）：
 *   $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang \
 *     -shared -fPIC -O2 -Wall -o app/src/main/jniLibs/arm64-v8a/libpadmouse_evioctl.so \
 *     app/src/main/jni/evioctl.c
 *
 * 返回 0 表示成功；失败返回 errno（>0），由 Kotlin 侧转成可读原因。
 */
#include <jni.h>
#include <errno.h>
#include <sys/ioctl.h>

JNIEXPORT jint JNICALL
Java_com_fseven_padmouse_GamepadReader_00024EvIoctlNative_nativeIoctl(
    JNIEnv *env, jobject thiz, jint fd, jint request, jint value) {
    (void) env;
    (void) thiz;
    if (ioctl((int) fd, (unsigned long) (unsigned int) request,
              (unsigned long) (unsigned int) value) == -1) {
        return errno;
    }
    return 0;
}
