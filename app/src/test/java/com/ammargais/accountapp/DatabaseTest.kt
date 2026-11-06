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
    @Test fun persistsAcrossReopenAndRejectsInvalidWriteAtomically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test.db")
        val db = LedgerDb(context, "test.db")
        val a = Account(id = "a", name = "حساب تجريبي")
        db.update { it.saveAccount(a) }
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
