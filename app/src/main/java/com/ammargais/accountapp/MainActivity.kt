package com.ammargais.accountapp

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ammargais.accountapp.core.*
import java.math.BigInteger
import java.time.LocalDate

private val Green = Color(0xFF12675B)
private val Ink = Color(0xFF182F2B)
private val Red = Color(0xFFAD443B)
private val Soft = Color(0xFFF4F7F5)
private fun ltr(value: String) = "\u2066$value\u2069"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White, primaryContainer = Color(0xFFD8EDE7), secondary = Green,
                onPrimaryContainer = Ink, secondaryContainer = Color(0xFFD8EDE7), onSecondaryContainer = Green,
                background = Soft, surface = Color.White, onSurface = Ink, onBackground = Ink, error = Red)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { DaftarApp() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DaftarApp(vm: AccountViewModel = viewModel()) {
    var route by rememberSaveable { mutableStateOf("home") }
    var tab by rememberSaveable { mutableStateOf(0) }
    var accountId by rememberSaveable { mutableStateOf("") }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var exportId by rememberSaveable { mutableStateOf("") }
    var exportCurrency by rememberSaveable { mutableStateOf("") }
    var exportFrom by rememberSaveable { mutableStateOf("0001-01-01") }
    var exportTo by rememberSaveable { mutableStateOf("9999-12-31") }
    val ledger = vm.ledger
    val account = ledger.accounts.find { it.id == accountId }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::readBackup) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        if (it != null && exportId.isNotBlank()) vm.exportCsv(it, exportId, exportCurrency.takeIf(String::isNotBlank)?.let(Currency::valueOf), exportFrom, exportTo)
    }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    LaunchedEffect(route, tab) { snackbar.currentSnackbarData?.dismiss() }
    fun goBack() { route = if ((route == "movement" || route == "exchange") && account != null) "detail" else "home" }
    BackHandler(route != "home") { if (!vm.busy) goBack() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Column { Text(when(route) { "detail" -> account?.name ?: "الحساب"; "movement" -> "حركة جديدة"; "exchange" -> "تبديل عملة الرصيد"; else -> "دفتر" }, fontWeight = FontWeight.Bold)
            if (route == "home") Text("حساباتك، بوضوح", style = MaterialTheme.typography.labelMedium, color = Green) } },
            navigationIcon = { if (route != "home") IconButton(onClick = { goBack() }, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Soft),
            actions = { if (route == "detail") TextButton(onClick = { editingId = accountId; showAccount = true }, enabled = !vm.busy) { Text("تعديل") } }) },
        bottomBar = { if (route == "home") NavigationBar(containerColor = Color.White) {
            listOf("الرئيسية", "الحسابات", "الإعدادات").forEachIndexed { i, title -> NavigationBarItem(selected = tab == i, onClick = { tab = i },
                icon = { Icon(listOf(Icons.Default.Home, Icons.Default.List, Icons.Default.Settings)[i], null) }, label = { Text(title) }) }
        } },
        floatingActionButton = { if (vm.ready && (route == "home" && tab < 2 && (tab == 1 || ledger.accounts.any { !it.archived }) || route == "detail" && account?.archived == false)) {
            val actionLabel = if (ledger.accounts.none { !it.archived } || route == "home" && tab == 1) "حساب جديد" else "حركة جديدة"
            ExtendedFloatingActionButton(onClick = {
                if (ledger.accounts.none { !it.archived } || route == "home" && tab == 1) { editingId = ""; showAccount = true }
                else { if (route == "home") accountId = ""; route = "movement" }
            }, modifier = Modifier.semantics { contentDescription = actionLabel }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(actionLabel) }, containerColor = Green, contentColor = Color.White)
        } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!vm.ready) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (vm.busy) CircularProgressIndicator() else { Text("تعذر فتح البيانات؛ بياناتك لم تُحذف"); Button(onClick = { vm.reload() }) { Text("إعادة المحاولة") } }
            } else when (route) {
                "movement" -> MovementForm(ledger, accountId, vm.busy, onSave = { e -> vm.mutate({ accountId = e.lines.first().accountId; route = "detail" }) { it.add(e) } })
                "exchange" -> if (account != null) ExchangeForm(ledger, account, vm.busy) { e ->
                    vm.mutate({ route = "detail" }) { current ->
                        val source = e.lines.first(); val available = current.balance(account.id, source.currency, e.date)
                        require(available.signum() == -source.units.signum() && available.abs() >= source.units.abs()) { "الرصيد المتاح في هذا التاريخ لا يكفي للتبديل" }
                        current.add(e)
                    }
                }
                "detail" -> if (account != null) AccountDetail(ledger, account, vm.busy,
                    onExchange = { route = "exchange" }, onReverse = { e, reason -> vm.mutate { it.reverse(e, reason) } },
                    onExport = { c, from, to -> exportId = account.id; exportCurrency = c?.name ?: ""; exportFrom = from; exportTo = to; csv.launch("Daftar_Statement_${LocalDate.now()}.csv") },
                    onShare = { through ->
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Statement.summary(ledger, account, through)) }, "مشاركة ملخص الحساب"))
                    })
                else -> when (tab) {
                    0 -> Dashboard(ledger, onOpen = { accountId = it; route = "detail" }, onAdd = { editingId = ""; showAccount = true })
                    1 -> AccountList(ledger) { accountId = it; route = "detail" }
                    else -> SettingsScreen(vm.busy, ledger, onBackup = { backup.launch("Daftar_Backup_${LocalDate.now()}.json") }, onRestore = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                }
            }
            if (vm.busy && vm.ready) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (showAccount) AccountDialog(ledger.accounts.find { it.id == editingId }, vm.busy, onDismiss = { showAccount = false }) { a -> vm.mutate({ showAccount = false }) { it.saveAccount(a) } }
    vm.pendingRestore?.let { incoming -> AlertDialog(onDismissRequest = { if (!vm.busy) vm.dismissRestore() }, title = { Text("استعادة النسخة الاحتياطية") },
        text = { Text("الملف يحتوي ${incoming.accounts.size} حسابًا و${incoming.entries.size} حركة. ستُضاف البيانات الجديدة فقط؛ الحركات الموجودة لن تتكرر، وأسماء حساباتك الحالية ستبقى كما هي.") },
        confirmButton = { TextButton(onClick = { vm.restore() }, enabled = !vm.busy) { Text("استعادة ودمج") } },
        dismissButton = { TextButton(onClick = { vm.dismissRestore() }, enabled = !vm.busy) { Text("إلغاء") } }) }
}

@Composable private fun NumberText(text: String, modifier: Modifier = Modifier, color: Color = Ink, large: Boolean = false) {
    Text(text, modifier, color = color, fontWeight = FontWeight.Bold, fontSize = if (large) 28.sp else 17.sp,
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))
}
@Composable private fun CurrencyChips(selected: Currency?, allowAll: Boolean = false, select: (Currency?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allowAll) FilterChip(selected = selected == null, onClick = { select(null) }, label = { Text("الكل") })
        Currency.entries.forEach { c -> FilterChip(selected = selected == c, onClick = { select(c) }, label = { Text(c.name) }) }
    }
}
@Composable private fun Info(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun Section(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
@Composable private fun Empty(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.List, null, Modifier.size(40.dp), tint = Green)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun Dashboard(ledger: Ledger, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("AED") }
    val c = Currency.valueOf(code)
    val totals = remember(ledger, c) { ledger.totals(c) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { CurrencyChips(c) { code = it!!.name } }
        item {
            Surface(color = Green, shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("نظرة على ${c.title}", color = Color(0xFFD5EBE5))
                    Text("المستحق لنا", color = Color.White)
                    NumberText("${Money.format(totals.first, c)}  ${c.name}", color = Color.White, large = true)
                    HorizontalDivider(color = Color.White.copy(alpha = .2f))
                    Text("المستحق علينا", color = Color(0xFFD5EBE5))
                    NumberText("${Money.format(totals.second, c)}  ${c.name}", color = Color.White)
                }
            }
        }
        item { Text("${ledger.accounts.count { !it.archived }} حساب نشط  •  كل عملة لها رصيد مستقل", style = MaterialTheme.typography.bodyMedium) }
        if (ledger.accounts.isEmpty()) item {
            Empty("ابدأ بحساب واحد", "أضف شخصًا أو جهة تتعامل معها.")
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("إضافة أول حساب") }
            Spacer(Modifier.height(14.dp))
            Info("مثال: لك عند أحمد 100 دولار؟ سجّل حركة مدين. استلمت منه 40؟ سجّل حركة دائن. المتبقي لك 60 دولارًا.")
        } else {
            item { Section("آخر الحركات") }
            val recent = ledger.entries.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt }).filter { e -> e.lines.any { it.currency == c } }.take(8)
            if (recent.isEmpty()) item { Empty("لا توجد حركات بهذه العملة", "اختر عملة أخرى أو أضف حركة جديدة.") }
            items(recent, key = { it.id }) { e ->
                val line = e.lines.first { it.accountId != Ledger.CONTROL && it.currency == c }
                val name = ledger.accounts.first { it.id == line.accountId }.name
                Surface(onClick = { onOpen(line.accountId) }, shape = RoundedCornerShape(18.dp), color = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, fontWeight = FontWeight.Bold)
                        NumberText("${Money.format(line.units.abs(), c)} ${c.name}", color = if (line.units.signum() > 0) Green else Red)
                        Text("${if (line.units.signum() > 0) "مدين" else "دائن"}  •  ${ltr(e.date)}  •  ${e.note}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun AccountList(ledger: Ledger, onOpen: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    val filtered = ledger.accounts.filter { it.archived == archived && (it.name.contains(search, true) || it.phone.contains(search)) }.sortedBy { it.name }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("ابحث باسم الحساب أو الهاتف") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!archived, { archived = false }, label = { Text("نشطة") }); FilterChip(archived, { archived = true }, label = { Text("مؤرشفة") }) } }
        if (filtered.isEmpty()) item { Empty("لا توجد حسابات هنا", "أضف حسابًا أو غيّر البحث.") }
        items(filtered, key = { it.id }) { a -> Surface(onClick = { onOpen(a.id) }, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                val balances = Currency.entries.map { it to ledger.balance(a.id, it) }.filter { it.second != BigInteger.ZERO }
                if (balances.isEmpty()) Text("لا يوجد رصيد مستحق", color = Green)
                balances.forEach { (c, b) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Statement.side(b), color = if (b.signum() > 0) Green else Red)
                    NumberText("${Money.format(b.abs(), c)} ${c.name}", color = if (b.signum() > 0) Green else Red)
                } }
            }
        } }
    }
}

@Composable private fun AccountDetail(ledger: Ledger, account: Account, busy: Boolean, onExchange: () -> Unit, onReverse: (Entry, String) -> Unit,
    onExport: (Currency?, String, String) -> Unit, onShare: (String) -> Unit) {
    var currency by rememberSaveable(account.id) { mutableStateOf("") }
    var search by rememberSaveable(account.id) { mutableStateOf("") }
    var from by rememberSaveable(account.id) { mutableStateOf("") }
    var to by rememberSaveable(account.id) { mutableStateOf("") }
    var filters by rememberSaveable(account.id) { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var reverseReason by rememberSaveable { mutableStateOf("") }
    val selectedCurrency = currency.takeIf(String::isNotBlank)?.let(Currency::valueOf)
    val start = from.ifEmpty { "0001-01-01" }; val end = to.ifEmpty { "9999-12-31" }
    val validRange = start <= end
    val rows = remember(ledger, account.id, selectedCurrency, start, end, search) { if (!validRange) emptyList() else ledger.rows(account.id, selectedCurrency, start, end).filter { it.entry.note.contains(search, true) || it.entry.reference.contains(search, true) }.reversed() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (account.archived) item { Info("الحساب مؤرشف. يمكن مشاهدة الكشف أو إعادة تنشيطه من تعديل.") }
        item { CurrencyChips(selectedCurrency, true) { currency = it?.name ?: "" } }
        item { Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section(if (to.isBlank()) "الرصيد الحالي" else "الرصيد حتى $to")
                (selectedCurrency?.let { listOf(it) } ?: Currency.entries).forEach { c -> val b = ledger.balance(account.id, c, end)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Statement.side(b), color = if (b.signum() < 0) Red else Green)
                        NumberText("${Money.format(b.abs(), c)} ${c.name}")
                    }
                }
            }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExport(selectedCurrency, start, end) }, enabled = validRange && !busy, modifier = Modifier.weight(1f)) { Text("كشف CSV") }
            OutlinedButton(onClick = { onShare(end) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("مشاركة الرصيد") }
        } }
        if (!account.archived) item { TextButton(onClick = onExchange, enabled = !busy) { Text("تبديل عملة مبلغ مستحق") } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Section("كشف الحركات"); TextButton(onClick = { filters = !filters }) { Text(if (filters) "إخفاء التصفية" else "بحث وتصفية") } } }
        if (filters) {
            item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("ابحث في البيان أو المرجع") }, singleLine = true) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DateField(from, { from = it }, "من تاريخ", Modifier.weight(1f)); DateField(to, { to = it }, "إلى تاريخ", Modifier.weight(1f)) } }
            item { TextButton(onClick = { from = ""; to = ""; search = "" }) { Text("مسح التصفية") }; Text("ملف CSV يشمل الفترة والعملة المختارتين؛ البحث النصي يخص العرض هنا.", style = MaterialTheme.typography.bodySmall) }
        }
        if (!validRange) item { Text("تاريخ البداية بعد تاريخ النهاية", color = Red) }
        if (rows.isEmpty() && validRange) item { Empty("لا توجد حركات", "أضف حركة أو غيّر خيارات التصفية.") }
        items(rows, key = { "${it.entry.id}-${it.line.currency}" }) { row -> Surface(onClick = { selectedId = row.entry.id; reverseReason = "" }, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.entry.note, fontWeight = FontWeight.Bold)
                NumberText("${Money.format(row.line.units.abs(), row.line.currency)} ${row.line.currency.name}", color = if (row.line.units.signum() > 0) Green else Red)
                Text("${if (row.line.units.signum() > 0) "مدين" else "دائن"}  •  ${ltr(row.entry.date)}", style = MaterialTheme.typography.bodySmall)
                Text("الرصيد بعدها: ${Money.format(row.balance.abs(), row.line.currency)} ${row.line.currency.name} ${Statement.side(row.balance)}", style = MaterialTheme.typography.bodySmall)
                if (row.entry.kind == EntryKind.REVERSAL) Text("حركة عكسية", color = Red, style = MaterialTheme.typography.labelMedium)
                if (ledger.entries.any { it.reverses == row.entry.id }) Text("تم تصحيحها بحركة عكسية", color = Red, style = MaterialTheme.typography.labelMedium)
            }
        } }
    }
    ledger.entries.find { it.id == selectedId }?.let { e ->
        val canReverse = e.kind != EntryKind.REVERSAL && ledger.entries.none { it.reverses == e.id }
        AlertDialog(onDismissRequest = { if (!busy) selectedId = "" }, title = { Text("تفاصيل الحركة") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(e.note); Text("التاريخ: ${ltr(e.date)}"); Text("المرجع: ${e.reference.ifEmpty { "—" }}")
                Text("معرّف: ${e.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                e.lines.filter { it.accountId != Ledger.CONTROL }.forEach { Text("${it.currency}  ${Money.format(it.units.abs(), it.currency)}  ${if (it.units.signum() > 0) "مدين" else "دائن"}") }
                if (e.kind == EntryKind.EXCHANGE) {
                    val l = e.lines.filter { it.accountId != Ledger.CONTROL }
                    Text("السعر المسجل: 1 ${l[0].currency} = ${Money.rate(l[0].units.abs(), l[0].currency, l[1].units.abs(), l[1].currency)} ${l[1].currency}")
                }
                if (canReverse) { Text("لإلغاء أثر الحركة، سجّل عكسها بتاريخ اليوم ثم أضف الحركة الصحيحة. يبقى الأصل في الكشف."); OutlinedTextField(reverseReason, { reverseReason = it.take(500) }, label = { Text("سبب التصحيح") }) }
            }
        }, confirmButton = { if (canReverse) TextButton(onClick = { onReverse(e, reverseReason); selectedId = "" }, enabled = reverseReason.isNotBlank() && !busy) { Text("تأكيد عكس الحركة") } },
            dismissButton = { TextButton(onClick = { selectedId = "" }, enabled = !busy) { Text("إغلاق") } })
    }
}

@Composable private fun DateField(value: String, onChange: (String) -> Unit, label: String = "التاريخ", modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }, modifier = modifier.heightIn(min = 52.dp)) { Text("$label: ${if (value.isEmpty()) "الكل" else ltr(value)}") }
}

@Composable private fun AccountDialog(existing: Account?, busy: Boolean, onDismiss: () -> Unit, onSave: (Account) -> Unit) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var phone by rememberSaveable(existing?.id) { mutableStateOf(existing?.phone ?: "") }
    var archived by rememberSaveable(existing?.id) { mutableStateOf(existing?.archived ?: false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (existing == null) "حساب جديد" else "تعديل الحساب") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("اسم الشخص أو الجهة") }, singleLine = true)
            OutlinedTextField(phone, { phone = it.take(80) }, label = { Text("رقم الهاتف • اختياري") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(archived, { archived = it }); Text("أرشفة الحساب وإيقاف الحركات الجديدة") }
            Text("يمكن تسجيل الأرصدة الافتتاحية بعد إنشاء الحساب من حركة جديدة.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave((existing ?: Account(name = name)).copy(name = name, phone = phone, archived = archived)) }, enabled = name.isNotBlank() && !busy) { Text("حفظ الحساب") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") } })
}

@Composable private fun MovementForm(ledger: Ledger, initialAccount: String, busy: Boolean, onSave: (Entry) -> Unit) {
    var id by rememberSaveable { mutableStateOf(initialAccount) }
    var code by rememberSaveable { mutableStateOf("AED") }
    var amount by rememberSaveable { mutableStateOf("") }
    var debit by rememberSaveable { mutableStateOf(true) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    val c = Currency.valueOf(code)
    val parsed = runCatching { Money.parse(amount, c) }
    val account = ledger.accounts.find { it.id == id }
    val preview = parsed.getOrNull()?.let { ledger.balance(id, c) + if (debit) it else -it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box { OutlinedButton(onClick = { accountMenu = true }, Modifier.fillMaxWidth()) { Text(account?.name ?: "اختر الحساب") }
            DropdownMenu(accountMenu, { accountMenu = false }) { ledger.accounts.filter { !it.archived }.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { id = a.id; accountMenu = false }) } }
        }
        DirectionSelector(debit) { debit = it }
        Text(if (debit) "يزيد المستحق لك على هذا الحساب، أو يقلّل ما عليك له." else "يزيد المستحق لهذا الحساب عليك، أو يقلّل ما لك عليه.", style = MaterialTheme.typography.bodyMedium)
        CurrencyChips(c) { code = it!!.name }
        OutlinedTextField(amount, { amount = it.take(30); error = "" }, Modifier.fillMaxWidth(), label = { Text("المبلغ • ${c.title}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            isError = amount.isNotEmpty() && parsed.isFailure, supportingText = { if (amount.isNotEmpty() && parsed.isFailure) Text(parsed.exceptionOrNull()?.message ?: "مبلغ غير صالح") }, textStyle = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr))
        DateField(date, { date = it }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("البيان • مثال: سداد أو مبلغ مستحق") }, minLines = 2)
        OutlinedTextField(reference, { reference = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("رقم الإيصال أو المرجع • اختياري") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(opening, { opening = it }); Text("هذه الحركة رصيد افتتاحي") }
        if (account != null && preview != null) Info("الرصيد بعد الحفظ: ${ltr("${Money.format(preview.abs(), c)} ${c.name}")} — ${Statement.side(preview)}")
        if (error.isNotBlank()) Text(error, color = Red)
        Button(onClick = {
            try {
                require(account != null && !account.archived) { "اختر حسابًا نشطًا" }
                val e = Ledger.movement(id, c, Money.parse(amount, c), debit, date, note.ifBlank { if (opening) "رصيد افتتاحي" else if (debit) "حركة مدين" else "حركة دائن" }, reference, opening)
                ledger.add(e); onSave(e)
            } catch (e: Exception) { error = e.message ?: "تحقق من بيانات الحركة" }
        }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = !busy) { Text(if (busy) "جارٍ الحفظ…" else "حفظ الحركة") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun DirectionSelector(debit: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(debit, { onChange(true) }, label = { Text("مدين • لنا") }, modifier = Modifier.weight(1f))
        FilterChip(!debit, { onChange(false) }, label = { Text("دائن • علينا") }, modifier = Modifier.weight(1f))
    }
}

@Composable private fun ExchangeForm(ledger: Ledger, account: Account, busy: Boolean, onSave: (Entry) -> Unit) {
    var from by rememberSaveable { mutableStateOf("USD") }; var to by rememberSaveable { mutableStateOf("SDG") }
    var first by rememberSaveable { mutableStateOf("") }; var second by rememberSaveable { mutableStateOf("") }
    var receivable by rememberSaveable { mutableStateOf(true) }; var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf("") }; var error by rememberSaveable { mutableStateOf("") }
    val fc = Currency.valueOf(from); val tc = Currency.valueOf(to)
    val fm = runCatching { Money.parse(first, fc) }.getOrNull(); val tm = runCatching { Money.parse(second, tc) }.getOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Info("تغيير عملة مبلغ مستحق في حساب ${account.name} بسعر تتفقان عليه. أدخل المبلغين الفعليين؛ هذه العملية لا تسجّل سدادًا نقديًا.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(receivable, { receivable = true }, label = { Text("تبديل مبلغ لنا") }); FilterChip(!receivable, { receivable = false }, label = { Text("تبديل مبلغ علينا") })
        }
        Section("المبلغ الذي سيُخصم من الرصيد الأصلي")
        CurrencyChips(fc) { from = it!!.name }
        OutlinedTextField(first, { first = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("المبلغ الأصلي • $from") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Section("المبلغ البديل بالعملة الجديدة")
        CurrencyChips(tc) { to = it!!.name }
        OutlinedTextField(second, { second = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("المبلغ البديل • $to") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        if (fm != null && tm != null && fc != tc) {
            Info("السعر: 1 $from = ${Money.rate(fm, fc, tm, tc)} $to\nسيقل المستحق ${if (receivable) "لنا" else "علينا"} بمقدار ${Money.format(fm, fc)} $from ويزيد بمقدار ${Money.format(tm, tc)} $to.")
        }
        DateField(date, { date = it }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("البيان • اختياري") })
        if (error.isNotBlank()) Text(error, color = Red)
        Button(onClick = {
            try {
                val fa = Money.parse(first, fc); val ta = Money.parse(second, tc)
                val available = ledger.balance(account.id, fc, date)
                require(available.signum() == if (receivable) 1 else -1) { "لا يوجد رصيد ${if (receivable) "لنا" else "علينا"} بهذه العملة والتاريخ" }
                require(available.abs() >= fa) { "المبلغ أكبر من الرصيد المستحق" }
                val e = Ledger.exchange(account.id, fc, fa, tc, ta, receivable, date, note.ifBlank { "تبديل عملة رصيد من $from إلى $to" })
                ledger.add(e); onSave(e)
            } catch (e: Exception) { error = e.message ?: "تحقق من المبالغ والعملات" }
        }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = !busy) { Text("تأكيد تبديل العملة") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun SettingsScreen(busy: Boolean, ledger: Ledger, onBackup: () -> Unit, onRestore: () -> Unit) {
    val last = LocalContext.current.getSharedPreferences("prefs", 0).getString("lastBackup", null)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Section("حماية دفتر الحسابات")
        Info("الحركات تُحفظ تلقائيًا داخل الجهاز. احفظ نسخة احتياطية خارج التطبيق قبل تغيير الهاتف أو إزالته.")
        Button(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("حفظ نسخة احتياطية") }
        Text("آخر نسخة: ${last ?: "لم تُحفظ نسخة بعد"}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("استعادة نسخة احتياطية") }
        Text("اختر مكان الحفظ من مدير الملفات، أو Google Drive إذا ظهر ضمن خيارات جهازك. النسخة ملف غير مشفّر؛ احتفظ بها في مكان خاص.", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Section("دليل سريع")
        Text("• مدين: يزيد المبلغ المستحق لك أو يقلّل ما عليك.\n• دائن: يزيد المبلغ المستحق عليك أو يقلّل ما لك.\n• استلام سداد من شخص: سجّل دائن. دفع مبلغ له: سجّل مدين.\n• لكل عملة رصيد مستقل؛ USDT منفصلة عن USD.\n• لتصحيح خطأ: افتح الحركة واعكسها، ثم أضف الصحيحة.\n• CSV لكشف الحساب؛ النسخة الاحتياطية لاستعادة الدفتر كاملًا.")
        Section("عن دفتر")
        Text("دفتر بسيط لحسابات الأشخاص والجهات، يعمل دون إنترنت أو تسجيل دخول. لا يحتسب أرباحًا أو ضرائب، ولا يقيّم العملات تلقائيًا.")
        Text("الإصدار 0.1.0 • نسخة تجريبية\n${ledger.accounts.size} حساب • ${ledger.entries.size} حركة", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}
