package com.focus.notes

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private class Plan(val s: Int, val en: Int, val kind: Int, val indent: Int, val checked: Boolean)

private class Snap(val md: String, val s: Int, val e: Int)

/**
 * Редактор заметки: поле ввода с оформлением (спаны).
 * Текст хранится как Markdown (см. MarkdownCodec), в поле он показан оформленным.
 * Документ всегда заканчивается символом перевода строки (курсор до него не доходит).
 */
class EditorView(context: Context) : EditText(context) {

    var onStateChanged: (() -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null
    var onImageTap: ((String) -> Unit)? = null
    var onImageLongPress: ((String) -> Unit)? = null

    /** Пока возвращает true, системная панель (вырезать/копировать/вставить) не показывается. */
    var suppressToolbar: () -> Boolean = { false }

    private var ready = false
    private var busy = false
    private var editing = false
    private var chStart = 0
    private var chBefore = 0
    private var chCount = 0
    private var enterAt = -1
    private var typingOverride: Set<Inline>? = null

    private val undoStack = ArrayList<Snap>()
    private val redoStack = ArrayList<Snap>()
    private var lastSnap = Snap("", 0, 0)
    private var pendingSnapshot = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable { commitSnapshot() }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var checkTap = -1
    private var checkMoved = false
    private var downX = 0f
    private var downY = 0f

    private var imgTap: AttachmentImageSpan? = null
    private var imgMoved = false
    private var imgLongFired = false
    private val imgLongRunnable = Runnable { fireImageLongPress() }

    /** Палец на экране: прокрутка в это время идёт от пользователя. */
    private var touching = false

    /** Когда в последний раз менялся курсор или текст: тогда прокрутка к курсору допустима. */
    private var lastCaretActivity = 0L

    private var padH = 0
    private var padTop = 0
    private var padBottom = 0

    private var actionMode: ActionMode? = null

    private val actionCb = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            if (suppressToolbar()) post { hideSystemToolbar() }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
        }
    }

    init {
        Ed.density = resources.displayMetrics.density
        setBackgroundColor(Color.TRANSPARENT)
        gravity = Gravity.TOP or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(0f, 1.12f)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        padH = Ed.dp(16f).toInt()
        padTop = padH / 2
        padBottom = padH * 4
        setPadding(padH, padTop, padH, padBottom)

        customSelectionActionModeCallback = actionCb
        customInsertionActionModeCallback = actionCb

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!busy) editing = true
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (busy) return
                chStart = start
                chBefore = before
                chCount = count
                if (before == 0 && count == 1 && s != null && start < s.length && s[start] == '\n') {
                    enterAt = start
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (!busy) handleChange()
            }
        })
        ready = true
    }

    /** Ширина текста нужна картинкам: считаем её до построения разметки. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (w > 0) Ed.contentWidth = w
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ---------------- прокрутка ----------------

    private fun markCaretActivity() {
        lastCaretActivity = SystemClock.uptimeMillis()
    }

    /**
     * Поле само возвращает прокрутку к курсору при любой перерисовке (например, когда
     * догрузилась картинка). Пока пользователь не касается экрана и не двигает курсор,
     * такие автоматические прокрутки игнорируем.
     */
    override fun scrollTo(x: Int, y: Int) {
        val recent = SystemClock.uptimeMillis() - lastCaretActivity < 1500
        if (touching || recent) super.scrollTo(x, y)
    }

    // ---------------- место под панель форматирования ----------------

    /** px > 0: освободить справа место под панель; 0: вернуть обычный отступ. */
    fun reserveRight(px: Int) {
        setPadding(padH, padTop, if (px > 0) px else padH, padBottom)
    }

    /** Убирает системную панель, сохраняя выделение. */
    fun hideSystemToolbar() {
        val mode = actionMode ?: return
        val a = selectionStart
        val b = selectionEnd
        actionMode = null
        mode.finish()
        if (a >= 0 && b >= 0 && a <= text.length && b <= text.length) setSelection(a, b)
    }

    // ---------------- вставка из буфера ----------------

    private fun selectInserted(from: Int) {
        val end = selectionEnd
        val top = max(0, text.length - 1)
        if (from >= 0 && end > from) setSelection(from.coerceIn(0, top), min(end, top))
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val from = min(selectionStart, selectionEnd)
            val result = super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
            selectInserted(from)
            return result
        }
        return super.onTextContextMenuItem(id)
    }

    /** Вставка с панели буфера клавиатуры приходит как обычный ввод: узнаём её по размеру. */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val big = text != null &&
                    (text.length >= 25 || (text.length >= 2 && text.contains('\n')))
                val from = min(selectionStart, selectionEnd)
                val ok = super.commitText(text, newCursorPosition)
                if (ok && big) selectInserted(from)
                return ok
            }
        }
    }

    // ---------------- загрузка и сохранение ----------------

    fun markdown(): String = MarkdownCodec.toMarkdown(text)

    fun loadMarkdown(md: String) {
        loadInternal(md)
        undoStack.clear()
        redoStack.clear()
        uiHandler.removeCallbacks(commitRunnable)
        pendingSnapshot = false
        lastSnap = Snap(markdown(), 0, 0)
    }

    private fun loadInternal(md: String) {
        busy = true
        try {
            setText(MarkdownCodec.parse(md), BufferType.EDITABLE)
            syncCheckStrikes()
        } finally {
            busy = false
            editing = false
        }
        typingOverride = null
        setSelection(0)
        onStateChanged?.invoke()
    }

    // ---------------- абзацы ----------------

    private fun paraStart(pos: Int): Int {
        val e = text
        var i = pos.coerceIn(0, e.length)
        while (i > 0 && e[i - 1] != '\n') i--
        return i
    }

    private fun paraEnd(pos: Int): Int {
        val e = text
        var i = pos.coerceIn(0, e.length)
        while (i < e.length && e[i] != '\n') i++
        return if (i < e.length) i + 1 else i
    }

    private fun blockAt(ps: Int): BlockSpan? {
        val e = text
        if (ps < 0 || ps >= e.length) return null
        for (b in e.getSpans(ps, ps + 1, BlockSpan::class.java)) {
            if (e.getSpanStart(b) <= ps && e.getSpanEnd(b) > ps) return b
        }
        return null
    }

    private fun paragraphHasImage(ps: Int): Boolean {
        val pe = paraEnd(ps)
        return text.getSpans(ps, pe, AttachmentImageSpan::class.java).isNotEmpty()
    }

    private fun setBlockOn(ps: Int, kind: Int, indent: Int, checked: Boolean) {
        val e = text
        if (ps < 0 || ps >= e.length) return
        val pe = paraEnd(ps)
        for (b in e.getSpans(ps, pe, BlockSpan::class.java)) e.removeSpan(b)
        if (kind != Kind.NONE) {
            e.setSpan(BlockSpan(kind, indent, checked), ps, pe, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun selectedParagraphs(): List<Int> {
        val e = text
        val a = max(0, min(selectionStart, selectionEnd))
        val b = max(0, max(selectionStart, selectionEnd))
        val res = ArrayList<Int>()
        val limit = paraStart(if (b > a) b - 1 else b)
        var ps = paraStart(a)
        while (ps < e.length) {
            res.add(ps)
            if (ps >= limit) break
            ps = paraEnd(ps)
        }
        return res
    }

    /**
     * После любой правки: ровно один BlockSpan на абзац, точно по его границам.
     * Спан считается принадлежащим абзацу, если пересекается с ним: при вводе
     * первой буквы в пустом пункте Android сдвигает спан на символ вправо,
     * и раньше пункт из-за этого терял оформление.
     */
    private fun normalizeBlocks() {
        val e = text
        val sorted = e.getSpans(0, e.length, BlockSpan::class.java).sortedBy { e.getSpanStart(it) }

        val plan = ArrayList<Plan>()
        var j = 0
        var ps = 0
        while (ps < e.length) {
            val pe = paraEnd(ps)
            while (j < sorted.size && e.getSpanEnd(sorted[j]) <= ps) j++
            if (j < sorted.size) {
                val b = sorted[j]
                if (e.getSpanStart(b) < pe && b.kind != Kind.NONE) {
                    plan.add(Plan(ps, pe, b.kind, b.indent, b.checked))
                }
            }
            ps = pe
        }

        var same = sorted.size == plan.size
        if (same) {
            for (i in plan.indices) {
                val b = sorted[i]
                val p = plan[i]
                if (e.getSpanStart(b) != p.s || e.getSpanEnd(b) != p.en ||
                    b.kind != p.kind || b.indent != p.indent || b.checked != p.checked
                ) {
                    same = false
                    break
                }
            }
        }
        if (same) return

        for (b in sorted) e.removeSpan(b)
        for (p in plan) {
            e.setSpan(BlockSpan(p.kind, p.indent, p.checked), p.s, p.en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Зачёркивание отмеченных пунктов чек-листа вычисляется из состояния галочки. */
    private fun syncCheckStrikes() {
        val e = text
        val want = ArrayList<IntArray>()
        for (b in e.getSpans(0, e.length, BlockSpan::class.java)) {
            if (b.kind == Kind.CHECK && b.checked) {
                val s = e.getSpanStart(b)
                var en = e.getSpanEnd(b)
                if (en > s && e[en - 1] == '\n') en--
                if (en > s) want.add(intArrayOf(s, en))
            }
        }
        want.sortBy { it[0] }
        val have = e.getSpans(0, e.length, CheckStrikeSpan::class.java).sortedBy { e.getSpanStart(it) }

        var same = have.size == want.size
        if (same) {
            for (i in want.indices) {
                if (e.getSpanStart(have[i]) != want[i][0] || e.getSpanEnd(have[i]) != want[i][1]) {
                    same = false
                    break
                }
            }
        }
        if (same) return

        for (h in have) e.removeSpan(h)
        for (w in want) e.setSpan(CheckStrikeSpan(), w[0], w[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Символ-заменитель без картинки (например, после вставки) убираем. */
    private fun stripOrphanObjects() {
        val e = text
        var i = e.length - 1
        while (i >= 0) {
            if (e[i] == '\uFFFC' &&
                e.getSpans(i, i + 1, AttachmentImageSpan::class.java).isEmpty()
            ) {
                e.delete(i, i + 1)
            }
            i--
        }
    }

    // ---------------- реакция на ввод ----------------

    private fun handleChange() {
        markCaretActivity()
        busy = true
        try {
            val e = text
            stripOrphanObjects()
            if (e.isEmpty() || e[e.length - 1] != '\n') e.append("\n")
            applyTypingStyle()
            normalizeBlocks()
            if (enterAt >= 0) handleEnter(enterAt)
            syncCheckStrikes()
        } finally {
            enterAt = -1
            busy = false
            editing = false
        }
        clampSelection()
        scheduleSnapshot()
        onContentChanged?.invoke()
        onStateChanged?.invoke()
    }

    private fun handleEnter(idx: Int) {
        val e = text
        if (idx < 0 || idx >= e.length - 1 || e[idx] != '\n') return

        val ps1 = paraStart(idx)
        val ps2 = idx + 1
        val atStart = ps1 == idx
        val orig = if (atStart) blockAt(ps2) else blockAt(ps1)
        val k = orig?.kind ?: Kind.NONE
        val indent = orig?.indent ?: 0
        val secondEmpty = e[ps2] == '\n'

        // Enter на пустом пункте списка или цитаты: выходим из списка.
        if (atStart && secondEmpty && (Kind.isList(k) || k == Kind.QUOTE)) {
            e.delete(idx, idx + 1)
            normalizeBlocks()
            setBlockOn(ps1, Kind.NONE, 0, false)
            setSelection(ps1)
            return
        }

        when {
            k == Kind.TITLE || k == Kind.SMALL -> {
                if (!atStart) setBlockOn(ps2, Kind.NONE, 0, false)
            }
            atStart && k != Kind.NONE -> setBlockOn(ps1, k, indent, false)
            !atStart && k == Kind.CHECK -> setBlockOn(ps2, Kind.CHECK, indent, false)
        }
    }

    private fun clampSelection() {
        val len = text.length
        if (len == 0) return
        val s = selectionStart
        val en = selectionEnd
        if (s < 0 || en < 0) return
        if (s == len || en == len) setSelection(min(s, len - 1), min(en, len - 1))
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        markCaretActivity()
        if (!ready || busy || selStart < 0 || selEnd < 0) return
        if (editing) return
        val len = text.length
        if (len > 0 && (selStart == len || selEnd == len)) {
            setSelection(min(selStart, len - 1), min(selEnd, len - 1))
            return
        }
        typingOverride = null
        onStateChanged?.invoke()
    }

    // ---------------- оформление текста ----------------

    private fun stylesAtChar(pos: Int): Set<Inline> {
        val e = text
        val res = HashSet<Inline>()
        if (pos < 0 || pos >= e.length) return res
        for (sp in e.getSpans(pos, pos + 1, Any::class.java)) {
            if (!ours(e, sp)) continue
            if (e.getSpanStart(sp) <= pos && e.getSpanEnd(sp) > pos) {
                for (t in Inline.values()) if (spanMatches(sp, t)) res.add(t)
            }
        }
        return res
    }

    private fun inherited(pos: Int): Set<Inline> {
        val e = text
        if (pos <= 0 || pos > e.length) return emptySet()
        if (e[pos - 1] == '\n') return emptySet()
        return stylesAtChar(pos - 1)
    }

    private fun applyTypingStyle() {
        if (chCount <= 0) return
        val e = text
        val s = chStart
        val en = min(chStart + chCount, e.length)
        if (s < 0 || s >= en) return
        if (chCount == 1 && e[s] == '\n') return
        val want = typingOverride ?: inherited(s)
        for (t in Inline.values()) {
            if (want.contains(t)) addInline(t, s, en) else removeInline(t, s, en)
        }
    }

    private fun addInline(t: Inline, s: Int, en: Int) {
        val e = text
        var a = s
        var b = en
        for (sp in e.getSpans(max(0, s - 1), min(e.length, en + 1), Any::class.java)) {
            if (!ours(e, sp) || !spanMatches(sp, t)) continue
            val ss = e.getSpanStart(sp)
            val se = e.getSpanEnd(sp)
            if (se >= s && ss <= en) {
                a = min(a, ss)
                b = max(b, se)
                e.removeSpan(sp)
            }
        }
        e.setSpan(newSpan(t), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun removeInline(t: Inline, s: Int, en: Int) {
        val e = text
        for (sp in e.getSpans(s, en, Any::class.java)) {
            if (!ours(e, sp) || !spanMatches(sp, t)) continue
            val ss = e.getSpanStart(sp)
            val se = e.getSpanEnd(sp)
            e.removeSpan(sp)
            if (ss < s) e.setSpan(newSpan(t), ss, s, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (se > en) e.setSpan(newSpan(t), en, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun covers(t: Inline, a: Int, b: Int): Boolean {
        val e = text
        val cover = BooleanArray(b - a)
        for (sp in e.getSpans(a, b, Any::class.java)) {
            if (!ours(e, sp) || !spanMatches(sp, t)) continue
            val from = max(e.getSpanStart(sp), a)
            val to = min(e.getSpanEnd(sp), b)
            for (k in from until to) cover[k - a] = true
        }
        for (k in a until b) {
            if (e[k] != '\n' && !cover[k - a]) return false
        }
        return true
    }

    // ---------------- команды панели ----------------

    private fun beforeCommand() {
        if (pendingSnapshot) commitSnapshot()
    }

    private fun afterCommand() {
        scheduleSnapshot()
        onContentChanged?.invoke()
        onStateChanged?.invoke()
    }

    fun inlineActive(t: Inline): Boolean {
        val a = min(selectionStart, selectionEnd)
        val b = max(selectionStart, selectionEnd)
        if (a < 0) return false
        if (a == b) return (typingOverride ?: inherited(a)).contains(t)
        return covers(t, a, b)
    }

    fun toggleInline(t: Inline) {
        val a = min(selectionStart, selectionEnd)
        val b = max(selectionStart, selectionEnd)
        if (a < 0) return
        if (a == b) {
            val cur = typingOverride ?: inherited(a)
            typingOverride = if (cur.contains(t)) cur - t else cur + t
            onStateChanged?.invoke()
            return
        }
        beforeCommand()
        if (covers(t, a, b)) removeInline(t, a, b) else addInline(t, a, b)
        afterCommand()
    }

    fun currentKind(): Int {
        val a = max(0, selectionStart)
        return blockAt(paraStart(a))?.kind ?: Kind.NONE
    }

    /** Отступы имеют смысл только для пунктов списка и чек-листа. */
    fun indentApplicable(): Boolean {
        if (selectionStart < 0) return false
        return selectedParagraphs().any { Kind.isList(blockAt(it)?.kind ?: Kind.NONE) }
    }

    fun applyBlock(kind: Int, toggle: Boolean) {
        beforeCommand()
        normalizeBlocks()
        val starts = selectedParagraphs().filter { !paragraphHasImage(it) }
        if (starts.isEmpty()) return
        val allSame = starts.all { (blockAt(it)?.kind ?: Kind.NONE) == kind }
        val target = if (toggle && allSame) Kind.NONE else kind
        for (ps in starts) {
            val old = blockAt(ps)
            val indent = if (Kind.isList(target)) (old?.indent ?: 0) else 0
            val checked = target == Kind.CHECK && old != null && old.kind == Kind.CHECK && old.checked
            setBlockOn(ps, target, indent, checked)
        }
        syncCheckStrikes()
        afterCommand()
    }

    fun changeIndent(delta: Int) {
        beforeCommand()
        normalizeBlocks()
        for (ps in selectedParagraphs()) {
            if (paragraphHasImage(ps)) continue
            val b = blockAt(ps) ?: continue
            if (!Kind.isList(b.kind)) continue
            if (delta < 0 && b.indent == 0) {
                setBlockOn(ps, Kind.NONE, 0, false)
            } else {
                val n = (b.indent + delta).coerceIn(0, 3)
                if (n != b.indent) setBlockOn(ps, b.kind, n, b.checked)
            }
        }
        syncCheckStrikes()
        afterCommand()
    }

    fun clearFormatting() {
        beforeCommand()
        normalizeBlocks()
        val a = max(0, min(selectionStart, selectionEnd))
        val b = max(0, max(selectionStart, selectionEnd))
        val rs: Int
        val re: Int
        if (b > a) {
            rs = a
            re = b
        } else {
            rs = paraStart(a)
            re = paraEnd(a)
        }
        if (rs < re) {
            for (t in Inline.values()) removeInline(t, rs, re)
        }
        for (ps in selectedParagraphs()) setBlockOn(ps, Kind.NONE, 0, false)
        syncCheckStrikes()
        typingOverride = if (a == b) emptySet() else null
        afterCommand()
    }

    private fun toggleCheckAt(ps: Int) {
        val b = blockAt(ps) ?: return
        if (b.kind != Kind.CHECK) return
        beforeCommand()
        setBlockOn(ps, Kind.CHECK, b.indent, !b.checked)
        syncCheckStrikes()
        afterCommand()
    }

    // ---------------- картинки ----------------

    /**
     * Вставляет картинки отдельными строками под текущим абзацем
     * (или в текущей пустой строке) и оставляет под ними пустую строку для текста.
     */
    fun insertImages(names: List<String>) {
        if (names.isEmpty()) return
        beforeCommand()
        var caret = 0
        busy = true
        try {
            val e = text
            val pos = max(0, min(selectionStart, selectionEnd))
            val ps = paraStart(pos)
            val pe = paraEnd(pos)
            val emptyPara = pe - ps == 1 && e[ps] == '\n'
            val at = if (emptyPara) ps else pe

            if (emptyPara) {
                for (b in e.getSpans(ps, pe, BlockSpan::class.java)) e.removeSpan(b)
            }

            val sb = StringBuilder()
            for (n in names) sb.append('\uFFFC').append('\n')
            if (!emptyPara) sb.append('\n')
            e.insert(at, sb)

            var k = at
            for (name in names) {
                e.setSpan(AttachmentImageSpan(name), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                k += 2
            }
            for (t in Inline.values()) removeInline(t, at, at + sb.length)
            caret = k

            normalizeBlocks()
            syncCheckStrikes()
        } finally {
            busy = false
            editing = false
        }
        typingOverride = null
        setSelection(caret.coerceIn(0, max(0, text.length - 1)))
        afterCommand()
    }

    fun removeImage(name: String) {
        beforeCommand()
        busy = true
        try {
            val e = text
            val spans = e.getSpans(0, e.length, AttachmentImageSpan::class.java)
                .filter { it.name == name }
                .sortedByDescending { e.getSpanStart(it) }
            for (sp in spans) {
                val s = e.getSpanStart(sp)
                if (s < 0) continue
                val ps = paraStart(s)
                val pe = paraEnd(s)
                if (pe - ps == 2 && s == ps) e.delete(ps, pe) else e.delete(s, s + 1)
            }
            if (e.isEmpty() || e[e.length - 1] != '\n') e.append("\n")
            normalizeBlocks()
            syncCheckStrikes()
        } finally {
            busy = false
            editing = false
        }
        clampSelection()
        afterCommand()
    }

    private fun imageAt(x: Float, y: Float): AttachmentImageSpan? {
        val lay = layout ?: return null
        val e = text
        val yy = y - totalPaddingTop + scrollY
        if (yy < 0f || yy > lay.height) return null
        val line = lay.getLineForVertical(yy.toInt())
        val ls = lay.getLineStart(line)
        val le = lay.getLineEnd(line)
        return e.getSpans(ls, le, AttachmentImageSpan::class.java).firstOrNull()
    }

    private fun cancelSuper(ev: MotionEvent) {
        val c = MotionEvent.obtain(ev)
        c.action = MotionEvent.ACTION_CANCEL
        super.onTouchEvent(c)
        c.recycle()
    }

    private fun fireImageLongPress() {
        val sp = imgTap ?: return
        imgLongFired = true
        val now = SystemClock.uptimeMillis()
        val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.onTouchEvent(c)
        c.recycle()
        onImageLongPress?.invoke(sp.name)
    }

    // ---------------- отмена и возврат ----------------

    private fun scheduleSnapshot() {
        pendingSnapshot = true
        uiHandler.removeCallbacks(commitRunnable)
        uiHandler.postDelayed(commitRunnable, 700)
    }

    private fun commitSnapshot() {
        uiHandler.removeCallbacks(commitRunnable)
        pendingSnapshot = false
        val md = markdown()
        if (md == lastSnap.md) return
        undoStack.add(lastSnap)
        if (undoStack.size > 100) undoStack.removeAt(0)
        lastSnap = Snap(md, selectionStart, selectionEnd)
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty() || pendingSnapshot

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo() {
        commitSnapshot()
        if (undoStack.isEmpty()) return
        redoStack.add(lastSnap)
        restore(undoStack.removeAt(undoStack.size - 1))
    }

    fun redo() {
        commitSnapshot()
        if (redoStack.isEmpty()) return
        undoStack.add(lastSnap)
        restore(redoStack.removeAt(redoStack.size - 1))
    }

    private fun restore(sn: Snap) {
        loadInternal(sn.md)
        lastSnap = sn
        val top = max(0, text.length - 1)
        setSelection(sn.s.coerceIn(0, top), sn.e.coerceIn(0, top))
        onContentChanged?.invoke()
        onStateChanged?.invoke()
    }

    // ---------------- нажатия: чекбокс и картинки ----------------

    private fun checkboxAt(x: Float, y: Float): Int {
        val lay = layout ?: return -1
        val e = text
        val yy = y - totalPaddingTop + scrollY
        if (yy < 0f) return -1
        val line = lay.getLineForVertical(yy.toInt())
        val ls = lay.getLineStart(line)
        if (ls >= e.length) return -1
        if (ls > 0 && e[ls - 1] != '\n') return -1
        val b = blockAt(ls) ?: return -1
        if (b.kind != Kind.CHECK) return -1
        val left = totalPaddingLeft - scrollX + b.indent * Ed.dp(22f)
        return if (x >= left && x <= left + Ed.dp(32f)) ls else -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        val result = handleTouch(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            touching = false
        }
        return result
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ps = checkboxAt(ev.x, ev.y)
                if (ps >= 0) {
                    checkTap = ps
                    checkMoved = false
                    downX = ev.x
                    downY = ev.y
                    return true
                }
                checkTap = -1

                val im = imageAt(ev.x, ev.y)
                uiHandler.removeCallbacks(imgLongRunnable)
                if (im != null) {
                    imgTap = im
                    imgMoved = false
                    imgLongFired = false
                    downX = ev.x
                    downY = ev.y
                    uiHandler.postDelayed(
                        imgLongRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                } else {
                    imgTap = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (checkTap >= 0) {
                    if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) checkMoved = true
                    return true
                }
                if (imgTap != null) {
                    if (imgLongFired) return true
                    if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) {
                        imgMoved = true
                        uiHandler.removeCallbacks(imgLongRunnable)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (checkTap >= 0) {
                    val ps = checkTap
                    checkTap = -1
                    if (!checkMoved) toggleCheckAt(ps)
                    return true
                }
                val sp = imgTap
                if (sp != null) {
                    uiHandler.removeCallbacks(imgLongRunnable)
                    imgTap = null
                    if (imgLongFired) {
                        imgLongFired = false
                        return true
                    }
                    if (!imgMoved) {
                        cancelSuper(ev)
                        onImageTap?.invoke(sp.name)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (checkTap >= 0) {
                    checkTap = -1
                    return true
                }
                uiHandler.removeCallbacks(imgLongRunnable)
                imgTap = null
                imgLongFired = false
            }
        }
        return super.onTouchEvent(ev)
    }
}
