package br.com.atmis.mdaguias

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object {
        // >>> Altere aqui se o endereço do sistema mudar <<<
        const val START_URL = "https://remix-mda-especialidades-9542.ai.studio/"

        private const val REQ_FILE = 1001
        private const val REQ_STORAGE = 1002

        // Mantém URLs "blob:" vivas por 60s e captura o nome do arquivo (atributo download)
        private const val DOWNLOAD_HOOK_JS = """
            (function(){
              if (window.__mdaHooked) return; window.__mdaHooked = true;
              var origRevoke = URL.revokeObjectURL;
              URL.revokeObjectURL = function(u){ setTimeout(function(){ origRevoke.call(URL, u); }, 60000); };
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){
                if (this.download) window.__mdaName = this.download;
                return origClick.apply(this, arguments);
              };
              document.addEventListener('click', function(e){
                var a = (e.target && e.target.closest) ? e.target.closest('a[download]') : null;
                if (a) window.__mdaName = a.getAttribute('download');
              }, true);
            })();
        """
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            progress,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.TOP)
        )
        setContentView(root)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }

        setupWebView()

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false) // target=_blank abre na mesma tela
            builtInZoomControls = false
            userAgentString = "$userAgentString MDAGuiasApp/1.0"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    "intent" -> {
                        handleIntentScheme(view, uri.toString()); true
                    }
                    else -> {
                        // mailto:, tel:, whatsapp:, etc.
                        openExternal(uri); true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                view.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadDataWithBaseURL(null, offlineHtml(), "text/html", "UTF-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val intent = fileChooserParams.createIntent().apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_FILE)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    toast("Nenhum app disponível para selecionar arquivos")
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("blob:") -> downloadBlob(url, mimeType ?: "")
                url.startsWith("data:") -> saveDataUrl(url)
                else -> downloadHttp(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    // ---------- Downloads ----------

    private fun downloadHttp(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Baixando: $name")
        } catch (e: Exception) {
            toast("Falha no download: ${e.message}")
        }
    }

    private fun downloadBlob(url: String, mime: String) {
        val safeMime = mime.replace("'", "")
        val js = "(function(){fetch('" + url + "').then(function(r){return r.blob();}).then(function(b){" +
                "var fr=new FileReader();fr.onloadend=function(){var d=fr.result;var i=d.indexOf(',');" +
                "AndroidBridge.save(d.substring(i+1), b.type||'" + safeMime + "', window.__mdaName||'');" +
                "window.__mdaName=null;};fr.readAsDataURL(b);})" +
                ".catch(function(e){AndroidBridge.fail(String(e));});})();"
        webView.evaluateJavascript(js, null)
    }

    private fun saveDataUrl(url: String) {
        try {
            val comma = url.indexOf(',')
            val meta = url.substring(5, comma)
            val mime = meta.substringBefore(';')
            val data = url.substring(comma + 1)
            val bytes = if (meta.contains(";base64")) {
                Base64.decode(data, Base64.DEFAULT)
            } else {
                Uri.decode(data).toByteArray()
            }
            saveToDownloads(bytes, defaultName(mime), mime)
        } catch (e: Exception) {
            toast("Falha ao salvar arquivo: ${e.message}")
        }
    }

    private fun defaultName(mime: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return "arquivo_" + System.currentTimeMillis() + (if (ext != null) ".$ext" else "")
    }

    private fun saveToDownloads(bytes: ByteArray, name: String, mime: String) {
        val type = mime.ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("não foi possível criar o arquivo")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            runOnUiThread {
                toast("Salvo em Downloads: $name")
                openFile(uri, type)
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            toast("Salvo em Downloads: $name")
        }
    }

    private fun openFile(uri: Uri, mime: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            // Sem app para abrir esse tipo; o arquivo continua em Downloads
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun save(base64: String, mime: String, suggestedName: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val name = suggestedName.ifBlank { defaultName(mime) }
                saveToDownloads(bytes, name, mime)
            } catch (e: Exception) {
                toast("Falha ao salvar arquivo: ${e.message}")
            }
        }

        @JavascriptInterface
        fun fail(msg: String) {
            toast("Falha no download: $msg")
        }
    }

    // ---------- Navegação externa ----------

    private fun handleIntentScheme(view: WebView, url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                intent.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            toast("Nenhum app disponível para abrir este link")
        }
    }

    // ---------- Ciclo de vida ----------

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val result: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 0) {
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ---------- Utilitários ----------

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun offlineHtml(): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;
               justify-content:center;height:100vh;margin:0;color:#333;text-align:center;padding:24px;box-sizing:border-box}
          h2{margin:0 0 8px} p{color:#666;margin:0 0 24px}
          button{background:#1E5AA8;color:#fff;border:0;border-radius:8px;padding:14px 28px;font-size:16px}
        </style></head><body>
          <h2>Sem conexão</h2>
          <p>Não foi possível carregar o sistema. Verifique sua internet e tente novamente.</p>
          <button onclick="location.href='$START_URL'">Tentar novamente</button>
        </body></html>
    """.trimIndent()
}
