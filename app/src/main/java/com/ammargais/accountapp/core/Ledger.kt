package com.ammargais.accountapp.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

enum class Currency(val title: String, val decimals: Int = 2) {
    AED("درهم إماراتي"), SDG("جنيه سوداني"), SAR("ريال سعودي"),
    EGP("جنيه مصري"), USD("دولار أمريكي"), USDT("تيثر", 6)
}

object Money {
    fun parse(raw: String, currency: Currency): BigInteger {
        val input = raw.trim().map { c ->
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫' -> '.'
                '٬' -> ','
                else -> c
            }
        }.joinToString("")
        require(input.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,${currency.decimals}})?"))) {
            "أدخل مبلغًا صحيحًا؛ ${currency.name} تقبل حتى ${currency.decimals} منازل عشرية. مثال: 1,250.50"
        }
        val value = BigDecimal(input.replace(",", ""))
        require(value.signum() > 0 && value <= BigDecimal("999999999999999")) { "المبلغ يجب أن يكون أكبر من صفر وأقل من ألف تريليون" }
        return value.movePointRight(currency.decimals).toBigIntegerExact()
    }
    fun plain(units: BigInteger, currency: Currency): String = BigDecimal(units, currency.decimals).toPlainString()
    fun format(units: BigInteger, currency: Currency): String {
        val value = BigDecimal(units.abs(), currency.decimals)
        val scale = if (currency == Currency.USDT) value.stripTrailingZeros().scale().coerceAtLeast(2) else 2
        val parts = value.setScale(scale).toPlainString().split('.')
        val grouped = parts[0].reversed().chunked(3).joinToString(",").reversed()
        return (if (units.signum() < 0) "−" else "") + grouped + if (parts.size > 1) ".${parts[1]}" else ""
    }
    fun rate(from: BigInteger, fromCurrency: Currency, to: BigInteger, toCurrency: Currency): String =
        BigDecimal(to, toCurrency.decimals).divide(BigDecimal(from, fromCurrency.decimals), 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

data class Account(val id: String = UUID.randomUUID().toString(), val name: String, val phone: String = "", val archived: Boolean = false)
data class Line(val accountId: String, val currency: Currency, val units: BigInteger)
enum class EntryKind { NORMAL, OPENING, EXCHANGE, REVERSAL }
data class Entry(
    val id: String = UUID.randomUUID().toString(), val date: String = LocalDate.now().toString(),
    val createdAt: Long = System.currentTimeMillis(), val note: String, val reference: String = "",
    val kind: EntryKind = EntryKind.NORMAL, val reverses: String? = null, val lines: List<Line>
)
data class StatementRow(val entry: Entry, val line: Line, val balance: BigInteger)
data class Ledger(val accounts: List<Account> = emptyList(), val entries: List<Entry> = emptyList()) {
    private val currentBalances by lazy {
        val values = mutableMapOf<Pair<String, Currency>, BigInteger>()
        entries.forEach { e -> e.lines.forEach { l -> val key = l.accountId to l.currency; values[key] = values.getOrDefault(key, BigInteger.ZERO) + l.units } }
        values
    }
    fun balance(account: String, currency: Currency, through: String = "9999-12-31"): BigInteger =
        if (through == "9999-12-31") currentBalances.getOrDefault(account to currency, BigInteger.ZERO) else entries.asSequence().filter { it.date <= through }.flatMap { it.lines.asSequence() }
            .filter { it.accountId == account && it.currency == currency }.fold(BigInteger.ZERO) { a, l -> a + l.units }

    fun totals(currency: Currency): Pair<BigInteger, BigInteger> {
        val balances = accounts.map { balance(it.id, currency) }
        return balances.filter { it.signum() > 0 }.fold(BigInteger.ZERO, BigInteger::add) to
            balances.filter { it.signum() < 0 }.fold(BigInteger.ZERO, BigInteger::add).abs()
    }
    fun rows(account: String, currency: Currency? = null, from: String = "0001-01-01", to: String = "9999-12-31"): List<StatementRow> {
        require(from <= to) { "تاريخ البداية يجب أن يسبق تاريخ النهاية" }
        val running = mutableMapOf<Currency, BigInteger>()
        return entries.sortedWith(compareBy<Entry> { it.date }.thenBy { it.createdAt }.thenBy { it.id }).flatMap { entry ->
            entry.lines.filter { it.accountId == account && (currency == null || it.currency == currency) }.mapNotNull { line ->
                val balance = running.getOrDefault(line.currency, BigInteger.ZERO) + line.units
                running[line.currency] = balance
                if (entry.date in from..to) StatementRow(entry, line, balance) else null
            }
        }
    }
    fun add(entry: Entry): Ledger {
        require(entries.none { it.id == entry.id }) { "تم تسجيل هذه الحركة بالفعل" }
        require(entry.lines.filter { it.accountId != CONTROL }.all { line -> accounts.any { it.id == line.accountId && !it.archived } }) { "الحساب غير متاح أو مؤرشف" }
        if (entry.kind == EntryKind.EXCHANGE) {
            val source = entry.lines.first()
            val available = balance(source.accountId, source.currency, entry.date)
            require(available.signum() == -source.units.signum() && available.abs() >= source.units.abs()) { "الرصيد المتاح لا يكفي لتبديل العملة" }
        }
        return copy(entries = entries + entry).also { it.validate() }
    }
    fun saveAccount(account: Account): Ledger {
        val trimmed = account.copy(name = account.name.trim(), phone = account.phone.trim())
        require(trimmed.name.isNotEmpty() && trimmed.name.length <= 80) { "اكتب اسمًا بين 1 و80 حرفًا" }
        require(accounts.none { it.id != trimmed.id && it.name.equals(trimmed.name, true) }) { "يوجد حساب بهذا الاسم؛ اختر اسمًا يميزه" }
        return copy(accounts = accounts.filterNot { it.id == trimmed.id } + trimmed).also { it.validate() }
    }
    fun reverse(original: Entry, reason: String, date: String = LocalDate.now().toString()): Ledger {
        require(entries.any { it == original }) { "الحركة غير موجودة" }
        require(original.kind != EntryKind.REVERSAL && entries.none { it.reverses == original.id }) { "هذه الحركة معكوسة بالفعل" }
        require(reason.trim().isNotEmpty()) { "اكتب سبب التصحيح" }
        val reverse = Entry(date = date, note = reason.trim(), kind = EntryKind.REVERSAL, reverses = original.id,
            lines = original.lines.map { it.copy(units = -it.units) })
        // Reversals remain possible for archived accounts; the original is never deleted.
        return copy(entries = entries + reverse).also { it.validate() }
    }
    fun merge(other: Ledger): Ledger {
        other.validate()
        val existingAccounts = accounts.associateBy { it.id }
        val existingEntries = entries.associateBy { it.id }
        require(other.entries.all { existingEntries[it.id] == null || existingEntries[it.id] == it }) { "النسخة تحتوي حركة متعارضة؛ لم تتغير بياناتك" }
        // Current account labels take precedence; immutable journal IDs prevent duplicates.
        return Ledger(accounts + other.accounts.filter { it.id !in existingAccounts }, entries + other.entries.filter { it.id !in existingEntries }).also { it.validate() }
    }
    fun validate() {
        require(accounts.size <= 5000 && entries.size <= 50000) { "تجاوز الملف حدود هذه النسخة" }
        require(accounts.map { it.id }.toSet().size == accounts.size && entries.map { it.id }.toSet().size == entries.size) { "معرّفات مكررة" }
        require(accounts.map { it.name.trim().lowercase(java.util.Locale.ROOT) }.toSet().size == accounts.size) { "يوجد حسابان مختلفان بالاسم نفسه؛ غيّر اسم أحدهما قبل الدمج" }
        val accountIds = accounts.map { it.id }.toSet()
        val entryById = entries.associateBy { it.id }
        accounts.forEach { require(it.id.isNotBlank() && it.id != CONTROL && it.id.length <= 100 && it.name.isNotBlank() && it.name.length <= 80 && it.phone.length <= 80) { "بيانات الحساب غير صالحة" } }
        entries.forEach { entry ->
            require(entry.id.isNotBlank() && entry.id.length <= 100 && entry.createdAt >= 0 && entry.note.isNotBlank() && entry.note.length <= 500 && entry.reference.length <= 100) { "بيانات الحركة غير صالحة" }
            val parsed = LocalDate.parse(entry.date)
            require(parsed.toString() == entry.date && parsed.year in 1900..9999) { "تاريخ غير صالح" }
            require(entry.lines.size in 2..4 && entry.lines.all { it.units != BigInteger.ZERO && it.units.abs().toString().length <= 21 && (it.accountId == CONTROL || it.accountId in accountIds) }) { "أطراف الحركة غير صالحة" }
            require(entry.lines.filter { it.accountId != CONTROL }.map { it.accountId }.toSet().size == 1) { "كل حركة تخص حسابًا واحدًا" }
            entry.lines.groupBy { it.currency }.forEach { (_, lines) ->
                require(lines.size == 2 && lines.count { it.accountId == CONTROL } == 1 && lines.fold(BigInteger.ZERO) { total, line -> total + line.units } == BigInteger.ZERO) { "القيد غير متوازن داخل العملة" }
            }
            if (entry.kind == EntryKind.EXCHANGE) {
                val contactLines = entry.lines.filter { it.accountId != CONTROL }
                require(contactLines.size == 2 && contactLines[0].currency != contactLines[1].currency && contactLines[0].units.signum() != contactLines[1].units.signum()) { "تسوية العملة غير صالحة" }
            } else if (entry.kind != EntryKind.REVERSAL) require(entry.lines.size == 2) { "الحركة العادية يجب أن تحتوي طرفين" }
            if (entry.kind == EntryKind.REVERSAL) {
                val original = entryById[entry.reverses]
                require(original != null && original.kind != EntryKind.REVERSAL && original.id != entry.id && entry.date >= original.date && entry.lines == original.lines.map { it.copy(units = -it.units) }) { "الحركة العكسية غير مطابقة أو تسبق الأصل" }
            } else require(entry.reverses == null) { "مرجع عكس غير صالح" }
        }
        val reversed = entries.mapNotNull { it.reverses }
        require(reversed.toSet().size == reversed.size) { "تم عكس نفس الحركة أكثر من مرة" }
    }
    companion object {
        const val CONTROL = "__counterparty_control__"
        fun movement(account: String, currency: Currency, amount: BigInteger, debit: Boolean, date: String, note: String, reference: String = "", opening: Boolean = false): Entry {
            require(amount > BigInteger.ZERO) { "المبلغ يجب أن يكون موجبًا" }
            val signed = if (debit) amount else -amount
            return Entry(date = date, note = note.trim(), reference = reference.trim(), kind = if (opening) EntryKind.OPENING else EntryKind.NORMAL,
                lines = listOf(Line(account, currency, signed), Line(CONTROL, currency, -signed)))
        }
        fun exchange(account: String, from: Currency, fromAmount: BigInteger, to: Currency, toAmount: BigInteger, receivable: Boolean, date: String, note: String): Entry {
            require(from != to && fromAmount > BigInteger.ZERO && toAmount > BigInteger.ZERO) { "اختر عملتين مختلفتين ومبلغين موجبين" }
            val sign = if (receivable) BigInteger.ONE else -BigInteger.ONE
            return Entry(date = date, note = note.trim(), kind = EntryKind.EXCHANGE, lines = listOf(
                Line(account, from, -fromAmount * sign), Line(CONTROL, from, fromAmount * sign),
                Line(account, to, toAmount * sign), Line(CONTROL, to, -toAmount * sign)))
        }
    }
}
