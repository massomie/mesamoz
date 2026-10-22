package ir.engpmis.academy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * مس‌آموز — پوستهٔ اندروید.
 * سایت را از [BuildConfig.SITE_URL] باز می‌کند تا ورود با ایمیل، همگام‌سازی پیشرفت و
 * به‌روزرسانی محتوا مثل نسخهٔ وب کار کند. سرویس‌ورکر سایت پس از اولین اجرا آن را آفلاین هم نگه می‌دارد.
 * اگر بار اول اینترنت نباشد، نسخهٔ داخل برنامه (assets/www/index.html) باز می‌شود و با برگشت اتصال
 * دوباره به سایت برمی‌گردد.
 * پیوندهای ایمیل (https://mesamoz.ir/#/verify?token=… و #/reset?token=…) از طریق App Links
 * داخل همین برنامه باز می‌شوند.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var offlineMode = false
    private var offlineAt = 0L
    private var lastBack = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val siteUrl: String = BuildConfig.SITE_URL
    private val siteHost: String by lazy { Uri.parse(siteUrl).host ?: "" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val navy = getColor(R.color.navy)
        val root = FrameLayout(this).apply { setBackgroundColor(navy) }
        web = WebView(this).apply { setBackgroundColor(navy) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(getColor(R.color.copper))
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.GONE
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))
        setContentView(root)

        // از اندروید ۱۱ به بعد حاشیهٔ نوار وضعیت، ناوبری و صفحه‌کلید را خودمان اعمال می‌کنیم
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString CopperAcademyApp/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.addJavascriptInterface(Bridge(), "CAApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: ""
                if ((scheme == "https" || scheme == "http") && u.host == siteHost) return false
                if (scheme == "file" || scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") return false
                openExternal(u)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                bar.progress = 0
                bar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                bar.visibility = View.GONE
                window.setBackgroundDrawable(ColorDrawable(navy))
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !offlineMode) loadOffline()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar.progress = newProgress
                if (newProgress >= 100) bar.visibility = View.GONE
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("http")) downloadHttp(url, userAgent, contentDisposition, mimeType)
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(siteLink(intent) ?: siteUrl)
    }

    /** پیوندی از دامنهٔ سایت که با App Links به برنامه رسیده (مثلاً لینک تأیید ایمیل یا بازنشانی رمز) */
    private fun siteLink(i: Intent?): String? {
        val src = i ?: return null
        val u = src.data ?: return null
        if (src.action != Intent.ACTION_VIEW || u.scheme != "https" || u.host != siteHost) return null
        return u.toString()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = siteLink(intent) ?: return
        offlineMode = false
        stopWatching()
        web.loadUrl(link)
    }

    private fun loadOffline() {
        offlineMode = true
        offlineAt = SystemClock.elapsedRealtime()
        web.loadUrl(OFFLINE_URL)
        toast(getString(R.string.offline_mode))
        watchNetwork()
    }

    private fun goOnline() {
        offlineMode = false
        stopWatching()
        web.loadUrl(siteUrl)
    }

    /** با برگشت اینترنت، دوباره سایت اصلی بارگذاری می‌شود */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // اعلان فوری هنگام ثبت را نادیده می‌گیریم تا اگر سرور در دسترس نیست، چرخه ایجاد نشود
                if (SystemClock.elapsedRealtime() - offlineAt < 8000) return
                runOnUiThread { if (offlineMode) goOnline() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun stopWatching() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun downloadHttp(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent)
                setMimeType(mimeType)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT >= 29) setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                else setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, name)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast(getString(R.string.download_started))
        } catch (_: Exception) {
            openExternal(Uri.parse(url))
        }
    }

    /** ذخیرهٔ فایل‌هایی که صفحه می‌سازد (پشتیبان JSON، جدول CSV) در پوشهٔ Downloads */
    private fun saveBytes(name: String, mime: String, bytes: ByteArray): Boolean = try {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("stream")
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            FileOutputStream(File(dir, safe)).use { it.write(bytes) }
        }
        true
    } catch (_: Exception) {
        false
    }

    inner class Bridge {
        @JavascriptInterface
        fun config(): String {
            val base = siteUrl.trimEnd('/')
            return JSONObject().put("api", base).put("siteUrl", base).toString()
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun isOffline(): Boolean = offlineMode

        @JavascriptInterface
        fun goOnline() {
            runOnUiThread { this@MainActivity.goOnline() }
        }

        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            Thread {
                val ok = try {
                    saveBytes(name, mime, Base64.decode(base64, Base64.DEFAULT))
                } catch (_: Exception) {
                    false
                }
                runOnUiThread { toast(if (ok) getString(R.string.saved_to, name) else getString(R.string.save_failed)) }
            }.start()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.caBack&&window.caBack())?1:0") { r ->
            if (r == "1") return@evaluateJavascript
            if (web.canGoBack()) {
                web.goBack()
                return@evaluateJavascript
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBack < 2000) finish()
            else {
                lastBack = now
                toast(getString(R.string.press_again))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        stopWatching()
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_FILE = 41
        private const val OFFLINE_URL = "file:///android_asset/www/index.html"
    }
}
