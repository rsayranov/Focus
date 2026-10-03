package com.focus.notes

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

class NoteActivity : Activity() {

    private lateinit var noteFile: File
    private lateinit var editor: EditorView
    private lateinit var panel: FormatPanel
    private lateinit var titleView: EditText

    private val store by lazy { FileStore(File(filesDir, "notes")) }

    private var loaded = false
    private var savedMd = ""
    private var shownTitle = ""
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
        shownTitle = noteFile.name.removeSuffix(FileStore.NOTE_EXT)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(6), dp(6))
        }
        val iconView = TextView(this).apply {
            textSize = 22f
            text = icon ?: "📄"
        }
        titleView = EditText(this).apply {
            setSingleLine(true)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setText(shownTitle)
            setOnEditorActionListener { _, _, _ ->
                commitTitle()
                editor.requestFocus()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) commitTitle()
            }
        }
        val toolsButton = TextView(this).apply {
            text = "Аа"
            textSize = 20f
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        bar.addView(iconView)
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(toolsButton)

        editor = EditorView(this)
        panel = FormatPanel(this, editor)
        toolsButton.setOnClickListener { panel.toggle(toolsButton) }

        editor.suppressToolbar = { panel.isShowing }
        panel.onVisibilityChanged = { shown ->
            editor.reserveRight(if (shown) panel.reservePx else 0)
        }
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
        editor.requestFocus()

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
        if (::titleView.isInitialized) commitTitle()
        saveNow()
    }

    /** Название заметки это имя файла: при изменении файл переименовывается. */
    private fun commitTitle() {
        val wanted = titleView.text.toString().trim()
        if (wanted == shownTitle) return
        try {
            saveNow()
            noteFile = store.rename(noteFile, wanted)
            shownTitle = wanted
            titleView.setText(wanted)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Не удалось переименовать", Toast.LENGTH_LONG).show()
            titleView.setText(shownTitle)
        }
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
