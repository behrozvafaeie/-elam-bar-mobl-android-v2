package ir.elambar.mobl

import android.app.Application
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ElambarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { StartupCrashReporter.write(this, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}

internal object StartupCrashReporter {
    private const val DIRECTORY = "crash-reports"
    private const val FILE_NAME = "last-startup-crash.txt"

    fun pendingFile(context: android.content.Context): File =
        File(File(context.filesDir, DIRECTORY), FILE_NAME)

    @Synchronized
    fun write(context: android.content.Context, threadName: String, error: Throwable) {
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        FileOutputStream(File(directory, FILE_NAME), false).bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine("Elaam Bar Mobl startup crash report")
            out.appendLine("Time: $timestamp")
            out.appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            out.appendLine("Android API: ${Build.VERSION.SDK_INT}")
            out.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            out.appendLine("Thread: $threadName")
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 5) {
                out.appendLine("Cause[$depth]: ${current.javaClass.name}")
                current.stackTrace.take(40).forEach { frame ->
                    out.appendLine("  at ${frame.className}.${frame.methodName}(${frame.fileName ?: "Unknown"}:${frame.lineNumber})")
                }
                current = current.cause
                depth++
            }
        }
    }
}
