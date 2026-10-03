package com.focus.notes

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private lateinit var output: TextView
    private val noteFile: File by lazy { File(filesDir, "notes/test.note") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            textSize = 18f
            text = "Focus, сборка ${versionLabel()}"
        }

        val createButton = Button(this).apply {
            text = "Создать / дополнить тестовую заметку"
            setOnClickListener { createOrUpdate() }
        }

        val readButton = Button(this).apply {
            text = "Прочитать заметку"
            setOnClickListener { showNote() }
        }

        output = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
            text = "Нажми кнопку."
        }

        root.addView(title)
        root.addView(createButton)
        root.addView(readButton)
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Suppress("DEPRECATION")
    private fun versionLabel(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        return "${info.versionName} (код ${info.versionCode})"
    }

    private fun createOrUpdate() {
        try {
            val count = if (noteFile.exists()) NoteFile.read(noteFile).attachments.size else 0
            val n = count + 1
            val tmp = File(cacheDir, "att-$n.txt")
            tmp.writeText("Вложение номер $n")
            val text = "# Тестовая заметка\n\nЭто ==маркер== и обычный текст.\n\nВложений: $n\n"
            NoteFile.save(noteFile, text, mapOf("test-$n.txt" to tmp))
            tmp.delete()
            showNote()
        } catch (e: Exception) {
            output.text = "Ошибка: ${e.message}"
        }
    }

    private fun showNote() {
        try {
            if (!noteFile.exists()) {
                output.text = "Заметки ещё нет."
                return
            }
            val note = NoteFile.read(noteFile)
            val sb = StringBuilder()
            sb.append("Файл .note: ${noteFile.length()} байт\n\n")
            sb.append("Текст:\n${note.text}\n")
            sb.append("Вложения (${note.attachments.size}):\n")
            note.attachments.forEach { sb.append("- ${it.name}, ${it.size} байт\n") }
            output.text = sb.toString()
        } catch (e: Exception) {
            output.text = "Ошибка: ${e.message}"
        }
    }
}
