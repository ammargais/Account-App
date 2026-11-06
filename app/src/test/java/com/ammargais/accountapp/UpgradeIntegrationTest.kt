package com.ammargais.accountapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ammargais.accountapp.core.*
import com.ammargais.accountapp.data.LedgerDb
import com.ammargais.accountapp.io.StatementPdf
import com.ammargais.accountapp.io.Attachments
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpgradeIntegrationTest {
    @Test fun loadsV1DatabaseWithoutRewritingHistoricalRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("v1-upgrade.db")
        val db = LedgerDb(context, "v1-upgrade.db")
        val a = Account("old", "حساب قديم")
        val e = Ledger.movement(a.id, Currency.USD, BigInteger("12345"), true, "2026-11-01", "رصيد سابق")
        val accountJson = BackupCodec.account(a).apply { remove("group"); remove("favorite") }.toString()
        val entryJson = BackupCodec.entry(e).apply { listOf("action", "dueDate", "payment", "allocation", "attachments").forEach { remove(it) } }.toString()
        db.writableDatabase.execSQL("INSERT INTO accounts(id,payload) VALUES (?,?)", arrayOf(a.id, accountJson))
        db.writableDatabase.execSQL("INSERT INTO journal(id,payload) VALUES (?,?)", arrayOf(e.id, entryJson))
        db.close()
        LedgerDb(context, "v1-upgrade.db").use { upgraded ->
            assertEquals(e, upgraded.load().entries.single())
            upgraded.update { it.saveAccount(a.copy(group = AccountGroup.SUPPLIER, favorite = true)) }
            assertEquals(BigInteger("12345"), upgraded.load().balance(a.id, Currency.USD))
            upgraded.readableDatabase.rawQuery("SELECT payload FROM journal WHERE id=?", arrayOf(e.id)).use { it.moveToFirst(); assertEquals(entryJson, it.getString(0)) }
        }
    }
    @Test fun rendersMultiPageArabicStatementWithPaymentsAndIndependentCurrencies() {
        val a = Account("a", "وكالة تجريبية — بيانات للاختبار")
        var ledger = Ledger(listOf(a))
        val opening = Ledger.movement("a", Currency.USD, BigInteger("10000"), true, "2026-10-01", "رصيد قبل الفترة")
        ledger = ledger.add(opening)
        repeat(20) { i -> ledger = ledger.add(Ledger.movement("a", Currency.USD, BigInteger("12500"), true, "2026-11-01", "حجز تجريبي رقم ${i + 1} — بيان عربي واضح للمراجعة", "REF-${i + 1}").copy(action = Action.OWED_TO_ME, dueDate = "2026-11-10")) }
        ledger = ledger.add(ledger.settle("a", Currency.USD, BigInteger("4000"), Currency.AED, BigInteger("14700"), true, "2026-11-02", "سداد بالدرهم"))
        ledger = ledger.add(Ledger.movement("a", Currency.SDG, BigInteger("1500000000"), false, "2026-11-02", "مستحق مورد بالجنيه"))
        val folder = File("build/pdf-preview").apply { mkdirs() }
        var bitmap: android.graphics.Bitmap? = null; var pages = 0
        StatementPdf.render(ledger, a, null, "2026-11-01", "2026-11-06", newPage = {
            pages++
            bitmap = android.graphics.Bitmap.createBitmap(1190, 1684, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap!!).apply { drawColor(android.graphics.Color.WHITE); scale(2f, 2f) }
        }, finishPage = {
            File(folder, "page_$pages.png").outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap!!.recycle()
        })
        assertTrue(pages > 1)

    }
    @Test fun restoredAttachmentBytesRemainReadableAndHashChecked() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = "synthetic receipt bytes".toByteArray()
        val id = SecureBackup.hash(bytes)
        val store = Attachments(context)
        store.install(id, bytes)
        store.install(id, bytes)
        assertArrayEquals(bytes, store.file(Attachment(id, "test.pdf", "application/pdf", bytes.size.toLong())).readBytes())
        try { store.install("../not-allowed", bytes); fail() } catch (_: IllegalArgumentException) {}
    }
}
