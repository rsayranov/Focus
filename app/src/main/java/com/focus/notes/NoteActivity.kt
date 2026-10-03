package com.focus.notes

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class NoteActivity : Activity() {

    private lateinit var noteFile: File
    private lateinit var editor: EditorView
    private lateinit var panel: FormatPanel

    private var loaded = false
    private var savedMd = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveNow() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        noteFile = File(path)

        val icon = try {
            NoteFile.readIcon(noteFile)
        } catch (e: Exception) {
            null
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(6), dp(6))
        }
        val title = TextView(this).apply {
            textSize = 20f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = (icon ?: "📄") + " " + noteFile.name.removeSuffix(FileStore.NOTE_EXT)
        }
        val toolsButton = TextView(this).apply {
            text = "Аа"
            textSize = 20f
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(toolsButton)

        editor = EditorView(this)
        panel = FormatPanel(this, editor)
        toolsButton.setOnClickListener { panel.toggle(toolsButton) }

        editor.onStateChanged = { panel.refresh() }
        editor.onContentChanged = {
            uiHandler.removeCallbacks(autosave)
            uiHandler.postDelayed(autosave, 1500)
        }

        root.addView(bar)
        root.addView(
            editor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        try {
            val note = NoteFile.read(noteFile)
            editor.loadMarkdown(note.text)
            savedMd = editor.markdown()
            loaded = true
        } catch (e: Exception) {
            editor.isEnabled = false
            Toast.makeText(this, "Не удалось открыть заметку: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(autosave)
        if (::panel.isInitialized) panel.dismiss()
        saveNow()
    }

    private fun saveNow() {
        if (!loaded) return
        try {
            val md = editor.markdown()
            if (md == savedMd) return
            NoteFile.save(noteFile, md)
            savedMd = md
        } catch (e: Exception) {
            Toast.makeText(this, "Не сохранено: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
