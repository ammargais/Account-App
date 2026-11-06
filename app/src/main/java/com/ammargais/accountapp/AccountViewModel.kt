package com.ammargais.accountapp

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ammargais.accountapp.core.*
import com.ammargais.accountapp.data.LedgerDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AccountViewModel(app: Application) : AndroidViewModel(app) {
    private val database = LedgerDb(app)
    private val lock = Mutex()
    var ledger by mutableStateOf(Ledger()); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var pendingRestore by mutableStateOf<Ledger?>(null); private set
    init { reload() }
    fun clearMessage() { message = null }
    fun dismissRestore() { pendingRestore = null }
    fun reload() = runTask {
        ledger = withContext(Dispatchers.IO) { lock.withLock { database.load() } }
        ready = true
    }
    fun mutate(success: () -> Unit = {}, transform: (Ledger) -> Ledger) = runTask {
        check(ready) { "انتظر تحميل دفتر الحسابات" }
        ledger = withContext(Dispatchers.IO) { lock.withLock { database.update(transform) } }
        message = "تم الحفظ في الجهاز"
        success()
    }
    fun exportBackup(uri: Uri) = runTask {
        val snapshot = ledger
        withContext(Dispatchers.IO) {
            val bytes = BackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
            require(bytes.size <= BackupCodec.MAX_BYTES) { "النسخة أكبر من الحد المدعوم" }
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("تعذر فتح مكان الحفظ")
        }
        getApplication<Application>().getSharedPreferences("prefs", 0).edit().putString("lastBackup", java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')).apply()
        message = "تم حفظ النسخة في المكان المختار"
    }
    fun readBackup(uri: Uri) = runTask {
        pendingRestore = withContext(Dispatchers.IO) {
            val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= BackupCodec.MAX_BYTES) { "الملف أكبر من 20 ميغابايت" }; output.write(buffer, 0, count) }
                output.toByteArray()
            } ?: error("تعذر قراءة الملف")
            BackupCodec.decode(bytes.toString(Charsets.UTF_8)).also { ledger.merge(it) }
        }
    }
    fun restore() { val snapshot = pendingRestore ?: return; mutate({ pendingRestore = null }) { it.merge(snapshot) } }
    fun exportCsv(uri: Uri, accountId: String, currency: Currency?, from: String, to: String) = runTask {
        val snapshot = ledger
        withContext(Dispatchers.IO) {
            val account = snapshot.accounts.first { it.id == accountId }
            val csv = Statement.csv(snapshot, account, currency, from, to)
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)); it.flush() } ?: error("تعذر حفظ كشف الحساب")
        }
        message = "تم حفظ كشف CSV؛ يمكنك فتحه في Excel"
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
