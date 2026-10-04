package com.focus.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.math.max
import kotlin.math.min

/** Источник картинок для редактора (реализует AttachmentStore). */
interface ImageSource {
    /** Ширина и высота картинки в пикселях или null, если файла нет. */
    fun dims(name: String): IntArray?

    /** Готовая картинка из кэша; если её ещё нет, запускает загрузку и возвращает null. */
    fun bitmap(name: String, targetWidth: Int): Bitmap?
}

object Ed {
    var density = 1f
    var contentWidth = 0
    var images: ImageSource? = null

    val ACCENT: Int = 0xFFE8892B.toInt()
    val TEXT_DIM: Int = 0xFF8E8E93.toInt()
    val BAR: Int = 0xFFC7C7CC.toInt()
    val MARK_BG: Int = 0x99FFE066.toInt()

    fun dp(v: Float): Float = v * density
}

object Kind {
    const val NONE = 0
    const val TITLE = 1
    const val SMALL = 2
    const val QUOTE = 3
    const val BULLET = 4
    const val NUMBER = 5
    const val CHECK = 6

    fun isList(k: Int): Boolean = k == BULLET || k == NUMBER || k == CHECK
}

enum class Inline(val bit: Int) {
    BOLD(1),
    ITALIC(2),
    UNDERLINE(4),
    STRIKE(8),
    MARK(16)
}

/** Зачёркивание, которое ставится автоматически на отмеченных пунктах чек-листа. */
class CheckStrikeSpan : StrikethroughSpan()

/**
 * Картинка в тексте: стоит на одном символе-заменителе (U+FFFC),
 * рисуется во всю ширину текста.
 */
class AttachmentImageSpan(val name: String) : ReplacementSpan() {

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val holder = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun widthPx(): Int = max(Ed.contentWidth, 1)

    private fun imageHeight(w: Int): Int {
        val d = Ed.images?.dims(name)
        return if (d != null && d[0] > 0) {
            max(1, (w.toLong() * d[1] / d[0]).toInt())
        } else {
            (w * 0.6f).toInt()
        }
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val w = widthPx()
        val h = imageHeight(w)
        if (fm != null) {
            fm.ascent = -(h + Ed.dp(6f).toInt())
            fm.descent = 0
            fm.top = fm.ascent
            fm.bottom = 0
        }
        return w
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val w = widthPx()
        val h = imageHeight(w)
        val dst = RectF(x, (y - h).toFloat(), x + w, y.toFloat())
        val bmp = Ed.images?.bitmap(name, w)
        val r = Ed.dp(10f)
        val path = Path()
        path.addRoundRect(dst, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
        } else {
            holder.color = 0xFFEDEDF0.toInt()
            canvas.drawRect(dst, holder)
        }
        canvas.restore()
    }
}

fun spanMatches(sp: Any, t: Inline): Boolean = when (t) {
    Inline.BOLD -> sp is StyleSpan && (sp.style and Typeface.BOLD) != 0
    Inline.ITALIC -> sp is StyleSpan && (sp.style and Typeface.ITALIC) != 0
    Inline.UNDERLINE -> sp is UnderlineSpan
    Inline.STRIKE -> sp is StrikethroughSpan && sp !is CheckStrikeSpan
    Inline.MARK -> sp is BackgroundColorSpan
}

fun newSpan(t: Inline): Any = when (t) {
    Inline.BOLD -> StyleSpan(Typeface.BOLD)
    Inline.ITALIC -> StyleSpan(Typeface.ITALIC)
    Inline.UNDERLINE -> UnderlineSpan()
    Inline.STRIKE -> StrikethroughSpan()
    Inline.MARK -> BackgroundColorSpan(Ed.MARK_BG)
}

fun inlineBits(sp: Any): Int = when (sp) {
    is StyleSpan -> (if ((sp.style and Typeface.BOLD) != 0) Inline.BOLD.bit else 0) or
        (if ((sp.style and Typeface.ITALIC) != 0) Inline.ITALIC.bit else 0)
    is UnderlineSpan -> Inline.UNDERLINE.bit
    is CheckStrikeSpan -> 0
    is StrikethroughSpan -> Inline.STRIKE.bit
    is BackgroundColorSpan -> Inline.MARK.bit
    else -> 0
}

/** Спаны подсветки набора текста (клавиатура) к нашему оформлению не относятся. */
fun ours(e: Spanned, sp: Any): Boolean =
    (e.getSpanFlags(sp) and Spanned.SPAN_COMPOSING) == 0

/**
 * Оформление абзаца: заголовок, цитата, список, чек-лист и уровень вложенности.
 * Один такой спан покрывает ровно один абзац (вместе с его символом перевода строки).
 */
class BlockSpan(
    val kind: Int,
    val indent: Int = 0,
    val checked: Boolean = false
) : MetricAffectingSpan(), LeadingMarginSpan {

    private fun indentPx(): Float = indent * Ed.dp(22f)

    private fun style(tp: TextPaint) {
        when (kind) {
            Kind.TITLE -> {
                tp.textSize = tp.textSize * 1.5f
                tp.typeface = Typeface.create(tp.typeface, Typeface.BOLD)
            }
            Kind.SMALL -> {
                tp.textSize = tp.textSize * 1.22f
                tp.typeface = Typeface.create(tp.typeface, Typeface.BOLD)
            }
        }
    }

    override fun updateMeasureState(tp: TextPaint) {
        style(tp)
    }

    override fun updateDrawState(tp: TextPaint) {
        style(tp)
        if (kind == Kind.QUOTE || (kind == Kind.CHECK && checked)) {
            tp.color = Ed.TEXT_DIM
        }
    }

    override fun getLeadingMargin(first: Boolean): Int {
        val extra = when (kind) {
            Kind.BULLET -> 24f
            Kind.NUMBER -> 30f
            Kind.CHECK -> 30f
            Kind.QUOTE -> 16f
            else -> 0f
        }
        val ind = if (Kind.isList(kind)) indentPx() else 0f
        return (ind + Ed.dp(extra)).toInt()
    }

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout
    ) {
        if (kind == Kind.NONE || kind == Kind.TITLE || kind == Kind.SMALL) return

        val oldStyle = p.style
        val oldColor = p.color
        val oldStroke = p.strokeWidth
        val oldCap = p.strokeCap

        val left = x + dir * (if (Kind.isList(kind)) indentPx() else 0f)
        val cy = baseline - p.textSize * 0.32f

        when (kind) {
            Kind.QUOTE -> {
                p.style = Paint.Style.FILL
                p.color = Ed.BAR
                val a = left + dir * Ed.dp(2f)
                val b = left + dir * Ed.dp(5f)
                c.drawRect(min(a, b), top.toFloat(), max(a, b), bottom.toFloat(), p)
            }
            Kind.BULLET -> if (first) {
                p.style = Paint.Style.FILL
                c.drawCircle(left + dir * Ed.dp(9f), cy, Ed.dp(2.8f), p)
            }
            Kind.NUMBER -> if (first) {
                p.style = Paint.Style.FILL
                c.drawText(numberFor(text, start).toString() + ".", left, baseline.toFloat(), p)
            }
            Kind.CHECK -> if (first) {
                val size = Ed.dp(17f)
                val l = left + Ed.dp(2f)
                val rect = RectF(l, cy - size / 2f, l + size, cy + size / 2f)
                p.strokeWidth = Ed.dp(1.6f)
                p.strokeCap = Paint.Cap.ROUND
                if (checked) {
                    p.style = Paint.Style.FILL
                    p.color = Ed.ACCENT
                    c.drawRoundRect(rect, Ed.dp(4.5f), Ed.dp(4.5f), p)
                    p.style = Paint.Style.STROKE
                    p.color = 0xFFFFFFFF.toInt()
                    c.drawLine(l + size * 0.25f, cy, l + size * 0.43f, cy + size * 0.2f, p)
                    c.drawLine(l + size * 0.43f, cy + size * 0.2f, l + size * 0.77f, cy - size * 0.2f, p)
                } else {
                    p.style = Paint.Style.STROKE
                    p.color = Ed.TEXT_DIM
                    c.drawRoundRect(rect, Ed.dp(4.5f), Ed.dp(4.5f), p)
                }
            }
        }

        p.style = oldStyle
        p.color = oldColor
        p.strokeWidth = oldStroke
        p.strokeCap = oldCap
    }

    /** Номер пункта: считаем предыдущие пункты нумерованного списка того же уровня. */
    private fun numberFor(text: CharSequence, start: Int): Int {
        val sp = text as? Spanned ?: return 1
        var n = 1
        var pos = start
        while (pos > 0) {
            var ps = pos - 1
            while (ps > 0 && text[ps - 1] != '\n') ps--
            val b = sp.getSpans(ps, ps + 1, BlockSpan::class.java)
                .firstOrNull { sp.getSpanStart(it) <= ps && sp.getSpanEnd(it) > ps }
                ?: break
            if (b.kind == Kind.NUMBER && b.indent == indent) {
                n++
            } else if (Kind.isList(b.kind) && b.indent > indent) {
                // вложенный пункт, пропускаем
            } else {
                break
            }
            pos = ps
        }
        return n
    }
}
