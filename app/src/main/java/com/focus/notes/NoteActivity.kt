package com.focus.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
    private lateinit var attachments: AttachmentStore

    private val store by lazy { FileStore(File(filesDir, "notes")) }

    private var loaded = false
    private var savedMd = ""
    private var shownTitle = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveNow(false) }

    companion object {
        private const val REQ_IMAGES = 1001
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)

        val path = intent.getStringExtra("path")
        if (path == null) {
            finish()
            return
        }
        noteFile = File(path)
        attachments = AttachmentStore(this, noteFile)
        Ed.images = attachments

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
        panel.onInsertImage = { pickImages() }
        editor.onStateChanged = { panel.refresh() }
        editor.onContentChanged = {
            uiHandler.removeCallbacks(autosave)
            uiHandler.postDelayed(autosave, 1500)
        }
        editor.onImageTap = { name -> openImage(name) }
        editor.onImageLongPress = { name -> imageMenu(name) }
        attachments.onImageReady = { editor.invalidate() }

        root.addView(bar)
        root.addView(
            editor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
        editor.requestFocus()

        try {
            val note = NoteFile.read(noteFile)
            attachments.preloadDims(Attachments.referencedNames(note.text))
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
        // При выходе из заметки заодно вычищаем вложения, на которые нет ссылок.
        saveNow(isFinishing)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::attachments.isInitialized) {
            if (Ed.images === attachments) Ed.images = null
            attachments.dispose()
        }
    }

    // ---------------- изображения ----------------

    @Suppress("DEPRECATION")
    private fun pickImages() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_IMAGES)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть галерею", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGES || resultCode != RESULT_OK || data == null) return

        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isEmpty()) return

        Toast.makeText(this, "Добавляю…", Toast.LENGTH_SHORT).show()
        val target = attachments
        Thread {
            val names = ArrayList<String>()
            var failed = 0
            for (u in uris) {
                try {
                    names.add(target.importImage(u))
                } catch (e: Exception) {
                    failed++
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (names.isNotEmpty()) editor.insertImages(names)
                if (failed > 0) {
                    Toast.makeText(this, "Не удалось добавить: $failed", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openImage(name: String) {
        val f = attachments.ensure(name)
        if (f == null) {
            Toast.makeText(this, "Изображение недоступно", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ImageViewerActivity::class.java).putExtra("path", f.absolutePath)
        )
    }

    private fun imageMenu(name: String) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Открыть", "Удалить")) { _, which ->
                if (which == 0) openImage(name) else editor.removeImage(name)
            }
            .show()
    }

    // ---------------- название и сохранение ----------------

    /** Название заметки это имя файла: при изменении файл переименовывается. */
    private fun commitTitle() {
        val wanted = titleView.text.toString().trim()
        if (wanted == shownTitle) return
        try {
            saveNow(false)
            noteFile = store.rename(noteFile, wanted)
            attachments.noteFile = noteFile
            shownTitle = wanted
            titleView.setText(wanted)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Не удалось переименовать", Toast.LENGTH_LONG).show()
            titleView.setText(shownTitle)
        }
    }

    /**
     * closing = true: заметку закрываем, поэтому в архив попадают только вложения,
     * на которые есть ссылки в тексте, а лишние удаляются.
     */
    private fun saveNow(closing: Boolean) {
        if (!loaded) return
        try {
            val md = editor.markdown()
            val pending = attachments.pendingFiles()
            val referenced = Attachments.referencedNames(md)

            val add = if (closing) pending.filterKeys { it in referenced } else pending
            val remove: Set<String> =
                if (closing && noteFile.exists()) NoteFile.attachmentNames(noteFile) - referenced
                else emptySet()

            if (md == savedMd && add.isEmpty() && remove.isEmpty()) {
                if (closing) attachments.markSaved(pending.keys)
                return
            }

            NoteFile.save(noteFile, md, add, remove)
            savedMd = md
            attachments.markSaved(if (closing) pending.keys else add.keys)
        } catch (e: Exception) {
            Toast.makeText(this, "Не сохранено: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
