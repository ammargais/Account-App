package com.ammargais.accountapp.io

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.ammargais.accountapp.MainActivity
import com.ammargais.accountapp.R
import com.ammargais.accountapp.core.SecureBackup
import com.ammargais.accountapp.data.LedgerDb
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretStore {
    private const val ALIAS = "daftar.auto-backup.password.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun seal(password: CharArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        return try { Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP) } finally { bytes.fill(0) }
    }
    fun open(value: String): CharArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val clear = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try { clear.toString(Charsets.UTF_8).toCharArray() } finally { clear.fill(0) }
    }
}
object BackgroundServices {
    const val PREFS = "prefs"
    private val backupLock = Mutex()
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun configureBackup(context: Context, uri: Uri, password: CharArray) {
        require(password.size >= 10) { "كلمة المرور لا تقل عن 10 أحرف" }
        val tree = DocumentFile.fromTreeUri(context, uri)
        require(tree?.canWrite() == true) { "المجلد لا يسمح بالكتابة؛ اختر مجلدًا آخر" }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val sealed = SecretStore.seal(password)
        prefs(context).edit().putString("backupTree", uri.toString()).putString("backupSecret", sealed).putBoolean("autoBackup", true)
            .putString("backupLocation", tree.name ?: "المجلد المختار").remove("backupError").commit()
        schedule(context); afterChange(context)
    }
    fun stopBackup(context: Context) {
        prefs(context).edit().putBoolean("autoBackup", false).remove("backupSecret").apply()
        WorkManager.getInstance(context).cancelUniqueWork("daftar-backup-daily")
        WorkManager.getInstance(context).cancelUniqueWork("daftar-backup-change")
    }
    fun schedule(context: Context) {
        if (!prefs(context).getBoolean("autoBackup", false) && !prefs(context).getBoolean("reminders", false)) return
        val wm = WorkManager.getInstance(context)
        if (prefs(context).getBoolean("autoBackup", false)) wm.enqueueUniquePeriodicWork("daftar-backup-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        if (prefs(context).getBoolean("reminders", false)) wm.enqueueUniquePeriodicWork("daftar-due-reminder", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DueWorker>(24, TimeUnit.HOURS).build())
        else wm.cancelUniqueWork("daftar-due-reminder")
    }
    fun setReminders(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("reminders", enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork("daftar-due-reminder")
    }
    fun afterChange(context: Context) {
        if (prefs(context).getBoolean("autoBackup", false)) WorkManager.getInstance(context).enqueueUniqueWork("daftar-backup-change", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(30, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    suspend fun backup(context: Context) = backupLock.withLock {
        val prefs = prefs(context)
        if (!prefs.getBoolean("autoBackup", false)) return@withLock
        val password = SecretStore.open(prefs.getString("backupSecret", null) ?: error("أعد إعداد النسخ الآلي"))
        val snapshot = LedgerDb(context).use { it.load() }
        val bytes = try { SecureBackup.encrypt(SecureBackup.pack(snapshot) { Attachments(context).file(it).readBytes() }, password) } finally { password.fill('\u0000') }
        currentCoroutineContext().ensureActive()
        if (!prefs.getBoolean("autoBackup", false)) return@withLock
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(prefs.getString("backupTree", ""))) ?: error("اختر مجلد النسخ مجددًا")
        val name = "Daftar_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}_${UUID.randomUUID().toString().take(6)}.daftar"
        val dest = tree.createFile("application/octet-stream", name) ?: error("تعذر إنشاء نسخة في المجلد المختار")
        try {
            context.contentResolver.openOutputStream(dest.uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("تعذر كتابة النسخة")
            val verified = context.contentResolver.openInputStream(dest.uri)?.use { it.readLimited(SecureBackup.MAX_BYTES + 52) } ?: error("تعذر التحقق من النسخة")
            check(SecureBackup.hash(verified) == SecureBackup.hash(bytes)) { "النسخة غير مكتملة" }
            prefs.edit().putString("lastBackup", LocalDateTime.now().toString().take(16).replace('T', ' ')).putString("lastBackupName", name).remove("backupError").commit()
        } catch (e: Exception) { dest.delete(); throw e }
    }
    fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("daftar_status", "تنبيهات دفتر", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, "daftar_status").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text)
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}
class BackupWorker(app: Context, params: WorkerParameters) : CoroutineWorker(app, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try { BackgroundServices.backup(applicationContext); Result.success() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            BackgroundServices.prefs(applicationContext).edit().putString("backupError", "تعذر النسخ الآلي. تحقق من المجلد والمساحة أو أعد اختياره؛ النسخ السابقة محفوظة.").commit()
            BackgroundServices.notify(applicationContext, 41, "تعذر حفظ نسخة دفتر", "افتح الإعدادات للتحقق من مكان النسخ الاحتياطي")
            Result.retry()
        }
    }
}
class DueWorker(app: Context, params: WorkerParameters) : CoroutineWorker(app, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = BackgroundServices.prefs(applicationContext)
        if (!prefs.getBoolean("reminders", false)) return@withContext Result.success()
        try {
            val today = LocalDate.now().toString()
            val ledger = LedgerDb(applicationContext).use { it.load() }
            val count = ledger.openDebts(through = today).count { it.dueDate != null && it.dueDate <= today }
            if (count > 0) BackgroundServices.notify(applicationContext, 42, "لديك مستحقات تحتاج مراجعة", "افتح دفتر لمراجعة المواعيد والسدادات")
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
