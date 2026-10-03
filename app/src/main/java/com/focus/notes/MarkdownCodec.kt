package com.focus.notes

import android.text.SpannableStringBuilder
import android.text.Spanned
import kotlin.math.max
import kotlin.math.min

/**
 * Перевод между Markdown и оформленным текстом редактора.
 * Правила: один абзац = одна строка файла.
 *   # Название, ## Малый, > Цитата,
 *   - пункт, 1. пункт, - [ ] / - [x] чек-лист (вложенность: 4 пробела на уровень),
 *   **жирный**, *курсив*, <u>подчёркнутый</u>, ~~зачёркнутый~~, ==маркер==.
 */
object MarkdownCodec {

    private const val T_BOLD = 1
    private const val T_ITALIC = 2
    private const val T_STRIKE = 3
    private const val T_MARK = 4
    private const val T_UOPEN = 5
    private const val T_UCLOSE = 6

    private const val PUNCT = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val NUM_PREFIX = Regex("^\\d+\\. ")
    private val NUM_BARE = Regex("^\\d+\\.$")
    private val HEAD_SMALL = Regex("^#{2,6} ")
    private val ORDER = listOf(
        Inline.BOLD, Inline.ITALIC, Inline.UNDERLINE, Inline.STRIKE, Inline.MARK
    )

    private class Parsed(val kind: Int, val indent: Int, val checked: Boolean, val rest: String)
    private class Tok(val id: Int, val lit: String)
    private class Rng(val type: Inline, val s: Int, val e: Int)
    private class InlineResult(val text: String, val ranges: List<Rng>)

    // ---------------- Markdown -> редактор ----------------

    fun parse(md: String): SpannableStringBuilder {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        if (lines.size > 1 && lines[lines.size - 1].isEmpty()) {
            lines.removeAt(lines.size - 1)
        }

        val out = SpannableStringBuilder()
        for (line in lines) {
            val p = if (line.startsWith("\\") && parseBlock(line.substring(1)).kind != Kind.NONE) {
                Parsed(Kind.NONE, 0, false, line.substring(1))
            } else {
                parseBlock(line)
            }

            val start = out.length
            val inl = parseInline(p.rest)
            out.append(inl.text)
            out.append('\n')
            val end = out.length

            for (r in inl.ranges) {
                out.setSpan(newSpan(r.type), start + r.s, start + r.e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (p.kind != Kind.NONE) {
                out.setSpan(
                    BlockSpan(p.kind, p.indent, p.checked),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return out
    }

    private fun parseBlock(line: String): Parsed {
        var leading = 0
        while (leading < line.length && line[leading] == ' ') leading++
        val body = line.substring(leading)
        val indent = min(3, (leading + 3) / 4)

        fun item(kind: Int, checked: Boolean, cut: Int): Parsed =
            Parsed(kind, indent, checked, body.substring(min(cut, body.length)))

        if (body.startsWith("- [ ] ")) return item(Kind.CHECK, false, 6)
        if (body == "- [ ]") return item(Kind.CHECK, false, 5)
        if (body.startsWith("- [x] ") || body.startsWith("- [X] ")) return item(Kind.CHECK, true, 6)
        if (body == "- [x]" || body == "- [X]") return item(Kind.CHECK, true, 5)
        if (body.startsWith("- ") || body.startsWith("* ") || body.startsWith("+ ")) {
            return item(Kind.BULLET, false, 2)
        }
        if (body == "-") return item(Kind.BULLET, false, 1)
        val num = NUM_PREFIX.find(body)
        if (num != null) return item(Kind.NUMBER, false, num.value.length)
        if (NUM_BARE.containsMatchIn(body)) return item(Kind.NUMBER, false, body.length)

        if (leading == 0) {
            if (body.startsWith("# ")) return Parsed(Kind.TITLE, 0, false, body.substring(2))
            val small = HEAD_SMALL.find(body)
            if (small != null) return Parsed(Kind.SMALL, 0, false, body.substring(small.value.length))
            if (body.startsWith("> ")) return Parsed(Kind.QUOTE, 0, false, body.substring(2))
            if (body == ">") return Parsed(Kind.QUOTE, 0, false, "")
        }
        return Parsed(Kind.NONE, 0, false, line)
    }

    private fun typeOf(id: Int): Inline = when (id) {
        T_BOLD -> Inline.BOLD
        T_ITALIC -> Inline.ITALIC
        T_STRIKE -> Inline.STRIKE
        T_MARK -> Inline.MARK
        else -> Inline.UNDERLINE
    }

    private fun parseInline(s: String): InlineResult {
        val toks = ArrayList<Tok>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                toks.add(Tok(0, buf.toString()))
                buf.setLength(0)
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && PUNCT.indexOf(s[i + 1]) >= 0) {
                buf.append(s[i + 1])
                i += 2
                continue
            }
            if (s.startsWith("**", i)) { flush(); toks.add(Tok(T_BOLD, "**")); i += 2; continue }
            if (s.startsWith("~~", i)) { flush(); toks.add(Tok(T_STRIKE, "~~")); i += 2; continue }
            if (s.startsWith("==", i)) { flush(); toks.add(Tok(T_MARK, "==")); i += 2; continue }
            if (s.startsWith("<u>", i)) { flush(); toks.add(Tok(T_UOPEN, "<u>")); i += 3; continue }
            if (s.startsWith("</u>", i)) { flush(); toks.add(Tok(T_UCLOSE, "</u>")); i += 4; continue }
            if (c == '*') { flush(); toks.add(Tok(T_ITALIC, "*")); i += 1; continue }
            buf.append(c)
            i++
        }
        flush()

        // Непарные маркеры считаем обычным текстом.
        val paired = BooleanArray(toks.size)
        for (id in intArrayOf(T_BOLD, T_ITALIC, T_STRIKE, T_MARK)) {
            var open = -1
            for (k in toks.indices) {
                if (toks[k].id != id) continue
                if (open < 0) {
                    open = k
                } else {
                    paired[open] = true
                    paired[k] = true
                    open = -1
                }
            }
        }
        var uopen = -1
        for (k in toks.indices) {
            if (toks[k].id == T_UOPEN) {
                if (uopen < 0) uopen = k
            } else if (toks[k].id == T_UCLOSE && uopen >= 0) {
                paired[uopen] = true
                paired[k] = true
                uopen = -1
            }
        }

        val sb = StringBuilder()
        val ranges = ArrayList<Rng>()
        val openAt = HashMap<Inline, Int>()
        for (k in toks.indices) {
            val t = toks[k]
            if (t.id == 0 || !paired[k]) {
                sb.append(t.lit)
                continue
            }
            val type = typeOf(t.id)
            val isClose = when (t.id) {
                T_UOPEN -> false
                T_UCLOSE -> true
                else -> openAt.containsKey(type)
            }
            if (!isClose) {
                openAt[type] = sb.length
            } else {
                val a = openAt.remove(type) ?: sb.length
                if (sb.length > a) ranges.add(Rng(type, a, sb.length))
            }
        }
        return InlineResult(sb.toString(), ranges)
    }

    // ---------------- редактор -> Markdown ----------------

    fun toMarkdown(e: Spanned): String {
        val n = e.length
        val blocks = HashMap<Int, BlockSpan>()
        for (b in e.getSpans(0, n, BlockSpan::class.java)) {
            blocks[e.getSpanStart(b)] = b
        }

        val out = StringBuilder()
        var ps = 0
        while (ps < n) {
            var le = ps
            while (le < n && e[le] != '\n') le++

            val b = blocks[ps]
            val kind = b?.kind ?: Kind.NONE
            val pad = " ".repeat((b?.indent ?: 0) * 4)
            val inl = inlineMd(e, ps, le)

            val line = when (kind) {
                Kind.TITLE -> "# $inl"
                Kind.SMALL -> "## $inl"
                Kind.QUOTE -> "> $inl"
                Kind.BULLET -> pad + "- " + escapeBracket(inl)
                Kind.NUMBER -> pad + "1. " + inl
                Kind.CHECK -> pad + (if (b != null && b.checked) "- [x] " else "- [ ] ") + inl
                else -> if (parseBlock(inl).kind != Kind.NONE) "\\" + inl else inl
            }
            out.append(line).append('\n')

            ps = if (le < n) le + 1 else le
        }
        return out.toString()
    }

    private fun escapeBracket(s: String): String =
        if (s.startsWith("[ ]") || s.startsWith("[x]") || s.startsWith("[X]")) "\\" + s else s

    private fun openMarker(t: Inline): String = when (t) {
        Inline.BOLD -> "**"
        Inline.ITALIC -> "*"
        Inline.UNDERLINE -> "<u>"
        Inline.STRIKE -> "~~"
        Inline.MARK -> "=="
    }

    private fun closeMarker(t: Inline): String =
        if (t == Inline.UNDERLINE) "</u>" else openMarker(t)

    private fun escapeChar(e: Spanned, idx: Int, ps: Int, le: Int): String {
        val c = e[idx]
        return when (c) {
            '\\', '*', '<' -> "\\" + c
            '~', '=' -> {
                val dbl = (idx + 1 < le && e[idx + 1] == c) || (idx > ps && e[idx - 1] == c)
                if (dbl) "\\" + c else c.toString()
            }
            else -> c.toString()
        }
    }

    private fun inlineMd(e: Spanned, ps: Int, le: Int): String {
        val len = le - ps
        if (len <= 0) return ""

        val masks = IntArray(len)
        for (sp in e.getSpans(ps, le, Any::class.java)) {
            if (!ours(e, sp)) continue
            val bits = inlineBits(sp)
            if (bits == 0) continue
            val a = max(e.getSpanStart(sp), ps) - ps
            val b = min(e.getSpanEnd(sp), le) - ps
            for (k in a until b) masks[k] = masks[k] or bits
        }

        val sb = StringBuilder()
        val open = ArrayList<Inline>()
        for (i in 0..len) {
            val mask = if (i < len) masks[i] else 0

            var cut = -1
            for (k in open.indices) {
                if ((mask and open[k].bit) == 0) {
                    cut = k
                    break
                }
            }
            if (cut >= 0) {
                val keep = ArrayList<Inline>(open.subList(0, cut))
                val reopen = ArrayList<Inline>()
                for (k in cut + 1 until open.size) {
                    if ((mask and open[k].bit) != 0) reopen.add(open[k])
                }
                for (k in open.size - 1 downTo cut) sb.append(closeMarker(open[k]))
                open.clear()
                open.addAll(keep)
                for (t in reopen) {
                    sb.append(openMarker(t))
                    open.add(t)
                }
            }
            for (t in ORDER) {
                if ((mask and t.bit) != 0 && !open.contains(t)) {
                    sb.append(openMarker(t))
                    open.add(t)
                }
            }
            if (i < len) sb.append(escapeChar(e, ps + i, ps, le))
        }
        return sb.toString()
    }
}
