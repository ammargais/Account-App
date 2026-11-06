package com.ammargais.accountapp.core

import org.json.JSONArray
import org.json.JSONObject

object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    fun account(a: Account) = JSONObject().put("id", a.id).put("name", a.name).put("phone", a.phone).put("archived", a.archived)
    fun account(o: JSONObject) = Account(o.getString("id"), o.getString("name"), o.getString("phone"), o.getBoolean("archived"))
    fun entry(e: Entry): JSONObject = JSONObject().put("id", e.id).put("date", e.date).put("createdAt", e.createdAt)
        .put("note", e.note).put("reference", e.reference).put("kind", e.kind.name).put("reverses", e.reverses ?: JSONObject.NULL)
        .put("lines", JSONArray(e.lines.map { JSONObject().put("accountId", it.accountId).put("currency", it.currency.name).put("units", it.units.toString()) }))
    fun entry(o: JSONObject): Entry = Entry(o.getString("id"), o.getString("date"), o.getLong("createdAt"), o.getString("note"), o.getString("reference"),
        EntryKind.valueOf(o.getString("kind")), if (o.isNull("reverses")) null else o.getString("reverses"),
        o.getJSONArray("lines").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Line(it.getString("accountId"), Currency.valueOf(it.getString("currency")), it.getString("units").also { s -> require(s.length <= 22) }.toBigInteger()) } } })
    fun encode(ledger: Ledger): String {
        ledger.validate()
        return JSONObject().put("format", "daftar-backup").put("version", 1)
            .put("accounts", JSONArray(ledger.accounts.map(::account))).put("entries", JSONArray(ledger.entries.map(::entry))).toString()
    }
    fun decode(text: String): Ledger {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "ملف النسخة أكبر من 20 ميغابايت" }
        val o = JSONObject(text)
        require(o.getString("format") == "daftar-backup" && o.getInt("version") == 1) { "صيغة النسخة غير مدعومة" }
        val a = o.getJSONArray("accounts"); val e = o.getJSONArray("entries")
        require(a.length() <= 5000 && e.length() <= 50000) { "حجم النسخة يتجاوز الحد المسموح" }
        return Ledger((0 until a.length()).map { account(a.getJSONObject(it)) }, (0 until e.length()).map { entry(e.getJSONObject(it)) }).also { it.validate() }
    }
}
