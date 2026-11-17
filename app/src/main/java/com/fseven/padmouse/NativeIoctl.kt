package com.fseven.padmouse

import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/** App 进程能提供、UserService（shell uid）加载 .so 所需的三条路径。 */
data class NativeLibPaths(
    val nativeLibraryDir: String?,
    val codeCacheDir: String?,
    val apkPath: String?,
)

/**
 * JNI ioctl 桥（libnative_ioctl.so）。
 *
 * Android 16（API 36）移除了三参 ioctlInt(FileDescriptor,int,int)，Java 层无法传递
 * EVIOCGRAB 的 arg=1，只能原生调用 libc ioctl。
 *
 * 该类必须是顶层对象：JNI 符号为 Java_com_fseven_padmouse_NativeIoctl_nativeIoctl；
 * 若嵌在其它类或 companion object 内，符号会多出 $Companion$ 段导致 UnsatisfiedLinkError。
 *
 * 加载顺序（APK 的 android:extractNativeLibs="false"，nativeLibraryDir 里可能没有 .so）：
 * 1. <nativeLibraryDir>/libnative_ioctl.so
 * 2. 从 APK 的 lib/<abi>/libnative_ioctl.so 解压到 codeCacheDir 再 System.load
 * 3. UserService 为 shell uid、可能无法遍历 App 私有 codeCacheDir 时，解压到 /data/local/tmp 兜底
 */
internal object NativeIoctl {

    private const val LIB_FILE = "libnative_ioctl.so"
    private const val TMP_DIR = "/data/local/tmp"

    @Volatile
    private var loaded = false

    @Volatile
    var loadError: String? = null
        private set

    @Synchronized
    fun ensureLoaded(paths: NativeLibPaths?): Boolean {
        if (loaded) return true
        val errors = ArrayList<String>()

        // 1) extractNativeLibs=true 时 .so 已解压在 nativeLibraryDir。
        val libDir = paths?.nativeLibraryDir?.takeIf { it.isNotBlank() }
        if (libDir == null) {
            errors.add("nativeLibraryDir 为空")
        } else {
            val file = File(libDir, LIB_FILE)
            if (file.isFile && load(file.absolutePath, errors)) return true
            errors.add("nativeLibraryDir 无 " + LIB_FILE)
        }

        // 2) extractNativeLibs=false：按卡片要求从 APK 解压到 codeCacheDir。
        val codeCache = paths?.codeCacheDir?.takeIf { it.isNotBlank() }
        if (codeCache == null) {
            errors.add("codeCacheDir 为空")
        } else {
            val target = File(codeCache, LIB_FILE)
            if (extractTo(paths, target) && load(target.absolutePath, errors)) return true
            errors.add("codeCacheDir 加载失败: " + target.absolutePath)
        }

        // 3) shell uid 无法遍历 App 私有目录时的兜底（UserService 对该目录有写权限）。
        val tmp = File(TMP_DIR, LIB_FILE)
        if (extractTo(paths, tmp) && load(tmp.absolutePath, errors)) return true
        errors.add("/data/local/tmp 加载失败: " + tmp.absolutePath)

        loadError = errors.joinToString("；")
        return false
    }

    external fun nativeIoctl(fd: Int, request: Int, value: Int): Int

    private fun load(path: String, errors: MutableList<String>): Boolean = try {
        System.load(path)
        loaded = true
        loadError = null
        true
    } catch (t: Throwable) {
        errors.add("System.load(" + path + ") 失败: " + t.javaClass.simpleName + ": " + t.message)
        false
    }

    private fun extractTo(paths: NativeLibPaths?, target: File): Boolean {
        val apk = paths?.apkPath?.takeIf { it.isNotBlank() } ?: return false
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/" + abi + "/" + LIB_FILE) ?: return false
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target.setReadable(true, false)
            target.setExecutable(true, false)
            true
        } catch (t: Throwable) {
            loadError = "解压到 " + target.absolutePath + " 失败: " + t.javaClass.simpleName + ": " + t.message
            false
        }
    }
}
