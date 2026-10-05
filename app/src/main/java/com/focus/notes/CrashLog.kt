package com.focus.notes

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запись ошибок и служебных отчётов в файл, чтобы потом показать их текст. */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"
    private const val PREFS = "crashlog"
    private const val KEY_LAST_EXIT = "last_exit_ts"

    @Volatile
    private var started = false

    private var trimReports = 0

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !is Recorder) {
            Thread.setDefaultUncaughtExceptionHandler(Recorder(app, current))
        }
        if (!started) {
            started = true
            registerMemoryWatch(app)
            if (Build.VERSION.SDK_INT >= 30) checkExitHistory(app)
        }
    }

    /** Возвращает накопленный текст (один раз) или null. */
    fun take(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.isFile) return null
        val text = try {
            f.readText()
        } catch (e: Exception) {
            null
        }
        f.delete()
        return text?.take(8000)
    }

    /** Дописывает служебный отчёт (текст должен начинаться с метки в квадратных скобках). */
    fun record(ctx: Context, text: String) {
        try {
            val f = File(ctx.applicationContext.filesDir, FILE_NAME)
            val old = if (f.isFile) f.readText() else ""
            if (old.length > 6000) return
            val sep = if (old.isEmpty()) "" else "\n\n----\n\n"
            f.writeText(old + sep + stamp() + "\n" + text)
        } catch (ignored: Throwable) {
            // отчёт не должен сам вызывать сбой
        }
    }

    fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val max = rt.maxMemory() / 1048576
        val native = Debug.getNativeHeapAllocatedSize() / 1048576
        return "память: Java $used из $max МБ, нативная $native МБ"
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    // ---------------- предупреждения о нехватке памяти ----------------

    private fun registerMemoryWatch(app: Context) {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // 5, 10, 15: приложение на экране, а системе не хватает памяти
                if (level in 5..15 && trimReports < 5) {
                    trimReports++
                    record(app, "[отчёт] Система просит освободить память (уровень $level). " + memoryLine())
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            override fun onLowMemory() {
                record(app, "[отчёт] Система сообщила о нехватке памяти (onLowMemory). " + memoryLine())
            }
        })
    }

    // ---------------- история завершений процесса (Android 11+) ----------------

    @TargetApi(30)
    private fun checkExitHistory(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_EXIT, 0L)
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8)

            var newest = last
            for (info in list) {
                if (info.timestamp > newest) newest = info.timestamp
            }

            val sb = StringBuilder()
            var count = 0
            for (info in list) {
                if (info.timestamp <= last) continue
                if (!interesting(info)) continue
                if (count >= 4) break
                count++
                sb.append(describe(info)).append('\n')
            }

            if (newest > last) prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
            if (sb.isNotEmpty()) {
                record(
                    ctx,
                    "[отчёт] Системные записи о завершении процесса приложения " +
                        "(сначала самые новые):\n" + sb
                )
            }
        } catch (ignored: Throwable) {
            // история недоступна: это не причина для сбоя
        }
    }

    /** Нас интересуют завершения, случившиеся пока приложение было на экране, и все сбои. */
    @TargetApi(30)
    private fun interesting(info: ApplicationExitInfo): Boolean {
        val r = info.reason
        if (r == ApplicationExitInfo.REASON_USER_REQUESTED ||
            r == ApplicationExitInfo.REASON_USER_STOPPED ||
            r == ApplicationExitInfo.REASON_EXIT_SELF
        ) {
            return false
        }
        if (r == ApplicationExitInfo.REASON_CRASH ||
            r == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            r == ApplicationExitInfo.REASON_ANR
        ) {
            return true
        }
        return info.importance <= 230
    }

    @TargetApi(30)
    private fun describe(info: ApplicationExitInfo): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
        return "$time: ${reasonName(info.reason)}; важность процесса ${info.importance}" +
            "; статус ${info.status}; pss ${info.pss} КБ, rss ${info.rss} КБ" +
            "; процесс ${info.processName}; описание: ${info.description ?: "нет"}"
    }

    @TargetApi(30)
    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (зависание)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (исключение)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (сбой в системном коде)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (нехватка памяти)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (убит сигналом)"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "код $r"
    }

    // ---------------- необработанные исключения ----------------

    private class Recorder(
        private val ctx: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                File(ctx.filesDir, FILE_NAME).writeText(
                    stamp() + ", поток " + t.name + "\n" + memoryLine() + "\n" +
                        Log.getStackTraceString(e)
                )
            } catch (ignored: Throwable) {
                // запись сбоя не должна сама вызывать новый сбой
            }
            previous?.uncaughtException(t, e)
        }
    }
}
