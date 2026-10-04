package com.focus.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

object Tool {
    const val UNDO = 1
    const val REDO = 2
    const val OUTDENT = 3
    const val INDENT = 4
    const val BOLD = 5
    const val ITALIC = 6
    const val UNDERLINE = 7
    const val STRIKE = 8
    const val HEADING = 9
    const val CHECK = 10
    const val NUMBER = 11
    const val BULLET = 12
    const val MARK = 13
    const val CLEAR = 14
    const val INSERT = 15
    const val IMAGE = 16
}

/** Кнопка-иконка: тонкие линии в стиле Obsidian, рисуются кодом на сетке 24x24. */
class IconButton(context: Context, val tool: Int) : View(context) {

    var active = false
    var dim = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val s = Ed.dp(26f) / 24f
        c.save()
        c.translate((width - 24f * s) / 2f, (height - 24f * s) / 2f)
        c.scale(s, s)

        val color = when {
            dim -> 0xFFC7C7CC.toInt()
            active -> Ed.ACCENT
            else -> 0xFF3A3A3C.toInt()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.7f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = color
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT

        when (tool) {
            Tool.UNDO -> drawUndo(c)
            Tool.REDO -> {
                c.save()
                c.scale(-1f, 1f, 12f, 12f)
                drawUndo(c)
                c.restore()
            }
            Tool.OUTDENT -> drawIndent(c, false)
            Tool.INDENT -> drawIndent(c, true)
            Tool.BOLD -> letter(c, "B", Typeface.DEFAULT_BOLD)
            Tool.ITALIC -> letter(c, "I", Typeface.create(Typeface.SERIF, Typeface.ITALIC))
            Tool.UNDERLINE -> {
                letter(c, "U", Typeface.DEFAULT)
                c.drawLine(6.5f, 20.5f, 17.5f, 20.5f, p)
            }
            Tool.STRIKE -> {
                letter(c, "S", Typeface.DEFAULT)
                c.drawLine(4.5f, 12.5f, 19.5f, 12.5f, p)
            }
            Tool.HEADING -> {
                p.style = Paint.Style.FILL
                p.textSize = 17f
                c.drawText("A", 2.5f, 18f, p)
                p.textSize = 12f
                c.drawText("a", 14f, 18f, p)
            }
            Tool.CHECK -> {
                c.drawRoundRect(RectF(4f, 4f, 20f, 20f), 3.5f, 3.5f, p)
                path.reset()
                path.moveTo(8f, 12.2f)
                path.lineTo(11f, 15.2f)
                path.lineTo(16.2f, 9f)
                c.drawPath(path, p)
            }
            Tool.NUMBER -> {
                for (y in floatArrayOf(6f, 12f, 18f)) c.drawLine(10f, y, 21f, y, p)
                p.style = Paint.Style.FILL
                p.textSize = 7f
                c.drawText("1", 3.5f, 8.4f, p)
                c.drawText("2", 3.5f, 14.4f, p)
                c.drawText("3", 3.5f, 20.4f, p)
            }
            Tool.BULLET -> {
                for (y in floatArrayOf(6f, 12f, 18f)) c.drawLine(10f, y, 21f, y, p)
                p.style = Paint.Style.FILL
                for (y in floatArrayOf(6f, 12f, 18f)) c.drawCircle(5f, y, 1.5f, p)
            }
            Tool.MARK -> {
                p.style = Paint.Style.FILL
                p.color = 0xFFFFE066.toInt()
                c.drawRoundRect(RectF(3.5f, 14f, 20.5f, 20f), 1.5f, 1.5f, p)
                p.color = color
                letter(c, "A", Typeface.DEFAULT_BOLD, 16f, 17f)
            }
            Tool.CLEAR -> {
                letter(c, "T", Typeface.DEFAULT, 16f, 17f)
                p.style = Paint.Style.STROKE
                c.drawLine(5f, 20f, 19f, 4f, p)
            }
            Tool.INSERT -> {
                c.drawLine(12f, 5f, 12f, 19f, p)
                c.drawLine(5f, 12f, 19f, 12f, p)
            }
            Tool.IMAGE -> {
                c.drawRoundRect(RectF(3.5f, 4.5f, 20.5f, 19.5f), 3f, 3f, p)
                c.drawCircle(9f, 10f, 1.6f, p)
                path.reset()
                path.moveTo(4f, 17f)
                path.lineTo(9.5f, 12.5f)
                path.lineTo(13f, 15.5f)
                path.lineTo(16f, 13f)
                path.lineTo(20f, 17f)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }

    private fun drawUndo(c: Canvas) {
        path.reset()
        path.moveTo(8f, 5f)
        path.lineTo(4f, 9f)
        path.lineTo(8f, 13f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(4f, 9f)
        path.lineTo(13f, 9f)
        path.cubicTo(17.5f, 9f, 20f, 11.5f, 20f, 15f)
        path.cubicTo(20f, 18.5f, 17.5f, 20.5f, 13f, 20.5f)
        path.lineTo(9f, 20.5f)
        c.drawPath(path, p)
    }

    private fun drawIndent(c: Canvas, right: Boolean) {
        c.drawLine(4f, 5f, 20f, 5f, p)
        c.drawLine(12f, 10f, 20f, 10f, p)
        c.drawLine(12f, 14f, 20f, 14f, p)
        c.drawLine(4f, 19f, 20f, 19f, p)
        path.reset()
        if (right) {
            path.moveTo(4f, 8.5f)
            path.lineTo(8f, 12f)
            path.lineTo(4f, 15.5f)
        } else {
            path.moveTo(8f, 8.5f)
            path.lineTo(4f, 12f)
            path.lineTo(8f, 15.5f)
        }
        c.drawPath(path, p)
    }

    private fun letter(c: Canvas, ch: String, tf: Typeface, size: Float = 18f, baseline: Float = 17.5f) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = tf
        p.textSize = size
        c.drawText(ch, 12f, baseline, p)
        p.style = Paint.Style.STROKE
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.DEFAULT
    }
}

/** Панель форматирования: кнопки по две в ряд, открывается по кнопке «Аа». */
class FormatPanel(private val ctx: Context, private val editor: EditorView) {

    /** Вызывается при открытии (true) и закрытии (false) панели. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Вызывается, когда в подменю «Вставка» выбрано «Изображение». */
    var onInsertImage: (() -> Unit)? = null

    private var popup: PopupWindow? = null
    private val buttons = ArrayList<IconButton>()

    val isShowing: Boolean get() = popup?.isShowing == true

    /** Сколько места справа нужно освободить под панель. */
    val reservePx: Int get() = dp(124)

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun toggle(anchor: View) {
        if (isShowing) dismiss() else show(anchor)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
        buttons.clear()
    }

    fun refresh() {
        if (!isShowing) return
        val kind = editor.currentKind()
        val indentOk = editor.indentApplicable()
        for (b in buttons) {
            b.active = when (b.tool) {
                Tool.BOLD -> editor.inlineActive(Inline.BOLD)
                Tool.ITALIC -> editor.inlineActive(Inline.ITALIC)
                Tool.UNDERLINE -> editor.inlineActive(Inline.UNDERLINE)
                Tool.STRIKE -> editor.inlineActive(Inline.STRIKE)
                Tool.MARK -> editor.inlineActive(Inline.MARK)
                Tool.HEADING -> kind == Kind.TITLE || kind == Kind.SMALL || kind == Kind.QUOTE
                Tool.CHECK -> kind == Kind.CHECK
                Tool.NUMBER -> kind == Kind.NUMBER
                Tool.BULLET -> kind == Kind.BULLET
                else -> false
            }
            b.dim = (b.tool == Tool.UNDO && !editor.canUndo()) ||
                (b.tool == Tool.REDO && !editor.canRedo()) ||
                ((b.tool == Tool.OUTDENT || b.tool == Tool.INDENT) && !indentOk)
            b.invalidate()
        }
    }

    private fun background(): GradientDrawable = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), 0xFFE5E5EA.toInt())
    }

    private fun show(anchor: View) {
        buttons.clear()
        val grid = GridLayout(ctx).apply {
            columnCount = 2
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val order = intArrayOf(
            Tool.UNDO, Tool.REDO,
            Tool.OUTDENT, Tool.INDENT,
            Tool.BOLD, Tool.ITALIC,
            Tool.UNDERLINE, Tool.STRIKE,
            Tool.HEADING, Tool.CHECK,
            Tool.NUMBER, Tool.BULLET,
            Tool.MARK, Tool.CLEAR,
            Tool.INSERT
        )
        for (tool in order) {
            val btn = IconButton(ctx, tool)
            btn.setOnClickListener { onTool(btn) }
            val lp = GridLayout.LayoutParams()
            lp.width = dp(52)
            lp.height = dp(46)
            grid.addView(btn, lp)
            buttons.add(btn)
        }

        val w = PopupWindow(grid, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        w.setBackgroundDrawable(background())
        w.isOutsideTouchable = true
        w.elevation = dp(8).toFloat()
        w.setOnDismissListener {
            popup = null
            buttons.clear()
            onVisibilityChanged?.invoke(false)
        }
        popup = w
        w.showAsDropDown(anchor, 0, dp(4), Gravity.END)
        onVisibilityChanged?.invoke(true)
        editor.hideSystemToolbar()
        refresh()
    }

    private fun onTool(btn: IconButton) {
        if (btn.dim) return
        when (btn.tool) {
            Tool.UNDO -> editor.undo()
            Tool.REDO -> editor.redo()
            Tool.OUTDENT -> editor.changeIndent(-1)
            Tool.INDENT -> editor.changeIndent(1)
            Tool.BOLD -> editor.toggleInline(Inline.BOLD)
            Tool.ITALIC -> editor.toggleInline(Inline.ITALIC)
            Tool.UNDERLINE -> editor.toggleInline(Inline.UNDERLINE)
            Tool.STRIKE -> editor.toggleInline(Inline.STRIKE)
            Tool.MARK -> editor.toggleInline(Inline.MARK)
            Tool.CHECK -> editor.applyBlock(Kind.CHECK, true)
            Tool.NUMBER -> editor.applyBlock(Kind.NUMBER, true)
            Tool.BULLET -> editor.applyBlock(Kind.BULLET, true)
            Tool.CLEAR -> editor.clearFormatting()
            Tool.HEADING -> showHeadingMenu(btn)
            Tool.INSERT -> showInsertMenu(btn)
        }
        refresh()
    }

    private fun showInsertMenu(anchor: View) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        lateinit var menu: PopupWindow

        val image = IconButton(ctx, Tool.IMAGE)
        image.setOnClickListener {
            menu.dismiss()
            onInsertImage?.invoke()
        }
        box.addView(image, LinearLayout.LayoutParams(dp(52), dp(46)))

        menu = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        menu.setBackgroundDrawable(background())
        menu.isOutsideTouchable = true
        menu.elevation = dp(10).toFloat()
        menu.showAsDropDown(anchor, 0, 0)
    }

    private fun showHeadingMenu(anchor: View) {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        lateinit var menu: PopupWindow

        fun add(label: String, sizeSp: Float, bold: Boolean, kind: Int, quote: Boolean = false) {
            val tv = TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (quote) Ed.TEXT_DIM else 0xFF1C1C1E.toInt())
                setPadding(dp(12), dp(10), dp(18), dp(10))
            }
            val row: View = if (quote) {
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, 0, 0)
                    val bar = View(ctx)
                    bar.setBackgroundColor(Ed.BAR)
                    addView(bar, LinearLayout.LayoutParams(dp(3), dp(22)))
                    addView(tv)
                }
            } else {
                tv
            }
            row.setOnClickListener {
                editor.applyBlock(kind, false)
                menu.dismiss()
                refresh()
            }
            box.addView(row)
        }

        add("Название", 24f, true, Kind.TITLE)
        add("Малый", 19f, true, Kind.SMALL)
        add("Обычный текст", 16f, false, Kind.NONE)
        add("Цитата", 16f, false, Kind.QUOTE, true)

        menu = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        menu.setBackgroundDrawable(background())
        menu.isOutsideTouchable = true
        menu.elevation = dp(10).toFloat()
        menu.showAsDropDown(anchor, 0, 0)
    }
}
