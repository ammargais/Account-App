package com.ammargais.accountapp.io

import com.ammargais.accountapp.core.*
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal OOXML workbook; user text is always inline text and can never become a formula. */
object StatementXlsx {
    private fun xml(s: String) = buildString { s.forEach { c ->
        when(c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if(c >= ' ' || c == '\n' || c == '\t' || c == '\r') append(c) }
    } }
    fun write(out: OutputStream, ledger: Ledger, account: Account, currency: Currency?, from: String, to: String) {
        val rows = Statement.table(ledger, account, currency, from, to)
        ZipOutputStream(out).use { zip ->
            fun file(path: String, content: String) { zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            file("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            file("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            file("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="كشف الحساب" sheetId="1" r:id="rId1"/><sheet name="ملخص" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            file("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""")
            fun sheet(data: List<List<String>>, monetary: Set<Int>): String = buildString {
                append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="16" width="23" customWidth="1"/></cols><sheetData>""")
                data.forEachIndexed { r, row ->
                    append("<row r=\"${r+1}\">")
                    row.forEachIndexed { c, value ->
                        val ref = "${('A'.code+c).toChar()}${r+1}"
                        // Excel has only 15 significant digits; retain larger amounts as exact text.
                        val numeric = r > 0 && c in monetary && value.matches(Regex("[0-9]+(\\.[0-9]+)?")) && value.filter(Char::isDigit).trimStart('0').length <= 15
                        if(numeric) append("<c r=\"$ref\"><v>$value</v></c>")
                        else append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            file("xl/worksheets/sheet1.xml", sheet(rows, setOf(6,7,8,13)))
            val summary = listOf(listOf("الحساب", account.name), listOf("من", from), listOf("إلى", to),
                listOf("الملخص", Statement.convertedSummary(ledger, account, to) ?: "عملة الملخص غير محددة"),
                listOf("ملاحظة", "الحركات الأصلية بعملاتها. الأرقام التي تتجاوز دقة Excel محفوظة كنص لحماية قيمتها."))
            file("xl/worksheets/sheet2.xml", sheet(summary, emptySet()))
        }
    }
}
