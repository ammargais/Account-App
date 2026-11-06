package com.ammargais.accountapp.io

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.ammargais.accountapp.core.*
import java.io.OutputStream
import java.math.BigInteger

/** One renderer for the native PDF and visual tests; Arabic shaping uses Android text layout. */
object StatementPdf {
    fun write(out: OutputStream, ledger: Ledger, account: Account, currency: Currency?, from: String, to: String) {
        val doc = PdfDocument(); var page: PdfDocument.Page? = null
        try {
            render(ledger, account, currency, from, to,
                newPage = { n -> doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()).also { page = it }.canvas },
                finishPage = { doc.finishPage(page!!); page = null })
            doc.writeTo(out)
        } finally { if (page != null) doc.finishPage(page!!); doc.close() }
    }
    internal fun render(ledger: Ledger, account: Account, currency: Currency?, from: String, to: String,
        newPage: (Int) -> Canvas, finishPage: () -> Unit) {
        require(from <= to)
        var canvas: Canvas? = null; var number = 0; var y = 0f; var activeCurrency: Currency? = null
        val ink = Color.rgb(24, 47, 43); val green = Color.rgb(18, 103, 91)
        val xs = listOf(40f, 165f, 290f, 465f); val widths = listOf(125, 125, 175, 90)
        fun ltr(s: String) = "\u2066$s\u2069"
        fun text(value: String, width: Int = 515, size: Float = 10f, bold: Boolean = false, color: Int = ink): StaticLayout {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL) }
            return StaticLayout.Builder.obtain(value, 0, value.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL).setLineSpacing(2f, 1f).setIncludePad(true).build()
        }
        fun draw(layout: StaticLayout, x: Float = 40f, top: Float = y) { canvas!!.save(); canvas!!.translate(x, top); layout.draw(canvas!!); canvas!!.restore() }
        fun line(value: String, size: Float = 10f, bold: Boolean = false, color: Int = ink) {
            val layout = text(value, size = size, bold = bold, color = color); draw(layout); y += layout.height + 7
        }
        fun header() {
            canvas!!.drawRect(40f, y, 555f, y + 28f, Paint().apply { color = green })
            listOf("الرصيد", "الحركة", "البيان والمرجع", "التاريخ").forEachIndexed { i, label -> draw(text(label, widths[i] - 14, 10f, true, Color.WHITE), xs[i] + 7, y + 5) }
            y += 28
        }
        fun finish() {
            canvas?.let { c ->
                c.drawLine(40f, 798f, 555f, 798f, Paint().apply { color = Color.LTGRAY })
                c.drawText(number.toString(), 40f, 815f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = ink })
                finishPage()
            }; canvas = null
        }
        fun start() {
            finish(); number++; canvas = newPage(number); y = 28f
            line("دفتر • كشف حساب", 20f, true, green); line(account.name, 14f, true)
            line("الفترة: ${if (from == "0001-01-01") "من البداية" else ltr(from)} — ${if (to == "9999-12-31") "حتى الآن" else ltr(to)}")
            activeCurrency?.let { line("${it.title} • ${it.name} • تابع", bold = true, color = green); header() }
        }
        start(); line("لنا: مستحق لصاحب الدفتر • علينا: مستحق لصاحب الحساب • كل عملة مستقلة.", 9f)
        val currencies = currency?.let { listOf(it) } ?: Currency.entries.filter { c -> ledger.entries.any { e -> e.lines.any { it.accountId == account.id && it.currency == c } } }.ifEmpty { listOf(Currency.AED) }
        currencies.forEach { c ->
            activeCurrency = null
            if (y + 120 > 780f) start()
            line("${c.title} • ${c.name}", 12f, true, green)
            val opening = ledger.entries.filter { it.date < from }.flatMap { it.lines }.filter { it.accountId == account.id && it.currency == c }.fold(BigInteger.ZERO) { sum, l -> sum + l.units }
            line("رصيد بداية الفترة: ${ltr(Money.format(opening.abs(), c) + " " + c.name)} • ${Statement.side(opening)}", bold = true)
            activeCurrency = c; header()
            val rows = ledger.rows(account.id, c, from, to)
            if (rows.isEmpty()) line("لا توجد حركات خلال الفترة.")
            rows.forEachIndexed { index, row ->
                val e = row.entry
                val description = buildString {
                    append(e.note)
                    if (e.reference.isNotBlank()) append("\nمرجع: ${e.reference}")
                    e.payment?.let { p -> append("\nدفع: ${ltr(Money.format(p.units, p.currency) + " " + p.currency.name)}")
                        if (p.currency != c) append("\n${ltr("1 ${c.name} = ${Money.rate(row.line.units.abs(), c, p.units, p.currency)} ${p.currency.name}")}") }
                    e.dueDate?.let { append("\nاستحقاق: ${ltr(it)}") }
                    if (e.kind == EntryKind.REVERSAL) append("\nعكس: ${e.reverses?.take(8)}")
                }
                val cells = listOf(
                    "${ltr(Money.format(row.balance.abs(), c))}\n${c.name} • ${Statement.side(row.balance)}",
                    "${ltr(Money.format(row.line.units.abs(), c))}\n${c.name} • ${if (row.line.units.signum() > 0) "مدين" else "دائن"}",
                    description, "${ltr(e.date)}\n${ltr(e.id.take(8))}")
                    .mapIndexed { i, value -> text(value, widths[i] - 14, if (i < 2) 9f else 9.5f, i < 2) }
                val height = cells.maxOf { it.height } + 16f
                if (y + height > 780f) start()
                canvas!!.drawRect(40f, y, 555f, y + height, Paint().apply { color = if (index % 2 == 0) Color.rgb(241, 247, 245) else Color.WHITE })
                cells.forEachIndexed { i, layout -> draw(layout, xs[i] + 7, y + 8) }
                y += height
                canvas!!.drawLine(40f, y, 555f, y, Paint().apply { color = Color.rgb(220, 232, 228) })
            }
            if (y + 45 > 780f) start()
            y += 10
            val closing = ledger.balance(account.id, c, to)
            line("رصيد نهاية الفترة: ${ltr(Money.format(closing.abs(), c) + " " + c.name)} • ${Statement.side(closing)}", 11f, true, green)
            y += 16
        }
        finish()
    }
}
