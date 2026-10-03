package com.focus.notes

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Временный экран заметки: простое текстовое поле.
 * Настоящий редактор будет отдельным шагом.
 */
class NoteActivity : Activity() {

    private lateinit var noteFile: File
    private lateinit var editor: EditText
    private lateinit var status: TextView

    private var loaded = false
    private var savedText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        noteFile = File(path)

        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val icon = try {
            NoteFile.readIcon(noteFile)
        } catch (e: Exception) {
            null
        }
        val title = TextView(this).apply {
            textSize = 20f
            text = (icon ?: "📄") + " " + noteFile.name.removeSuffix(FileStore.NOTE_EXT)
        }

        val saveButton = Button(this).apply {
            text = "Сохранить"
            isAllCaps = false
            setOnClickListener { saveNote(false) }
        }

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
        }

        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
        }

        root.addView(title)
        root.addView(saveButton)
        root.addView(status)
        root.addView(
            editor,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)

        try {
            val note = NoteFile.read(noteFile)
            editor.setText(note.text)
            savedText = note.text
            loaded = true
            status.text = "Вложений: ${note.attachments.size}"
        } catch (e: Exception) {
            status.text = "Не удалось открыть заметку: ${e.message}"
            editor.isEnabled = false
            saveButton.isEnabled = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (loaded && editor.text.toString() != savedText) {
            saveNote(true)
        }
    }

    private fun saveNote(silent: Boolean) {
        if (!loaded) return
        try {
            val text = editor.text.toString()
            NoteFile.save(noteFile, text)
            savedText = text
            if (!silent) {
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Не сохранено: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
