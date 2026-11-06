package com.ammargais.accountapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ammargais.accountapp.core.*
import java.time.LocalDate
import java.util.Locale

@Composable internal fun CurrencyDropdown(label: String, currencies: List<Currency>, code: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = currencies.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("$label: ${code.ifEmpty { "اختر العملة" }}") }
        DropdownMenu(open, { open = false }) { currencies.forEach { c ->
            DropdownMenuItem(text = { Text("${c.name} • ${c.title}") }, onClick = { onSelect(c.name); open = false })
        } }
    }
}

@Composable internal fun ConvertedSummaryCard(ledger: Ledger, code: String?, accountId: String? = null,
    through: String = LocalDate.now().toString(), onConfigure: (() -> Unit)? = null) {
    val target = ledger.currencies.find { it.name == code }
    if (target == null) {
        Info(if (accountId == null) "اختر عملة الملخص العام من إدارة العملات." else "اختر عملة ملخص هذا الحساب من تعديل الحساب.")
        return
    }
    val date = if (through == "9999-12-31") LocalDate.now().toString() else through
    val summary = remember(ledger, target, accountId, date) { Reporting.summarize(ledger, target, accountId, date) }
    var expanded by rememberSaveable(accountId, code) { mutableStateOf(false) }
    Surface(color = if(summary.complete) Green else MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val color = if(summary.complete) Color.White else MaterialTheme.colorScheme.onErrorContainer
            Text("عملة الملخص: ${target.name}", color = color)
            if (summary.complete) {
                Text("صافي الرصيد • ${Statement.side(summary.net!!)}", color = color)
                NumberText("${Money.format(summary.net!!.abs(), target)} ${target.name}", color = color, large = true)
                Text("لنا: ${ltr(Money.format(summary.receivable!!, target))}  •  علينا: ${ltr(Money.format(summary.payable!!, target))}", color = color)
            } else {
                Text("الملخص غير مكتمل", fontWeight = FontWeight.Bold, color = color)
                Text("أضف سعر تحويل من ${summary.missing.joinToString("، ")} إلى ${target.name}.", color = color)
                if(onConfigure != null) TextButton(onClick = onConfigure) { Text("إكمال أسعار التحويل") }
            }
            TextButton(onClick = { expanded = !expanded }, colors = ButtonDefaults.textButtonColors(contentColor = color)) { Text(if(expanded) "إخفاء التفاصيل" else "كيف حُسب الملخص؟") }
            if(expanded) {
                Text("حسب أرصدة وأسعار $date. الحركات الأصلية محفوظة بعملاتها.", color = color, style = MaterialTheme.typography.bodySmall)
                summary.parts.forEach { p ->
                    val name = ledger.accounts.first { it.id == p.accountId }.name
                    Text("$name • ${p.currency.name} ${Money.format(p.original.abs(), p.currency)} ${Statement.side(p.original)}", color = color)
                    Text(p.converted?.let { "${Money.format(it.abs(), target)} ${target.name} • السعر ${p.rate}${p.rateDate?.let { d -> " بتاريخ $d" } ?: ""}" } ?: "سعر التحويل غير متوفر", color = color, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable internal fun CurrencySettingsScreen(vm: AccountViewModel) {
    val ledger = vm.ledger
    var code by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var decimals by rememberSaveable { mutableStateOf("2") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var error by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("العملات التي تتعامل بها")
        if(ledger.currencies.isEmpty()) Info("لا توجد عملات مضافة. اكتب اسم العملة ورمزها لتبدأ.")
        ledger.currencies.forEach { Text("${it.name} • ${it.title} • ${it.decimals} منازل عشرية") }
        OutlinedTextField(code, { code = it.uppercase(Locale.ROOT).take(12) }, Modifier.fillMaxWidth(), label = { Text("رمز العملة") }, singleLine = true)
        OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("اسم العملة") }, singleLine = true)
        OutlinedTextField(decimals, { decimals = it.take(1) }, Modifier.fillMaxWidth(), label = { Text("المنازل العشرية من 0 إلى 8") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("تُحفظ دقة العملة لحماية مبالغ الحركات السابقة.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            try {
                val c = Currency(code.trim(), title.trim(), decimals.toIntOrNull() ?: -1); c.validate()
                vm.mutate({ code = ""; title = ""; error = "" }) { it.addCurrency(c) }
            } catch(e: IllegalArgumentException) { error = e.message ?: "تحقق من بيانات العملة" }
        }, enabled = !vm.busy && code.isNotBlank() && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("إضافة العملة") }
        HorizontalDivider()
        CurrencyDropdown("عملة الملخص العام", ledger.currencies, ledger.settings.summaryCurrency ?: "") { selected ->
            vm.mutate { it.copy(settings = it.settings.copy(summaryCurrency = selected)) }
        }
        if (ledger.currencies.size >= 2) {
            Section("أسعار التحويل للملخص")
            Text("السعر يعني: وحدة واحدة من العملة الأولى تساوي المبلغ المكتوب من الثانية. لا يُغيّر تسجيل السعر الحركات.")
            CurrencyDropdown("من عملة", ledger.currencies, from) { from = it }
            CurrencyDropdown("إلى عملة", ledger.currencies, to) { to = it }
            OutlinedTextField(value, { value = it.take(31).map { c -> when(c) { in '٠'..'٩' -> '0' + (c-'٠'); '٫' -> '.'; else -> c } }.joinToString("") }, Modifier.fillMaxWidth(),
                label = { Text("سعر الوحدة") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            DateField(date, { date = it }, "تاريخ السعر", Modifier.fillMaxWidth())
            Button(onClick = {
                try {
                    val rate = ExchangeRate(from, to, value.trim(), date); rate.validate(ledger.currencies)
                    vm.mutate({ value = ""; error = "" }) { it.copy(settings = it.settings.withRate(rate)) }
                } catch(e: IllegalArgumentException) { error = e.message ?: "تحقق من سعر التحويل" }
            }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("حفظ سعر التحويل") }
            ledger.settings.rates.sortedByDescending { it.date }.take(20).forEach { r ->
                Text(ltr("1 ${r.from} = ${r.value} ${r.to}") + " • ${r.date}")
            }
        }
        if(error.isNotBlank()) Text(error, color = Red)
        Spacer(Modifier.height(30.dp))
    }
}
