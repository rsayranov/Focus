package com.focus.notes

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.BreakIterator

data class StoreEntry(
    val file: File,
    val isFolder: Boolean,
    val name: String,
    val icon: String?
)

data class FolderItem(
    val file: File,
    val depth: Int,
    val name: String,
    val icon: String?
)

data class TrashItem(
    val file: File,
    val originalName: String,
    val displayName: String,
    val isFolder: Boolean
)

/**
 * Папки - настоящие папки на диске, заметки - файлы .note.
 * Корзина лежит в скрытой папке .trash внутри корня.
 */
class FileStore(val root: File) {

    private val trashDir = File(root, ".trash")
    private val itemsDir = File(trashDir, "items")
    private val originsDir = File(trashDir, "origins")

    init {
        root.mkdirs()
    }

    companion object {
        const val NOTE_EXT = ".note"
        const val FOLDER_META = ".folder.json"

        /** Оставляет первый символ-эмоджи из введённого текста; не эмоджи -> null. */
        fun normalizeIcon(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            if (Character.isLetterOrDigit(t.codePointAt(0))) return null
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(t)
            val end = bi.next()
            return if (end > 0) t.substring(0, end) else null
        }
    }

    // ---------- просмотр ----------

    private fun children(dir: File): List<File> {
        val all = dir.listFiles() ?: return emptyList()
        return all
            .filter {
                !it.name.startsWith(".") &&
                    (it.isDirectory || (it.isFile && it.name.endsWith(NOTE_EXT)))
            }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun list(dir: File): List<StoreEntry> {
        return children(dir).map { f ->
            if (f.isDirectory) {
                StoreEntry(f, true, f.name, folderIcon(f))
            } else {
                val icon = try {
                    NoteFile.readIcon(f)
                } catch (e: Exception) {
                    null
                }
                StoreEntry(f, false, f.name.removeSuffix(NOTE_EXT), icon)
            }
        }
    }

    /** Все папки дерева (для выбора "Переместить в…"); корень первым. */
    fun listAllFolders(exclude: File? = null): List<FolderItem> {
        val result = mutableListOf(FolderItem(root, 0, "Корень", null))
        val excluded = exclude?.canonicalPath

        fun walk(dir: File, depth: Int) {
            for (f in children(dir)) {
                if (!f.isDirectory) continue
                if (excluded != null && f.canonicalPath == excluded) continue
                result.add(FolderItem(f, depth, f.name, folderIcon(f)))
                walk(f, depth + 1)
            }
        }

        walk(root, 1)
        return result
    }

    fun relativePath(file: File): String = file.relativeTo(root).path

    // ---------- создание, переименование, перемещение ----------

    fun createFolder(parent: File, name: String): File {
        val clean = validateName(name)
        val target = File(parent, clean)
        if (target.exists()) throw IOException("«$clean» уже существует")
        if (!target.mkdir()) throw IOException("Не удалось создать папку")
        return target
    }

    fun createNote(parent: File, name: String): File {
        val clean = validateName(name)
        val target = File(parent, clean + NOTE_EXT)
        if (target.exists()) throw IOException("«$clean» уже существует")
        NoteFile.save(target, "")
        return target
    }

    fun rename(file: File, newName: String): File {
        val clean = validateName(newName)
        val target = File(file.parentFile, if (file.isDirectory) clean else clean + NOTE_EXT)
        if (target.absolutePath == file.absolutePath) return file
        if (target.exists()) throw IOException("«$clean» уже существует")
        moveFile(file, target)
        return target
    }

    fun move(file: File, destDir: File): File {
        if (file.parentFile?.canonicalPath == destDir.canonicalPath) return file
        if (file.isDirectory) {
            val src = file.canonicalPath
            val dst = destDir.canonicalPath
            if (dst == src || dst.startsWith(src + File.separator)) {
                throw IOException("Нельзя переместить папку внутрь себя")
            }
        }
        val target = uniqueFile(destDir, file.name)
        moveFile(file, target)
        return target
    }

    // ---------- иконки ----------

    fun folderIcon(folder: File): String? {
        val meta = File(folder, FOLDER_META)
        if (!meta.isFile) return null
        return try {
            JSONObject(meta.readText()).optString("icon", "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** icon == null убирает иконку. */
    fun setIcon(file: File, icon: String?) {
        val clean: String? = if (icon == null) {
            null
        } else {
            normalizeIcon(icon) ?: throw IOException("Это не эмоджи")
        }
        if (file.isDirectory) {
            val meta = File(file, FOLDER_META)
            if (clean == null) {
                meta.delete()
            } else {
                meta.writeText(JSONObject().put("icon", clean).toString())
            }
        } else {
            NoteFile.setIcon(file, clean)
        }
    }

    // ---------- корзина ----------

    fun moveToTrash(file: File) {
        itemsDir.mkdirs()
        originsDir.mkdirs()
        val id = System.currentTimeMillis().toString() + "-" + file.name
        val origin = file.parentFile?.let { relativePath(it) } ?: ""
        moveFile(file, File(itemsDir, id))
        File(originsDir, "$id.txt").writeText(origin)
    }

    fun listTrash(): List<TrashItem> {
        val all = itemsDir.listFiles() ?: return emptyList()
        return all.sortedByDescending { it.name }.map { f ->
            val original = f.name.substringAfter("-")
            TrashItem(f, original, original.removeSuffix(NOTE_EXT), f.isDirectory)
        }
    }

    /** Если исходной папки уже нет, элемент возвращается в корень. */
    fun restore(item: TrashItem) {
        val originFile = File(originsDir, item.file.name + ".txt")
        val rel = if (originFile.exists()) originFile.readText() else ""
        var parent = if (rel.isEmpty()) root else File(root, rel)
        if (!parent.isDirectory) parent = root
        moveFile(item.file, uniqueFile(parent, item.originalName))
        originFile.delete()
    }

    fun deleteForever(item: TrashItem) {
        item.file.deleteRecursively()
        File(originsDir, item.file.name + ".txt").delete()
    }

    fun emptyTrash() {
        trashDir.deleteRecursively()
    }

    // ---------- вспомогательное ----------

    private fun validateName(raw: String): String {
        val n = raw.trim()
        if (n.isEmpty()) throw IOException("Введите название")
        if (n.startsWith(".")) throw IOException("Название не может начинаться с точки")
        if (n.length > 100) throw IOException("Слишком длинное название")
        val bad = n.any {
            it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' ||
                it == '"' || it == '<' || it == '>' || it == '|' || it.code < 32
        }
        if (bad) throw IOException("В названии есть недопустимые символы")
        return n
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val isNote = name.endsWith(NOTE_EXT)
        val base = if (isNote) name.removeSuffix(NOTE_EXT) else name
        val ext = if (isNote) NOTE_EXT else ""
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private fun moveFile(src: File, dst: File) {
        if (!src.renameTo(dst)) throw IOException("Не удалось переместить «${src.name}»")
    }
}
