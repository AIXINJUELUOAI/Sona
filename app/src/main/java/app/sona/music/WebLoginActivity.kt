package app.sona.music

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class WebLoginActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var canGoBack by mutableStateOf(false)
    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val music = application as MusicApplication
        setContent {
            SonaTheme(music.settings.theme) { _, _ ->
                // Let Android handle the cross-Activity predictive return when web history is empty.
                val backPreview = rememberBackPreview(enabled = canGoBack, restoreAfterCommit = true,
                    onBack = { webView?.goBack() })
                Column(Modifier.fillMaxSize().backPreview(backPreview).background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing)) {
                    TopAppBar(title = { Text("网易云音乐网页登录") }, navigationIcon = { FilledTonalIconButton(onClick = { if (canGoBack) webView?.goBack() else finish() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ArrowBack, "返回") } },
                        actions = { TextButton(onClick = { captureSession(); finish() }) { Text("完成") } })
                    AndroidView(factory = { context ->
                        WebView(context).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            CookieManager.getInstance().setAcceptCookie(true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val host = request.url.host.orEmpty()
                                    return request.url.scheme != "https" || !(host == "163.com" || host.endsWith(".163.com") || host == "126.net" || host.endsWith(".126.net"))
                                }
                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canGoBack = view.canGoBack()
                                }
                                override fun onPageFinished(view: WebView, url: String) {
                                    canGoBack = view.canGoBack()
                                    captureSession()
                                }
                            }
                            loadUrl("https://music.163.com/#/login")
                        }
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    private fun captureSession() {
        val cookie = CookieManager.getInstance().getCookie("https://music.163.com/").orEmpty()
        if (cookie.split(';').any { it.trim().startsWith("MUSIC_U=") }) (application as MusicApplication).settings.cookie = cookie
    }
    override fun onDestroy() {
        webView?.destroy(); webView = null
        super.onDestroy()
    }
}
