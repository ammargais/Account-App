package com.ammargais.accountapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ammargais.accountapp.core.*
import org.json.JSONObject

/** SQLite is the durable source of truth. All writes are atomic; journal rows are immutable. */
class LedgerDb(context: Context, name: String = "daftar.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE journal (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("تحتاج قاعدة البيانات ترقية مدعومة؛ لم نحذف بياناتك")
    }
    @Synchronized fun load(): Ledger {
        val accounts = readableDatabase.rawQuery("SELECT payload FROM accounts ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(BackupCodec.account(JSONObject(c.getString(0)))) }
        }
        val entries = readableDatabase.rawQuery("SELECT payload FROM journal ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(BackupCodec.entry(JSONObject(c.getString(0)))) }
        }
        return Ledger(accounts, entries).also { it.validate() }
    }
    @Synchronized fun update(transform: (Ledger) -> Ledger): Ledger {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = load()
            val next = transform(old).also { it.validate() }
            val oldEntries = old.entries.associateBy { it.id }
            val nextEntries = next.entries.associateBy { it.id }
            val nextAccountIds = next.accounts.map { it.id }.toSet()
            val oldAccounts = old.accounts.associateBy { it.id }
            require(old.entries.all { nextEntries[it.id] == it }) { "لا يمكن حذف أو تغيير حركة محفوظة؛ استخدم العكس" }
            require(old.accounts.all { it.id in nextAccountIds }) { "استخدم أرشفة الحساب بدل الحذف" }
            next.accounts.filter { oldAccounts[it.id] != it }.forEach { a ->
                db.insertWithOnConflict("accounts", null, ContentValues().apply { put("id", a.id); put("payload", BackupCodec.account(a).toString()) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            }
            next.entries.filter { it.id !in oldEntries }.forEach { e ->
                db.insertOrThrow("journal", null, ContentValues().apply { put("id", e.id); put("payload", BackupCodec.entry(e).toString()) })
            }
            db.setTransactionSuccessful()
            return next
        } finally { db.endTransaction() }
    }
}
