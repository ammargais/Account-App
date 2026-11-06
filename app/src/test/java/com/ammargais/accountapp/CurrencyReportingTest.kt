package com.ammargais.accountapp

import com.ammargais.accountapp.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class CurrencyReportingTest {
    private val jpy = Currency("JPY", "ين", 0)
    private val kwd = Currency("KWD", "دينار", 3)
    private val account = Account("a", "عميل", currencyCodes = listOf("JPY", "KWD"), summaryCurrency = "KWD")
    private val base = Ledger(listOf(account), currencies = listOf(jpy, kwd))
    private fun reject(f: () -> Unit) { try { f(); fail("Expected rejection") } catch(_: IllegalArgumentException) {} }
    @Test fun freshBookHasNoPreselectedCurrencies() { assertTrue(Ledger().currencies.isEmpty()); assertNull(Ledger().settings.summaryCurrency) }
    @Test fun customPrecisionIsExactAndRejectsFractionForZeroDecimalCurrency() {
        assertEquals(BigInteger("15000000"), Money.parse("15,000,000", jpy))
        assertEquals("0.001", Money.format(BigInteger.ONE, kwd))
        assertEquals("15,000,000", Money.format(Money.parse("15000000", jpy), jpy))
        reject { Money.parse("1.1", jpy) }
        reject { Money.parse("1.0001", kwd) }
        reject { Currency("jpy", "ين").validate() }
        reject { base.addCurrency(jpy) }
    }
    @Test fun accountOnlyAcceptsSelectedCurrenciesButKeepsHistoricalBalancesWhenDisabled() {
        val e = Ledger.movement("a", kwd, BigInteger.ONE, true, "2026-11-01", "قديم")
        val book = base.add(e).saveAccount(account.copy(currencyCodes = listOf("JPY")))
        assertEquals(BigInteger.ONE, book.balance("a", kwd))
        reject { book.add(e.copy(id="new")) }
        reject { base.addCurrency(Currency("JPY", "متعارض", 2)) }
    }
    @Test fun missingRateNeverReturnsPartialTotal() {
        val book = base.add(Ledger.movement("a", jpy, BigInteger("15000000"), true, "2026-11-01", "إجمالي"))
            .add(Ledger.movement("a", kwd, BigInteger("3000"), true, "2026-11-01", "مبلغ"))
        val report = Reporting.summarize(book, kwd)
        assertFalse(report.complete); assertEquals(listOf("JPY"), report.missing)
        assertNull(report.net); assertNull(report.receivable); assertNull(report.payable)
    }
    @Test fun ratesRespectAsOfDateAndPreserveJournal() {
        val book = base.add(Ledger.movement("a", jpy, BigInteger("15000000"), true, "2026-11-01", "إجمالي"))
        val converted = book.copy(settings = BookSettings("KWD", listOf(ExchangeRate("JPY", "KWD", "0.002", "2026-11-01"), ExchangeRate("JPY", "KWD", "0.003", "2026-11-05"))))
        assertEquals(BigInteger("30000000"), Reporting.summarize(converted, kwd, through="2026-11-02").net)
        assertEquals(BigInteger("45000000"), Reporting.summarize(converted, kwd, through="2026-11-06").net)
        assertEquals(book.entries, converted.entries)
        assertEquals(BigInteger("15000000"), converted.balance("a", jpy))
    }
    @Test fun inverseRateRoundingAndSignsAreDeterministic() {
        val book = base.saveAccount(Account("b", "مورد")).add(Ledger.movement("a", jpy, BigInteger("100"), true, "2026-11-01", "لنا"))
            .add(Ledger.movement("b", jpy, BigInteger("50"), false, "2026-11-01", "علينا"))
            .copy(settings=BookSettings(rates=listOf(ExchangeRate("KWD","JPY","3","2026-11-01"))))
        val report = Reporting.summarize(book, kwd, through="2026-11-02")
        assertEquals(BigInteger("33333"),report.receivable); assertEquals(BigInteger("16667"),report.payable)
        assertEquals(BigInteger("16666"),report.net)
    }
    @Test fun backupPersistsDefinitionsSelectionAndRatesAndRejectsConflict() {
        val book = base.copy(settings=BookSettings("KWD",listOf(ExchangeRate("JPY","KWD","0.002","2026-11-01"))))
            .add(Ledger.movement("a", kwd, BigInteger.ONE, true, "2026-11-01", "دقة"))
        assertEquals(book,BackupCodec.decode(BackupCodec.encode(book)))
        assertEquals(book,book.merge(book))
        reject { book.merge(Ledger(currencies=listOf(Currency("KWD","دينار",2)))) }
    }
    @Test fun oldBackupsOnlyImportUsedCurrencies() {
        val old = org.json.JSONObject(BackupCodec.encode(Ledger(listOf(Account("a","قديم")), currencies=Currency.entries)
            .add(Ledger.movement("a",Currency.USDT,BigInteger.ONE,true,"2026-11-01","قديم"))))
        old.put("version",2);old.remove("metadata")
        val loaded=BackupCodec.decode(old.toString())
        assertEquals(listOf(Currency.USDT),loaded.currencies)
        assertEquals(BigInteger.ONE,loaded.balance("a",Currency.USDT))
    }
}
