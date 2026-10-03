package com.focus.notes

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class AttachmentInfo(val name: String, val size: Long)

data class NoteContent(
    val text: String,
    val attachments: List<AttachmentInfo>,
    val icon: String?
)

/**
 * Формат .note: zip-архив.
 *   note.md              - текст заметки (Markdown, UTF-8)
 *   meta.json            - служебные данные (пока только иконка), необязателен
 *   attachments/<имя>    - вложения
 */
object NoteFile {
    const val TEXT_ENTRY = "note.md"
    const val META_ENTRY = "meta.json"
    const val ATTACH_DIR = "attachments/"

    private class IconOp(val value: String?)

    fun read(file: File): NoteContent {
        return ZipFile(file).use { zip ->
            if (zip.getEntry(TEXT_ENTRY) == null) {
                throw IOException("В файле нет $TEXT_ENTRY")
            }
            val text = readTextBytes(zip).toString(Charsets.UTF_8)
            val attachments = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(ATTACH_DIR) }
                .map { AttachmentInfo(it.name.removePrefix(ATTACH_DIR), it.size) }
                .toList()
            NoteContent(text, attachments, readMetaIcon(zip))
        }
    }

    fun readIcon(file: File): String? {
        return ZipFile(file).use { readMetaIcon(it) }
    }

    fun extractAttachment(file: File, name: String, out: File) {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(ATTACH_DIR + name)
                ?: throw IOException("Вложение не найдено: $name")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
    }

    /**
     * Создаёт или обновляет заметку. Иконка и существующие вложения сохраняются,
     * кроме удаляемых (remove) и заменяемых новыми (add).
     */
    fun save(
        target: File,
        text: String,
        add: Map<String, File> = emptyMap(),
        remove: Set<String> = emptySet()
    ) {
        rewrite(target, text, add, remove, null)
    }

    /** Задаёт иконку заметки (null - убрать). Текст и вложения не меняются. */
    fun setIcon(target: File, icon: String?) {
        rewrite(target, null, emptyMap(), emptySet(), IconOp(icon))
    }

    /**
     * Запись идёт во временный файл, затем он заменяет оригинал,
     * поэтому сбой посреди записи не портит заметку.
     * text == null означает "оставить прежний текст".
     * iconOp == null означает "оставить прежнюю иконку".
     */
    private fun rewrite(
        target: File,
        text: String?,
        add: Map<String, File>,
        remove: Set<String>,
        iconOp: IconOp?
    ) {
        add.keys.forEach { checkName(it) }

        val dir = target.absoluteFile.parentFile
            ?: throw IOException("Не удалось определить папку")
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        val old: ZipFile? = if (target.exists()) ZipFile(target) else null

        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                val textBytes: ByteArray = when {
                    text != null -> text.toByteArray(Charsets.UTF_8)
                    old != null -> readTextBytes(old)
                    else -> ByteArray(0)
                }
                zos.putNextEntry(ZipEntry(TEXT_ENTRY))
                zos.write(textBytes)
                zos.closeEntry()

                if (old != null) {
                    for (entry in old.entries().asSequence()) {
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (name == TEXT_ENTRY) continue
                        if (name == META_ENTRY && iconOp != null) continue
                        if (name.startsWith(ATTACH_DIR)) {
                            val short = name.removePrefix(ATTACH_DIR)
                            if (short in remove || short in add) continue
                        }
                        zos.putNextEntry(ZipEntry(name))
                        old.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                val newIcon = iconOp?.value
                if (newIcon != null) {
                    zos.putNextEntry(ZipEntry(META_ENTRY))
                    zos.write(
                        JSONObject().put("icon", newIcon).toString()
                            .toByteArray(Charsets.UTF_8)
                    )
                    zos.closeEntry()
                }

                for ((name, source) in add) {
                    zos.putNextEntry(ZipEntry(ATTACH_DIR + name))
                    source.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            old?.close()
        }
    }

    private fun readTextBytes(zip: ZipFile): ByteArray {
        val entry = zip.getEntry(TEXT_ENTRY) ?: return ByteArray(0)
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun readMetaIcon(zip: ZipFile): String? {
        val entry = zip.getEntry(META_ENTRY) ?: return null
        return try {
            val json = zip.getInputStream(entry).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            JSONObject(json).optString("icon", "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkName(name: String) {
        if (name.isBlank() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\')
        ) {
            throw IOException("Недопустимое имя вложения: $name")
        }
    }
}
