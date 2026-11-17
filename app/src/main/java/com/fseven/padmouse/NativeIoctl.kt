package com.fseven.padmouse

import java.io.File

/**
 * JNI ioctl 桥（libpadmouse_evioctl.so）。
 *
 * Android 16（API 36）移除了三参 ioctlInt(FileDescriptor,int,int)，Java 层无法传递
 * EVIOCGRAB 的 arg=1，只能原生调用 libc ioctl。
 *
 * 该类必须是顶层对象：JNI 符号为 Java_com_fseven_padmouse_NativeIoctl_nativeIoctl；
 * 若嵌在其它类或 companion object 内，符号会多出 $Companion$ 段导致 UnsatisfiedLinkError。
 */
internal object NativeIoctl {

    private const val LIB_FILE = "libpadmouse_evioctl.so"

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    /**
     * 显式从 App 的 nativeLibraryDir 加载，供 Shizuku UserService（shell uid）使用：
     * System.loadLibrary 依赖 java.library.path，在 UserService 里通常找不到 APK lib 目录。
     */
    @Synchronized
    fun ensureLoaded(nativeLibDir: String?): Boolean {
        if (loaded) return true
        if (nativeLibDir.isNullOrBlank()) {
            loadError = "未获得 nativeLibraryDir"
            return false
        }
        val lib = File(nativeLibDir, LIB_FILE)
        if (!lib.isFile) {
            loadError = "找不到 " + lib.absolutePath
            return false
        }
        return try {
            System.load(lib.absolutePath)
            loaded = true
            loadError = null
            true
        } catch (t: Throwable) {
            loadError = t.javaClass.simpleName + ": " + t.message
            false
        }
    }

    external fun nativeIoctl(fd: Int, request: Int, value: Int): Int
}
