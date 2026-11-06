package com.ammargais.accountapp

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.ammargais.accountapp.io.BackgroundServices
import java.math.BigInteger
import java.time.LocalDate

internal val Green = Color(0xFF12675B)
internal val Ink = Color(0xFF182F2B)
internal val Red = Color(0xFFAD443B)
internal val Soft = Color(0xFFF4F7F5)
internal fun ltr(value: String) = "\u2066$value\u2069"

class MainActivity : FragmentActivity() {
    var unlocked by mutableStateOf(false); private set
    var lockEnabled by mutableStateOf(false); private set
    fun unlock() { unlocked = true }
    fun setAppLock(enabled: Boolean, onError: (String) -> Unit) {
        authenticate({
            BackgroundServices.prefs(this).edit().putBoolean("appLock", enabled).apply()
            lockEnabled = enabled; unlocked = true
            if (enabled) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }, onError)
    }
    override fun onStop() { super.onStop(); if (lockEnabled && !isChangingConfigurations) unlocked = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockEnabled = BackgroundServices.prefs(this).getBoolean("appLock", false)
        unlocked = !lockEnabled
        if (lockEnabled) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        BackgroundServices.schedule(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, onPrimary = Color.White, primaryContainer = Color(0xFFD8EDE7), secondary = Green,
                onPrimaryContainer = Ink, secondaryContainer = Color(0xFFD8EDE7), onSecondaryContainer = Green,
                background = Soft, surface = Color.White, onSurface = Ink, onBackground = Ink, error = Red)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().then(if (unlocked) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})) { DaftarApp() }
                        if (!unlocked) androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
                            dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
                            Surface(modifier = Modifier.fillMaxSize()) { LockScreen(this@MainActivity) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DaftarApp(vm: AccountViewModel = viewModel()) {
    var route by rememberSaveable { mutableStateOf("home") }
    var tab by rememberSaveable { mutableStateOf(0) }
    var accountId by rememberSaveable { mutableStateOf("") }
    var debtId by rememberSaveable { mutableStateOf("") }
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
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        if (it != null && exportId.isNotBlank()) vm.exportCsv(it, exportId, exportCurrency.takeIf(String::isNotBlank)?.let(ledger::currency), exportFrom, exportTo)
    }
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
        if (it != null && exportId.isNotBlank()) vm.exportXlsx(it, exportId, exportCurrency.takeIf(String::isNotBlank)?.let(ledger::currency), exportFrom, exportTo)
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        if (it != null && exportId.isNotBlank()) vm.exportPdf(it, exportId, exportCurrency.takeIf(String::isNotBlank)?.let(ledger::currency), exportFrom, exportTo)
    }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    LaunchedEffect(route, tab) { snackbar.currentSnackbarData?.dismiss() }
    fun goBack() { route = if ((route == "movement" || route == "exchange") && account != null) "detail" else "home" }
    BackHandler(route != "home") { if (!vm.busy) goBack() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Column { Text(when(route) { "detail" -> account?.name ?: "الحساب"; "movement" -> "حركة جديدة"; "exchange" -> "تبديل عملة الرصيد"; "currencies" -> "العملات وأسعار التحويل"; else -> "دفتر" }, fontWeight = FontWeight.Bold)
            if (route == "home") Text("حساباتك، بوضوح", style = MaterialTheme.typography.labelMedium, color = Green) } },
            navigationIcon = { if (route != "home") IconButton(onClick = { goBack() }, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Soft),
            actions = { if (route == "detail") TextButton(onClick = { editingId = accountId; showAccount = true }, enabled = !vm.busy) { Text("تعديل") } }) },
        bottomBar = { if (route == "home") NavigationBar(containerColor = Color.White) {
            listOf("الرئيسية", "الحسابات", "المستحقات", "الإعدادات").forEachIndexed { i, title -> NavigationBarItem(selected = tab == i, onClick = { tab = i },
                icon = { Icon(listOf(Icons.Default.Home, Icons.Default.List, Icons.Default.DateRange, Icons.Default.Settings)[i], null) }, label = { Text(title) }) }
        } },
        floatingActionButton = { if (vm.ready && (route == "home" && tab < 2 && (tab == 1 || ledger.accounts.any { !it.archived }) || route == "detail" && account?.archived == false)) {
            val actionLabel = if (ledger.currencies.isEmpty()) "إضافة عملة" else if (ledger.accounts.none { !it.archived } || route == "home" && tab == 1) "حساب جديد" else "حركة جديدة"
            ExtendedFloatingActionButton(onClick = {
                if (ledger.currencies.isEmpty()) { route = "currencies" }
                else if (ledger.accounts.none { !it.archived } || route == "home" && tab == 1) { editingId = ""; showAccount = true }
                else { if (route == "home") accountId = ""; debtId = ""; route = "movement" }
            }, modifier = Modifier.semantics { contentDescription = actionLabel }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(actionLabel) }, containerColor = Green, contentColor = Color.White)
        } }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!vm.ready) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (vm.busy) CircularProgressIndicator() else { Text("تعذر فتح البيانات؛ بياناتك لم تُحذف"); Button(onClick = { vm.reload() }) { Text("إعادة المحاولة") } }
            } else when (route) {
                "currencies" -> CurrencySettingsScreen(vm)
                "movement" -> MovementForm(ledger, accountId, debtId, vm.busy, vm::importAttachment, onSave = { e -> vm.mutate({ accountId = e.lines.first().accountId; route = "detail" }) { it.add(e) } })
                "exchange" -> if (account != null) ExchangeForm(ledger, account, vm.busy) { e ->
                    vm.mutate({ route = "detail" }) { current ->
                        val source = e.lines.first(); val available = current.balance(account.id, source.currency, e.date)
                        require(available.signum() == -source.units.signum() && available.abs() >= source.units.abs()) { "الرصيد المتاح في هذا التاريخ لا يكفي للتبديل" }
                        current.add(e)
                    }
                }
                "detail" -> if (account != null) AccountDetail(ledger, account, vm.busy,
                    onPdf = { c, from, to, share ->
                        if (share) vm.exportPdf(null, account.id, c, from, to)
                        else { exportId = account.id; exportCurrency = c?.name ?: ""; exportFrom = from; exportTo = to; pdf.launch("Daftar_Statement_${LocalDate.now()}.pdf") }
                    }, onAttachment = vm::openAttachment,
                    onXlsx = { c, from, to -> exportId = account.id; exportCurrency = c?.name ?: ""; exportFrom = from; exportTo = to; xlsx.launch("Daftar_Statement_${LocalDate.now()}.xlsx") },
                    onExchange = { route = "exchange" }, onReverse = { e, reason -> vm.mutate { it.reverse(e, reason) } },
                    onExport = { c, from, to -> exportId = account.id; exportCurrency = c?.name ?: ""; exportFrom = from; exportTo = to; csv.launch("Daftar_Statement_${LocalDate.now()}.csv") },
                    onShare = { through ->
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Statement.summary(ledger, account, through)) }, "مشاركة ملخص الحساب"))
                    })
                else -> when (tab) {
                    0 -> Dashboard(ledger, onCurrencies = { route = "currencies" }, onOpen = { accountId = it; route = "detail" }, onAdd = { editingId = ""; showAccount = true })
                    1 -> AccountList(ledger) { accountId = it; route = "detail" }
                    2 -> DueScreen(ledger) { debt -> accountId = debt.lines.first().accountId; debtId = debt.id; route = "movement" }
                    else -> SettingsScreen(vm, onCurrencies = { route = "currencies" })
                }
            }
            if (vm.busy && vm.ready) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (showAccount) AccountDialog(ledger, ledger.accounts.find { it.id == editingId }, vm.busy, onDismiss = { showAccount = false }) { a -> vm.mutate({ showAccount = false }) { it.saveAccount(a) } }
    vm.pendingRestore?.let { incoming -> AlertDialog(onDismissRequest = { if (!vm.busy) vm.dismissRestore() }, title = { Text("استعادة النسخة الاحتياطية") },
        text = { Text("الملف يحتوي ${incoming.accounts.size} حسابًا و${incoming.entries.size} حركة. ستُضاف البيانات الجديدة فقط؛ الحركات الموجودة لن تتكرر، وأسماء حساباتك الحالية ستبقى كما هي.") },
        confirmButton = { TextButton(onClick = { vm.restore() }, enabled = !vm.busy) { Text("استعادة ودمج") } },
        dismissButton = { TextButton(onClick = { vm.dismissRestore() }, enabled = !vm.busy) { Text("إلغاء") } }) }
}

@Composable internal fun NumberText(text: String, modifier: Modifier = Modifier, color: Color = Ink, large: Boolean = false) {
    Text(text, modifier, color = color, fontWeight = FontWeight.Bold, fontSize = if (large) 28.sp else 17.sp,
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr))
}
@Composable internal fun CurrencyChips(selected: Currency?, allowAll: Boolean = false, currencies: List<Currency>, select: (Currency?) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allowAll) FilterChip(selected = selected == null, onClick = { select(null) }, label = { Text("الكل") })
        currencies.forEach { c -> FilterChip(selected = selected == c, onClick = { select(c) }, label = { Text(c.name) }) }
    }
}
@Composable internal fun Info(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable internal fun Section(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
@Composable internal fun Empty(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.List, null, Modifier.size(40.dp), tint = Green)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun Dashboard(ledger: Ledger, onCurrencies: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Section("ملخص الحسابات") }
        item { ConvertedSummaryCard(ledger, ledger.settings.summaryCurrency, onConfigure = onCurrencies) }
        item { OutlinedButton(onClick = onCurrencies, modifier = Modifier.fillMaxWidth()) { Text("إدارة العملات وأسعار التحويل") } }
        if (ledger.currencies.isEmpty()) item {
            Empty("أضف عملاتك أولًا", "اكتب العملات التي تتعامل بها، ثم أنشئ حساباتك.")
            Button(onClick = onCurrencies, modifier = Modifier.fillMaxWidth()) { Text("إضافة أول عملة") }
        } else if (ledger.accounts.isEmpty()) item {
            Empty("ابدأ بحساب واحد", "حدد العملات التي تتعامل بها مع الشخص أو الجهة.")
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("إضافة أول حساب") }
        } else {
            item { Section("آخر الحركات") }
            items(ledger.entries.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt }).take(8), key = { it.id }) { e ->
                val line = e.lines.first { it.accountId != Ledger.CONTROL }; val c = line.currency
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
    var group by rememberSaveable { mutableStateOf("") }
    val filtered = ledger.accounts.filter { it.archived == archived && (group.isBlank() || it.group.name == group) && (it.name.contains(search, true) || it.phone.contains(search)) }.sortedWith(compareByDescending<Account> { it.favorite }.thenBy { it.name })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("ابحث باسم الحساب أو الهاتف") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!archived, { archived = false }, label = { Text("نشطة") }); FilterChip(archived, { archived = true }, label = { Text("مؤرشفة") }) } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(group.isBlank(), { group = "" }, label = { Text("كل التصنيفات") })
            AccountGroup.entries.forEach { g -> FilterChip(group == g.name, { group = g.name }, label = { Text(g.title) }) }
        } }
        if (filtered.isEmpty()) item { Empty("لا توجد حسابات هنا", "أضف حسابًا أو غيّر البحث.") }
        items(filtered, key = { it.id }) { a -> Surface(onClick = { onOpen(a.id) }, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((if (a.favorite) "★ " else "") + a.name + " • " + a.group.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                val balances = ledger.currencies.map { it to ledger.balance(a.id, it) }.filter { it.second != BigInteger.ZERO }
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
    onXlsx: (Currency?, String, String) -> Unit, onExport: (Currency?, String, String) -> Unit, onShare: (String) -> Unit, onPdf: (Currency?, String, String, Boolean) -> Unit, onAttachment: (Attachment) -> Unit) {
    var currency by rememberSaveable(account.id) { mutableStateOf("") }
    var search by rememberSaveable(account.id) { mutableStateOf("") }
    var from by rememberSaveable(account.id) { mutableStateOf("") }
    var to by rememberSaveable(account.id) { mutableStateOf("") }
    var filters by rememberSaveable(account.id) { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var reverseReason by rememberSaveable { mutableStateOf("") }
    val selectedCurrency = currency.takeIf(String::isNotBlank)?.let(ledger::currency)
    val start = from.ifEmpty { "0001-01-01" }; val end = to.ifEmpty { "9999-12-31" }
    val validRange = start <= end
    val rows = remember(ledger, account.id, selectedCurrency, start, end, search) { if (!validRange) emptyList() else ledger.rows(account.id, selectedCurrency, start, end).filter { it.entry.note.contains(search, true) || it.entry.reference.contains(search, true) || it.entry.date.contains(search) || Money.plain(it.line.units.abs(), it.line.currency).contains(search.replace(",", "")) }.reversed() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ConvertedSummaryCard(ledger, account.summaryCurrency, account.id, end) }
        if (account.archived) item { Info("الحساب مؤرشف. يمكن مشاهدة الكشف أو إعادة تنشيطه من تعديل.") }
        item { CurrencyChips(selectedCurrency, true, ledger.currencies) { currency = it?.name ?: "" } }
        item { Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section(if (to.isBlank()) "الرصيد الحالي" else "الرصيد حتى $to")
                (selectedCurrency?.let { listOf(it) } ?: ledger.currencies).forEach { c -> val b = ledger.balance(account.id, c, end)
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
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPdf(selectedCurrency, start, end, false) }, enabled = validRange && !busy, modifier = Modifier.weight(1f)) { Text("حفظ PDF") }
            OutlinedButton(onClick = { onPdf(selectedCurrency, start, end, true) }, enabled = validRange && !busy, modifier = Modifier.weight(1f)) { Text("مشاركة PDF") }
        } }
        item { OutlinedButton(onClick = { onXlsx(selectedCurrency, start, end) }, enabled = validRange && !busy, modifier = Modifier.fillMaxWidth()) { Text("حفظ Excel") } }
        if (!account.archived) item { TextButton(onClick = onExchange, enabled = !busy) { Text("تبديل عملة مبلغ مستحق") } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Section("كشف الحركات"); TextButton(onClick = { filters = !filters }) { Text(if (filters) "إخفاء التصفية" else "بحث وتصفية") } } }
        if (filters) {
            item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("ابحث بالبيان أو المرجع أو المبلغ أو التاريخ") }, singleLine = true) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DateField(from, { from = it }, "من تاريخ", Modifier.weight(1f)); DateField(to, { to = it }, "إلى تاريخ", Modifier.weight(1f)) } }
            item { TextButton(onClick = { from = ""; to = ""; search = "" }) { Text("مسح التصفية") }; Text("ملفات PDF وExcel وCSV تشمل الفترة والعملة المختارتين؛ البحث النصي يخص العرض هنا.", style = MaterialTheme.typography.bodySmall) }
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
                if (e.action != Action.LEGACY) Text(e.action.title, fontWeight = FontWeight.Bold)
                e.payment?.let { p ->
                    val l = e.lines.first()
                    Text("الدفع الفعلي: ${Money.format(p.units, p.currency)} ${p.currency.name}")
                    Text("السعر: 1 ${l.currency} = ${Money.rate(l.units.abs(), l.currency, p.units, p.currency)} ${p.currency}")
                }
                e.dueDate?.let { Text("موعد الاستحقاق: ${ltr(it)}") }
                e.allocation?.let { Text("سداد مرتبط بالدين: ${it.debtId.take(8)}") }
                if (e.action.isDebt) Text("المتبقي المخصص لهذا الدين: ${Money.format(ledger.remaining(e), e.lines.first().currency)} ${e.lines.first().currency}")
                e.attachments.forEach { a -> TextButton(onClick = { onAttachment(a) }, enabled = !busy) { Text("فتح المرفق: ${a.name}") } }
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

@Composable internal fun DateField(value: String, onChange: (String) -> Unit, label: String = "التاريخ", modifier: Modifier = Modifier, allowFuture: Boolean = false) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).apply { if (!allowFuture) datePicker.maxDate = System.currentTimeMillis() }.show()
    }, modifier = modifier.heightIn(min = 52.dp)) { Text("$label: ${if (value.isEmpty()) "الكل" else ltr(value)}") }
}

@Composable private fun AccountDialog(ledger: Ledger, existing: Account?, busy: Boolean, onDismiss: () -> Unit, onSave: (Account) -> Unit) {
    var selectedCodes by rememberSaveable(existing?.id) { mutableStateOf(existing?.currencyCodes ?: if (existing != null) ledger.currencies.map { it.name } else emptyList()) }
    var summary by rememberSaveable(existing?.id) { mutableStateOf(existing?.summaryCurrency ?: "") }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var phone by rememberSaveable(existing?.id) { mutableStateOf(existing?.phone ?: "") }
    var group by rememberSaveable(existing?.id) { mutableStateOf(existing?.group?.name ?: "PERSON") }
    var favorite by rememberSaveable(existing?.id) { mutableStateOf(existing?.favorite ?: false) }
    var groupMenu by remember { mutableStateOf(false) }
    var archived by rememberSaveable(existing?.id) { mutableStateOf(existing?.archived ?: false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (existing == null) "حساب جديد" else "تعديل الحساب") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("اسم الشخص أو الجهة") }, singleLine = true)
            OutlinedTextField(phone, { phone = it.take(80) }, label = { Text("رقم الهاتف • اختياري") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            Box { OutlinedButton(onClick = { groupMenu = true }) { Text("التصنيف: ${AccountGroup.valueOf(group).title}") }
                DropdownMenu(groupMenu, { groupMenu = false }) { AccountGroup.entries.forEach { g -> DropdownMenuItem(text = { Text(g.title) }, onClick = { group = g.name; groupMenu = false }) } }
            }
            Section("عملات التعامل")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ledger.currencies.forEach { c ->
                FilterChip(c.name in selectedCodes, { selectedCodes = if (c.name in selectedCodes) selectedCodes - c.name else selectedCodes + c.name }, label = { Text(c.name) })
            } }
            Text("اختر عملة واحدة على الأقل. تغيير العملات لا يحذف الحركات السابقة.", style = MaterialTheme.typography.bodySmall)
            CurrencyDropdown("عملة ملخص الحساب", ledger.currencies, summary) { summary = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(favorite, { favorite = it }); Text("تثبيت ضمن الحسابات المفضلة") }
            if (existing != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(archived, { archived = it }); Text("أرشفة الحساب وإيقاف الحركات الجديدة") }
            Text("يمكن تسجيل الأرصدة الافتتاحية بعد إنشاء الحساب من حركة جديدة.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave((existing ?: Account(name = name)).copy(name = name, phone = phone, archived = archived, group = AccountGroup.valueOf(group), favorite = favorite, currencyCodes = selectedCodes, summaryCurrency = summary.takeIf { it.isNotBlank() })) }, enabled = name.isNotBlank() && selectedCodes.isNotEmpty() && !busy) { Text("حفظ الحساب") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("إلغاء") } })
}

@Composable private fun DirectionSelector(debit: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilterChip(debit, { onChange(true) }, label = { Text("مدين • لنا") }, modifier = Modifier.weight(1f))
        FilterChip(!debit, { onChange(false) }, label = { Text("دائن • علينا") }, modifier = Modifier.weight(1f))
    }
}

@Composable private fun ExchangeForm(ledger: Ledger, account: Account, busy: Boolean, onSave: (Entry) -> Unit) {
    var from by rememberSaveable { mutableStateOf("") }; var to by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }; var second by rememberSaveable { mutableStateOf("") }
    var receivable by rememberSaveable { mutableStateOf(true) }; var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var note by rememberSaveable { mutableStateOf("") }; var error by rememberSaveable { mutableStateOf("") }
    val choices = ledger.currenciesFor(account)
    if (choices.size < 2) { Empty("أضف عملتين للحساب", "فعّل العملات من تعديل الحساب قبل تبديل الرصيد."); return }
    val fc = choices.find { it.name == from } ?: choices.first(); val tc = choices.find { it.name == to } ?: choices[1]
    val fm = runCatching { Money.parse(first, fc) }.getOrNull(); val tm = runCatching { Money.parse(second, tc) }.getOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Info("تغيير عملة مبلغ مستحق في حساب ${account.name} بسعر تتفقان عليه. أدخل المبلغين الفعليين؛ هذه العملية لا تسجّل سدادًا نقديًا.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(receivable, { receivable = true }, label = { Text("تبديل مبلغ لنا") }); FilterChip(!receivable, { receivable = false }, label = { Text("تبديل مبلغ علينا") })
        }
        Section("المبلغ الذي سيُخصم من الرصيد الأصلي")
        CurrencyChips(fc, currencies = choices) { from = it!!.name }
        OutlinedTextField(first, { first = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("المبلغ الأصلي • ${fc.name}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Section("المبلغ البديل بالعملة الجديدة")
        CurrencyChips(tc, currencies = choices) { to = it!!.name }
        OutlinedTextField(second, { second = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("المبلغ البديل • ${tc.name}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        if (fm != null && tm != null && fc != tc) {
            Info("السعر: 1 ${fc.name} = ${Money.rate(fm, fc, tm, tc)} ${tc.name}\nسيقل المستحق ${if (receivable) "لنا" else "علينا"} بمقدار ${Money.format(fm, fc)} ${fc.name} ويزيد بمقدار ${Money.format(tm, tc)} ${tc.name}.")
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
                val e = Ledger.exchange(account.id, fc, fa, tc, ta, receivable, date, note.ifBlank { "تبديل عملة رصيد من ${fc.name} إلى ${tc.name}" })
                ledger.add(e); onSave(e)
            } catch (e: Exception) { error = e.message ?: "تحقق من المبالغ والعملات" }
        }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = !busy) { Text("تأكيد تبديل العملة") }
        Spacer(Modifier.height(24.dp))
    }
}

