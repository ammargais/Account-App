package com.ammargais.accountapp.core

import org.json.JSONArray
import org.json.JSONObject

object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    fun account(a: Account) = JSONObject().put("id", a.id).put("name", a.name).put("phone", a.phone).put("archived", a.archived)
        .put("group", a.group.name).put("favorite", a.favorite)
    fun account(o: JSONObject) = Account(o.getString("id"), o.getString("name"), o.getString("phone"), o.getBoolean("archived"),
        AccountGroup.valueOf(o.optString("group", "PERSON")), o.optBoolean("favorite", false))
    private fun units(s: String) = s.also { require(it.matches(Regex("-?[0-9]{1,21}"))) { "مبلغ غير صالح في النسخة" } }.toBigInteger()
    fun entry(e: Entry): JSONObject = JSONObject().put("id", e.id).put("date", e.date).put("createdAt", e.createdAt)
        .put("note", e.note).put("reference", e.reference).put("kind", e.kind.name).put("reverses", e.reverses ?: JSONObject.NULL)
        .put("action", e.action.name).put("dueDate", e.dueDate ?: JSONObject.NULL)
        .put("payment", e.payment?.let { JSONObject().put("currency", it.currency.name).put("units", it.units.toString()) } ?: JSONObject.NULL)
        .put("allocation", e.allocation?.let { JSONObject().put("debtId", it.debtId).put("units", it.units.toString()) } ?: JSONObject.NULL)
        .put("attachments", JSONArray(e.attachments.map { JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size) }))
        .put("lines", JSONArray(e.lines.map { JSONObject().put("accountId", it.accountId).put("currency", it.currency.name).put("units", it.units.toString()) }))
    fun entry(o: JSONObject): Entry = Entry(id = o.getString("id"), date = o.getString("date"), createdAt = o.getLong("createdAt"), note = o.getString("note"), reference = o.getString("reference"),
        kind = EntryKind.valueOf(o.getString("kind")), reverses = if (o.isNull("reverses")) null else o.getString("reverses"),
        lines = o.getJSONArray("lines").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Line(it.getString("accountId"), Currency.valueOf(it.getString("currency")), units(it.getString("units"))) } } },
        action = Action.valueOf(o.optString("action", "LEGACY")), dueDate = if (o.isNull("dueDate")) null else o.getString("dueDate"),
        payment = o.optJSONObject("payment")?.let { Payment(Currency.valueOf(it.getString("currency")), units(it.getString("units"))) },
        allocation = o.optJSONObject("allocation")?.let { Allocation(it.getString("debtId"), units(it.getString("units"))) },
        attachments = (o.optJSONArray("attachments") ?: JSONArray()).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Attachment(it.getString("id"), it.getString("name"), it.getString("mime"), it.getLong("size")) } } })
    fun encode(ledger: Ledger): String {
        ledger.validate()
        return JSONObject().put("format", "daftar-backup").put("version", 2)
            .put("accounts", JSONArray(ledger.accounts.map(::account))).put("entries", JSONArray(ledger.entries.map(::entry))).toString()
    }
    fun decode(text: String): Ledger {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "بيانات الدفتر أكبر من 20 ميغابايت" }
        val o = JSONObject(text)
        require(o.getString("format") == "daftar-backup" && o.getInt("version") in 1..2) { "صيغة النسخة غير مدعومة" }
        val a = o.getJSONArray("accounts"); val e = o.getJSONArray("entries")
        require(a.length() <= 5000 && e.length() <= 50000) { "حجم النسخة يتجاوز الحد المسموح" }
        return Ledger((0 until a.length()).map { account(a.getJSONObject(it)) }, (0 until e.length()).map { entry(e.getJSONObject(it)) }).also { it.validate() }
    }
}
