package xyz.wraithnet.phantom.ui.home

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import xyz.wraithnet.phantom.data.NativeStore
import xyz.wraithnet.phantom.data.PurchaseClient
import xyz.wraithnet.phantom.data.PurchaseOfferChanged
import xyz.wraithnet.phantom.data.objects

@Composable
internal fun NativePurchaseScreen(back: () -> Unit, client: PurchaseClient, refreshVersion: Int = 0, renewSource: String? = null, copySubscription: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = isTelevision()
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
    var payment by remember { mutableStateOf<JSONObject?>(null) }
    var paymentUrl by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableIntStateOf(0) }
    var legalPage by remember { mutableStateOf<String?>(null) }
    val exportCode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(("Phantom — код восстановления\n\n" + displayedCode +
                    "\n\nВведите в Phantom → Купить подписку → Восстановить покупку.\nХраните в тайне.\n").toByteArray())
            } ?: error("Не удалось сохранить файл")
        }.onFailure { failure = "Не удалось сохранить код. Повторите сохранение." }
    }
    DisposableEffect(displayedCode.isNotBlank(), recovering) {
        val window = (context as? Activity)?.window
        if (displayedCode.isNotBlank() || recovering) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    suspend fun refresh() {
        val fresh = client.sync()
        catalog = fresh
        client.pending()?.let {
            selectedPlan = it.optInt("plan_id", selectedPlan)
            selectedDays = it.optInt("days", selectedDays)
        }
        payment = fresh.optJSONObject("payment")
        paymentUrl = payment?.optString("payment_url")?.let(NativeStore::paymentUrl)
        if (payment?.optJSONObject("subscription")?.optBoolean("active") == true) info = "Подписка добавлена. Можно подключаться к VPN."
        if (payment?.optString("status") == "CANCELED") info = "Оплата отменена. Можно выбрать тариф заново."
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null; info = null
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                failure = e.message ?: "Не удалось выполнить действие. Повторите проверку."
                if (e is PurchaseOfferChanged) {
                    payment = null; paymentUrl = null
                    try { refresh() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Keep the quote error and retry with the next refresh. */ }
                }
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(renewSource) {
        if (renewSource != null) {
            busy = true
            try {
                val result = client.renewal(renewSource)
                access = result.getString("access_id"); renewalPlan = result.getInt("plan_id")
                selectedPlan = result.getInt("plan_id"); selectedDays = 0
                refresh()
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { failure = e.message ?: "Не удалось получить условия продления." }
            finally { busy = false }
        }
    }
    LaunchedEffect(refreshVersion, checking) {
        try { refresh() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { failure = e.message ?: "Не удалось загрузить магазин." }
    }
    LaunchedEffect(client, refreshVersion) {
        while (true) {
            delay(5_000)
            if (busy || client.pending() == null) continue
            try { refresh() }
            catch (cancel: CancellationException) { throw cancel }
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
    BackHandler { if (recovering) recovering = false else back() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, modifier = Modifier.tvFocusRing()) { Text("Назад") }
                Text(if (recovering) "Восстановить покупку" else "Купить подписку", style = MaterialTheme.typography.titleLarge)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (recovering) {
                OutlinedTextField(code, { code = it }, label = { Text("Код восстановления") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth().tvFocusRing(), onClick = {
                    action {
                        val card = client.recover(code)
                        code = ""; refresh(); access = card.getString("access_id"); recovering = false
                        info = "Покупка восстановлена."
                    }
                }) { Text("Восстановить") }
            } else {
                Text("Без регистрации, почты и пароля", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val cards = catalog?.optJSONArray("cards")?.objects().orEmpty()
                cards.forEach { card ->
                    val id = card.getString("access_id")
                    val sub = card.optJSONObject("subscription")
                    if (sub != null) TextButton(onClick = { copySubscription(sub.getString("url")) }, modifier = Modifier.tvFocusRing()) { Text("Скопировать подписку") }
                    if (sub != null) OutlinedButton(
                        onClick = { access = id; selectedPlan = sub.getInt("plan_id"); selectedDays = 0 },
                        enabled = !busy && client.pending() == null, modifier = Modifier.fillMaxWidth().tvFocusRing()) {
                        Text("Продлить · до " + sub.optString("expire_at").take(10))
                    }
                    if (client.recoveryCode(id).isNotBlank()) TextButton(
                        onClick = { displayedCode = client.recoveryCode(id) }, modifier = Modifier.tvFocusRing()) {
                        Text("Код восстановления · " + card.optString("client_id", "Покупка"))
                    }
                }
                val chosenCard = cards.firstOrNull { it.optString("access_id") == access }
                val renewing = renewSource != null || chosenCard?.optJSONObject("subscription") != null
                if (renewing) {
                    Text("Продление подписки", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { if (renewSource != null) back() else access = null }, enabled = !busy && client.pending() == null,
                        modifier = Modifier.tvFocusRing()) { Text("Купить новую") }
                }
                val plans = catalog?.optJSONArray("plans")?.objects().orEmpty().filter {
                    !renewing || it.getInt("id") == (renewalPlan ?: chosenCard?.optJSONObject("subscription")?.optInt("plan_id"))
                }
                val plan = plans.firstOrNull { it.getInt("id") == selectedPlan } ?: plans.firstOrNull()
                plans.forEach { item ->
                    Surface(shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (item === plan) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(item.getString("name"), style = MaterialTheme.typography.titleMedium)
                            Text("Устройств: ${item.getInt("devices")} · ${item.getInt("traffic_gb")} ГБ / месяц")
                            TextButton(enabled = !busy && client.pending() == null, onClick = { selectedPlan = item.getInt("id"); selectedDays = 0 },
                                modifier = Modifier.tvFocusRing()) { Text(if (item === plan) "Выбран" else "Выбрать") }
                        }
                    }
                }
                val durations = plan?.getJSONArray("durations")?.objects().orEmpty()
                val duration = durations.firstOrNull { it.getInt("days") == selectedDays } ?: durations.firstOrNull()
                durations.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = item === duration, enabled = !busy && client.pending() == null,
                            onClick = { selectedDays = item.getInt("days") }, modifier = Modifier.tvFocusRing())
                        Text("${item.getInt("days")} дней · ${item.getString("amount")} ₽")
                    }
                }
                if (client.pending() != null) Text(when (payment?.optString("status")) {
                    "COMPLETED" -> "Оплата подтверждена. Получаем подписку…"
                    "VERIFYING" -> "Сервер проверяет платёж. Не оплачивайте повторно."
                    else -> "Покупка сохранена. Можно продолжить этот же счёт."
                })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(accepted, { accepted = it }, modifier = Modifier.tvFocusRing())
                    Text("Принимаю условия оферты и обработки персональных данных", style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = { legalPage = "https://go.veor.pro/legal/offer.txt" }, modifier = Modifier.tvFocusRing()) { Text("Оферта") }
                    TextButton(onClick = { legalPage = "https://go.veor.pro/legal/privacy.txt" }, modifier = Modifier.tvFocusRing()) { Text("Конфиденциальность") }
                }
                Button(enabled = !busy && plan != null && duration != null && accepted &&
                    (renewSource == null || renewalPlan != null) && catalog?.optBoolean("sales_enabled") == true && catalog?.optBoolean("payment_available") == true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusRing(), onClick = {
                        action {
                            val result = client.checkout(plan!!, duration!!, if (renewing) access else null, renewing)
                            payment = result; displayedCode = client.recoveryCode(result.getString("access_id"))
                            paymentUrl = result.optString("payment_url").let(NativeStore::paymentUrl)
                            if (!tv) paying = paymentUrl
                            refresh()
                        }
                    }) { Text(if (client.pending() != null) "Продолжить оплату" else "Оплатить · ${duration?.optString("amount").orEmpty()} ₽") }
                if (tv && paymentUrl != null) {
                    Text("Отсканируйте код телефоном и оплатите. Подписка появится на TV автоматически.")
                    val qr = remember(paymentUrl) {
                        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(paymentUrl!!, com.google.zxing.BarcodeFormat.QR_CODE, 480, 480)
                        com.journeyapps.barcodescanner.BarcodeEncoder().createBitmap(matrix).asImageBitmap()
                    }
                    Image(qr, "QR-код оплаты", Modifier.size(200.dp))
                }
                TextButton(enabled = !busy, onClick = { action { refresh() } }, modifier = Modifier.tvFocusRing()) { Text("Проверить оплату") }
                TextButton(onClick = { recovering = true }, modifier = Modifier.tvFocusRing()) { Text("Восстановить покупку") }
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

