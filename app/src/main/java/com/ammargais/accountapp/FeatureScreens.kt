package com.ammargais.accountapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ammargais.accountapp.core.*
import com.ammargais.accountapp.io.BackgroundServices
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.time.LocalDate

private fun attachmentJson(items: List<Attachment>) = JSONArray(items.map { JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size) }).toString()
private fun attachments(text: String): List<Attachment> = JSONArray(text).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Attachment(it.getString("id"), it.getString("name"), it.getString("mime"), it.getLong("size")) } } }

@Composable internal fun MovementForm(ledger: Ledger, initialAccount: String, initialDebt: String, busy: Boolean,
    onImport: (Uri, (Attachment) -> Unit) -> Unit, onSave: (Entry) -> Unit) {
    val initial = ledger.entries.find { it.id == initialDebt }
    var id by rememberSaveable { mutableStateOf(initialAccount) }
    var code by rememberSaveable { mutableStateOf(initial?.lines?.first()?.currency?.name ?: "") }
    var amount by rememberSaveable { mutableStateOf("") }
    var actionName by rememberSaveable { mutableStateOf(if (initial == null) Action.OWED_TO_ME.name else if (initial.action.debit) Action.RECEIVED.name else Action.PAID.name) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var due by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var debtMenu by remember { mutableStateOf(false) }
    var allocationId by rememberSaveable { mutableStateOf(initialDebt) }
    var otherCurrency by rememberSaveable { mutableStateOf(false) }
    var paidCode by rememberSaveable { mutableStateOf("") }
    var paidAmount by rememberSaveable { mutableStateOf("") }
    var attached by rememberSaveable { mutableStateOf("[]") }
    var error by rememberSaveable { mutableStateOf("") }
    var pendingDuplicate by remember { mutableStateOf<Entry?>(null) }
    val account = ledger.accounts.find { it.id == id }
    val choices = account?.let(ledger::currenciesFor) ?: ledger.currencies
    if (choices.isEmpty()) { Empty("أضف عملات للحساب", "من الإعدادات أضف العملات، ثم فعّلها من تعديل الحساب."); return }
    if (initial != null && initial.lines.first().currency !in choices) {
        Empty("فعّل عملة الدين للحساب", "فعّل ${initial.lines.first().currency.name} من تعديل الحساب، ثم ارجع لتسجيل السداد.")
        return
    }
    val c = choices.find { it.name == code } ?: choices.first()
    val paidCurrency = choices.find { it.name == paidCode } ?: choices.firstOrNull { it != c } ?: c
    val action = Action.valueOf(actionName)
    val isPayment = action == Action.RECEIVED || action == Action.PAID
    val parsed = runCatching { Money.parse(amount, c) }
    val preview = parsed.getOrNull()?.let { ledger.balance(id, c) + if (action.debit) it else -it }
    val files = attachments(attached)
    val eligible = ledger.openDebts(id, date).filter { it.lines.first().currency == c && it.action.debit != action.debit }
    val selectedDebt = eligible.find { it.id == allocationId }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri) { a -> attached = attachmentJson((attachments(attached) + a).distinctBy { it.id }) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box { OutlinedButton(onClick = { accountMenu = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text(account?.name ?: "اختر الحساب") }
            DropdownMenu(accountMenu, { accountMenu = false }) { ledger.accounts.filter { !it.archived }.sortedWith(compareByDescending<Account> { it.favorite }.thenBy { it.name }).forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { id = a.id; allocationId = ""; accountMenu = false }) } }
        }
        listOf(listOf(Action.OWED_TO_ME, Action.I_OWE), listOf(Action.RECEIVED, Action.PAID)).forEach { choices ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { choices.forEach { a -> FilterChip(action == a, { actionName = a.name; allocationId = ""; opening = false }, label = { Text(a.title) }, modifier = Modifier.weight(1f), enabled = !busy) } }
        }
        Text(when (action) {
            Action.OWED_TO_ME -> "تسجيل مبلغ جديد مستحق لك على هذا الحساب."
            Action.I_OWE -> "تسجيل مبلغ جديد مستحق لهذا الحساب عليك."
            Action.RECEIVED -> "تسجيل مبلغ استلمته من هذا الشخص؛ يقلّل ما لك أو يزيد ما له."
            else -> "تسجيل مبلغ دفعته لهذا الشخص؛ يقلّل ما عليك أو يزيد ما لك."
        }, style = MaterialTheme.typography.bodyMedium)
        CurrencyChips(c, currencies = choices) { code = it!!.name; allocationId = "" }
        if (isPayment) {
            Box { OutlinedButton(onClick = { debtMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(selectedDebt?.let { "سداد: ${it.note.take(35)}" } ?: "ربط بدين محدد • اختياري") }
                DropdownMenu(debtMenu, { debtMenu = false }) {
                    DropdownMenuItem(text = { Text("دفعة عامة غير مخصصة") }, onClick = { allocationId = ""; debtMenu = false })
                    eligible.forEach { debt -> DropdownMenuItem(text = { Text("${debt.note.take(28)} • ${Money.format(ledger.remaining(debt, date), c)} ${c.name}") }, onClick = { allocationId = debt.id; debtMenu = false }) }
                }
            }
            selectedDebt?.let { Info("المتبقي لهذا الدين: ${ltr("${Money.format(ledger.remaining(it, date), c)} ${c.name}")}") }
        }
        OutlinedTextField(amount, { amount = it.take(30); error = "" }, Modifier.fillMaxWidth(), label = { Text("المبلغ • ${c.title}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            isError = amount.isNotEmpty() && parsed.isFailure, supportingText = { if (amount.isNotEmpty() && parsed.isFailure) Text(parsed.exceptionOrNull()?.message ?: "مبلغ غير صالح") }, textStyle = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr))
        if (isPayment) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(otherCurrency, { otherCurrency = it }, enabled = !busy); Text("السداد بعملة أخرى") }
            if (otherCurrency) {
                Text("المبلغ أعلاه يُخصم من المستحق بعملته الأصلية. أدخل ما تم دفعه فعليًا هنا:")
                CurrencyChips(paidCurrency, currencies = choices) { paidCode = it!!.name }
                OutlinedTextField(paidAmount, { paidAmount = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("المبلغ المدفوع فعليًا • ${paidCurrency.name}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                val paid = runCatching { Money.parse(paidAmount, paidCurrency) }.getOrNull()
                if (paid != null && parsed.isSuccess) Info("السعر المتفق عليه: ${ltr("1 ${c.name} = ${Money.rate(parsed.getOrThrow(), c, paid, paidCurrency)} ${paidCurrency.name}")}\nلا يُنشأ دين جديد بعملة الدفع.")
            }
        }
        DateField(date, { date = it }, modifier = Modifier.fillMaxWidth())
        if (action.isDebt) {
            DateField(due, { due = it }, "موعد الاستحقاق • اختياري", Modifier.fillMaxWidth(), allowFuture = true)
            if (due.isNotEmpty()) TextButton(onClick = { due = "" }) { Text("إزالة موعد الاستحقاق") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(opening, { opening = it }); Text("هذه الحركة رصيد افتتاحي") }
        }
        OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("البيان • اختياري") }, minLines = 2)
        OutlinedTextField(reference, { reference = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("رقم الإشعار أو الحجز أو المعاملة • اختياري") }, singleLine = true)
        files.forEach { a -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(a.name, Modifier.weight(1f)); TextButton(onClick = { attached = attachmentJson(files.filterNot { it.id == a.id }) }, enabled = !busy) { Text("إزالة") } } }
        OutlinedButton(onClick = { pick.launch(arrayOf("image/*", "application/pdf")) }, enabled = files.size < 5 && !busy, modifier = Modifier.fillMaxWidth()) { Text("إرفاق إشعار • صورة أو PDF") }
        Text("حتى 5 مرفقات، 10 ميغابايت للمرفق.", style = MaterialTheme.typography.bodySmall)
        if (account != null && preview != null) Info("الرصيد بعد الحفظ: ${ltr("${Money.format(preview.abs(), c)} ${c.name}")} — ${Statement.side(preview)}")
        if (error.isNotBlank()) Text(error, color = Red)
        Button(onClick = {
            try {
                require(account != null && !account.archived) { "اختر حسابًا نشطًا" }
                require(allocationId.isBlank() || selectedDebt != null) { "الدين المحدد غير متاح بهذا التاريخ؛ اختر الدين مجددًا" }
                val units = Money.parse(amount, c)
                val base = if (isPayment && otherCurrency) {
                    require(paidCurrency != c) { "اختر عملة دفع مختلفة أو ألغ خيار السداد بعملة أخرى" }
                    ledger.settle(id, c, units, paidCurrency, Money.parse(paidAmount, paidCurrency), action == Action.RECEIVED, date,
                        note.ifBlank { action.title }, reference, allocationId.takeIf { it.isNotBlank() })
                } else Ledger.movement(id, c, units, action.debit, date, note.ifBlank { if (opening) "رصيد افتتاحي" else action.title }, reference, opening && action.isDebt).copy(
                    action = action, dueDate = if (action.isDebt) due.takeIf { it.isNotBlank() } else null,
                    payment = if (isPayment) Payment(c, units) else null,
                    allocation = if (isPayment) allocationId.takeIf { it.isNotBlank() }?.let { Allocation(it, units) } else null)
                val entry = base.copy(attachments = files)
                ledger.add(entry)
                if (ledger.duplicates(entry).isNotEmpty()) pendingDuplicate = entry else onSave(entry)
            } catch (e: Exception) { error = e.message ?: "تحقق من بيانات الحركة" }
        }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = !busy) { Text(if (busy) "جارٍ الحفظ…" else "حفظ الحركة") }
        Spacer(Modifier.height(24.dp))
    }
    pendingDuplicate?.let { e -> AlertDialog(onDismissRequest = { pendingDuplicate = null }, title = { Text("حركة مشابهة مسجلة") },
        text = { Text("وجدنا مرجعًا مكررًا أو حركة بنفس الحساب والمبلغ والعملة والتاريخ. راجع البيانات؛ يمكنك الحفظ إذا كانت عملية مستقلة.") },
        confirmButton = { TextButton(onClick = { pendingDuplicate = null; onSave(e) }, enabled = !busy) { Text("حفظ كعملية مستقلة") } },
        dismissButton = { TextButton(onClick = { pendingDuplicate = null }) { Text("الرجوع للمراجعة") } }) }
}

@Composable internal fun DueScreen(ledger: Ledger, onPay: (Entry) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("الكل") }
    val today = LocalDate.now().toString()
    val all = ledger.openDebts(through = today)
    val selected = all.filter { e -> when (filter) { "متأخر" -> e.dueDate != null && e.dueDate < today; "اليوم" -> e.dueDate == today; "قادم" -> e.dueDate != null && e.dueDate > today; else -> true } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Section("الديون والسدادات المخصصة") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("الكل", "متأخر", "اليوم", "قادم").forEach { f -> FilterChip(filter == f, { filter = f }, label = { Text(f) }) } } }
        item { Text("تعرض هذه القائمة الديون المصنفة في النسخة الجديدة. الدفعات العامة غير المربوطة تؤثر في رصيد الحساب، ولا تُغلق دينًا هنا تلقائيًا.", style = MaterialTheme.typography.bodySmall) }
        if (selected.isEmpty()) item { Empty("لا توجد مستحقات هنا", "أضف دينًا وحدد موعده، أو غيّر التصفية.") }
        items(selected, key = { it.id }) { e ->
            val l = e.lines.first(); val account = ledger.accounts.first { it.id == l.accountId }
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(account.name, fontWeight = FontWeight.Bold); Text(e.note)
                    Text(if (e.action.debit) "مستحق لنا" else "مستحق علينا")
                    NumberText("${Money.format(ledger.remaining(e, today), l.currency)} ${l.currency}")
                    Text("الاستحقاق: ${e.dueDate ?: "غير محدد"}", color = if (e.dueDate != null && e.dueDate < today) Red else Ink)
                    Text("أصل الدين: ${Money.format(l.units.abs(), l.currency)} ${l.currency}", style = MaterialTheme.typography.bodySmall)
                    if (!account.archived) OutlinedButton(onClick = { onPay(e) }) { Text("تسجيل سداد") } else Text("الحساب مؤرشف؛ أعد تنشيطه لتسجيل سداد")
                }
            }
        }
    }
}

@Composable internal fun SettingsScreen(vm: AccountViewModel, onCurrencies: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val prefs = remember { BackgroundServices.prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val last = remember(tick) { prefs.getString("lastBackup", null) }
    val failure = remember(tick) { prefs.getString("backupError", null) }
    val auto = remember(tick) { prefs.getBoolean("autoBackup", false) }
    val reminders = remember(tick) { prefs.getBoolean("reminders", false) }
    var mode by rememberSaveable { mutableStateOf("") }
    var selectedUri by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) { selectedUri = uri.toString(); mode = "export"; password = ""; confirmation = "" } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { selectedUri = uri.toString(); mode = "restore"; password = ""; confirmation = "" } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { selectedUri = uri.toString(); mode = "auto"; password = ""; confirmation = "" } }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        BackgroundServices.setReminders(context, granted)
        if (!granted) error = "لم يُسمح بالإشعارات؛ يمكنك متابعة المواعيد من شاشة المستحقات."
    }
    fun dismissPassword() {
        if (mode == "export" && selectedUri.isNotBlank()) runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(selectedUri)) }
        mode = ""; selectedUri = ""; password = ""; confirmation = ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = onCurrencies, modifier = Modifier.fillMaxWidth()) { Text("العملات وأسعار التحويل") }
        Section("حماية دفتر الحسابات")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("قفل بالبصمة أو رمز الهاتف", Modifier.weight(1f)); Switch(activity?.lockEnabled == true, { enabled -> activity?.setAppLock(enabled) { error = it } }, enabled = !vm.busy) }
        Info("الحركات تُحفظ داخل الجهاز. النسخة المشفرة تحفظ الدفتر والمرفقات خارج التطبيق. احتفظ بكلمة مرور النسخ؛ ستحتاجها عند تغيير الهاتف.")
        Button(onClick = { create.launch("Daftar_Backup_${LocalDate.now()}.daftar") }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("حفظ نسخة مشفرة الآن") }
        OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("استعادة نسخة احتياطية") }
        Text("آخر نسخة ناجحة: ${last ?: "لم تُحفظ نسخة بعد"}")
        if (failure != null) Text(failure, color = Red)
        Text("النسخ التلقائي: ${if (auto) "مفعّل • ${prefs.getString("backupLocation", "المجلد المختار")}" else "غير مفعّل"}")
        OutlinedButton(onClick = { folder.launch(null) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(if (auto) "تغيير مجلد النسخ أو كلمة المرور" else "تفعيل النسخ التلقائي") }
        if (auto) TextButton(onClick = { BackgroundServices.stopBackup(context) }, enabled = !vm.busy) { Text("إيقاف النسخ التلقائي") }
        Text("ينشئ نسخة يومية وبعد تغييرات الدفتر عندما يسمح أندرويد بالتنفيذ. لا تُستبدل النسخ السابقة. قد لا يظهر بعض مزودي السحابة ضمن اختيار المجلد؛ الحفظ لديهم لا يؤكد اكتمال الرفع إلى السحابة.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("تذكير بالمستحقات", Modifier.weight(1f)); Switch(reminders, { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else { BackgroundServices.setReminders(context, enabled) }
        }) }
        Text("تنبيه محلي عام لك؛ لا تُرسل رسائل إلى العملاء. قد يتأخر التنبيه حسب إعدادات البطارية.", style = MaterialTheme.typography.bodySmall)
        if (error.isNotBlank()) Text(error, color = Red)
        HorizontalDivider(); Section("دليل سريع")
        Text("لي عنده: دين جديد لك. له عندي: دين جديد عليك. استلمت منه / دفعت له: تسجيل دفع فعلي.\nاربط السداد بالدين لتظهر دفعاته والمتبقي في المستحقات. لكل عملة رصيد مستقل. عملة الملخص لا تغيّر الحركات الأصلية.\nللتصحيح افتح الحركة واعكسها ثم أضف الصحيحة؛ اعكس السدادات المرتبطة قبل عكس أصل الدين.")
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ammargais/Account-App/releases"))) }, modifier = Modifier.fillMaxWidth()) { Text("تحميل أحدث إصدار من GitHub") }
        Text("دفتر 0.3.0 • نسخة تجريبية\n${vm.ledger.accounts.size} حساب • ${vm.ledger.entries.size} حركة", style = MaterialTheme.typography.bodySmall)
        Text("دفتر للمستحقات، وليس تقرير أرباح أو ضرائب. السداد بعملة أخرى يوثّق المبلغ المدفوع ولا يدير رصيد صندوق.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
    if (mode.isNotBlank()) AlertDialog(onDismissRequest = ::dismissPassword, title = { Text(if (mode == "restore") "فتح النسخة الاحتياطية" else "كلمة مرور النسخ الاحتياطي") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (mode == "restore") "أدخل كلمة مرور النسخة المشفرة. للنسخ القديمة JSON اتركها فارغة." else "10 أحرف على الأقل. احفظها في مكان آمن؛ لا يمكن استعادة النسخة بدونها.")
            OutlinedTextField(password, { password = it.take(200) }, label = { Text("كلمة مرور النسخة") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            if (mode != "restore") OutlinedTextField(confirmation, { confirmation = it.take(200) }, label = { Text("تأكيد كلمة المرور") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = {
        val uri = Uri.parse(selectedUri); val secret = password.toCharArray()
        when (mode) { "restore" -> vm.readBackup(uri, secret); "auto" -> vm.configureBackup(uri, secret); else -> vm.exportBackup(uri, secret) }
        mode = ""; selectedUri = ""; password = ""; confirmation = ""
    }, enabled = !vm.busy && (mode == "restore" || password.length >= 10 && password == confirmation)) { Text(if (mode == "restore") "فتح ومعاينة" else "تأكيد") } },
    dismissButton = { TextButton(onClick = ::dismissPassword) { Text("إلغاء") } })
}
