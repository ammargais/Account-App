package com.ammargais.accountapp.core

import org.json.JSONArray
import org.json.JSONObject

object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    fun account(a: Account) = JSONObject().put("id", a.id).put("name", a.name).put("phone", a.phone).put("archived", a.archived)
        .put("group", a.group.name).put("favorite", a.favorite)
        .put("currencyCodes", a.currencyCodes?.let { JSONArray(it) } ?: JSONObject.NULL).put("summaryCurrency", a.summaryCurrency ?: JSONObject.NULL)
    fun account(o: JSONObject) = Account(o.getString("id"), o.getString("name"), o.getString("phone"), o.getBoolean("archived"),
        AccountGroup.valueOf(o.optString("group", "PERSON")), o.optBoolean("favorite", false),
        o.optJSONArray("currencyCodes")?.let { a -> (0 until a.length()).map { a.getString(it) } },
        if (o.isNull("summaryCurrency")) null else o.getString("summaryCurrency"))
    private fun units(s: String) = s.also { require(it.matches(Regex("-?[0-9]{1,23}"))) { "مبلغ غير صالح في النسخة" } }.toBigInteger()
    fun entry(e: Entry): JSONObject = JSONObject().put("id", e.id).put("date", e.date).put("createdAt", e.createdAt)
        .put("note", e.note).put("reference", e.reference).put("kind", e.kind.name).put("reverses", e.reverses ?: JSONObject.NULL)
        .put("action", e.action.name).put("dueDate", e.dueDate ?: JSONObject.NULL)
        .put("payment", e.payment?.let { JSONObject().put("currency", it.currency.name).put("units", it.units.toString()) } ?: JSONObject.NULL)
        .put("allocation", e.allocation?.let { JSONObject().put("debtId", it.debtId).put("units", it.units.toString()) } ?: JSONObject.NULL)
        .put("attachments", JSONArray(e.attachments.map { JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size) }))
        .put("lines", JSONArray(e.lines.map { JSONObject().put("accountId", it.accountId).put("currency", it.currency.name).put("units", it.units.toString()) }))
    fun entry(o: JSONObject, currencies: List<Currency> = Currency.entries): Entry = Entry(id = o.getString("id"), date = o.getString("date"), createdAt = o.getLong("createdAt"), note = o.getString("note"), reference = o.getString("reference"),
        kind = EntryKind.valueOf(o.getString("kind")), reverses = if (o.isNull("reverses")) null else o.getString("reverses"),
        lines = o.getJSONArray("lines").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Line(it.getString("accountId"), currencies.firstOrNull { c -> c.name == it.getString("currency") } ?: throw IllegalArgumentException("عملة غير معرفة في النسخة"), units(it.getString("units"))) } } },
        action = Action.valueOf(o.optString("action", "LEGACY")), dueDate = if (o.isNull("dueDate")) null else o.getString("dueDate"),
        payment = o.optJSONObject("payment")?.let { Payment(currencies.firstOrNull { c -> c.name == it.getString("currency") } ?: throw IllegalArgumentException("عملة غير معرفة في النسخة"), units(it.getString("units"))) },
        allocation = o.optJSONObject("allocation")?.let { Allocation(it.getString("debtId"), units(it.getString("units"))) },
        attachments = (o.optJSONArray("attachments") ?: JSONArray()).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Attachment(it.getString("id"), it.getString("name"), it.getString("mime"), it.getLong("size")) } } })
    fun metadata(ledger: Ledger): JSONObject = JSONObject()
        .put("currencies", JSONArray(ledger.currencies.map { JSONObject().put("code", it.name).put("title", it.title).put("decimals", it.decimals) }))
        .put("summaryCurrency", ledger.settings.summaryCurrency ?: JSONObject.NULL)
        .put("rates", JSONArray(ledger.settings.rates.map { JSONObject().put("from", it.from).put("to", it.to).put("value", it.value).put("date", it.date) }))
    fun currencies(o: JSONObject): List<Currency> = o.getJSONArray("currencies").let { a ->
        require(a.length() <= 100)
        (0 until a.length()).map { a.getJSONObject(it).let { c -> Currency(c.getString("code"), c.getString("title"), c.getInt("decimals")).also { it.validate() } } }
    }
    fun settings(o: JSONObject): BookSettings = BookSettings(
        if (o.isNull("summaryCurrency")) null else o.getString("summaryCurrency"),
        (o.optJSONArray("rates") ?: JSONArray()).let { a -> require(a.length() <= 10000); (0 until a.length()).map { a.getJSONObject(it).let { r -> ExchangeRate(r.getString("from"), r.getString("to"), r.getString("value"), r.getString("date")) } } })
    fun encode(ledger: Ledger): String {
        ledger.validate()
        return JSONObject().put("format", "daftar-backup").put("version", 3).put("metadata", metadata(ledger))
            .put("accounts", JSONArray(ledger.accounts.map(::account))).put("entries", JSONArray(ledger.entries.map(::entry))).toString()
    }
    fun decode(text: String): Ledger {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "بيانات الدفتر أكبر من 20 ميغابايت" }
        val o = JSONObject(text)
        require(o.getString("format") == "daftar-backup" && o.getInt("version") in 1..3) { "صيغة النسخة غير مدعومة" }
        val a = o.getJSONArray("accounts"); val e = o.getJSONArray("entries")
        require(a.length() <= 5000 && e.length() <= 50000) { "حجم النسخة يتجاوز الحد المسموح" }
        val meta = o.optJSONObject("metadata")
        require(o.getInt("version") < 3 || meta != null) { "تعريفات العملات مفقودة" }
        val catalog = meta?.let(::currencies) ?: Currency.entries
        val entries = (0 until e.length()).map { entry(e.getJSONObject(it), catalog) }
        val used = entries.flatMap { it.lines.map { l -> l.currency } + listOfNotNull(it.payment?.currency) }.distinct()
        return Ledger((0 until a.length()).map { account(a.getJSONObject(it)) }, entries, if (meta == null) used else catalog,
            meta?.let(::settings) ?: BookSettings()).also { it.validate() }
    }
}
