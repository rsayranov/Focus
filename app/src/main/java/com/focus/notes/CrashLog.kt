package com.focus.notes

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запись необработанных ошибок и служебных отчётов в файл, чтобы потом показать их текст. */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is Recorder) return
        Thread.setDefaultUncaughtExceptionHandler(Recorder(app, current))
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
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sep = if (old.isEmpty()) "" else "\n\n----\n\n"
            f.writeText(old + sep + stamp + "\n" + text)
        } catch (ignored: Throwable) {
            // отчёт не должен сам вызывать сбой
        }
    }

    private class Recorder(
        private val ctx: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(ctx.filesDir, FILE_NAME).writeText(
                    stamp + ", поток " + t.name + "\n" + Log.getStackTraceString(e)
                )
            } catch (ignored: Throwable) {
                // запись сбоя не должна сама вызывать новый сбой
            }
            previous?.uncaughtException(t, e)
        }
    }
}
