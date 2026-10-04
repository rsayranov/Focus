package com.focus.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

private val EMOJIS = listOf(
    "📁", "📂", "📄", "📝", "📓", "📔", "📒", "📚",
    "📖", "🔖", "📌", "📎", "⭐", "❤️", "🔥", "💡",
    "✅", "❗", "❓", "🎯", "🏠", "💼", "🛒", "✈️",
    "🚗", "🍎", "🍕", "☕", "🎵", "🎬", "🎮", "📷",
    "💻", "📱", "🔑", "💰", "🧾", "📅", "⏰", "🌱",
    "🌍", "🐱", "🐶", "🎁", "🎓", "💪", "🧠", "🔧"
)

class MainActivity : Activity() {

    private lateinit var store: FileStore
    private lateinit var currentDir: File
    private var inTrash = false

    private lateinit var titleView: TextView
    private lateinit var buttonRow: LinearLayout
    private lateinit var listBox: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)

        store = FileStore(File(filesDir, "notes"))
        currentDir = store.root

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        titleView = TextView(this).apply {
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        }
        buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(titleView)
        root.addView(buttonRow)
        root.addView(
            ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        val crash = CrashLog.take(this)
        if (crash != null) showCrash(crash)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            inTrash -> {
                inTrash = false
                refresh()
            }
            currentDir.absolutePath != store.root.absolutePath -> {
                currentDir = currentDir.parentFile ?: store.root
                refresh()
            }
            else -> super.onBackPressed()
        }
    }

    // ---------------- сбой ----------------

    private fun showCrash(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Приложение недавно закрылось с ошибкой")
            .setView(scroll)
            .setPositiveButton("Скопировать") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ---------------- экраны ----------------

    private fun refresh() {
        buttonRow.removeAllViews()
        listBox.removeAllViews()
        if (inTrash) showTrash() else showFolder()
    }

    private fun showFolder() {
        val rel = store.relativePath(currentDir)
        titleView.text = if (rel.isEmpty()) "Focus" else "Focus / " + rel.replace("/", " / ")

        addButton("＋ Папка") {
            askName("Новая папка", "") { name ->
                safely { store.createFolder(currentDir, name) }
            }
        }
        addButton("＋ Заметка") {
            askName("Новая заметка", "") { name ->
                safely { store.createNote(currentDir, name) }
            }
        }
        addButton("🗑 Корзина") {
            inTrash = true
            refresh()
        }

        val entries = store.list(currentDir)
        if (entries.isEmpty()) {
            listBox.addView(emptyLabel("Здесь пусто"))
        }
        for (entry in entries) {
            val symbol = entry.icon ?: if (entry.isFolder) "📁" else "📄"
            listBox.addView(
                makeRow(
                    symbol,
                    entry.name,
                    onTap = {
                        if (entry.isFolder) {
                            currentDir = entry.file
                            refresh()
                        } else {
                            startActivity(
                                Intent(this, NoteActivity::class.java)
                                    .putExtra("path", entry.file.absolutePath)
                            )
                        }
                    },
                    onHold = { entryMenu(entry) }
                )
            )
        }
    }

    private fun showTrash() {
        titleView.text = "Корзина"

        addButton("← Назад") {
            inTrash = false
            refresh()
        }
        addButton("Очистить") {
            confirm("Удалить всё из корзины навсегда?") {
                safely { store.emptyTrash() }
            }
        }

        val items = store.listTrash()
        if (items.isEmpty()) {
            listBox.addView(emptyLabel("Корзина пуста"))
        }
        for (item in items) {
            val symbol = if (item.isFolder) "📁" else "📄"
            listBox.addView(
                makeRow(
                    symbol,
                    item.displayName,
                    onTap = { trashMenu(item) },
                    onHold = { trashMenu(item) }
                )
            )
        }
    }

    // ---------------- меню и диалоги ----------------

    private fun entryMenu(entry: StoreEntry) {
        val items = arrayOf("Переименовать", "Сменить иконку", "Переместить в…", "В корзину")
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askName("Переименовать", entry.name) { name ->
                        safely { store.rename(entry.file, name) }
                    }
                    1 -> pickIcon("Иконка: ${entry.name}") { icon ->
                        safely { store.setIcon(entry.file, icon) }
                    }
                    2 -> pickFolder(entry) { dest ->
                        safely { store.move(entry.file, dest) }
                    }
                    3 -> safely { store.moveToTrash(entry.file) }
                }
            }
            .show()
    }

    private fun trashMenu(item: TrashItem) {
        AlertDialog.Builder(this)
            .setTitle(item.displayName)
            .setItems(arrayOf("Восстановить", "Удалить навсегда")) { _, which ->
                if (which == 0) {
                    safely { store.restore(item) }
                } else {
                    confirm("Удалить «${item.displayName}» навсегда?") {
                        safely { store.deleteForever(item) }
                    }
                }
            }
            .show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun pickIcon(title: String, onPick: (String?) -> Unit) {
        val input = EditText(this).apply {
            hint = "Свой эмоджи (вставь с клавиатуры) и нажми OK"
            setSingleLine(true)
        }
        val grid = GridLayout(this).apply { columnCount = 6 }
        val scroll = ScrollView(this).apply { addView(grid) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        box.addView(input)
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
        )

        lateinit var dialog: AlertDialog

        for (emoji in EMOJIS) {
            grid.addView(
                TextView(this).apply {
                    text = emoji
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        dialog.dismiss()
                        onPick(emoji)
                    }
                }
            )
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val typed = input.text.toString()
                if (typed.isNotBlank()) onPick(typed)
            }
            .setNeutralButton("Убрать") { _, _ -> onPick(null) }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
    }

    private fun pickFolder(entry: StoreEntry, onPick: (File) -> Unit) {
        val folders = store.listAllFolders(if (entry.isFolder) entry.file else null)
        val labels = folders
            .map { "    ".repeat(it.depth) + (it.icon ?: "📁") + " " + it.name }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Переместить в…")
            .setItems(labels) { _, index -> onPick(folders[index].file) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Да") { _, _ -> onYes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------- мелочи интерфейса ----------------

    /** Выполняет действие, показывает ошибку при сбое и обновляет экран. */
    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Ошибка", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun addButton(label: String, onTap: () -> Unit) {
        buttonRow.addView(
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setOnClickListener { onTap() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    private fun emptyLabel(message: String): View {
        return TextView(this).apply {
            text = message
            textSize = 16f
            setPadding(dp(4), dp(16), dp(4), dp(16))
        }
    }

    private fun makeRow(
        symbol: String,
        name: String,
        onTap: () -> Unit,
        onHold: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener { onTap() }
            setOnLongClickListener {
                onHold()
                true
            }
        }
        row.addView(
            TextView(this).apply {
                text = symbol
                textSize = 24f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        row.addView(
            TextView(this).apply {
                text = name
                textSize = 18f
            }
        )
        return row
    }
}
