package xyz.wraithnet.phantom.ui.home

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Message
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

/** A payment-only browser. No subscription URL, guest cookie or native JS bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InAppPayment(url: String, close: () -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun dismissPopup() { popup?.destroy(); popup = null }
    fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "go.veor.pro" && uri.path?.startsWith("/pay-return") == true) {
                    dismissPopup(); close(); return true
                }
                if (uri.scheme == "https") return false
                message = "Этот способ требует приложение банка. Для оплаты внутри Phantom выберите банковскую карту."
                return true
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) message = "Не удалось загрузить оплату. Закройте окно и продолжите сохранённый счёт."
            }
            // Default SSL handling cancels the request. No trust exceptions.
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
                dismissPopup()
                val child = WebView(v.context)
                configure(child)
                popup = child
                (result.obj as WebView.WebViewTransport).webView = child
                result.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { if (window === popup) dismissPopup() }
        }
    }
    BackHandler { if (popup != null) dismissPopup() else close() }
    DisposableEffect(Unit) {
        onDispose { dismissPopup(); browser?.stopLoading(); browser?.destroy(); browser = null }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { if (popup != null) dismissPopup() else close() }, modifier = Modifier.tvFocusRing()) {
            Text(if (popup != null) "Назад к оплате" else "Закрыть оплату")
        }
        message?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { context ->
                WebView(context).also { configure(it); browser = it; it.loadUrl(url) }
            }, modifier = Modifier.fillMaxSize())
            popup?.let { child ->
                Surface(Modifier.fillMaxSize()) {
                    AndroidView(factory = { child }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
