package com.ammargais.accountapp

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ammargais.accountapp.core.*
import com.ammargais.accountapp.io.SecretStore
import com.ammargais.accountapp.io.StatementPdf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger

@RunWith(AndroidJUnit4::class)
class DeviceFeatureTest {
    @Test fun nativePdfIsReadableAndHasMultiplePages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = Account("pdf-test", "حساب تجريبي عربي")
        var ledger = Ledger(listOf(account), currencies = Currency.entries)
        repeat(30) { index -> ledger = ledger.add(Ledger.movement(account.id, Currency.USD, BigInteger("10000"), true, "2026-11-01", "حجز تجريبي ${index + 1} — بيان عربي واضح", "REF-$index")) }
        val file = File(context.cacheDir, "device-statement.pdf")
        file.outputStream().use { StatementPdf.write(it, ledger, account, null, "2026-11-01", "2026-11-06") }
        assertTrue(file.length() > 1000)
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertTrue(renderer.pageCount >= 2)
            renderer.openPage(0).use { page ->
                val bitmap = android.graphics.Bitmap.createBitmap(595, 842, android.graphics.Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pixels = IntArray(595 * 842); bitmap.getPixels(pixels, 0, 595, 0, 0, 595, 842)
                assertTrue(pixels.any { android.graphics.Color.alpha(it) > 0 && (it and 0x00FFFFFF) != 0x00FFFFFF })
                bitmap.recycle()
            }
        }
        file.delete()
    }
    @Test fun keystoreProtectsAndRecoversAutomaticBackupPassword() {
        val password = "Synthetic backup test secret".toCharArray()
        val sealed = SecretStore.seal(password)
        assertFalse(sealed.contains(String(password)))
        assertArrayEquals(password, SecretStore.open(sealed))
        assertNotEquals(sealed, SecretStore.seal(password))
    }
}
