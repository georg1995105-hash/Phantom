package xyz.wraithnet.phantom.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import xyz.wraithnet.phantom.data.NativeStore
import xyz.wraithnet.phantom.data.PurchaseClient
import xyz.wraithnet.phantom.data.PurchaseOfferChanged
import xyz.wraithnet.phantom.data.RenewalUnavailable
import xyz.wraithnet.phantom.data.objects

@Composable
internal fun SubscriptionOverview(
    hasSavedInvoice: Boolean,
    openPurchase: () -> Unit,
    openRecovery: () -> Unit,
    accessCard: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            accessCard()
            Button(onClick = openPurchase, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing()) {
                Text(if (hasSavedInvoice) "Сохранённый счёт" else "Купить подписку Phantom")
            }
            TextButton(onClick = openRecovery, modifier = Modifier.tvFocusRing()) {
                Text("Восстановить покупку")
            }
        }
    }
}

@Composable
internal fun NativePurchaseScreen(
    back: () -> Unit,
    client: PurchaseClient,
    refreshVersion: Int = 0,
    renewSource: String? = null,
    renewTitle: String? = null,
    onRenewalUnavailable: ((String) -> Unit)? = null,
    copySubscription: (String) -> Unit = {},
    startRecovery: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = isTelevision()
    val refreshing = remember { Mutex() }
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var recovering by rememberSaveable { mutableStateOf(startRecovery) }
    var code by remember { mutableStateOf("") }
    var displayedCode by remember { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var selectedPlan by rememberSaveable { mutableIntStateOf(0) }
    var selectedDays by rememberSaveable { mutableIntStateOf(0) }
    var access by rememberSaveable { mutableStateOf<String?>(null) }
    var renewalPlan by remember(renewSource) { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf(client.pending()) }
    var payment by remember { mutableStateOf<JSONObject?>(null) }
    var paymentUrl by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableIntStateOf(0) }
    var legalPage by remember { mutableStateOf<String?>(null) }
    var confirmDismiss by remember { mutableStateOf(false) }
    val checkoutBrowser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Returning from the browser only requests a fresh server read.
        checking++
    }
    val exportCode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(("Phantom — код восстановления\n\n" + displayedCode +
                    "\n\nВ Phantom откройте Подписка → Восстановить покупку.\nХраните в тайне.\n").toByteArray())
            } ?: error("Не удалось сохранить файл")
        }.onFailure { failure = "Не удалось сохранить код. Повторите сохранение." }
    }
    DisposableEffect(displayedCode.isNotBlank(), recovering) {
        val window = (context as? Activity)?.window
        if (displayedCode.isNotBlank() || recovering) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    suspend fun refresh() = refreshing.withLock {
        val savedOrder = pending ?: client.pending()
        val fresh = client.sync()
        catalog = fresh
        pending = client.pending()
        payment = fresh.optJSONObject("payment")
        paymentUrl = payment?.optString("payment_url")?.let(NativeStore::paymentUrl)
        val status = payment?.optString("status")
        if (pending == null && savedOrder != null && status in setOf("COMPLETED", "CANCELED")) {
            selectedDays = 0
            accepted = false
        }
        if (status == "COMPLETED" && pending == null && savedOrder != null) {
            info = if (savedOrder.optString("mode") == "renew") "Подписка продлена."
                else "Подписка добавлена. Можно подключаться к VPN."
        }
        if (status == "CANCELED") info = "Оплата отменена. Можно оформить новый счёт."
    }
    suspend fun loadOffers() {
        if (client.pending() != null) {
            refresh()
            if (pending != null || renewSource == null || renewalPlan != null) return
        }
        if (renewSource != null && renewalPlan == null) {
            val result = client.renewal(renewSource)
            access = result.getString("access_id")
            renewalPlan = result.getInt("plan_id")
            selectedPlan = result.getInt("plan_id")
        }
        refresh()
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null; info = null
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                failure = e.message ?: "Не удалось выполнить действие. Повторите проверку."
                if (e is RenewalUnavailable && onRenewalUnavailable != null) {
                    onRenewalUnavailable(e.message.orEmpty())
                } else if (e is PurchaseOfferChanged) {
                    payment = null; paymentUrl = null
                    if (renewSource != null) {
                        renewalPlan = null
                        access = null
                    }
                    accepted = false
                    try { loadOffers() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (unavailable: RenewalUnavailable) { onRenewalUnavailable?.invoke(unavailable.message.orEmpty()) }
                    catch (_: Exception) { /* Keep the quote error and retry with the next refresh. */ }
                }
            }
            finally {
                pending = client.pending()
                busy = false
            }
        }
    }
    suspend fun showPayment(result: JSONObject) {
        displayedCode = client.recoveryCode(result.getString("access_id"))
        val url = result.optString("payment_url").let(NativeStore::paymentUrl)
        if (!tv && url != null && result.optString("status") == "PENDING") {
            // Let Android's browser handle bank-app links in the provider's checkout.
            val tab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .setSendToExternalDefaultHandlerEnabled(true)
                .build()
            try {
                checkoutBrowser.launch(tab.intent.setData(Uri.parse(url)))
                return
            } catch (_: ActivityNotFoundException) {
                paying = url
            } catch (_: SecurityException) {
                paying = url
            }
        }
        refresh()
        if (tv && paymentUrl == null && pending != null) paymentUrl = url
    }
    LaunchedEffect(refreshVersion, checking) {
        snapshotFlow { busy }.first { !it }
        action { loadOffers() }
    }
    LaunchedEffect(client, refreshVersion) {
        while (true) {
            delay(5_000)
            if (busy || pending == null || recovering || confirmDismiss) continue
            try {
                refresh()
                if (pending == null && renewSource != null && renewalPlan == null) action { loadOffers() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { failure = e.message ?: "Подтверждение задерживается. Повторите проверку." }
        }
    }
    if (paying != null) {
        InAppPayment(paying!!, saveRecovery = if (displayedCode.isBlank()) null else ({ exportCode.launch("Phantom-recovery.txt") })) {
            paying = null; checking++
        }
        return
    }
    if (legalPage != null) {
        InAppPayment(legalPage!!) { legalPage = null }
        return
    }
    val leave = {
        when {
            recovering && !startRecovery -> recovering = false
            !recovering && pending == null && selectedDays != 0 -> {
                selectedDays = 0
                accepted = false
            }
            else -> back()
        }
    }
    BackHandler(onBack = leave)
    val cards = catalog?.optJSONArray("cards")?.objects().orEmpty()
    val chosenCard = cards.firstOrNull { it.optString("access_id") == access }
    val renewing = renewSource != null || access != null
    val plans = catalog?.optJSONArray("plans")?.objects().orEmpty().filter {
        !renewing || it.getInt("id") == (renewalPlan ?: chosenCard?.optJSONObject("subscription")?.optInt("plan_id"))
    }
    val plan = plans.firstOrNull { it.getInt("id") == selectedPlan }
    val duration = plan?.getJSONArray("durations")?.objects()?.firstOrNull { it.getInt("days") == selectedDays }
    val scroll = rememberScrollState()
    LaunchedEffect(recovering, duration != null, pending != null) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = leave, modifier = Modifier.tvFocusRing()) { Text("Назад") }
                Text(when {
                    recovering -> "Восстановить покупку"
                    pending != null -> "Сохранённый счёт"
                    duration != null -> "Подтверждение"
                    renewing -> "Продление подписки"
                    else -> "Купить подписку Phantom"
                }, style = MaterialTheme.typography.titleLarge)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (recovering) {
                OutlinedTextField(code, { code = it }, label = { Text("Код восстановления") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth().tvFocusRing(), onClick = {
                    action {
                        client.recover(code)
                        code = ""
                        loadOffers()
                        info = "Покупка восстановлена."
                    }
                }) { Text("Восстановить") }
                cards.forEach { card ->
                    val id = card.getString("access_id")
                    card.optJSONObject("subscription")?.let { sub ->
                        TextButton(onClick = { copySubscription(sub.getString("url")) }, modifier = Modifier.tvFocusRing()) {
                            Text("Скопировать подписку")
                        }
                        OutlinedButton(
                            onClick = {
                                access = id
                                selectedDays = 0
                                accepted = false
                                recovering = false
                            },
                            enabled = !busy && pending == null, modifier = Modifier.fillMaxWidth().tvFocusRing(),
                        ) { Text("Продлить · до " + sub.optString("expire_at").take(10)) }
                    }
                    if (client.recoveryCode(id).isNotBlank()) TextButton(
                        onClick = { displayedCode = client.recoveryCode(id) }, modifier = Modifier.tvFocusRing(),
                    ) { Text("Код восстановления · " + card.optString("client_id", "Покупка")) }
                }
            } else {
                if (pending != null) {
                    val order = pending!!
                    val kind = if (order.optString("mode") == "renew") "Продление" else "Новая подписка"
                    Text("$kind · ${dayLabel(order.optInt("days"))} · ${priceLabel(order.optString("amount"))}")
                    val status = payment?.optString("status")
                    when (status) {
                        "COMPLETED" -> Text("Оплата подтверждена. Получаем подписку…")
                        "VERIFYING" -> Text("Сервер проверяет платёж.")
                    }
                    if (status != "COMPLETED" && status != "VERIFYING") {
                        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().tvFocusRing(), onClick = {
                            action {
                                val result = client.resumeCheckout()
                                if (result != null) showPayment(result) else loadOffers()
                            }
                        }) { Text("Продолжить оплату") }
                    }
                    if (status == "PENDING") {
                        TextButton(enabled = !busy, onClick = { confirmDismiss = true }, modifier = Modifier.tvFocusRing()) {
                            Text("Убрать счёт")
                        }
                        if (confirmDismiss) AlertDialog(
                            onDismissRequest = { confirmDismiss = false },
                            title = { Text("Убрать сохранённый счёт?") },
                            text = { Text("Счёт исчезнет из Phantom. Если страница ЮKassa ещё открыта, закройте её. ЮKassa закроет счёт по истечении срока. Если вы уже оплатили, сначала проверьте статус.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmDismiss = false
                                    action {
                                        client.dismissCheckout(order.getString("request_id"))
                                        pending = client.pending()
                                        selectedDays = 0
                                        accepted = false
                                        displayedCode = ""
                                        loadOffers()
                                        info = "Счёт убран."
                                    }
                                }) { Text("Убрать счёт") }
                            },
                            dismissButton = { TextButton(onClick = { confirmDismiss = false }) { Text("Оставить") } },
                        )
                    }
                } else {
                    if (renewSource != null) {
                        renewTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    } else if (!renewing && duration == null) {
                        Text("Без регистрации, почты и пароля", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (renewing && renewSource == null) {
                        TextButton(onClick = { access = null; selectedDays = 0; accepted = false }, enabled = !busy,
                            modifier = Modifier.tvFocusRing()) { Text("Купить новую") }
                    }
                    if (renewSource != null && access != null && client.recoveryCode(access).isNotBlank()) {
                        TextButton(onClick = { displayedCode = client.recoveryCode(access) },
                            modifier = Modifier.tvFocusRing()) { Text("Код восстановления") }
                    }
                    if (duration == null && plans.isNotEmpty()) {
                        val canPurchase = !busy && catalog?.optBoolean("sales_enabled") == true &&
                            catalog?.optBoolean("payment_available") == true
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = if (maxWidth >= 560.dp && plans.size > 1) 2 else 1
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                plans.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        row.forEach { item ->
                                            PurchasePlanCard(item, canPurchase, Modifier.weight(1f)) { days ->
                                                selectedPlan = item.getInt("id")
                                                selectedDays = days
                                                accepted = false
                                            }
                                        }
                                        if (row.size < columns) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        if (plans.any { it.optInt("id") in 15..16 }) {
                            Text("Работа при ограничениях мобильного интернета (белые списки) не гарантируется.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (plan != null && duration != null) {
                        Text(plan.getString("name"), style = MaterialTheme.typography.titleLarge)
                        Text("${dayLabel(duration.getInt("days"))} · ${priceLabel(duration.getString("amount"))}",
                            style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(accepted, { accepted = it }, modifier = Modifier.tvFocusRing())
                            Text("Принимаю условия оферты и обработки персональных данных", style = MaterialTheme.typography.bodySmall)
                        }
                        Row {
                            TextButton(onClick = { legalPage = "https://go.veor.pro/legal/offer.txt" }, modifier = Modifier.tvFocusRing()) { Text("Оферта") }
                            TextButton(onClick = { legalPage = "https://go.veor.pro/legal/privacy.txt" }, modifier = Modifier.tvFocusRing()) { Text("Конфиденциальность") }
                        }
                        Button(enabled = !busy && accepted && (!renewing || access != null) &&
                            catalog?.optBoolean("sales_enabled") == true && catalog?.optBoolean("payment_available") == true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing(), onClick = {
                                action { showPayment(client.checkout(plan, duration, if (renewing) access else null, renewing)) }
                            }) { Text("Оплатить · ${priceLabel(duration.getString("amount"))}") }
                    }
                }
                if (tv && paymentUrl != null && pending != null) {
                    Text("Отсканируйте код телефоном и оплатите. Подписка появится на TV автоматически.")
                    val qr = remember(paymentUrl) {
                        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(paymentUrl!!, com.google.zxing.BarcodeFormat.QR_CODE, 480, 480)
                        com.journeyapps.barcodescanner.BarcodeEncoder().createBitmap(matrix).asImageBitmap()
                    }
                    Image(qr, "QR-код оплаты", Modifier.size(200.dp))
                }
                val importPending = catalog?.optBoolean("subscription_import_pending") == true
                if (importPending && pending == null) {
                    Text("Подписка получена. Загрузка серверов пока не завершилась.")
                }
                val salesUnavailable = catalog?.let {
                    !it.optBoolean("sales_enabled") || !it.optBoolean("payment_available")
                } == true
                if (salesUnavailable && pending == null) Text("Новые счета временно недоступны.")
                if (failure != null || pending != null || importPending || salesUnavailable) {
                    TextButton(enabled = !busy, onClick = { action { loadOffers() } }, modifier = Modifier.tvFocusRing()) {
                        Text(if (pending != null) "Проверить оплату" else "Повторить")
                    }
                }
                if (renewSource == null && duration == null) {
                    TextButton(onClick = { recovering = true }, modifier = Modifier.tvFocusRing()) { Text("Восстановить покупку") }
                }
            }
            if (displayedCode.isNotBlank()) {
                Text("Код восстановления", style = MaterialTheme.typography.titleMedium)
                Text("Сохраните копию вне приложения: код вернёт доступ после смены телефона или удаления данных.")
                SelectionContainer { Text(displayedCode, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { exportCode.launch("Phantom-recovery.txt") }, modifier = Modifier.fillMaxWidth().tvFocusRing()) { Text("Сохранить код") }
                TextButton(onClick = { displayedCode = "" }, modifier = Modifier.tvFocusRing()) { Text("Скрыть код") }
            }
        }
    }
}

private fun priceLabel(amount: String): String = "${amount.removeSuffix(".00").replace('.', ',')} ₽"

private fun dayLabel(days: Int): String {
    val unit = when {
        days % 100 in 11..14 -> "дней"
        days % 10 == 1 -> "день"
        days % 10 in 2..4 -> "дня"
        else -> "дней"
    }
    return "$days $unit"
}

@Composable
private fun PurchasePlanCard(
    plan: JSONObject,
    enabled: Boolean,
    modifier: Modifier,
    onTerm: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val id = plan.getInt("id")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(plan.getString("name"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val description = when (id) {
                15 -> "Для личного использования"
                16 -> "Одна подписка для всей семьи"
                else -> ""
            }
            if (description.isNotEmpty()) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Text("Премиальные серверы и приоритетные маршруты",
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Устройств: ${plan.getInt("devices")} · ${plan.getInt("traffic_gb")} ГБ / месяц",
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            plan.getJSONArray("durations").objects().sortedBy { it.getInt("days") }.forEach { term ->
                val days = term.getInt("days")
                OutlinedButton(onClick = { onTerm(days) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing()) {
                    Text("${dayLabel(days)} · ${priceLabel(term.getString("amount"))}")
                }
            }
        }
    }
}
