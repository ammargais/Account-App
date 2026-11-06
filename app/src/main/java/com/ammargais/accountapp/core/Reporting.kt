package com.ammargais.accountapp.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class ExchangeRate(val from: String, val to: String, val value: String, val date: String) {
    fun validate(currencies: List<Currency>) {
        require(from != to && listOf(from, to).all { code -> currencies.any { it.name == code } }) { "اختر عملتين معرفتين مختلفتين" }
        require(value.matches(Regex("[0-9]{1,18}(\\.[0-9]{1,12})?")) && BigDecimal(value).signum() > 0) { "أدخل سعر تحويل موجبًا، دون فواصل آلاف، وحتى 12 منزلة عشرية" }
        require(LocalDate.parse(date).toString() == date && LocalDate.parse(date).year in 1900..9999) { "تاريخ السعر غير صالح" }
    }
}
data class BookSettings(val summaryCurrency: String? = null, val rates: List<ExchangeRate> = emptyList()) {
    fun validate(currencies: List<Currency>) {
        require(summaryCurrency == null || currencies.any { it.name == summaryCurrency }) { "اختر عملة ملخص معرفة" }
        require(rates.size <= 10000 && rates.map { Triple(it.from, it.to, it.date) }.distinct().size == rates.size) { "أسعار مكررة أو كثيرة" }
        rates.forEach { it.validate(currencies) }
    }
    fun withRate(rate: ExchangeRate) = copy(rates = rates.filterNot { it.from == rate.from && it.to == rate.to && it.date == rate.date } + rate)
}
data class ConvertedPart(val accountId: String, val currency: Currency, val original: BigInteger,
    val converted: BigInteger?, val rate: String?, val rateDate: String?)
data class ConvertedSummary(val currency: Currency, val parts: List<ConvertedPart>) {
    val complete get() = parts.all { it.converted != null }
    val missing get() = parts.filter { it.converted == null }.map { it.currency.name }.distinct()
    val receivable: BigInteger? get() = if (!complete) null else parts.mapNotNull { it.converted }.filter { it.signum() > 0 }.fold(BigInteger.ZERO, BigInteger::add)
    val payable: BigInteger? get() = if (!complete) null else parts.mapNotNull { it.converted }.filter { it.signum() < 0 }.fold(BigInteger.ZERO, BigInteger::add).abs()
    val net: BigInteger? get() = if (!complete) null else receivable!! - payable!!
}
object Reporting {
    /** Direct or inverse pair only. Never silently assume parity or triangulate through a third currency. */
    fun summarize(ledger: Ledger, target: Currency, accountId: String? = null, through: String = LocalDate.now().toString()): ConvertedSummary {
        require(target in ledger.currencies)
        val selected = ledger.accounts.filter { accountId == null || it.id == accountId }
        val parts = selected.flatMap { a -> ledger.currencies.mapNotNull { c ->
            val units = ledger.balance(a.id, c, through)
            if (units == BigInteger.ZERO) null else if (c == target) ConvertedPart(a.id, c, units, units, "1", null) else {
                val rate = ledger.settings.rates.filter { it.date <= through &&
                    (it.from == c.name && it.to == target.name || it.from == target.name && it.to == c.name) }
                    .sortedWith(compareByDescending<ExchangeRate> { it.date }.thenByDescending { it.from == c.name }).firstOrNull()
                val factor = rate?.let { if (it.from == c.name) BigDecimal(it.value) else BigDecimal.ONE.divide(BigDecimal(it.value), MathContext.DECIMAL128) }
                val converted = factor?.let { BigDecimal(units, c.decimals).multiply(it).movePointRight(target.decimals).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact() }
                ConvertedPart(a.id, c, units, converted, factor?.stripTrailingZeros()?.toPlainString(), rate?.date)
            }
        } }
        return ConvertedSummary(target, parts)
    }
}
