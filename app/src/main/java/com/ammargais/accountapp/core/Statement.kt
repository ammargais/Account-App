package com.ammargais.accountapp.core

import java.math.BigInteger

object Statement {
    private fun csvCell(raw: String): String {
        // Protect spreadsheet consumers against formula injection in user-supplied fields.
        val safe = if (raw.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$raw" else raw
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun csv(ledger: Ledger, account: Account, currency: Currency? = null, from: String = "0001-01-01", to: String = "9999-12-31"): String {
        val rows = ledger.rows(account.id, currency, from, to)
        val result = mutableListOf(listOf("الحساب", "التاريخ", "المعرف", "البيان", "المرجع", "العملة", "مدين", "دائن", "الرصيد المطلق", "الرصيد لصالح", "النوع", "عكس الحركة", "عملة الدفع", "المبلغ المدفوع", "الاستحقاق", "الدين المرتبط"))
        (currency?.let { listOf(it) } ?: Currency.entries).forEach { c ->
            val opening = ledger.entries.filter { it.date < from }.flatMap { it.lines }.filter { it.accountId == account.id && it.currency == c }.fold(BigInteger.ZERO) { a, l -> a + l.units }
            result += listOf(account.name, from, "", "رصيد قبل الفترة", "", c.name, "", "", Money.plain(opening.abs(), c), side(opening), "افتتاح الفترة", "", "", "", "", "")
        }
        rows.forEach { row -> result += listOf(account.name, row.entry.date, row.entry.id, row.entry.note, row.entry.reference, row.line.currency.name,
            if (row.line.units.signum() > 0) Money.plain(row.line.units, row.line.currency) else "",
            if (row.line.units.signum() < 0) Money.plain(row.line.units.abs(), row.line.currency) else "",
            Money.plain(row.balance.abs(), row.line.currency), side(row.balance), row.entry.kind.name, row.entry.reverses ?: "", row.entry.payment?.currency?.name ?: "", row.entry.payment?.let { Money.plain(it.units, it.currency) } ?: "", row.entry.dueDate ?: "", row.entry.allocation?.debtId ?: "") }
        return "\uFEFF" + result.joinToString("\r\n") { it.joinToString(",", transform = ::csvCell) }
    }
    fun side(units: BigInteger) = when (units.signum()) { 1 -> "لنا"; -1 -> "علينا"; else -> "متوازن" }
    fun summary(ledger: Ledger, account: Account, through: String = "9999-12-31"): String = buildString {
        appendLine("ملخص حساب: ${account.name}")
        appendLine("حتى ${if (through == "9999-12-31") java.time.LocalDate.now() else through}")
        Currency.entries.forEach { c -> val b = ledger.balance(account.id, c, through); if (b != BigInteger.ZERO) appendLine("${c.name}  ${Money.format(b.abs(), c)} — ${side(b)}") }
        appendLine("من منظور صاحب الدفتر: لنا = مستحق لصاحب الدفتر؛ علينا = مستحق لصاحب الحساب.")
    }
}
