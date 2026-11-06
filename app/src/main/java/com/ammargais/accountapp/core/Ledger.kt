package com.ammargais.accountapp.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

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
        val fractional = if (currency.decimals == 0) "" else "(?:\\.[0-9]{1,${currency.decimals}})?"
        require(input.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)$fractional"))) {
            "أدخل مبلغًا صحيحًا؛ ${currency.name} تقبل حتى ${currency.decimals} منازل عشرية. مثال: 1,250.50"
        }
        val value = BigDecimal(input.replace(",", ""))
        require(value.signum() > 0 && value <= BigDecimal("999999999999999")) { "المبلغ يجب أن يكون أكبر من صفر وأقل من ألف تريليون" }
        return value.movePointRight(currency.decimals).toBigIntegerExact()
    }
    fun plain(units: BigInteger, currency: Currency): String = BigDecimal(units, currency.decimals).toPlainString()
    fun format(units: BigInteger, currency: Currency): String {
        val value = BigDecimal(units.abs(), currency.decimals)
        val scale = value.stripTrailingZeros().scale().coerceIn(minOf(2, currency.decimals), currency.decimals)
        val parts = value.setScale(scale).toPlainString().split('.')
        val grouped = parts[0].reversed().chunked(3).joinToString(",").reversed()
        return (if (units.signum() < 0) "−" else "") + grouped + if (parts.size > 1) ".${parts[1]}" else ""
    }
    fun rate(from: BigInteger, fromCurrency: Currency, to: BigInteger, toCurrency: Currency): String =
        BigDecimal(to, toCurrency.decimals).divide(BigDecimal(from, fromCurrency.decimals), 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

enum class AccountGroup(val title: String) { PERSON("شخص"), CUSTOMER("عميل"), AGENT("وكيل"), SUPPLIER("مورد"), OTHER("أخرى") }
data class Account(val id: String = UUID.randomUUID().toString(), val name: String, val phone: String = "", val archived: Boolean = false,
    val group: AccountGroup = AccountGroup.PERSON, val favorite: Boolean = false,
    val currencyCodes: List<String>? = null, val summaryCurrency: String? = null)
enum class Action(val title: String, val debit: Boolean, val isDebt: Boolean = false) {
    LEGACY("حركة سابقة", true), OWED_TO_ME("لي عنده", true, true), I_OWE("له عندي", false, true),
    RECEIVED("استلمت منه", false), PAID("دفعت له", true)
}
data class Attachment(val id: String, val name: String, val mime: String, val size: Long)
data class Payment(val currency: Currency, val units: BigInteger)
data class Allocation(val debtId: String, val units: BigInteger)
data class Line(val accountId: String, val currency: Currency, val units: BigInteger)
enum class EntryKind { NORMAL, OPENING, EXCHANGE, REVERSAL, SETTLEMENT }
data class Entry(
    val id: String = UUID.randomUUID().toString(), val date: String = LocalDate.now().toString(),
    val createdAt: Long = System.currentTimeMillis(), val note: String, val reference: String = "",
    val kind: EntryKind = EntryKind.NORMAL, val reverses: String? = null, val lines: List<Line>,
    val action: Action = Action.LEGACY, val dueDate: String? = null,
    val payment: Payment? = null, val allocation: Allocation? = null, val attachments: List<Attachment> = emptyList()
)
data class StatementRow(val entry: Entry, val line: Line, val balance: BigInteger)
data class Ledger(val accounts: List<Account> = emptyList(), val entries: List<Entry> = emptyList(),
    val currencies: List<Currency> = entries.flatMap { e -> e.lines.map { it.currency } + listOfNotNull(e.payment?.currency) }.distinct(),
    val settings: BookSettings = BookSettings()) {
    fun currency(code: String): Currency = currencies.find { it.name == code } ?: throw IllegalArgumentException("العملة غير معرفة: $code")
    fun currenciesFor(account: Account): List<Currency> = currencies.filter { account.currencyCodes == null || it.name in account.currencyCodes }
    fun addCurrency(currency: Currency): Ledger {
        require(currencies.none { it.name == currency.name }) { "رمز العملة مسجل بالفعل" }
        return copy(currencies = currencies + currency).also { it.validate() }
    }
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
    fun isReversed(id: String) = entries.any { it.reverses == id }
    fun remaining(debt: Entry, through: String = "9999-12-31"): BigInteger {
        if (!debt.action.isDebt || debt.date > through || entries.any { it.reverses == debt.id && it.date <= through }) return BigInteger.ZERO
        val reversed = entries.filter { it.date <= through }.mapNotNull { it.reverses }.toSet()
        val paid = entries.filter { it.date <= through && it.id !in reversed && it.allocation?.debtId == debt.id }
            .fold(BigInteger.ZERO) { total, e -> total + e.allocation!!.units }
        return debt.lines.first().units.abs() - paid
    }
    fun openDebts(accountId: String? = null, through: String = LocalDate.now().toString()): List<Entry> = entries.filter {
        it.action.isDebt && (accountId == null || it.lines.first().accountId == accountId) && remaining(it, through) > BigInteger.ZERO
    }.sortedWith(compareBy<Entry> { it.dueDate ?: "9999-12-31" }.thenBy { it.date })
    fun duplicates(candidate: Entry): List<Entry> {
        val line = candidate.lines.first()
        return entries.filter { e -> e.kind != EntryKind.REVERSAL && !isReversed(e.id) && e.lines.first().accountId == line.accountId &&
            ((candidate.reference.isNotBlank() && e.reference.equals(candidate.reference, true)) ||
             (e.date == candidate.date && e.lines.filter { it.accountId != CONTROL } == candidate.lines.filter { it.accountId != CONTROL })) }
    }
    fun settle(accountId: String, debtCurrency: Currency, debtAmount: BigInteger, paidCurrency: Currency, paidAmount: BigInteger,
        received: Boolean, date: String, note: String, reference: String = "", debtId: String? = null): Entry {
        require(debtAmount > BigInteger.ZERO && paidAmount > BigInteger.ZERO) { "أدخل مبلغين موجبين" }
        require(debtCurrency != paidCurrency || debtAmount == paidAmount) { "مبلغ السداد بنفس العملة يجب أن يطابق المبلغ المسدد" }
        val available = balance(accountId, debtCurrency, date)
        if (debtId == null) require(available.signum() == (if (received) 1 else -1) && available.abs() >= debtAmount) { "المبلغ المسدد يتجاوز الرصيد المستحق في هذا التاريخ" }
        val signed = if (received) -debtAmount else debtAmount
        return Entry(date = date, note = note.trim(), reference = reference.trim(), kind = EntryKind.SETTLEMENT,
            lines = listOf(Line(accountId, debtCurrency, signed), Line(CONTROL, debtCurrency, -signed)),
            action = if (received) Action.RECEIVED else Action.PAID, payment = Payment(paidCurrency, paidAmount),
            allocation = debtId?.let { Allocation(it, debtAmount) })
    }
    fun add(entry: Entry): Ledger {
        require(entries.none { it.id == entry.id }) { "تم تسجيل هذه الحركة بالفعل" }
        require(entry.lines.isNotEmpty()) { "الحركة بلا أطراف" }
        require(entry.lines.filter { it.accountId != CONTROL }.all { line -> accounts.any { it.id == line.accountId && !it.archived } }) { "الحساب غير متاح أو مؤرشف" }
        val account = accounts.first { it.id == entry.lines.first().accountId }
        require(entry.lines.all { it.currency in currenciesFor(account) } && (entry.payment == null || entry.payment.currency in currenciesFor(account))) { "اختر عملة مفعلة لهذا الحساب" }
        if (entry.kind == EntryKind.EXCHANGE || entry.kind == EntryKind.SETTLEMENT && entry.allocation == null) {
            val source = entry.lines.first()
            val available = balance(source.accountId, source.currency, entry.date)
            require(available.signum() == -source.units.signum() && available.abs() >= source.units.abs()) { "الرصيد المتاح لا يكفي لهذه العملية في التاريخ المختار" }
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
        require(other.currencies.all { incoming -> currencies.none { it.name == incoming.name && it != incoming } }) { "تعريف عملة متعارض؛ لم تتغير بياناتك" }
        val mergedCurrencies = currencies + other.currencies.filter { c -> currencies.none { it.name == c.name } }
        val mergedAccounts = accounts.map { a ->
            val imported = other.accounts.find { it.id == a.id }
            if (imported == null || a.currencyCodes == null) a else a.copy(currencyCodes = (a.currencyCodes + (imported.currencyCodes ?: other.currencies.map { it.name })).distinct())
        } + other.accounts.filter { it.id !in existingAccounts }
        val mergedRates = settings.rates + other.settings.rates.filter { r -> settings.rates.none { it.from == r.from && it.to == r.to && it.date == r.date } }
        return copy(accounts = mergedAccounts, entries = entries + other.entries.filter { it.id !in existingEntries }, currencies = mergedCurrencies,
            settings = settings.copy(summaryCurrency = settings.summaryCurrency ?: other.settings.summaryCurrency, rates = mergedRates)).also { it.validate() }
    }
    fun validate() {
        require(currencies.size <= 100 && currencies.map { it.name }.distinct().size == currencies.size) { "تعريفات العملات مكررة أو كثيرة" }
        currencies.forEach { it.validate() }
        settings.validate(currencies)
        require(accounts.size <= 5000 && entries.size <= 50000) { "تجاوز الملف حدود هذه النسخة" }
        require(accounts.map { it.id }.toSet().size == accounts.size && entries.map { it.id }.toSet().size == entries.size) { "معرّفات مكررة" }
        require(accounts.map { it.name.trim().lowercase(java.util.Locale.ROOT) }.toSet().size == accounts.size) { "يوجد حسابان مختلفان بالاسم نفسه؛ غيّر اسم أحدهما قبل الدمج" }
        val accountIds = accounts.map { it.id }.toSet()
        val entryById = entries.associateBy { it.id }
        accounts.forEach { require(it.id.isNotBlank() && it.id != CONTROL && it.id.length <= 100 && it.name.isNotBlank() && it.name.length <= 80 && it.phone.length <= 80) { "بيانات الحساب غير صالحة" } }
        accounts.forEach { a ->
            require(a.currencyCodes == null || a.currencyCodes.distinct().size == a.currencyCodes.size && a.currencyCodes.all { code -> currencies.any { it.name == code } }) { "عملات الحساب غير صالحة" }
            require(a.summaryCurrency == null || currencies.any { it.name == a.summaryCurrency }) { "عملة ملخص الحساب غير معرفة" }
        }
        entries.forEach { entry ->
            require(entry.lines.all { it.currency in currencies } && (entry.payment == null || entry.payment.currency in currencies)) { "الحركة تحتوي عملة غير معرفة" }
            require(entry.id.isNotBlank() && entry.id.length <= 100 && entry.createdAt >= 0 && entry.note.isNotBlank() && entry.note.length <= 500 && entry.reference.length <= 100) { "بيانات الحركة غير صالحة" }
            require(entry.attachments.size <= 5 && entry.attachments.map { it.id }.toSet().size == entry.attachments.size) { "الحد الأقصى 5 مرفقات مختلفة للحركة" }
            entry.attachments.forEach { a -> require(a.id.matches(Regex("[a-f0-9]{64}")) && a.name.isNotBlank() && a.name.length <= 160 &&
                (a.mime.startsWith("image/") || a.mime == "application/pdf") && a.mime.length <= 100 && a.size in 1..10_485_760) { "مرفق غير صالح" } }
            if (entry.action != Action.LEGACY) {
                require(entry.kind in listOf(EntryKind.NORMAL, EntryKind.OPENING, EntryKind.SETTLEMENT)) { "نوع الحركة لا يقبل هذا التصنيف" }
                require(entry.lines.isNotEmpty() && entry.lines.first().accountId != CONTROL && entry.lines.first().units.signum() == if (entry.action.debit) 1 else -1) { "اتجاه الحركة لا يطابق نوعها" }
            }
            entry.dueDate?.let { due -> require(entry.action.isDebt && LocalDate.parse(due).toString() == due && due >= entry.date && LocalDate.parse(due).year <= 9999) { "الاستحقاق يجب ألا يسبق تاريخ الدين" } }
            entry.payment?.let { pay -> require(entry.action in listOf(Action.RECEIVED, Action.PAID) && pay.units > BigInteger.ZERO && pay.units.toString().length <= 23) { "بيانات السداد غير صالحة" } }
            if (entry.kind == EntryKind.SETTLEMENT) require(entry.payment != null) { "السداد يتطلب مبلغ الدفع الفعلي" }
            if (entry.payment != null) {
                val l = entry.lines.first()
                require(entry.payment.currency != l.currency || entry.payment.units == l.units.abs()) { "مبالغ السداد بنفس العملة غير متطابقة" }
            }
            if (entry.allocation != null) {
                val debt = entryById[entry.allocation.debtId]
                val l = entry.lines.first(); val dl = debt?.lines?.firstOrNull()
                require(debt != null && debt.action.isDebt && dl != null && entry.action in listOf(Action.RECEIVED, Action.PAID) && entry.date >= debt.date &&
                    dl.accountId == l.accountId && dl.currency == l.currency && dl.units.signum() != l.units.signum() && entry.allocation.units == l.units.abs()) { "تخصيص السداد لا يطابق الدين" }
            }
            val parsed = LocalDate.parse(entry.date)
            require(parsed.toString() == entry.date && parsed.year in 1900..9999) { "تاريخ غير صالح" }
            require(entry.lines.size in 2..4 && entry.lines.all { it.units != BigInteger.ZERO && it.units.abs().toString().length <= 23 && (it.accountId == CONTROL || it.accountId in accountIds) }) { "أطراف الحركة غير صالحة" }
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
        val reversedIds = entries.mapNotNull { it.reverses }.toSet()
        entries.filter { it.action.isDebt }.forEach { debt ->
            val paid = entries.filter { it.id !in reversedIds && it.allocation?.debtId == debt.id }.fold(BigInteger.ZERO) { sum, e -> sum + e.allocation!!.units }
            require(paid <= debt.lines.first().units.abs()) { "السدادات المخصصة تتجاوز أصل الدين" }
            require(debt.id !in reversedIds || paid == BigInteger.ZERO) { "اعكس السدادات المخصصة أولًا قبل عكس الدين" }
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
