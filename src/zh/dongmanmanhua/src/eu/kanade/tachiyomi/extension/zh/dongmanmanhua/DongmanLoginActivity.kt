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
 * 在 AndroidManifest.xml 中注册：
 * <activity android:name=".DongmanLoginActivity" android:exported="false" />
 *
 * 用户在 WebView 里完成登录后，自动从 CookieManager 读取
 * NEO_SES 和 NEO_CHK，存入 SharedPreferences 持久化保存。
 */
class DongmanLoginActivity : Activity() {

    private val baseUrl = "https://m.dongmanmanhua.cn"
    private val loginUrl = "$baseUrl/member/login?type=mPay"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).also { setContentView(it) }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        }

        val prefs = getPreferences(this)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // 每次页面加载完成都尝试读取 Cookie
                val cookieStr = CookieManager.getInstance().getCookie(baseUrl) ?: return
                val neoSes = extractCookieValue(cookieStr, "NEO_SES")
                val neoChk = extractCookieValue(cookieStr, "NEO_CHK")

                if (neoSes.isNotEmpty()) {
                    // 登录成功，持久化保存并关闭 Activity
                    prefs.edit()
                        .putString(KEY_NEO_SES, neoSes)
                        .putString(KEY_NEO_CHK, neoChk)
                        .apply()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }

        webView.loadUrl(loginUrl)
    }

    companion object {
        const val KEY_NEO_SES = "neo_ses"
        const val KEY_NEO_CHK = "neo_chk"
        const val PREFS_NAME = "dongman_login"

        fun getPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** 从完整 Cookie 字符串中提取指定 key 的值 */
        fun extractCookieValue(cookieStr: String, key: String): String {
            return cookieStr.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("$key=") }
                ?.removePrefix("$key=")
                ?.trim()
                ?: ""
        }

        /** 从 SharedPreferences 构建 Cookie 请求头值 */
        fun buildCookieHeader(context: Context): String {
            val prefs = getPreferences(context)
            val neoSes = prefs.getString(KEY_NEO_SES, "") ?: ""
            val neoChk = prefs.getString(KEY_NEO_CHK, "") ?: ""
            return buildString {
                if (neoSes.isNotEmpty()) append("NEO_SES=$neoSes; ")
                if (neoChk.isNotEmpty()) append("NEO_CHK=$neoChk")
            }.trimEnd(';', ' ')
        }

        /** 检查是否已登录（NEO_SES 不为空） */
        fun isLoggedIn(context: Context): Boolean =
            getPreferences(context).getString(KEY_NEO_SES, "").orEmpty().isNotEmpty()

        /** 退出登录：清除持久化的 Cookie */
        fun logout(context: Context) {
            getPreferences(context).edit()
                .remove(KEY_NEO_SES)
                .remove(KEY_NEO_CHK)
                .apply()
        }
    }
}
