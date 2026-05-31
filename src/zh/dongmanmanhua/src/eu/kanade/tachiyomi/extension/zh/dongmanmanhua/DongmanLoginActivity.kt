package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 咚漫 WebView 登录 Activity
 *
 * 使用方法：
 * 1. 在 AndroidManifest.xml 中注册（放在 src/zh/dongmanmanhua/AndroidManifest.xml）：
 *    <activity android:name=".DongmanLoginActivity" android:exported="false" />
 *
 * 2. 在 DongmanManhua.kt 中通过 Intent 启动此 Activity
 */
class DongmanLoginActivity : Activity() {

    private val baseUrl = "https://m.dongmanmanhua.cn"
    private val loginUrl = "$baseUrl/member/login"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = LoginWebViewClient()
            loadUrl(loginUrl)
        }
        setContentView(webView)
    }

    private inner class LoginWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // 尝试从 Cookie 中提取登录凭证
            val cookieStr = CookieManager.getInstance().getCookie(baseUrl) ?: return
            val neoSes = extractCookieValue(cookieStr, "NEO_SES")
            if (neoSes.isNotEmpty()) {
                // 保存到 SharedPreferences（与 DongmanManhua 共用）
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_NEO_SES, neoSes)
                    .putString(KEY_NEO_CHK, extractCookieValue(cookieStr, "NEO_CHK"))
                    .apply()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    companion object {
        const val KEY_NEO_SES = "neo_ses"
        const val KEY_NEO_CHK = "neo_chk"
        private const val PREFS_NAME = "eu.kanade.tachiyomi.extension.zh.dongmanmanhua_preferences"

        private fun extractCookieValue(cookieStr: String, key: String): String {
            return cookieStr.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("$key=") }
                ?.removePrefix("$key=")
                ?.trim()
                ?: ""
        }

        fun getPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun buildCookieHeader(context: Context): String {
            val prefs = getPreferences(context)
            val neoSes = prefs.getString(KEY_NEO_SES, "") ?: ""
            val neoChk = prefs.getString(KEY_NEO_CHK, "") ?: ""
            return buildString {
                if (neoSes.isNotEmpty()) append("NEO_SES=$neoSes; ")
                if (neoChk.isNotEmpty()) append("NEO_CHK=$neoChk")
            }.trimEnd(';', ' ')
        }

        fun isLoggedIn(context: Context): Boolean =
            getPreferences(context).getString(KEY_NEO_SES, "").orEmpty().isNotEmpty()

        fun logout(context: Context) {
            getPreferences(context).edit()
                .remove(KEY_NEO_SES)
                .remove(KEY_NEO_CHK)
                .apply()
        }
    }
}
