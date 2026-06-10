package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DongmanManhua : HttpSource(), ConfigurableSource {

    init {
        Handler(Looper.getMainLooper()).post {
            CookieManager.getInstance().setAcceptCookie(true)
        }
        Log.d("DongmanCookie", "扩展初始化完成")
    }

    override val name = "Dongman Manhua"
    override val lang get() = "zh-Hans"
    override val id get() = 7275979680702931948
    override val baseUrl = "https://m.dongmanmanhua.cn"
    override val supportsLatest = true

    private val cdnBase = "https://cdn.dongmanmanhua.cn"
    private val preferences by getPreferencesLazy()
    private val appContext by lazy { Injekt.get<android.app.Application>() }
    private var dialogContext: Context? = null
    private var isLoginDialogShowing = false
    private var loginSuccessHandled = false

    // ---------- 独立存储 ----------
    private fun getCookieDir(): File = File(appContext.filesDir, "dongmanmanhua").apply { mkdirs() }
    private fun getCookieFile(): File = File(getCookieDir(), "cookie.dat")

    private var cachedCookie: String? = null
    private var lastIndependentState: Boolean? = null

    private fun saveCookieToFile(neoSes: String, neoChk: String) {
        try {
            getCookieFile().writeText("$neoSes|$neoChk")
            Log.d("DongmanCookie", "Cookie 已写入文件: $neoSes|$neoChk")
            cachedCookie = buildCookieString(neoSes, neoChk)
            lastIndependentState = useIndependentStorage()
        } catch (e: Exception) {
            Log.e("DongmanCookie", "写入文件失败", e)
        }
    }

    private fun readCookieFromFile(): Pair<String, String> {
        return try {
            val content = getCookieFile().readText()
            val parts = content.split("|")
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        } catch (e: Exception) {
            "" to ""
        }
    }

    private fun deleteCookieFile() {
        try {
            getCookieFile().delete()
            Log.d("DongmanCookie", "Cookie 文件已删除")
        } catch (e: Exception) {
            Log.e("DongmanCookie", "删除文件失败", e)
        }
    }

    private fun buildCookieString(neoSes: String, neoChk: String): String = buildString {
        if (neoSes.isNotEmpty()) append("NEO_SES=$neoSes; ")
        if (neoChk.isNotEmpty()) append("NEO_CHK=$neoChk")
    }.trimEnd(';', ' ')

    private fun useIndependentStorage(): Boolean = preferences.getBoolean(PREF_INDEPENDENT_STORAGE, false)

    // ===================== 手动备用Cookie开关 =====================
    private fun getManualCookieEnable(): Boolean = preferences.getBoolean(PREF_MANUAL_COOKIE_SWITCH, false)

    private fun clearManualBackup() {
        deleteCookieFile()
        preferences.edit().putBoolean(PREF_MANUAL_COOKIE_SWITCH, false).apply()
    }

    private fun hasManualBackup(): Boolean {
        val (ses, _) = readCookieFromFile()
        return ses.isNotBlank()
    }

    private fun buildManualSwitchSummary(): String {
        val hasLocal = hasManualBackup()
        return "开启：请求头使用本地保存的NEO鉴权Cookie；关闭：使用原有策略\n本地缓存：${if (hasLocal) "存在" else "无"}"
    }

    // ===================== 探针校验 =====================
    private var lastProbeTime: Long = 0L
    private var cacheLoginValid: Boolean? = null

    private fun probeIsLoginValid(): Boolean {
        if (!getManualCookieEnable()) return true
        val now = System.currentTimeMillis()
        if (now - lastProbeTime < 60_000 && cacheLoginValid != null) {
            return cacheLoginValid!!
        }
        return try {
            val req = Request.Builder()
                .url("${baseUrl}/member/isLogin")
                .headers(headersBuilder().build())
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            val valid = body.contains("true", ignoreCase = true)
            lastProbeTime = now
            cacheLoginValid = valid
            if (!valid) {
                clearManualBackup()
                refreshCookieCache()
                loginIndicator.summary = buildLoginSummary()
                if (::manualCookieSwitch.isInitialized) {
                    manualCookieSwitch.summary = buildManualSwitchSummary()
                }
            }
            valid
        } catch (_: Exception) {
            true
        }
    }

    private fun refreshCookieCache() {
        val independent = useIndependentStorage()
        val cookie = if (getManualCookieEnable()) {
            val file = getCookieFile()
            if (file.exists()) {
                val (neoSes, neoChk) = readCookieFromFile()
                if (neoSes.isNotEmpty() && neoChk.isNotEmpty()) {
                    buildCookieString(neoSes, neoChk)
                } else null
            } else null
        } else {
            if (independent) {
                val file = getCookieFile()
                if (file.exists()) {
                    val (neoSes, neoChk) = readCookieFromFile()
                    if (neoSes.isNotEmpty() && neoChk.isNotEmpty()) {
                        buildCookieString(neoSes, neoChk)
                    } else {
                        val spNeoSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
                        val spNeoChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
                        buildCookieString(spNeoSes, spNeoChk)
                    }
                } else {
                    val spNeoSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
                    val spNeoChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
                    buildCookieString(spNeoSes, spNeoChk)
                }
            } else {
                val cmCookie = CookieManager.getInstance().getCookie(baseUrl) ?: ""
                if (cmCookie.contains("NEO_SES") || cmCookie.contains("NEO_CHK")) {
                    cmCookie
                } else {
                    val neoSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
                    val neoChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
                    buildCookieString(neoSes, neoChk)
                }
            }
        }
        cachedCookie = cookie?.ifEmpty { null }
        lastIndependentState = independent
        Log.d("DongmanCookie", "Cookie 缓存已刷新: ${cachedCookie ?: "(无)"}")
    }

    private lateinit var loginIndicator: SwitchPreferenceCompat
    private lateinit var manualCookieSwitch: SwitchPreferenceCompat

    // ══════════════════════════════════════════════════════════════════════
    // 设置页
    // ══════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context
        dialogContext = ctx

        val editor = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (value is String && (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true))) {
                editor.putBoolean(key, value.toBoolean())
            }
        }
        editor.apply()

        // 独立存储开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_INDEPENDENT_STORAGE
            title = "独立存储 Cookie"
            summary = "开启后使用私有文件保存登录态，不受清除全局Cookie影响"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                refreshCookieCache()
                loginIndicator.summary = buildLoginSummary()
                true
            }
        }.also(screen::addPreference)

        // 手动备用Cookie开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_MANUAL_COOKIE_SWITCH
            title = "启用手动备用Cookie模式"
            summary = buildManualSwitchSummary()
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                refreshCookieCache()
                loginIndicator.summary = buildLoginSummary()
                summary = buildManualSwitchSummary()
                true
            }
            manualCookieSwitch = this
        }.also(screen::addPreference)

        // 登录状态指示灯（仅显示，不可点击）
        SwitchPreferenceCompat(ctx).apply {
            key = "login_indicator"
            title = "登录状态"
            summary = buildLoginSummary()
            setDefaultValue(false)
            setEnabled(false)
            loginIndicator = this
        }.also(screen::addPreference)

        // WebView 登录触发器（拨动开关弹出对话框，自动弹回）
        SwitchPreferenceCompat(ctx).apply {
            key = "webview_login_trigger"
            title = "WebView 登录"
            summary = "拨动此开关弹出咚漫登录页面，登录后自动保存状态"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    showWebViewLoginDialog()
                    false
                } else {
                    true
                }
            }
        }.also(screen::addPreference)

        // 账号输入框
        EditTextPreference(ctx).apply {
            key = PREF_LOGIN_USERNAME
            title = "账号（手机号或邮箱）"
            summary = "用于密码登录，需先填账号再填密码"
            dialogTitle = "输入账号"
            setDefaultValue("")
        }.also(screen::addPreference)

        // 密码输入框
        EditTextPreference(ctx).apply {
            key = PREF_LOGIN_PASSWORD
            title = "密码"
            summary = "输入密码点确定后立即尝试 RSA 登录"
            dialogTitle = "输入密码"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val password = newValue as? String ?: ""
                if (password.isNotBlank()) {
                    val username = preferences.getString(PREF_LOGIN_USERNAME, "").orEmpty()
                    if (username.isBlank()) {
                        Toast.makeText(ctx, "请先填写账号", Toast.LENGTH_SHORT).show()
                    } else {
                        loginWithPassword(username, password)
                    }
                }
                preferences.edit().remove(PREF_LOGIN_PASSWORD).apply()
                false
            }
        }.also(screen::addPreference)

        // 彻底退出登录
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_LOGOUT_TRIGGER
            title = "彻底退出登录"
            summary = "清除所有登录信息（包括WebView），并删除独立存储备份"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                fullLogout()
                false
            }
        }.also(screen::addPreference)

        // 仅清除独立存储备份
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_CLEAR_BACKUP
            title = "清除独立存储备份"
            summary = "仅删除本地保存的Cookie备份，不影响WebView登录状态"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearBackupOnly()
                false
            }
        }.also(screen::addPreference)

        // 搜索模式开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_SEARCH_MODE
            title = "搜索显示小说"
            summary = "开启后搜索结果包含小说（首页HTML接口）\n关闭则只显示漫画（JSON接口，速度更快）"
            setDefaultValue(false)
        }.also(screen::addPreference)

        // 自动扣费开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_AUTO_PAY
            title = "自动购买付费章节"
            summary = "开启后，打开未购付费章节时将自动扣费解锁\n余额不足时会提示错误，需要先开启登录状态"
            setDefaultValue(false)
        }.also(screen::addPreference)

        // User-Agent 预设
        ListPreference(ctx).apply {
            key = PREF_UA
            title = "User-Agent 预设"
            summary = "%s"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent", "自定义（见下方输入框）")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "", PREF_UA_CUSTOM_FLAG)
            setDefaultValue(UA_MOBILE)
        }.also(screen::addPreference)

        // User-Agent 自定义
        EditTextPreference(ctx).apply {
            key = PREF_UA_CUSTOM
            title = "User-Agent 自定义值"
            summary = "选择「自定义」时生效\n当前值：${preferences.getString(PREF_UA_CUSTOM, "").orEmpty().ifEmpty { "（未填写）" }}"
            dialogTitle = "输入自定义 User-Agent"
            setDefaultValue("")
        }.also(screen::addPreference)

        refreshCookieCache()
    }

    // ══════════════════════════════════════════════════════════════════════
    // WebView 登录对话框（恢复猫咪区域，只隐藏底部空白）
    // ══════════════════════════════════════════════════════════════════════

    private fun showWebViewLoginDialog() {
        if (isLoginDialogShowing) return
        val actCtx = dialogContext
        if (actCtx == null) {
            Toast.makeText(appContext, "无法显示登录窗口：上下文丢失", Toast.LENGTH_SHORT).show()
            return
        }
        if (actCtx is android.app.Activity && (actCtx.isFinishing || actCtx.isDestroyed)) {
            Toast.makeText(appContext, "页面已关闭，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        isLoginDialogShowing = true
        loginSuccessHandled = false

        var dialog: AlertDialog? = null
        var isKeyboardVisible = false
        var lastLayoutTime = 0L                         // 防抖时间戳
        data class InputRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
        val formRects = mutableListOf<InputRect>()

        val webView = WebView(actCtx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = currentUserAgent().takeIf { it.isNotEmpty() } ?: UA_MOBILE
            CookieManager.getInstance().setAcceptCookie(true)

            isFocusable = true
            isFocusableInTouchMode = true

            setOnTouchListener { v, event ->
                if (!v.hasFocus()) v.requestFocus()
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val x = event.x
                    val y = event.y
                    Log.d("DongmanIME", "ACTION_DOWN x=$x y=$y webView.scrollY=${scrollY} formRects=$formRects")
                    if (formRects.isNotEmpty()) {
                        val inForm = formRects.any { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
                        Log.d("DongmanIME", "inForm=$inForm rect=${formRects.firstOrNull()}")
                        if (!inForm) {
                            Log.d("DongmanIME", "点击在表单外，吞掉事件")
                            return@setOnTouchListener true
                        }
                    } else {
                        Log.d("DongmanIME", "formRects为空，放行所有点击")
                    }
                }
                false
            }

            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    // 不再隐藏 #content（验证码容器），只调整表单样式
                    view?.evaluateJavascript("""
                        (function(){
                            var form = document.getElementById('formLogin');
                            if(form) {
                                form.style.minHeight = '100vh';
                                form.style.boxSizing = 'border-box';
                                form.style.paddingTop = '16px';
                            }
                        })();
                    """.trimIndent(), null)

                    // 延迟缓存表单坐标
                    view?.postDelayed({
                        view.evaluateJavascript("""
                            (function(){
                                var dpr = window.devicePixelRatio || 1;
                                var form = document.getElementById('formLogin');
                                if(!form) return '';
                                var r = form.getBoundingClientRect();
                                return (r.left*dpr)+','+(r.top*dpr)+','+(r.right*dpr)+','+(r.bottom*dpr)+'|dpr='+dpr+'|scrollY='+window.scrollY;
                            })()
                        """.trimIndent()) { value ->
                            Log.d("DongmanIME", "formRects JS原始返回: $value webView.scrollY=${view.scrollY}")
                            val raw = value?.trim('"') ?: return@evaluateJavascript
                            val coordPart = raw.substringBefore("|")
                            val parts = coordPart.split(",")
                            if (parts.size == 4) {
                                formRects.clear()
                                formRects.add(InputRect(
                                    parts[0].toFloat(),
                                    parts[1].toFloat(),
                                    parts[2].toFloat(),
                                    parts[3].toFloat()
                                ))
                                Log.d("DongmanIME", "缓存表单坐标: $formRects (来自: $raw)")
                            }
                        }
                    }, 500)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val cookieStr = CookieManager.getInstance().getCookie(baseUrl) ?: ""
                    Log.d("DongmanCookie", "WebView 对话框登录后 CookieManager: $cookieStr")
                    val neoSes = extractCookieValue(cookieStr, "NEO_SES")
                    val neoChk = extractCookieValue(cookieStr, "NEO_CHK")
                    if (!loginSuccessHandled && neoSes.isNotEmpty()) {
                        loginSuccessHandled = true
                        preferences.edit()
                            .putString(KEY_NEO_SES, neoSes)
                            .putString(KEY_NEO_CHK, neoChk)
                            .apply()
                        if (useIndependentStorage()) {
                            saveCookieToFile(neoSes, neoChk)
                        } else {
                            cachedCookie = buildCookieString(neoSes, neoChk)
                            lastIndependentState = useIndependentStorage()
                        }
                        saveCookieToFile(neoSes, neoChk)
                        refreshCookieCache()
                        Handler(Looper.getMainLooper()).post {
                            loginIndicator.isChecked = true
                            loginIndicator.summary = buildLoginSummary()
                            if (::manualCookieSwitch.isInitialized) {
                                manualCookieSwitch.summary = buildManualSwitchSummary()
                            }
                            Toast.makeText(actCtx, "登录成功", Toast.LENGTH_SHORT).show()
                            dialog?.dismiss()
                        }
                    }
                }
            }
            loadUrl("$baseUrl/member/login")
        }

        dialog = AlertDialog.Builder(actCtx)
            .setView(webView)
            .create()

        dialog.show()

        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webView.requestFocus()

        val rootView = dialog.window?.decorView ?: return
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val now = System.currentTimeMillis()
                if (now - lastLayoutTime < 100) return
                lastLayoutTime = now

                val rect = android.graphics.Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                var keyboardHeight = rootView.height - rect.bottom
                if (keyboardHeight < 0) return

                val keyboardNowVisible = keyboardHeight > 150

                Log.d("DongmanIME", "onGlobalLayout t=${System.currentTimeMillis()} rootHeight=${rootView.height} rectBottom=${rect.bottom} keyboardHeight=$keyboardHeight keyboardNowVisible=$keyboardNowVisible isKeyboardVisible=$isKeyboardVisible")

                if (keyboardNowVisible == isKeyboardVisible) return
                Log.d("DongmanIME", "★ 状态切换: $isKeyboardVisible -> $keyboardNowVisible (keyboardHeight=$keyboardHeight)")
                isKeyboardVisible = keyboardNowVisible

                if (keyboardNowVisible) {
                    val visibleBottom = rect.bottom
                    Log.d("DongmanIME", "键盘弹出 visibleBottom=$visibleBottom webView.scrollY=${webView.scrollY} webView.height=${webView.height}")

                    webView.evaluateJavascript("""
                        (function(){
                            var el = document.getElementById('formLogin');
                            if(!el) return 'NO_ELEMENT';
                            var dpr = window.devicePixelRatio || 1;
                            var top = el.offsetTop * dpr;
                            var bottom = (el.offsetTop + el.offsetHeight) * dpr;
                            return top + ',' + bottom;
                        })()
                    """.trimIndent()) { value ->
                        Log.d("DongmanIME", "JS回调 value=$value")
                        val parts = value?.trim('"')?.split(",") ?: return@evaluateJavascript
                        val top = parts[0].toFloatOrNull() ?: return@evaluateJavascript
                        val bottom = parts[1].toFloatOrNull() ?: return@evaluateJavascript
                        // 直接滚动到表单顶部
                        val targetScrollY = top.toInt()
                        Log.d("DongmanIME", "DPR修正后 top=$top bottom=$bottom targetScrollY=$targetScrollY 当前scrollY=${webView.scrollY}")
                        Handler(Looper.getMainLooper()).post {
                            webView.scrollTo(0, targetScrollY)
                            Log.d("DongmanIME", "scrollTo后 webView.scrollY=${webView.scrollY}")
                            // 滚动完成后重新缓存表单视口坐标（视口已变化）
                            webView.postDelayed({
                                webView.evaluateJavascript("""
                                    (function(){
                                        var dpr = window.devicePixelRatio || 1;
                                        var form = document.getElementById('formLogin');
                                        if(!form) return '';
                                        var r = form.getBoundingClientRect();
                                        return (r.left*dpr)+','+(r.top*dpr)+','+(r.right*dpr)+','+(r.bottom*dpr)+'|scrollY='+window.scrollY;
                                    })()
                                """.trimIndent()) { v2 ->
                                    Log.d("DongmanIME", "键盘弹出后重新缓存formRects JS返回: $v2 webView.scrollY=${webView.scrollY}")
                                    val raw2 = v2?.trim('"') ?: return@evaluateJavascript
                                    val coords = raw2.substringBefore("|").split(",")
                                    if (coords.size == 4) {
                                        formRects.clear()
                                        formRects.add(InputRect(
                                            coords[0].toFloat(), coords[1].toFloat(),
                                            coords[2].toFloat(), coords[3].toFloat()
                                        ))
                                        Log.d("DongmanIME", "键盘弹出后formRects已更新: $formRects")
                                    }
                                }
                            }, 200)
                        }
                    }
                } else {
                    Log.d("DongmanIME", "键盘收起 webView.scrollY=${webView.scrollY}")
                    webView.evaluateJavascript("""
                        (function(){
                            var el = document.getElementById('formLogin');
                            if(!el) return '0';
                            return String(el.offsetTop * (window.devicePixelRatio || 1));
                        })()
                    """.trimIndent()) { value ->
                        val top = value?.trim('"')?.toFloatOrNull() ?: return@evaluateJavascript
                        Log.d("DongmanIME", "收起JS回调 value=$value top=$top 当前scrollY=${webView.scrollY}")
                        Handler(Looper.getMainLooper()).post {
                            webView.scrollTo(0, top.toInt())
                            Log.d("DongmanIME", "收起scrollTo后 webView.scrollY=${webView.scrollY}")
                            // 收起后重新缓存表单视口坐标
                            webView.postDelayed({
                                webView.evaluateJavascript("""
                                    (function(){
                                        var dpr = window.devicePixelRatio || 1;
                                        var form = document.getElementById('formLogin');
                                        if(!form) return '';
                                        var r = form.getBoundingClientRect();
                                        return (r.left*dpr)+','+(r.top*dpr)+','+(r.right*dpr)+','+(r.bottom*dpr)+'|scrollY='+window.scrollY;
                                    })()
                                """.trimIndent()) { v2 ->
                                    Log.d("DongmanIME", "键盘收起后重新缓存formRects JS返回: $v2 webView.scrollY=${webView.scrollY}")
                                    val raw2 = v2?.trim('"') ?: return@evaluateJavascript
                                    val coords = raw2.substringBefore("|").split(",")
                                    if (coords.size == 4) {
                                        formRects.clear()
                                        formRects.add(InputRect(
                                            coords[0].toFloat(), coords[1].toFloat(),
                                            coords[2].toFloat(), coords[3].toFloat()
                                        ))
                                        Log.d("DongmanIME", "键盘收起后formRects已更新: $formRects")
                                    }
                                }
                            }, 200)
                        }
                    }
                }
            }
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)

        dialog.setOnDismissListener {
            isLoginDialogShowing = false
            loginSuccessHandled = false
            rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            webView.destroy()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 密码登录
    // ══════════════════════════════════════════════════════════════════════

    private fun loginWithPassword(username: String, password: String) {
        Thread {
            try {
                Log.d("DongmanCookie", "=== 开始密码登录 ===")
                val rsaResp = client.newCall(GET("$baseUrl/member/login/rsa/getKeys", headers)).execute()
                val rsaJson = JSONObject(rsaResp.body.string())
                val keyName = rsaJson.getString("keyName")
                val nvalue = rsaJson.getString("nvalue")
                val evalue = rsaJson.getString("evalue")
                val sessionKey = rsaJson.getString("sessionKey")

                fun lenChar(s: String) = s.length.toChar()
                val plain = "${lenChar(sessionKey)}$sessionKey${lenChar(username)}$username${lenChar(password)}$password"
                val encrypted = rsaEncryptToHex(plain, evalue, nvalue)

                val body = FormBody.Builder()
                    .add("encnm", keyName)
                    .add("encpw", encrypted)
                    .add("returnUrl", "$baseUrl/")
                    .add("loginType", "PHONE_NUMBER")
                    .build()
                val loginResp = client.newCall(POST("$baseUrl/member/login/doLoginById", headers, body)).execute()
                val loginJson = JSONObject(loginResp.body.string())

                if (loginJson.optInt("loginStatus", -1) == 0) {
                    val allSetCookies = loginResp.headers.values("Set-Cookie").joinToString("; ")
                    Log.d("DongmanCookie", "密码登录 Set-Cookie: $allSetCookies")
                    val neoSes = extractCookieValue(allSetCookies, "NEO_SES")
                    val neoChk = extractCookieValue(allSetCookies, "NEO_CHK")
                    if (neoSes.isNotEmpty()) {
                        saveLoginCookie(neoSes, neoChk)
                        CookieManager.getInstance().setCookie(baseUrl, "NEO_SES=$neoSes; path=/")
                        saveCookieToFile(neoSes, neoChk)
                        refreshCookieCache()
                        Handler(Looper.getMainLooper()).post {
                            if (!loginSuccessHandled) {
                                loginSuccessHandled = true
                                loginIndicator.isChecked = true
                                loginIndicator.summary = buildLoginSummary()
                                if (::manualCookieSwitch.isInitialized) {
                                    manualCookieSwitch.summary = buildManualSwitchSummary()
                                }
                                Toast.makeText(appContext, "登录成功", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(appContext, "登录失败：未获取到登录凭证", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val msg = loginJson.optString("loginMessage", "登录失败")
                    throw Exception(msg)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "登录失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun rsaEncryptToHex(data: String, modulusHex: String, exponentHex: String): String {
        val modulus = BigInteger(modulusHex, 16)
        val exponent = BigInteger(exponentHex, 16)
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it) }
    }

    private fun saveLoginCookie(neoSes: String, neoChk: String) {
        Log.d("DongmanCookie", "saveLoginCookie: neoSes=$neoSes, neoChk=$neoChk")
        preferences.edit()
            .putString(KEY_NEO_SES, neoSes)
            .putString(KEY_NEO_CHK, neoChk)
            .apply()
        if (useIndependentStorage()) {
            saveCookieToFile(neoSes, neoChk)
        }
        refreshCookieCache()
    }

    private fun fullLogout() {
        Log.d("DongmanCookie", "fullLogout: 彻底退出登录")
        CookieManager.getInstance().removeAllCookies(null)
        preferences.edit().remove(KEY_NEO_SES).remove(KEY_NEO_CHK).apply()
        if (useIndependentStorage()) {
            deleteCookieFile()
        }
        clearManualBackup()
        refreshCookieCache()
        loginIndicator.isChecked = false
        loginIndicator.summary = buildLoginSummary()
        if (::manualCookieSwitch.isInitialized) {
            manualCookieSwitch.summary = buildManualSwitchSummary()
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已彻底退出登录", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearBackupOnly() {
        Log.d("DongmanCookie", "clearBackupOnly: 仅清除独立存储备份，不影响WebView登录态")
        preferences.edit().remove(KEY_NEO_SES).remove(KEY_NEO_CHK).apply()
        if (useIndependentStorage()) {
            deleteCookieFile()
        }
        clearManualBackup()
        refreshCookieCache()
        loginIndicator.summary = buildLoginSummary()
        if (::manualCookieSwitch.isInitialized) {
            manualCookieSwitch.summary = buildManualSwitchSummary()
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已清除独立存储备份", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildLoginSummary(): String {
        val isLoggedIn = cachedCookie?.isNotEmpty() == true
        val status = if (isLoggedIn) "已登录" else "未登录"
        val storageMode = if (useIndependentStorage()) "私有文件（独立）" else "CookieManager/SP"
        val manualMode = if (getManualCookieEnable()) "手动备用开启" else "手动备用关闭"
        return "存储方式：$storageMode\n手动模式：$manualMode\n登录状态：$status"
    }

    private fun extractCookieValue(cookieStr: String, key: String): String =
        cookieStr.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")
            ?.trim() ?: ""

    private fun cookieHeader(): String {
        if (cachedCookie != null && lastIndependentState == useIndependentStorage()) {
            Log.d("DongmanCookie", "cookieHeader: 使用缓存 $cachedCookie")
            return cachedCookie!!
        }
        refreshCookieCache()
        return cachedCookie ?: ""
    }

    private fun currentUserAgent(): String {
        return when (val pref = preferences.getString(PREF_UA, UA_MOBILE) ?: UA_MOBILE) {
            PREF_UA_CUSTOM_FLAG -> preferences.getString(PREF_UA_CUSTOM, "").orEmpty()
            else -> pref
        }
    }

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder().set("Referer", "$baseUrl/")
        val ua = currentUserAgent()
        if (ua.isNotEmpty()) builder.set("User-Agent", ua)
        val cookie = cookieHeader()
        if (cookie.isNotEmpty()) {
            Log.d("DongmanCookie", "headersBuilder: 注入 Cookie -> $cookie")
            builder.set("Cookie", cookie)
        } else {
            Log.d("DongmanCookie", "headersBuilder: 没有 Cookie 可注入")
        }
        return builder
    }

    override val client = network.client

    // ══════════════════════════════════════════════════════════════════════
    // 首页（触发探针）
    // ══════════════════════════════════════════════════════════════════════

    override fun popularMangaRequest(page: Int): Request {
        probeIsLoginValid()
        return GET("$baseUrl/?pageName=home", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = document
            .select("a[href*=list?title_no], a[href*=episodeList?titleNo]")
            .distinctBy { it.absUrl("href") }
            .filter { it.attr("href").isNotEmpty() }
            .map(::mangaFromElement)
            .filter { it.title.isNotEmpty() }
        return MangasPage(entries, false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 最新更新（Latest）
    // ══════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/dailySchedule?sortOrder=UPDATE&webtoonCompleteType=ONGOING", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "div._list_SUNDAY"
            Calendar.MONDAY -> "div._list_MONDAY"
            Calendar.TUESDAY -> "div._list_TUESDAY"
            Calendar.WEDNESDAY -> "div._list_WEDNESDAY"
            Calendar.THURSDAY -> "div._list_THURSDAY"
            Calendar.FRIDAY -> "div._list_FRIDAY"
            Calendar.SATURDAY -> "div._list_SATURDAY"
            else -> "div"
        }
        val entries = document
            .select("div#dailyList > $day li > a, ul.daily_card li a")
            .map(::mangaFromElement)
            .distinctBy { it.url }
            .filter { it.title.isNotEmpty() }
        return MangasPage(entries, false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 搜索（支持混合模式）
    // ══════════════════════════════════════════════════════════════════════

    private val nextStartMap = mutableMapOf<String, Int>()
    private fun isMixedMode() = preferences.getBoolean(PREF_SEARCH_MODE, false)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (isMixedMode() && page == 1) {
            nextStartMap.remove(query)
            val body = FormBody.Builder().add("searchType", "WEBTOON").add("keyword", query).build()
            val headers = headersBuilder()
                .set("Origin", baseUrl)
                .set("Referer", "$baseUrl/search")
                .set("Content-Type", "application/x-www-form-urlencoded")
                .build()
            return POST("$baseUrl/search", headers, body)
        }
        val start = if (isMixedMode()) nextStartMap[query] ?: (1 + (page - 1) * 20) else 1 + (page - 1) * 20
        val body = FormBody.Builder().add("keyword", query).add("searchType", "WEBTOON").add("start", start.toString()).build()
        val headers = headersBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/search")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        return POST("$baseUrl/searchResult", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return if (response.request.url.toString().contains("/searchResult")) parseSearchResultJson(response) else parseSearchHtml(response)
    }

    private fun parseSearchHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        val allItems = document.select("ul._searchResultList > li")
        val totalEntries = allItems.size
        val entries = allItems
            .mapNotNull { li -> li.selectFirst("a.cleFix")?.let { searchMangaFromElement(it) } }
            .filter { it.title.isNotEmpty() }
        val total = document.select("._totalCount").attr("data-total").toIntOrNull() ?: 0
        val hasNextPage = total > totalEntries
        if (hasNextPage) {
            val keyword = extractKeywordFromBody(response)
            if (keyword.isNotEmpty()) nextStartMap[keyword] = totalEntries + 1
        }
        return MangasPage(entries, hasNextPage)
    }

    private fun parseSearchResultJson(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        val total = json.optInt("total", 0)
        val start = json.optInt("start", 0)
        val titleList = json.optJSONArray("titleList")
        val rawCount = titleList?.length() ?: 0
        val entries = mutableListOf<SManga>()
        if (titleList != null) {
            for (i in 0 until titleList.length()) {
                val item = titleList.getJSONObject(i)
                val platform = item.optString("displayPlatform", "ALL")
                if (platform != "ALL" && platform != "WEB") continue
                val manga = SManga.create().apply {
                    val titleNo = item.optString("titleNo", "")
                    url = "/episodeList?titleNo=$titleNo"
                    title = item.optString("title", "")
                    thumbnail_url = buildThumbnailUrl(
                        item.optString("thumbnailMobile", "")
                            .ifEmpty { item.optString("thumbnail", "") }
                            .ifEmpty { item.optString("representGenreBackgroundImageUrl", "") }
                    )
                }
                if (manga.title.isNotEmpty()) entries.add(manga)
            }
        }
        val hasNextPage = rawCount > 0 && (start - 1 + rawCount) < total
        if (hasNextPage && isMixedMode()) {
            val keyword = extractKeywordFromBody(response)
            if (keyword.isNotEmpty()) nextStartMap[keyword] = start + rawCount
        }
        return MangasPage(entries, hasNextPage)
    }

    private fun extractKeywordFromBody(response: Response): String {
        val body = response.request.body
        if (body is FormBody) {
            for (i in 0 until body.size) {
                if (body.name(i) == "keyword") return body.value(i)
            }
        }
        return ""
    }

    // ══════════════════════════════════════════════════════════════════════
    // 漫画详情
    // ══════════════════════════════════════════════════════════════════════

    override fun mangaDetailsRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().apply {
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
        return GET(baseUrl + manga.url, reqHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val detailDiv = document.selectFirst("div.detail_info")
        return SManga.create().apply {
            title = detailDiv?.selectFirst("p.subj")?.text()
                ?: document.selectFirst("h1.subj, h3.subj")?.text()
                ?: document.title().substringBefore("_")
            author = detailDiv?.selectFirst("p.author")?.text()
                ?: document.selectFirst("meta[property=com-dongman:webtoon:author]")?.attr("content")
            artist = author
            val genreBase = detailDiv?.selectFirst("p.genre")?.text() ?: ""
            val updateTag = extractUpdateTag(document.html())
            genre = if (updateTag.isNotEmpty()) "$genreBase, $updateTag" else genreBase
            description = detailDiv?.selectFirst("p.summary span.ellipsis")?.text()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            status = when (extractSerialStatus(document.html())) {
                "SERIES" -> SManga.ONGOING
                "TERMINATION" -> SManga.COMPLETED
                "REST" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 章节列表
    // ══════════════════════════════════════════════════════════════════════

    override fun chapterListRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().apply {
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
        return GET(baseUrl + manga.url, reqHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        var document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        while (true) {
            document.select("div#_episodeList ul li").forEach { li ->
                val a = li.selectFirst("a.workEpisodeListItem") ?: return@forEach
                val dataHref = a.attr("data-href").ifEmpty { a.absUrl("href") }
                if (dataHref.isEmpty()) return@forEach
                chapters.add(SChapter.create().apply {
                    val cleanUrl = dataHref.substringBefore("&source")
                    url = if (cleanUrl.startsWith("http")) {
                        cleanUrl.removePrefix("https://m.dongmanmanhua.cn").removePrefix("//m.dongmanmanhua.cn")
                    } else {
                        cleanUrl
                    }
                    val isFree = a.attr("data-free") == "true"
                    val rawName = a.selectFirst("p.sub_title span.ellipsis")?.text()
                        ?: a.selectFirst("p.sub_title")?.text()
                        ?: "第${li.attr("data-episode-no")}话"
                    name = if (isFree) rawName else "🔒 $rawName"
                    date_upload = dateFormat.tryParse(a.selectFirst("p.date")?.text()?.trim().orEmpty()) ?: 0L
                    chapter_number = li.attr("data-episode-no").toFloatOrNull() ?: -1f
                })
            }
            val nextPage = document.select("div.paginate a[onclick] + a").firstOrNull() ?: break
            val nextUrl = nextPage.absUrl("href")
            if (nextUrl.isEmpty()) break
            val reqHeaders = headersBuilder().apply {
                val cookie = cookieHeader()
                if (cookie.isNotEmpty()) set("Cookie", cookie)
            }.build()
            document = client.newCall(GET(nextUrl, reqHeaders)).execute().asJsoup()
        }
        return chapters.reversed()
    }

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    // ══════════════════════════════════════════════════════════════════════
    // 阅读页面 & 自动解锁（原始抛异常行为）
    // ══════════════════════════════════════════════════════════════════════

    override fun pageListRequest(chapter: SChapter): Request {
        val reqHeaders = headersBuilder().apply {
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
        val autoPay = preferences.getBoolean(PREF_AUTO_PAY, false)
        if (autoPay) {
            val titleNo = extractUrlParam(chapter.url, "title_no")
            val episodeNo = extractUrlParam(chapter.url, "episode_no")
            if (titleNo.isNotEmpty() && episodeNo.isNotEmpty()) {
                autoUnlockEpisode(titleNo, episodeNo)
            }
        }
        return GET(baseUrl + chapter.url, reqHeaders)
    }

    private fun autoUnlockEpisode(titleNo: String, episodeNo: String) {
        Log.d("DongmanCookie", "=== 自动解锁开始 titleNo=$titleNo, episodeNo=$episodeNo ===")
        val params = "title_no=$titleNo&episode_no=$episodeNo&platform=MWEB&client=APP_ANDROID"
        val savedCookie = cookieHeader()
        val reqHeaders = headersBuilder()
            .set("Referer", "$baseUrl/FANTASY/list?title_no=$titleNo")
            .set("X-Requested-With", "XMLHttpRequest")
            .apply { if (savedCookie.isNotEmpty()) set("Cookie", savedCookie) }
            .build()
        Log.d("DongmanCookie", "自动解锁请求头 Cookie: ${reqHeaders.get("Cookie")}")

        val priceResp = client.newCall(GET("$baseUrl/episode/unlock/getEpisodePrice?$params", reqHeaders)).execute()
        val priceJson = org.json.JSONObject(priceResp.body.string())
        val data = priceJson.optJSONObject("data") ?: return
        val isFree = data.optBoolean("free", true)
        if (isFree) return
        val isLimit = data.optBoolean("isLimit", false)
        if (isLimit) return
        val price = data.optInt("price", 0)
        val coinCount = data.optInt("coinCount", 0)
        val episodeName = data.optString("episodeName", "本话")
        if (coinCount < price) {
            throw Exception("余额不足：$episodeName 需要 $price 币，当前余额 $coinCount 币，请前往咚漫充值")
        }
        val payResp = client.newCall(GET("$baseUrl/episode/unlock/pay?$params", reqHeaders)).execute()
        val payJson = org.json.JSONObject(payResp.body.string())
        if (payJson.optInt("code") != 200) return
        Log.d("DongmanCookie", "自动解锁成功")
    }

    private fun extractUrlParam(url: String, key: String): String {
        val regex = Regex("""[?&]$key=([^&]+)""")
        return regex.find(url)?.groupValues?.get(1) ?: ""
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val imageRegex = Regex("""url\s*:\s*"(https://cdn\.dongmanmanhua\.cn/[^"]+)"""")
        return imageRegex.findAll(html)
            .mapIndexed { i, match -> Page(i, imageUrl = match.groupValues[1]) }
            .toList()
    }

    override fun imageRequest(page: Page): Request {
        return GET(
            page.imageUrl!!,
            headersBuilder()
                .set("Referer", "$baseUrl/")
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()
        )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ══════════════════════════════════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════════════════════════════════

    private fun extractSerialStatus(html: String): String {
        val regex = Regex("""serial_status['":\s]+([A-Z]+)""")
        return regex.find(html)?.groupValues?.get(1) ?: ""
    }

    private fun extractUpdateTag(html: String): String {
        val regex = Regex("""在(周[一二三四五六七日天])更新""")
        val match = regex.find(html) ?: return ""
        return "每${match.groupValues[1]}更新"
    }

    private fun buildThumbnailUrl(raw: String): String {
        if (raw.isEmpty()) return ""
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$cdnBase$raw"
            else -> raw
        }
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(
            "p.subj, .subj .ellipsis, ._items_name_t, .home_genre_t, p.chapter-title-02, .chapter-title-01"
        )?.text() ?: element.attr("title").ifEmpty { element.selectFirst("img")?.attr("alt") ?: "" }
        thumbnail_url = extractThumbnailUrl(element)
    }

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".info .subj .ellipsis, p.subj .ellipsis")?.text() ?: ""
        thumbnail_url = extractThumbnailUrl(element)
    }

    private fun extractThumbnailUrl(element: Element): String {
        val img = element.selectFirst(".pic img, img, a img")
        if (img != null) {
            img.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-src").takeIf { it.isNotEmpty() }?.let { return it }
            img.absUrl("src").takeIf { it.isNotEmpty() && !it.contains("placeholder") && !it.contains("transparent") }?.let { return it }
            img.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
            extractUrlFromStyle(img.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val style = element.attr("style")
        if (style.isNotEmpty()) {
            extractUrlFromStyle(style).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val picDiv = element.selectFirst(".pic, .thmb, .chapter-img-c")
        if (picDiv != null) {
            val picStyle = picDiv.attr("style")
            if (picStyle.isNotEmpty()) {
                extractUrlFromStyle(picStyle).takeIf { it.isNotEmpty() }?.let { return it }
            }
            val picImg = picDiv.selectFirst("img")
            if (picImg != null) {
                picImg.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-src").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.absUrl("src").takeIf { it.isNotEmpty() && !it.contains("placeholder") && !it.contains("transparent") }?.let { return it }
                picImg.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
                extractUrlFromStyle(picImg.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        element.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
        element.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
        element.attr("data-thumbnail").takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun extractUrlFromStyle(style: String): String {
        if (style.isEmpty()) return ""
        val regex = Regex("""background(?:-image)?\s*:\s*url\(['"]?([^'"()]+)['"]?\)""")
        val match = regex.find(style)
        if (match != null) return match.groupValues[1].trim()
        val from = style.indexOf("url(").takeIf { it != -1 }?.plus(4) ?: return ""
        val end = style.indexOf(")", from).takeIf { it != -1 } ?: return ""
        return style.substring(from, end).trim().removeSurrounding("\"").removeSurrounding("'")
    }

    companion object {
        private const val PREF_UA = "pref_user_agent"
        private const val PREF_UA_CUSTOM = "pref_user_agent_custom"
        private const val PREF_UA_CUSTOM_FLAG = "__custom__"
        private const val PREF_INDEPENDENT_STORAGE = "pref_independent_storage"
        private const val PREF_MANUAL_COOKIE_SWITCH = "pref_manual_cookie_switch"
        private const val PREF_LOGIN_USERNAME = "pref_login_username"
        private const val PREF_LOGIN_PASSWORD = "pref_login_password"
        private const val PREF_LOGOUT_TRIGGER = "pref_logout_trigger"
        private const val PREF_CLEAR_BACKUP = "pref_clear_backup"
        private const val PREF_SEARCH_MODE = "pref_search_mode"
        private const val PREF_AUTO_PAY = "pref_auto_pay"
        private const val KEY_NEO_SES = "neo_ses"
        private const val KEY_NEO_CHK = "neo_chk"

        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
                               }
