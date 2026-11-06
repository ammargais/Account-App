package com.ammargais.accountapp

import com.ammargais.accountapp.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class LedgerTest {
    private val a = Account(id = "a", name = "أحمد")
    private val b = Account(id = "b", name = "سارة")
    private val base = Ledger(listOf(a, b))
    private fun entry(amount: String, currency: Currency = Currency.USD, debit: Boolean = true, date: String = "2026-11-01", account: String = "a") =
        Ledger.movement(account, currency, Money.parse(amount, currency), debit, date, "تجربة")
    private fun fails(block: () -> Unit) { try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) {} }

    @Test fun receivedPaymentReducesReceivable() {
        val ledger = base.add(entry("100")).add(entry("40", debit = false))
        assertEquals("60.00", Money.plain(ledger.balance("a", Currency.USD), Currency.USD))
    }
    @Test fun paymentCanCrossZeroIntoPayable() {
        val ledger = base.add(entry("100")).add(entry("140", debit = false))
        assertEquals(BigInteger("-4000"), ledger.balance("a", Currency.USD))
        assertEquals("علينا", Statement.side(ledger.balance("a", Currency.USD)))
    }
    @Test fun allSixCurrenciesStayIndependent() {
        val ledger = Currency.entries.fold(base) { l, c -> l.add(entry("10", c)) }
        Currency.entries.forEach { assertEquals(Money.parse("10", it), ledger.balance("a", it)) }
        assertEquals(6, ledger.entries.size)
    }
    @Test fun totalsDoNotNetDifferentPeople() {
        val ledger = base.add(entry("100")).add(entry("70", debit = false, account = "b"))
        assertEquals(BigInteger("10000") to BigInteger("7000"), ledger.totals(Currency.USD))
    }
    @Test fun acceptsArabicAndPersianNumbers() {
        assertEquals(BigInteger("123450"), Money.parse("١٬٢٣٤٫٥٠", Currency.SDG))
        assertEquals(BigInteger("123450"), Money.parse("۱,۲۳۴.۵۰", Currency.AED))
    }
    @Test fun rejectsAmbiguousGroupingAndOverprecision() {
        listOf("1,23", "1.234,00", "1e3", "-10", "+10", "0", "NaN", "12.345", "1 200").forEach { s -> fails { Money.parse(s, Currency.USD) } }
    }
    @Test fun usdtSupportsSixDecimalsWithoutFloat() {
        var ledger = base
        repeat(10) { ledger = ledger.add(entry("0.000001", Currency.USDT)) }
        assertEquals(BigInteger.TEN, ledger.balance("a", Currency.USDT))
        assertEquals("0.000010", Money.plain(BigInteger.TEN, Currency.USDT))
        fails { Money.parse("0.0000001", Currency.USDT) }
    }
    @Test fun largeSudaneseAmountsAreExact() {
        val ledger = base.add(entry("999,999,999,999,999", Currency.SDG)).add(entry("0.01", Currency.SDG))
        assertEquals("999999999999999.01", Money.plain(ledger.balance("a", Currency.SDG), Currency.SDG))
    }
    @Test fun openingIsRecordedAsJournalMovement() {
        val e = Ledger.movement("a", Currency.AED, BigInteger("50000"), false, "2026-01-01", "رصيد سابق", opening = true)
        assertEquals(EntryKind.OPENING, e.kind)
        assertEquals(BigInteger("-50000"), base.add(e).balance("a", Currency.AED))
    }
    @Test fun debitAndCreditBalanceWithinEveryCurrency() {
        val ledger = base.add(entry("100")).add(Ledger.exchange("a", Currency.USD, BigInteger("10000"), Currency.SDG, BigInteger("90000000"), true, "2026-11-02", "تبديل"))
        ledger.entries.forEach { e -> e.lines.groupBy { it.currency }.values.forEach { assertEquals(BigInteger.ZERO, it.fold(BigInteger.ZERO) { s, l -> s + l.units }) } }
        assertEquals(BigInteger.ZERO, ledger.balance("a", Currency.USD))
        assertEquals(BigInteger("90000000"), ledger.balance("a", Currency.SDG))
    }
    @Test fun exchangePayablePreservesDirection() {
        val ledger = base.add(entry("100", debit = false)).add(Ledger.exchange("a", Currency.USD, BigInteger("10000"), Currency.AED, BigInteger("36725"), false, "2026-11-02", "تبديل"))
        assertEquals(BigInteger.ZERO, ledger.balance("a", Currency.USD))
        assertEquals(BigInteger("-36725"), ledger.balance("a", Currency.AED))
        assertEquals("3.6725", Money.rate(BigInteger("10000"), Currency.USD, BigInteger("36725"), Currency.AED))
    }
    @Test fun exchangeRequiresExistingSufficientBalance() {
        val e = Ledger.exchange("a", Currency.USD, BigInteger("10000"), Currency.SDG, BigInteger("90000000"), true, "2026-11-02", "تبديل")
        fails { base.add(e) }
        fails { base.add(entry("99")).add(e) }
        fails { base.add(entry("100", date = "2026-11-03")).add(e) }
    }
    @Test fun rejectsUnbalancedOrMissingAccounts() {
        val e = entry("100")
        fails { base.add(e.copy(lines = listOf(e.lines[0], e.lines[1].copy(units = BigInteger.ONE)))) }
        fails { Ledger().add(e) }
    }
    @Test fun reversalPreservesOriginalAndCannotRepeat() {
        val e = entry("100")
        val ledger = base.add(e).reverse(e, "مبلغ غير صحيح", "2026-11-02")
        assertEquals(BigInteger.ZERO, ledger.balance("a", Currency.USD))
        assertEquals(e, ledger.entries.first())
        fails { ledger.reverse(e, "مرة ثانية") }
        fails { ledger.reverse(ledger.entries.last(), "عكس العكس") }
    }
    @Test fun reversalCannotPrecedeOriginal() { val e = entry("100"); fails { base.add(e).reverse(e, "تصحيح", "2026-10-31") } }
    @Test fun archivedAccountKeepsTotalsAndCanReverse() {
        val e = entry("100")
        val ledger = base.add(e).saveAccount(a.copy(archived = true))
        assertEquals(BigInteger("10000"), ledger.totals(Currency.USD).first)
        fails { ledger.add(entry("1")) }
        assertEquals(BigInteger.ZERO, ledger.reverse(e, "تصحيح").balance("a", Currency.USD))
    }
    @Test fun dateFilterKeepsOpeningBalance() {
        val ledger = base.add(entry("100", date = "2026-10-01")).add(entry("40", debit = false, date = "2026-11-02"))
        val rows = ledger.rows("a", Currency.USD, "2026-11-01", "2026-11-03")
        assertEquals(1, rows.size); assertEquals(BigInteger("6000"), rows.single().balance)
        assertEquals(BigInteger("10000"), ledger.balance("a", Currency.USD, "2026-10-31"))
        fails { ledger.rows("a", from = "2026-11-03", to = "2026-11-01") }
    }
    @Test fun backupRoundtripAndRepeatedRestoreAreIdempotent() {
        val e = entry("100")
        val ledger = base.add(e).reverse(e, "تصحيح")
        val decoded = BackupCodec.decode(BackupCodec.encode(ledger))
        assertEquals(ledger, decoded)
        assertEquals(ledger, ledger.merge(decoded).merge(decoded))
    }
    @Test fun restoreKeepsCurrentAccountName() {
        val old = base.add(entry("100")); val updated = old.saveAccount(a.copy(name = "أحمد الجديد"))
        assertEquals("أحمد الجديد", updated.merge(old).accounts.first { it.id == "a" }.name)
    }
    @Test fun conflictingRestoreRejected() {
        val e = entry("100"); val original = base.add(e)
        fails { original.merge(base.add(e.copy(note = "تعديل على نفس المعرف"))) }
    }
    @Test fun restoreDoesNotSilentlyCombineDifferentPeopleWithSameName() {
        fails { base.merge(Ledger(listOf(a.copy(id = "different-person")))) }
    }
    @Test fun invalidBackupDoesNotPassValidation() {
        val encoded = BackupCodec.encode(base.add(entry("100")))
        fails { BackupCodec.decode(encoded.replace("\"version\":1", "\"version\":99")) }
        fails { BackupCodec.decode(encoded.replace("USD", "GBP")) }
    }
    @Test fun csvEscapesFormulasQuotesAndIncludesOpening() {
        val ledger = base.saveAccount(a.copy(name = "=1+1")).add(entry("100", date = "2026-10-01").copy(note = "=HYPERLINK(\"x\")"))
        val account = ledger.accounts.first { it.id == "a" }
        val csv = Statement.csv(ledger, account)
        assertTrue(csv.startsWith("\uFEFF")); assertTrue(csv.contains("'=1+1")); assertTrue(csv.contains("'=HYPERLINK(\"\"x\"\")"))
        val filtered = Statement.csv(ledger, account, Currency.USD, "2026-11-01", "2026-11-02")
        assertTrue(filtered.contains("رصيد قبل الفترة")); assertTrue(filtered.contains("100.00"))
    }
}
