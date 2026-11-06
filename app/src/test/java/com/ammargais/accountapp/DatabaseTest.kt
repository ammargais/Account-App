package com.ammargais.accountapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ammargais.accountapp.core.*
import com.ammargais.accountapp.data.LedgerDb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {
    @Test fun currencyDefinitionsAndRatesSurviveReopenAndInvalidChangesRollback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "metadata-${java.util.UUID.randomUUID()}.db"
        val unit = Currency("UNIT", "وحدة تجريبية", 3)
        val zero = Currency("ZERO", "بدون كسور", 0)
        val account = Account("a", "حساب", currencyCodes = listOf("UNIT"), summaryCurrency = "ZERO")
        val settings = BookSettings("ZERO", listOf(ExchangeRate("UNIT", "ZERO", "2.5", "2026-11-01")))
        LedgerDb(context, name).use { db ->
            assertTrue(db.load().currencies.isEmpty())
            db.update { it.addCurrency(unit).addCurrency(zero).saveAccount(account).copy(settings = settings) }
            try { db.update { it.copy(settings = BookSettings("MISSING")) } ; fail() } catch (_: IllegalArgumentException) {}
        }
        LedgerDb(context, name).use { db ->
            val book = db.load()
            assertEquals(listOf(unit, zero), book.currencies)
            assertEquals(settings, book.settings)
            assertEquals(account, book.accounts.single())
            try { db.update { it.copy(currencies = listOf(unit.copy(decimals = 2), zero)) }; fail() } catch (_: IllegalArgumentException) {}
            assertEquals(book, db.load())
        }
    }
    @Test fun persistsAcrossReopenAndRejectsInvalidWriteAtomically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test.db")
        val db = LedgerDb(context, "test.db")
        val a = Account(id = "a", name = "حساب تجريبي")
        db.update { it.addCurrency(Currency.USDT).saveAccount(a) }
        val e = Ledger.movement(a.id, Currency.USDT, BigInteger.ONE, true, "2026-11-01", "دقة")
        db.update { it.add(e) }
        try { db.update { it.add(e.copy(id = "invalid", lines = e.lines.take(1))) }; fail() } catch (_: IllegalArgumentException) {}
        db.close()
        val reopened = LedgerDb(context, "test.db")
        assertEquals(1, reopened.load().entries.size)
        assertEquals(BigInteger.ONE, reopened.load().balance(a.id, Currency.USDT))
        val backup = reopened.load()
        reopened.update { it.merge(backup) }
        assertEquals(1, reopened.load().entries.size)
        try { reopened.update { it.copy(entries = emptyList()) }; fail() } catch (_: IllegalArgumentException) {}
        assertEquals(1, reopened.load().entries.size)
        reopened.close()
    }
}
