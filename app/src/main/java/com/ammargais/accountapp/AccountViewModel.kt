package com.ammargais.accountapp

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ammargais.accountapp.core.*
import com.ammargais.accountapp.data.LedgerDb
import com.ammargais.accountapp.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class AccountViewModel(app: Application) : AndroidViewModel(app) {
    private val database = LedgerDb(app)
    private val lock = Mutex()
    var ledger by mutableStateOf(Ledger()); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var pendingRestore by mutableStateOf<Ledger?>(null); private set
    private var restoreFiles: Map<String, ByteArray> = emptyMap()
    init { reload() }
    fun clearMessage() { message = null }
    fun dismissRestore() { pendingRestore = null; restoreFiles = emptyMap() }
    fun reload() = runTask {
        ledger = withContext(Dispatchers.IO) { lock.withLock { database.load() } }
        ready = true
    }
    fun mutate(success: () -> Unit = {}, transform: (Ledger) -> Ledger) = runTask {
        check(ready) { "انتظر تحميل دفتر الحسابات" }
        ledger = withContext(Dispatchers.IO) { lock.withLock { database.update(transform) } }
        BackgroundServices.afterChange(getApplication())
        message = "تم الحفظ في الجهاز"
        success()
    }
    fun importAttachment(uri: Uri, success: (Attachment) -> Unit) = runTask {
        val a = withContext(Dispatchers.IO) { Attachments(getApplication()).import(uri) }
        success(a)
    }
    fun openAttachment(a: Attachment) = runTask { Attachments(getApplication()).open(a) }
    fun exportBackup(uri: Uri, password: CharArray) = runTask {
        val snapshot = ledger
        try {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val bytes = SecureBackup.encrypt(SecureBackup.pack(snapshot) { Attachments(app).file(it).readBytes() }, password)
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("تعذر فتح مكان الحفظ")
                val saved = app.contentResolver.openInputStream(uri)?.use { it.readLimited(SecureBackup.MAX_BYTES + 52) } ?: error("تعذر التحقق من النسخة")
                check(SecureBackup.hash(saved) == SecureBackup.hash(bytes)) { "النسخة غير مكتملة؛ أعد الحفظ" }
                BackgroundServices.prefs(app).edit().putString("lastBackup", java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')).apply()
            }
            message = "تم حفظ النسخة المشفرة مع المرفقات"
        } finally { password.fill('\u0000') }
    }
    fun configureBackup(uri: Uri, password: CharArray) = runTask {
        try { withContext(Dispatchers.IO) { BackgroundServices.configureBackup(getApplication(), uri, password) }; message = "تم تفعيل النسخ اليومي وبعد التغييرات. ستظهر نتيجة أول نسخة في الإعدادات." }
        finally { password.fill('\u0000') }
    }
    fun readBackup(uri: Uri, password: CharArray) = runTask {
        try {
            val bundle = withContext(Dispatchers.IO) {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readLimited(SecureBackup.MAX_BYTES + 52) } ?: error("تعذر قراءة الملف")
                if (SecureBackup.isEncrypted(bytes)) SecureBackup.unpack(SecureBackup.decrypt(bytes, password))
                else SecureBackup.Bundle(BackupCodec.decode(bytes.toString(Charsets.UTF_8)), emptyMap()).also { require(it.ledger.entries.all { e -> e.attachments.isEmpty() }) { "هذه النسخة لا تتضمن المرفقات؛ اختر ملف دفتر المشفر" } }
            }
            ledger.merge(bundle.ledger)
            pendingRestore = bundle.ledger; restoreFiles = bundle.files
        } finally { password.fill('\u0000') }
    }
    fun restore() {
        val snapshot = pendingRestore ?: return
        val files = restoreFiles
        runTask {
            ledger = withContext(Dispatchers.IO) { lock.withLock {
                database.update { current ->
                    val merged = current.merge(snapshot)
                    files.forEach { (id, bytes) -> Attachments(getApplication()).install(id, bytes) }
                    merged
                }
            } }
            dismissRestore(); BackgroundServices.afterChange(getApplication()); message = "تمت الاستعادة والدمج مع المرفقات"
        }
    }
    fun exportCsv(uri: Uri, accountId: String, currency: Currency?, from: String, to: String) = runTask {
        val snapshot = ledger
        withContext(Dispatchers.IO) {
            val account = snapshot.accounts.first { it.id == accountId }
            val csv = Statement.csv(snapshot, account, currency, from, to)
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)); it.flush() } ?: error("تعذر حفظ كشف الحساب")
        }
        message = "تم حفظ كشف CSV؛ يمكنك فتحه في Excel"
    }
    fun exportPdf(uri: Uri?, accountId: String, currency: Currency?, from: String, to: String) = runTask {
        val snapshot = ledger; val app = getApplication<Application>()
        val account = snapshot.accounts.first { it.id == accountId }
        val shareFile = withContext(Dispatchers.IO) {
            if (uri != null) { app.contentResolver.openOutputStream(uri, "wt")?.use { StatementPdf.write(it, snapshot, account, currency, from, to) } ?: error("تعذر حفظ PDF"); null }
            else File(app.cacheDir, "statements").apply { mkdirs() }.let { folder ->
                File(folder, "Daftar_${java.util.UUID.randomUUID()}.pdf").also { file -> file.outputStream().use { StatementPdf.write(it, snapshot, account, currency, from, to) } }
            }
        }
        if (shareFile != null) {
            val shared = FileProvider.getUriForFile(app, "${app.packageName}.files", shareFile)
            app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, shared); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "مشاركة كشف PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else message = "تم حفظ كشف PDF"
    }
    private fun runTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { message = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "تعذر تنفيذ العملية" else "تعذر تنفيذ العملية. تأكد من الملف أو مساحة الجهاز وحاول مجددًا." }
            finally { busy = false }
        }
    }
    override fun onCleared() { database.close(); super.onCleared() }
}
