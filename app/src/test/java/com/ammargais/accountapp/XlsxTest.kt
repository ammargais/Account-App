package com.ammargais.accountapp

import com.ammargais.accountapp.core.*
import com.ammargais.accountapp.io.StatementXlsx
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.util.zip.ZipInputStream

class XlsxTest {
    @Test fun workbookPreservesArabicLargeValuesAndUntrustedText() {
        val currency=Currency("UNIT","عملة تجريبية",3)
        val account=Account("a","=HYPERLINK(\"example\")",summaryCurrency="UNIT")
        val ledger=Ledger(listOf(account),currencies=listOf(currency)).add(Ledger.movement("a",currency,BigInteger("999999999999999999"),true,"2026-11-01","مبلغ دقيق & مرجع <1>"))
        val bytes=ByteArrayOutputStream().also { StatementXlsx.write(it,ledger,account,null,"2026-11-01","2026-11-06") }.toByteArray()
        File("build/xlsx-preview/statement.xlsx").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val files=mutableMapOf<String,String>()
        ZipInputStream(bytes.inputStream()).use { zip -> var entry=zip.nextEntry; while(entry!=null) { files[entry.name]=zip.readBytes().toString(Charsets.UTF_8);entry=zip.nextEntry } }
        assertTrue(files.containsKey("[Content_Types].xml"));assertTrue(files.containsKey("xl/workbook.xml"))
        val sheet=files.getValue("xl/worksheets/sheet1.xml")
        assertFalse(sheet.contains("<f>"));assertTrue(sheet.contains("999999999999999.999"))
        assertTrue(sheet.contains("&amp;"));assertTrue(sheet.contains("&lt;1&gt;"));assertTrue(sheet.contains("rightToLeft=\"1\""))
    }
}
