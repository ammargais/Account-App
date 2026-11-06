package com.ammargais.accountapp

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Explicit width/height qualifiers trigger Robolectric #8460 for dialogs with text fields.
@Config(sdk = [35], qualifiers = "ar-rSD-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) {
        try { compose.waitUntil(15000) { compose.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: Throwable) { println(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 20)); screenshot("failure"); throw e }
    }
    private fun screenshot(name: String) {
        val file = File("build/screenshots/$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun createAccountRecordDebtAndRepayment() {
        waitFor("نظرة على درهم إماراتي")
        screenshot("01_empty")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose.onNodeWithText("إضافة أول حساب").performScrollTo().performClick()
        compose.onNodeWithText("اسم الشخص أو الجهة").performTextInput("حساب تجريبي")
        compose.onNodeWithText("حفظ الحساب").performClick()
        compose.waitUntil(10000) { ViewModelProvider(compose.activity)[AccountViewModel::class.java].ledger.accounts.size == 1 }
        // Verify the saved account and navigation state survive Activity recreation.
        compose.activityRule.scenario.recreate()
        waitFor("حركة جديدة")
        compose.onNodeWithContentDescription("حركة جديدة").performClick()
        compose.onNodeWithText("اختر الحساب").performClick()
        compose.onNodeWithText("حساب تجريبي").performClick()
        compose.onNodeWithText("المبلغ • درهم إماراتي").performTextInput("100")
        screenshot("02_entry")
        compose.onNodeWithText("حفظ الحركة").performScrollTo().performClick()
        waitFor("الرصيد الحالي")
        compose.onAllNodesWithText("100.00 AED").onFirst().assertExists()
        compose.onNodeWithContentDescription("حركة جديدة").performClick()
        compose.onNodeWithText("دائن • علينا").performClick()
        compose.onNodeWithText("المبلغ • درهم إماراتي").performTextInput("40")
        compose.onNodeWithText("حفظ الحركة").performScrollTo().performClick()
        waitFor("60.00 AED")
        screenshot("03_statement")
    }
}
