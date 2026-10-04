package com.focus.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.util.regex.Pattern
import kotlin.math.max

object Attachments {
    const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 85

    /**
     * Запись картинки в Markdown: восклицательный знак, пустые квадратные скобки,
     * затем путь вида attachments/имя в круглых скобках.
     * Шаблон собран из частей намеренно.
     */
    val IMG_PATTERN: Pattern = Pattern.compile(
        "!" + "\\[[^\\]]*\\]" + "\\(attachments/([^)\\n]+)\\)"
    )

    fun referencedNames(md: String): Set<String> {
        val res = HashSet<String>()
        val m = IMG_PATTERN.matcher(md)
        while (m.find()) {
            val g = m.group(1)
            if (g != null) res.add(g)
        }
        return res
    }

    fun safeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    /**
     * Уменьшает картинку до 2K по длинной стороне (с учётом поворота из EXIF)
     * и сохраняет в JPEG. Прозрачность заменяется белым фоном.
     */
    fun compressImage(ctx: Context, uri: Uri, out: File) {
        val resolver = ctx.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { s ->
            if (s == null) throw IOException("Не удалось открыть изображение")
            BitmapFactory.decodeStream(s, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Не удалось прочитать изображение")
        }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri).use { s ->
            if (s == null) throw IOException("Не удалось открыть изображение")
            BitmapFactory.decodeStream(s, null, opts)
        } ?: throw IOException("Не удалось прочитать изображение")

        val orientation = try {
            resolver.openInputStream(uri).use { s ->
                if (s == null) 1 else ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            }
        } catch (e: Exception) {
            1
        }

        val m = Matrix()
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            6 -> m.postRotate(90f)
            7 -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            8 -> m.postRotate(270f)
        }
        val longest = max(decoded.width, decoded.height)
        if (longest > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / longest
            m.postScale(f, f)
        }

        var result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (result !== decoded) decoded.recycle()

        if (result.hasAlpha()) {
            val flat = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(flat)
            c.drawColor(Color.WHITE)
            c.drawBitmap(result, 0f, 0f, null)
            result.recycle()
            result = flat
        }

        out.outputStream().buffered().use {
            result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        result.recycle()
    }
}

/**
 * Рабочая папка открытой заметки: сюда достаются вложения из .note
 * и сюда же кладутся новые, пока они не записаны в архив.
 */
class AttachmentStore(private val ctx: Context, var noteFile: File) : ImageSource {

    val dir = File(ctx.cacheDir, "sess-" + System.nanoTime())
    var onImageReady: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val pending = LinkedHashSet<String>()
    private val missing = HashSet<String>()
    private val dimCache = HashMap<String, IntArray>()
    private val loading = HashSet<String>()
    private var counter = 0
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        dir.mkdirs()
    }

    fun fileFor(name: String): File = File(dir, name)

    /** Файл вложения в рабочей папке (при необходимости достаётся из .note). */
    fun ensure(name: String): File? {
        if (!Attachments.safeName(name)) return null
        val f = fileFor(name)
        if (f.isFile) return f
        if (name in missing) return null
        return try {
            NoteFile.extractAttachment(noteFile, name, f)
            f
        } catch (e: Exception) {
            f.delete()
            missing.add(name)
            null
        }
    }

    /** Вызывается из фонового потока. */
    fun importImage(uri: Uri): String {
        val n = synchronized(pending) {
            counter++
            counter
        }
        val name = "img-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + n + ".jpg"
        val f = fileFor(name)
        try {
            Attachments.compressImage(ctx, uri, f)
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        synchronized(pending) { pending.add(name) }
        return name
    }

    /** Вложения, которых ещё нет в .note. */
    fun pendingFiles(): Map<String, File> {
        val res = LinkedHashMap<String, File>()
        synchronized(pending) {
            for (n in pending) {
                val f = fileFor(n)
                if (f.isFile) res[n] = f
            }
        }
        return res
    }

    fun markSaved(names: Collection<String>) {
        val set = names.toSet()
        synchronized(pending) { pending.removeAll(set) }
    }

    fun dispose() {
        cache.evictAll()
        dir.deleteRecursively()
    }

    // ---------- ImageSource ----------

    override fun dims(name: String): IntArray? {
        val known = dimCache[name]
        if (known != null) return known
        val f = ensure(name) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            missing.add(name)
            return null
        }
        val d = intArrayOf(o.outWidth, o.outHeight)
        dimCache[name] = d
        return d
    }

    override fun bitmap(name: String, targetWidth: Int): Bitmap? {
        val cached = cache.get(name)
        if (cached != null) return cached
        if (name in loading || name in missing) return null
        val f = ensure(name) ?: return null
        val d = dims(name)
        loading.add(name)
        Thread {
            var sample = 1
            if (d != null && targetWidth > 0) {
                while (d[0] / (sample * 2) >= targetWidth) sample *= 2
            }
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = try {
                BitmapFactory.decodeFile(f.absolutePath, o)
            } catch (e: Throwable) {
                null
            }
            main.post {
                loading.remove(name)
                if (bmp != null) {
                    cache.put(name, bmp)
                    onImageReady?.invoke()
                } else {
                    missing.add(name)
                }
            }
        }.start()
        return null
    }
}
