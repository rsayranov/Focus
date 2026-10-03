package com.focus.notes

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class AttachmentInfo(val name: String, val size: Long)

data class NoteContent(val text: String, val attachments: List<AttachmentInfo>)

/**
 * Формат .note: zip-архив.
 *   note.md              - текст заметки (Markdown, UTF-8)
 *   attachments/<имя>    - вложения
 */
object NoteFile {
    const val TEXT_ENTRY = "note.md"
    const val ATTACH_DIR = "attachments/"

    fun read(file: File): NoteContent {
        return ZipFile(file).use { zip ->
            val textEntry = zip.getEntry(TEXT_ENTRY)
                ?: throw IOException("В файле нет $TEXT_ENTRY")
            val text = zip.getInputStream(textEntry).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val attachments = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(ATTACH_DIR) }
                .map { AttachmentInfo(it.name.removePrefix(ATTACH_DIR), it.size) }
                .toList()
            NoteContent(text, attachments)
        }
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
     * Создаёт или обновляет заметку. Существующие вложения сохраняются,
     * кроме удаляемых (remove) и заменяемых новыми (add).
     * Запись идёт во временный файл, затем он заменяет оригинал,
     * поэтому сбой посреди записи не портит заметку.
     */
    fun save(
        target: File,
        text: String,
        add: Map<String, File> = emptyMap(),
        remove: Set<String> = emptySet()
    ) {
        add.keys.forEach { checkName(it) }

        val dir = target.absoluteFile.parentFile
            ?: throw IOException("Не удалось определить папку")
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")

        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry(TEXT_ENTRY))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                if (target.exists()) {
                    ZipFile(target).use { old ->
                        for (entry in old.entries().asSequence()) {
                            if (entry.isDirectory || !entry.name.startsWith(ATTACH_DIR)) continue
                            val name = entry.name.removePrefix(ATTACH_DIR)
                            if (name in remove || name in add) continue
                            zos.putNextEntry(ZipEntry(entry.name))
                            old.getInputStream(entry).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
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
