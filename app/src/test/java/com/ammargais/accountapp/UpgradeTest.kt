package com.ammargais.accountapp

import com.ammargais.accountapp.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpgradeTest {
    private val account = Account("a", "وكيل تجريبي", group = AccountGroup.AGENT, favorite = true)
    private val base = Ledger(listOf(account))
    private fun money(s: String, c: Currency = Currency.USD) = Money.parse(s, c)
    private fun debt(receivable: Boolean = true, amount: String = "100", due: String? = "2026-11-10") = Ledger.movement("a", Currency.USD, money(amount), receivable, "2026-11-01", "خدمة", "REF-1")
        .copy(action = if (receivable) Action.OWED_TO_ME else Action.I_OWE, dueDate = due)
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun partialCrossCurrencyReceiptReducesOnlyOriginalDebt() {
        val d = debt(); val ledger = base.add(d)
        val pay = ledger.settle("a", Currency.USD, money("40"), Currency.AED, money("147", Currency.AED), true, "2026-11-02", "سداد", debtId = d.id)
        val next = ledger.add(pay)
        assertEquals(money("60"), next.balance("a", Currency.USD)); assertEquals(BigInteger.ZERO, next.balance("a", Currency.AED))
        assertEquals(money("60"), next.remaining(d)); assertEquals(money("147", Currency.AED), pay.payment!!.units)
        assertEquals("3.675", Money.rate(money("40"), Currency.USD, pay.payment.units, Currency.AED))
    }
    @Test fun crossCurrencyPaymentSettlesPayable() {
        val d = debt(false); val ledger = base.add(d)
        val next = ledger.add(ledger.settle("a", Currency.USD, money("100"), Currency.USDT, money("99.999999", Currency.USDT), false, "2026-11-02", "سداد مورد", debtId = d.id))
        assertEquals(BigInteger.ZERO, next.balance("a", Currency.USD)); assertEquals(BigInteger.ZERO, next.remaining(d))
        assertTrue(next.openDebts(through = "2026-11-03").isEmpty())
    }
    @Test fun cannotOverallocateOrAllocateToWrongAccount() {
        val d = debt(); val ledger = base.add(d)
        val first = ledger.settle("a", Currency.USD, money("80"), Currency.USD, money("80"), true, "2026-11-02", "جزء", debtId = d.id)
        val next = ledger.add(first)
        reject { next.add(next.settle("a", Currency.USD, money("30"), Currency.AED, money("110", Currency.AED), true, "2026-11-03", "زيادة", debtId = d.id)) }
        reject { next.saveAccount(Account("b", "آخر")).add(next.settle("b", Currency.USD, money("10"), Currency.USD, money("10"), true, "2026-11-03", "خطأ", debtId = d.id)) }
    }
    @Test fun cannotAllocateBeforeDebtOrWithWrongDirection() {
        val d = debt(); val ledger = base.add(d)
        reject { ledger.add(ledger.settle("a", Currency.USD, money("10"), Currency.USD, money("10"), true, "2026-10-31", "قبل الدين", debtId = d.id)) }
        reject { ledger.add(ledger.settle("a", Currency.USD, money("10"), Currency.USD, money("10"), false, "2026-11-02", "اتجاه خاطئ", debtId = d.id)) }
    }
    @Test fun reversePaymentReopensDebtAndDebtReversalRequiresUnwindingPayment() {
        val d = debt(); val ledger = base.add(d)
        val pay = ledger.settle("a", Currency.USD, money("40"), Currency.AED, money("147", Currency.AED), true, "2026-11-02", "سداد", debtId = d.id)
        val next = ledger.add(pay)
        reject { next.reverse(d, "إلغاء", "2026-11-03") }
        val reopened = next.reverse(pay, "إلغاء سداد", "2026-11-03")
        assertEquals(money("100"), reopened.remaining(d)); assertEquals(money("60"), reopened.remaining(d, "2026-11-02"))
        assertEquals(BigInteger.ZERO, reopened.reverse(d, "إلغاء دين", "2026-11-04").remaining(d))
    }
    @Test fun unallocatedForeignSettlementCannotExceedNetBalance() {
        val ledger = base.add(debt())
        reject { ledger.settle("a", Currency.USD, money("101"), Currency.AED, money("370", Currency.AED), true, "2026-11-02", "زيادة") }
        reject { ledger.settle("a", Currency.USD, money("20"), Currency.USD, money("19"), true, "2026-11-02", "فرق غير مسموح") }
    }
    @Test fun allocationDoesNotUseFuturePaymentsInAsOfBalance() {
        val d = debt(); val ledger = base.add(d)
        val pay = ledger.settle("a", Currency.USD, money("40"), Currency.USD, money("40"), true, "2026-11-05", "سداد", debtId = d.id)
        assertEquals(money("100"), ledger.add(pay).remaining(d, "2026-11-04"))
        assertEquals(money("60"), ledger.add(pay).remaining(d, "2026-11-05"))
    }
    @Test fun dueDateIsOptionalButCannotPrecedeDebt() {
        base.add(debt(due = null)).validate()
        reject { base.add(debt(due = "2026-10-31")) }
    }
    @Test fun legacyTransactionsAreNotGuessedToBeOpenDebts() {
        val legacy = Ledger.movement("a", Currency.USD, money("100"), true, "2026-11-01", "قديم")
        assertTrue(base.add(legacy).openDebts(through = "2026-11-10").isEmpty())
        assertEquals(money("100"), base.add(legacy).balance("a", Currency.USD))
    }
    @Test fun duplicateWarningIsAdvisoryAndChecksReferenceAcrossDates() {
        val d = debt(); val ledger = base.add(d)
        val similar = d.copy(id = "different", date = "2026-11-02", reference = "ref-1")
        assertEquals(1, ledger.duplicates(similar).size)
        assertEquals(2, ledger.add(similar).entries.size)
        assertTrue(ledger.reverse(d, "تصحيح", "2026-11-02").duplicates(similar).isEmpty())
    }
    @Test fun v1BackupAndNewMetadataRoundTrip() {
        val old = """{"format":"daftar-backup","version":1,"accounts":[{"id":"a","name":"قديم","phone":"","archived":false}],"entries":[]}"""
        assertEquals(AccountGroup.PERSON, BackupCodec.decode(old).accounts.first().group)
        val ledger = base.add(debt())
        assertEquals(ledger, BackupCodec.decode(BackupCodec.encode(ledger)))
        assertEquals(ledger, ledger.merge(BackupCodec.decode(BackupCodec.encode(ledger))))
    }
    @Test fun secureBackupWithReceiptRoundTripsAndRejectsWrongPasswordAndTampering() {
        val receipt = "synthetic receipt".toByteArray(); val id = SecureBackup.hash(receipt)
        val d = debt().copy(attachments = listOf(Attachment(id, "إشعار.pdf", "application/pdf", receipt.size.toLong())))
        val ledger = base.add(d)
        val packed = SecureBackup.pack(ledger) { receipt }
        val password = "a sufficiently long password".toCharArray()
        val encrypted = SecureBackup.encrypt(packed, password)
        assertFalse(encrypted.contentEquals(SecureBackup.encrypt(packed, password)))
        val decoded = SecureBackup.unpack(SecureBackup.decrypt(encrypted, password))
        assertEquals(ledger, decoded.ledger); assertArrayEquals(receipt, decoded.files[id])
        reject { SecureBackup.decrypt(encrypted, "wrong password".toCharArray()) }
        val tampered = encrypted.copyOf(); tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        reject { SecureBackup.decrypt(tampered, password) }
        reject { SecureBackup.encrypt(packed, "short".toCharArray()) }
    }
    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        return bytes.toByteArray()
    }
    @Test fun backupRejectsMissingModifiedAndUnexpectedAttachmentsAndTraversal() {
        val receipt = "receipt".toByteArray(); val id = SecureBackup.hash(receipt)
        val ledger = base.add(debt().copy(attachments = listOf(Attachment(id, "a.pdf", "application/pdf", receipt.size.toLong()))))
        val json = BackupCodec.encode(ledger).toByteArray()
        reject { SecureBackup.unpack(archive("ledger.json" to json)) }
        reject { SecureBackup.unpack(archive("ledger.json" to json, "attachments/$id" to "wrong!!".toByteArray())) }
        reject { SecureBackup.unpack(archive("ledger.json" to json, "../escape" to receipt)) }
        reject { SecureBackup.pack(ledger) { "changed".toByteArray() } }
    }
    @Test fun csvIncludesActualCurrencyAndLinkedDebt() {
        val d = debt(); val ledger = base.add(d)
        val pay = ledger.settle("a", Currency.USD, money("40"), Currency.AED, money("147", Currency.AED), true, "2026-11-02", "دفع", debtId = d.id)
        val csv = Statement.csv(ledger.add(pay), account)
        assertTrue(csv.contains("عملة الدفع")); assertTrue(csv.contains("\"AED\",\"147.00\"")); assertTrue(csv.contains(d.id))
    }
}
