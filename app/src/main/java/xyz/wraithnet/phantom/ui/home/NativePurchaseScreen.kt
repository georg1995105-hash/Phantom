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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import xyz.wraithnet.phantom.data.NativeStore
import xyz.wraithnet.phantom.data.PurchaseClient
import xyz.wraithnet.phantom.data.PurchaseOfferChanged
import xyz.wraithnet.phantom.data.RenewalUnavailable
import xyz.wraithnet.phantom.data.objects

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NativePurchaseScreen(
    back: () -> Unit,
    client: PurchaseClient,
    refreshVersion: Int = 0,
    renewSource: String? = null,
    renewTitle: String? = null,
    onRenewalUnavailable: ((String) -> Unit)? = null,
    copySubscription: (String) -> Unit = {},
    accessCard: (@Composable () -> Unit)? = null,
    openShop: (() -> Unit)? = null,
) {
    val embedded = accessCard != null
    val termHeading = remember { BringIntoViewRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = isTelevision()
    val refreshing = remember { Mutex() }
    var catalog by remember { mutableStateOf<JSONObject?>(null) }
    var recovering by rememberSaveable { mutableStateOf(false) }
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
        action { loadOffers() }
    }
    LaunchedEffect(client, refreshVersion) {
        while (true) {
            delay(5_000)
            if (busy || pending == null || recovering) continue
            try {
                refresh()
                if (pending == null && renewSource != null && renewalPlan == null) action { loadOffers() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { failure = e.message ?: "Подтверждение задерживается. Повторите проверку." }
        }
    }
    LaunchedEffect(selectedPlan) {
        if (embedded && selectedPlan != 0) termHeading.bringIntoView()
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
    val leave = { if (recovering) recovering = false else back() }
    BackHandler(enabled = !embedded || recovering, onBack = leave)
    val cards = catalog?.optJSONArray("cards")?.objects().orEmpty()
    val chosenCard = cards.firstOrNull { it.optString("access_id") == access }
    val renewing = renewSource != null || access != null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!embedded || recovering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = leave, modifier = Modifier.tvFocusRing()) { Text("Назад") }
                    Text(when {
                        recovering -> "Восстановить покупку"
                        pending != null -> "Сохранённый счёт"
                        renewing -> "Продление подписки"
                        else -> "Купить подписку"
                    }, style = MaterialTheme.typography.titleLarge)
                }
            } else {
                accessCard?.invoke()
                Text(if (pending != null) "Сохранённый счёт" else "Тарифы",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                        recovering = false
                        info = "Покупка восстановлена."
                    }
                }) { Text("Восстановить") }
            } else {
                if (pending != null) {
                    val order = pending!!
                    val kind = if (order.optString("mode") == "renew") "Продление" else "Новая подписка"
                    Text("$kind · ${order.optInt("days")} дн. · ${order.optString("amount")} ₽")
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
                } else {
                    if (renewSource != null) {
                        renewTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    } else if (!embedded && !renewing) {
                        Text("Без регистрации, почты и пароля", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!embedded && renewSource == null) cards.forEach { card ->
                        val id = card.getString("access_id")
                        val sub = card.optJSONObject("subscription")
                        if (sub != null) TextButton(onClick = { copySubscription(sub.getString("url")) }, modifier = Modifier.tvFocusRing()) { Text("Скопировать подписку") }
                        if (sub != null) OutlinedButton(
                            onClick = {
                                access = id
                                selectedPlan = sub.getInt("plan_id")
                                selectedDays = 0
                                accepted = false
                            },
                            enabled = !busy, modifier = Modifier.fillMaxWidth().tvFocusRing()) {
                            Text("Продлить · до " + sub.optString("expire_at").take(10))
                        }
                        if (client.recoveryCode(id).isNotBlank()) TextButton(
                            onClick = { displayedCode = client.recoveryCode(id) }, modifier = Modifier.tvFocusRing()) {
                            Text("Код восстановления · " + card.optString("client_id", "Покупка"))
                        }
                    }
                    if (renewing && renewSource == null) {
                        TextButton(onClick = { access = null; accepted = false }, enabled = !busy,
                            modifier = Modifier.tvFocusRing()) { Text("Купить новую") }
                    }
                    if (renewSource != null && access != null && client.recoveryCode(access).isNotBlank()) {
                        TextButton(onClick = { displayedCode = client.recoveryCode(access) },
                            modifier = Modifier.tvFocusRing()) { Text("Код восстановления") }
                    }
                    val plans = catalog?.optJSONArray("plans")?.objects().orEmpty().filter {
                        !renewing || it.getInt("id") == (renewalPlan ?: chosenCard?.optJSONObject("subscription")?.optInt("plan_id"))
                    }
                    val plan = plans.firstOrNull { it.getInt("id") == selectedPlan }
                        ?: if (embedded) null else plans.firstOrNull()
                    if (plans.isNotEmpty()) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val columns = if (maxWidth >= 560.dp && plans.size > 1) 2 else 1
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                plans.chunked(columns).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { item ->
                                            PurchasePlanCard(item, selected = item === plan,
                                                selectable = !renewing, enabled = !busy, modifier = Modifier.weight(1f),
                                                onSelect = {
                                                    if (item !== plan) {
                                                        selectedPlan = item.getInt("id")
                                                        selectedDays = 0
                                                    }
                                                },
                                            )
                                        }
                                        if (row.size < columns) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    val durations = plan?.getJSONArray("durations")?.objects().orEmpty()
                    val duration = durations.firstOrNull { it.getInt("days") == selectedDays } ?: durations.firstOrNull()
                    if (durations.isNotEmpty()) {
                        Column(Modifier.selectableGroup()) {
                            Text("Срок подписки", Modifier.bringIntoViewRequester(termHeading),
                                style = MaterialTheme.typography.titleSmall)
                            durations.forEach { item ->
                                val selected = item === duration
                                val shape = MaterialTheme.shapes.small
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing(shape).clip(shape)
                                    .selectable(selected = selected, enabled = !busy, role = Role.RadioButton,
                                        onClick = { selectedDays = item.getInt("days") })
                                    .padding(horizontal = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected, onClick = null, enabled = !busy)
                                    Text("${item.getInt("days")} дней", Modifier.weight(1f))
                                    Text(priceLabel(item.getString("amount")), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    if (plan != null && duration != null) {
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
                if (renewSource == null) {
                    TextButton(onClick = { recovering = true }, modifier = Modifier.tvFocusRing()) { Text("Восстановить покупку") }
                }
                openShop?.let {
                    TextButton(onClick = it, modifier = Modifier.tvFocusRing()) {
                        Text("В магазин")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.NorthEast, null, Modifier.size(16.dp))
                    }
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

@Composable
private fun PurchasePlanCard(
    plan: JSONObject,
    selected: Boolean,
    selectable: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val selection = if (selectable) {
        Modifier.tvFocusRing(shape).clip(shape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
    } else Modifier
    val startingTerm = plan.getJSONArray("durations").objects().minByOrNull { it.getInt("days") }
    Surface(
        modifier = modifier.then(selection),
        shape = shape,
        color = if (selected) colors.primaryContainer.copy(alpha = .62f) else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(plan.getString("name"), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                startingTerm?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(priceLabel(it.getString("amount")),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("за ${it.getInt("days")} дн.", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Text("Устройств: ${plan.getInt("devices")} · ${plan.getInt("traffic_gb")} ГБ / месяц",
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            if (selectable) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .65f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selected) "Тариф выбран" else "Выбрать срок", Modifier.weight(1f),
                        color = colors.primary, style = MaterialTheme.typography.labelLarge)
                    if (!selected) Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = colors.primary)
                }
            }
        }
    }
}
