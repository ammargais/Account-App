package com.ammargais.accountapp.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable, authenticated password backup. No password or key is stored in the file. */
object SecureBackup {
    const val MAX_BYTES = 100 * 1024 * 1024
    private const val ITERATIONS = 210_000
    private val MAGIC = "DAFTAR02".toByteArray(Charsets.US_ASCII)
    data class Bundle(val ledger: Ledger, val files: Map<String, ByteArray>)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun isEncrypted(bytes: ByteArray) = bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 10) { "استخدم كلمة مرور من 10 أحرف على الأقل" }
        require(plain.size <= MAX_BYTES) { "النسخة أكبر من 100 ميغابايت" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes); val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = MAGIC + salt + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce)); cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }
    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        require(isEncrypted(bytes) && bytes.size in 52..MAX_BYTES + 52) { "ملف النسخة المشفرة غير صالح" }
        val header = bytes.copyOfRange(0, 36)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, bytes.copyOfRange(8, 24)), GCMParameterSpec(128, bytes.copyOfRange(24, 36)))
            cipher.updateAAD(header); cipher.doFinal(bytes, 36, bytes.size - 36)
        } catch (_: java.security.GeneralSecurityException) { throw IllegalArgumentException("كلمة المرور غير صحيحة أو الملف تالف؛ لم تتغير بياناتك") }
    }
    fun pack(ledger: Ledger, load: (Attachment) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); var total = 0L
        ZipOutputStream(out).use { zip ->
            val data = BackupCodec.encode(ledger).toByteArray(Charsets.UTF_8)
            require(data.size <= BackupCodec.MAX_BYTES); total += data.size
            zip.putNextEntry(ZipEntry("ledger.json")); zip.write(data); zip.closeEntry()
            ledger.entries.flatMap { it.attachments }.distinctBy { it.id }.forEach { a ->
                val bytes = load(a)
                require(bytes.size.toLong() == a.size && hash(bytes) == a.id) { "تعذر التحقق من المرفق: ${a.name}" }
                total += bytes.size; require(total <= MAX_BYTES) { "حجم النسخة مع المرفقات يتجاوز 100 ميغابايت" }
                zip.putNextEntry(ZipEntry("attachments/${a.id}")); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray().also { require(it.size <= MAX_BYTES) }
    }
    fun unpack(bytes: ByteArray): Bundle {
        require(bytes.size <= MAX_BYTES) { "النسخة أكبر من 100 ميغابايت" }
        val files = mutableMapOf<String, ByteArray>(); var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && (entry.name == "ledger.json" || entry.name.matches(Regex("attachments/[a-f0-9]{64}"))) && entry.name !in files) { "محتوى النسخة غير صالح" }
                val out = ByteArrayOutputStream(); val block = ByteArray(8192)
                val limit = if (entry.name == "ledger.json") BackupCodec.MAX_BYTES else 10_485_760
                while (true) { val n = zip.read(block); if (n < 0) break; total += n; require(total <= MAX_BYTES && out.size() + n <= limit) { "النسخة تتجاوز الحجم المسموح" }; out.write(block, 0, n) }
                files[entry.name] = out.toByteArray(); require(files.size <= 50001) { "ملفات كثيرة في النسخة" }
            }
        }
        val ledger = BackupCodec.decode((files.remove("ledger.json") ?: error("النسخة لا تحتوي دفترًا")).toString(Charsets.UTF_8))
        val refs = ledger.entries.flatMap { it.attachments }.distinctBy { it.id }
        require(files.size == refs.size) { "عدد المرفقات لا يطابق الدفتر" }
        refs.forEach { a -> val data = files["attachments/${a.id}"]; require(data != null && data.size.toLong() == a.size && hash(data) == a.id) { "مرفق مفقود أو تالف: ${a.name}" } }
        return Bundle(ledger, files.mapKeys { it.key.removePrefix("attachments/") })
    }
}
