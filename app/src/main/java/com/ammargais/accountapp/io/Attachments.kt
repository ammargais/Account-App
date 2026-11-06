package com.ammargais.accountapp.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.ammargais.accountapp.core.Attachment
import com.ammargais.accountapp.core.SecureBackup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

fun InputStream.readLimited(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "الملف أكبر من الحجم المسموح" }; out.write(buffer, 0, n) }
    return out.toByteArray()
}
class Attachments(private val context: Context) {
    private val root get() = File(context.filesDir, "receipts").apply { mkdirs() }
    fun file(a: Attachment) = File(root, a.id.also { require(it.matches(Regex("[a-f0-9]{64}"))) })
    fun import(uri: Uri): Attachment {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        require(mime.startsWith("image/") || mime == "application/pdf") { "اختر صورة أو ملف PDF" }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "إشعار"
        val bytes = resolver.openInputStream(uri)?.use { it.readLimited(10_485_760) } ?: error("تعذر فتح المرفق")
        require(bytes.isNotEmpty()) { "المرفق فارغ" }
        val id = SecureBackup.hash(bytes)
        install(id, bytes)
        return Attachment(id, name.take(160), mime, bytes.size.toLong())
    }
    fun install(id: String, bytes: ByteArray) {
        require(id.matches(Regex("[a-f0-9]{64}")) && SecureBackup.hash(bytes) == id)
        val dest = File(root, id)
        if (!dest.exists()) {
            val temp = File.createTempFile("receipt", ".tmp", root)
            try { temp.outputStream().use { it.write(bytes); it.fd.sync() }; check(temp.renameTo(dest) || dest.exists()) { "تعذر حفظ المرفق" } } finally { temp.delete() }
        }
    }
    fun open(a: Attachment) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file(a))
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "فتح المرفق").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
