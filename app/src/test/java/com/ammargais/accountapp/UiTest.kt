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
    @get:Rule(order = 0) val cleanData = object : org.junit.rules.ExternalResource() {
        override fun before() {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            context.deleteDatabase("daftar.db")
            context.getSharedPreferences("prefs", 0).edit().clear().commit()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
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
        compose.onNodeWithText("استلمت منه").performClick()
        compose.onNodeWithText("المبلغ • درهم إماراتي").performTextInput("40")
        compose.onNodeWithText("حفظ الحركة").performScrollTo().performClick()
        waitFor("60.00 AED")
        screenshot("03_statement")
    }
    @Test fun settleLinkedDebtInAnotherCurrency() {
        waitFor("نظرة على درهم إماراتي")
        val today = java.time.LocalDate.now().toString()
        val debt = com.ammargais.accountapp.core.Ledger.movement("a", com.ammargais.accountapp.core.Currency.USD, java.math.BigInteger("10000"), true, today, "دين تجريبي")
            .copy(action = com.ammargais.accountapp.core.Action.OWED_TO_ME, dueDate = today)
        compose.runOnIdle { ViewModelProvider(compose.activity)[AccountViewModel::class.java].mutate {
            it.saveAccount(com.ammargais.accountapp.core.Account("a", "عميل تجريبي", group = com.ammargais.accountapp.core.AccountGroup.CUSTOMER, favorite = true)).add(debt)
        } }
        try { compose.waitUntil(10000) { ViewModelProvider(compose.activity)[AccountViewModel::class.java].ledger.entries.any { it.id == debt.id } } }
        catch (e: Throwable) {
            val vm = ViewModelProvider(compose.activity)[AccountViewModel::class.java]
            println("Seed status: ready=${vm.ready}, busy=${vm.busy}, accounts=${vm.ledger.accounts}, entries=${vm.ledger.entries}, message=${vm.message}")
            throw e
        }
        compose.onNodeWithText("المستحقات", useUnmergedTree = true).performClick()
        waitFor("تسجيل سداد")
        screenshot("04_due")
        compose.onNode(hasText("تسجيل سداد") and hasAnySibling(hasText("عميل تجريبي"))).performScrollTo().performClick()
        compose.onNodeWithText("المبلغ • دولار أمريكي").performScrollTo().performTextInput("40")
        compose.onNode(isToggleable() and hasAnySibling(hasText("السداد بعملة أخرى"))).performScrollTo().performClick()
        compose.onAllNodesWithText("AED").onLast().performScrollTo().performClick()
        compose.onNodeWithText("المبلغ المدفوع فعليًا • AED").performScrollTo().performTextInput("147")
        screenshot("05_cross_currency")
        compose.onNodeWithText("حفظ الحركة").performScrollTo().performClick()
        waitFor("60.00 USD")
        compose.runOnIdle {
            val ledger = ViewModelProvider(compose.activity)[AccountViewModel::class.java].ledger
            org.junit.Assert.assertEquals(java.math.BigInteger("6000"), ledger.remaining(debt))
            org.junit.Assert.assertEquals(java.math.BigInteger.ZERO, ledger.balance("a", com.ammargais.accountapp.core.Currency.AED))
            org.junit.Assert.assertEquals(java.math.BigInteger("14700"), ledger.entries.last().payment!!.units)
        }
        screenshot("06_paid_statement")
    }

}
