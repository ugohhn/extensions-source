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

// ══════════════════════════════════════════════════════════════════════
// LoginWebView：拦截键盘弹出时表单外的误触
// ══════════════════════════════════════════════════════════════════════

internal class LoginWebView(
    context: Context,
    // 通过 lambda 延迟获取 dialog 引用，避免构造时 dialog 尚未创建
    private val getDialog: () -> AlertDialog?,
) : WebView(context) {

    private var extFormRects: List<InputRect> = emptyList()
    private var swallowingCurrentSequence = false

    fun updateFormRects(rects: List<InputRect>) {
        extFormRects = rects
    }

    // 实时查询键盘是否显示，直接读系统 WindowInsets，无异步延迟
    private fun isKeyboardActuallyVisible(): Boolean {
        val decorView = getDialog()?.window?.decorView ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val insets = decorView.rootWindowInsets
            insets?.isVisible(android.view.WindowInsets.Type.ime()) == true &&
                (insets.getInsets(android.view.WindowInsets.Type.ime()).bottom > 150)
        } else {
            val rect = android.graphics.Rect()
            decorView.getWindowVisibleDisplayFrame(rect)
            (decorView.height - rect.bottom) > 150
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        Log.d("DongmanIME", "dispatchTouchEvent action=${event.action} x=${event.x} y=${event.y}")
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("DongmanIME", "onTouchEvent action=${event.action} x=${event.x} y=${event.y}")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val keyboardVisible = isKeyboardActuallyVisible()
                Log.d("DongmanIME", "ACTION_DOWN keyboardVisible=$keyboardVisible formRects=$extFormRects")
                if (keyboardVisible && extFormRects.isNotEmpty()) {
                    val inForm = extFormRects.any { rect ->
                        x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
                    }
                    Log.d("DongmanIME", "inForm=$inForm rect=${extFormRects.firstOrNull()}")
                    if (!inForm) {
                        Log.d("DongmanIME", "键盘弹出时点击表单外，吞掉整个序列")
                        swallowingCurrentSequence = true
                        return true
                    }
                } else {
                    Log.d("DongmanIME", "键盘未显示或 formRects 为空，放行")
                }
                swallowingCurrentSequence = false
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            -> {
                if (swallowingCurrentSequence) return true
            }
            MotionEvent.ACTION_CANCEL -> {
                swallowingCurrentSequence = false
            }
        }
        return super.onTouchEvent(event)
    }
}

// ══════════════════════════════════════════════════════════════════════
// 登录 Dialog 入口
// ══════════════════════════════════════════════════════════════════════

internal fun DongmanManhua.showWebViewLoginDialog() {
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

    refreshCookieCache()
    syncLoginIndicator()
    if (isLoginKnown()) {
        Toast.makeText(appContext, "已登录，无需重复登录", Toast.LENGTH_SHORT).show()
        Log.d("DongmanCookie", "WEBVIEW_LOGIN_SKIP_ALREADY_LOGGED_IN ${cookieStateForDebug()}")
        return
    }

    isLoginDialogShowing = true
    loginSuccessHandled = false

    var dialog: AlertDialog? = null
    var isKeyboardVisible = false
    var lastLayoutTime = 0L
    val formRects = mutableListOf<InputRect>()

    var loginPollHandler: Handler? = null
    var loginPollRunnable: Runnable? = null

    fun handleLoginSuccess(cookieStr: String): Boolean {
        val neoSes = extractCookieValue(cookieStr, "NEO_SES")
        val neoChk = extractCookieValue(cookieStr, "NEO_CHK")

        Log.d(
            "DongmanCookie",
            "LOGIN_CHECK handled=$loginSuccessHandled cookieLen=${cookieStr.length} " +
                "ses=${neoSes.length} chk=${neoChk.length} raw=${cookieStr.take(300)}",
        )

        if (loginSuccessHandled) {
            Log.d("DongmanCookie", "LOGIN_SKIP already_handled")
            return false
        }

        if (neoSes.isEmpty()) {
            Log.d(
                "DongmanCookie",
                "LOGIN_SKIP no_NEO_SES chk=${neoChk.length} raw=${cookieStr.take(300)}",
            )
            return false
        }

        loginSuccessHandled = true

        Log.d(
            "DongmanCookie",
            "LOGIN_SAVE ses=${neoSes.length} chk=${neoChk.length}",
        )

        saveLoginCookie(neoSes, neoChk)
        refreshCookieCache()

        Handler(Looper.getMainLooper()).post {
            syncLoginIndicator()
            Toast.makeText(actCtx, "登录成功", Toast.LENGTH_SHORT).show()
            dialog?.dismiss()
        }
        return true
    }

    val webView = LoginWebView(actCtx, getDialog = { dialog }).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = currentUserAgent().takeIf { it.isNotEmpty() } ?: DongmanManhua.UA_MOBILE
        CookieManager.getInstance().setAcceptCookie(true)
        isFocusable = true
        isFocusableInTouchMode = true

        webViewClient = object : WebViewClient() {

            // ── 页面可见后：调整表单样式 + 初始缓存 formRects + 启动验证码轮询 ──
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // 调整表单样式
                view?.evaluateJavascript(
                    """
                    (function(){
                        var form = document.getElementById('formLogin');
                        if(form) {
                            form.style.minHeight = '100vh';
                            form.style.boxSizing = 'border-box';
                            form.style.paddingTop = '16px';
                        }
                    })();
                    """.trimIndent(),
                    null,
                )

                // 延迟 500ms 缓存初始 formRects（等待页面渲染完成）
                view?.postDelayed({
                    view.evaluateJavascript(
                        """
                        (function(){
                            var dpr = window.devicePixelRatio || 1;
                            var form = document.getElementById('formLogin');
                            if(!form) return '';
                            var r = form.getBoundingClientRect();
                            return (r.left*dpr)+','+(r.top*dpr)+','+(r.right*dpr)+','+(r.bottom*dpr)+'|dpr='+dpr+'|scrollY='+window.scrollY;
                        })()
                        """.trimIndent(),
                    ) { value ->
                        Log.d("DongmanIME", "初始 formRects JS 返回: $value")
                        val raw = value?.trim('"') ?: return@evaluateJavascript
                        val coords = raw.substringBefore("|").split(",")
                        if (coords.size == 4) {
                            formRects.clear()
                            formRects.add(
                                InputRect(
                                    coords[0].toFloat(),
                                    coords[1].toFloat(),
                                    coords[2].toFloat(),
                                    coords[3].toFloat(),
                                ),
                            )
                            Log.d("DongmanIME", "初始 formRects 已缓存: $formRects")
                            (view as LoginWebView).updateFormRects(formRects.toList())
                        }
                    }
                }, 500)
            }

            // ── 页面加载完成：检测登录结果 ──
            override fun onPageFinished(view: WebView?, url: String?) {
                val cookieStr = CookieManager.getInstance().getCookie(baseUrl) ?: ""
                Log.d("DongmanCookie", "WebView 登录后 CookieManager: $cookieStr")
                handleLoginSuccess(cookieStr)
            }
        }
        loadUrl("$baseUrl/member/login")
    }

    dialog = AlertDialog.Builder(actCtx)
        .setView(webView)
        .create()

    dialog.show()

    loginPollHandler = Handler(Looper.getMainLooper())
    loginPollRunnable = object : Runnable {
        override fun run() {
            val cookieStr = CookieManager.getInstance().getCookie(baseUrl) ?: ""
            Log.d("DongmanCookie", "WebView 登录轮询 CookieManager: $cookieStr")
            if (!handleLoginSuccess(cookieStr)) {
                loginPollHandler?.postDelayed(this, 800)
            }
        }
    }
    loginPollHandler?.postDelayed(loginPollRunnable!!, 800)

    dialog.window?.apply {
        clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
        setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    webView.requestFocus()

    // ── 键盘高度监听：弹出时滚动表单入镜 + 更新 formRects，收起时复位 ──
    val rootView = dialog.window?.decorView ?: return
    val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            val now = System.currentTimeMillis()
            if (now - lastLayoutTime < 100) return
            lastLayoutTime = now

            val rect = android.graphics.Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val keyboardHeight = rootView.height - rect.bottom
            if (keyboardHeight < 0) return

            val keyboardNowVisible = keyboardHeight > 150
            Log.d("DongmanIME", "onGlobalLayout keyboardHeight=$keyboardHeight keyboardNowVisible=$keyboardNowVisible isKeyboardVisible=$isKeyboardVisible")

            if (keyboardNowVisible == isKeyboardVisible) return
            Log.d("DongmanIME", "★ 键盘状态切换: $isKeyboardVisible -> $keyboardNowVisible")
            isKeyboardVisible = keyboardNowVisible

            if (keyboardNowVisible) {
                // 键盘弹出：查询 formLogin 的 offsetTop，将其滚动到可见区域顶部
                webView.evaluateJavascript(
                    """
                    (function(){
                        var el = document.getElementById('formLogin');
                        if(!el) return 'NO_ELEMENT';
                        var dpr = window.devicePixelRatio || 1;
                        return String(el.offsetTop * dpr);
                    })()
                    """.trimIndent(),
                ) { value ->
                    val targetScrollY = value?.trim('"')?.toFloatOrNull()?.toInt() ?: return@evaluateJavascript
                    Log.d("DongmanIME", "键盘弹出 scrollTo(0, $targetScrollY)")
                    Handler(Looper.getMainLooper()).post {
                        webView.scrollTo(0, targetScrollY)
                        // 延迟 200ms 等滚动稳定后重新缓存 formRects
                        webView.postDelayed({
                            webView.evaluateJavascript(
                                """
                                (function(){
                                    var dpr = window.devicePixelRatio || 1;
                                    var form = document.getElementById('formLogin');
                                    if(!form) return '';
                                    var r = form.getBoundingClientRect();
                                    return (r.left*dpr)+','+(r.top*dpr)+','+(r.right*dpr)+','+(r.bottom*dpr);
                                })()
                                """.trimIndent(),
                            ) { v2 ->
                                val coords = v2?.trim('"')?.split(",") ?: return@evaluateJavascript
                                if (coords.size == 4) {
                                    formRects.clear()
                                    formRects.add(
                                        InputRect(
                                            coords[0].toFloat(),
                                            coords[1].toFloat(),
                                            coords[2].toFloat(),
                                            coords[3].toFloat(),
                                        ),
                                    )
                                    Log.d("DongmanIME", "键盘弹出后 formRects 更新: $formRects")
                                    webView.updateFormRects(formRects.toList())
                                }
                            }
                        }, 200)
                    }
                }
            } else {
                // 键盘收起：复位到顶部，重新缓存 formRects
                Handler(Looper.getMainLooper()).post {
                    webView.scrollTo(0, 0)
                    Log.d("DongmanIME", "键盘收起 scrollTo(0, 0)")
                    webView.postDelayed({
                        webView.evaluateJavascript(
                            """
                            (function(){
                                var dpr = window.devicePixelRatio || 1;
                                var form = document.getElementById('formLogin');
                                if(!form) return '';
                                var r = form.getBoundingClientRect();
                                return (r.left*dpr)+','+(r.top*dpr)+','+(r.right*dpr)+','+(r.bottom*dpr);
                            })()
                            """.trimIndent(),
                        ) { v2 ->
                            val coords = v2?.trim('"')?.split(",") ?: return@evaluateJavascript
                            if (coords.size == 4) {
                                formRects.clear()
                                formRects.add(
                                    InputRect(
                                        coords[0].toFloat(),
                                        coords[1].toFloat(),
                                        coords[2].toFloat(),
                                        coords[3].toFloat(),
                                    ),
                                )
                                Log.d("DongmanIME", "键盘收起后 formRects 更新: $formRects")
                                webView.updateFormRects(formRects.toList())
                            }
                        }
                    }, 200)
                }
            }
        }
    }
    rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)

    dialog.setOnDismissListener {
        isLoginDialogShowing = false
        loginSuccessHandled = false
        rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        loginPollRunnable?.let { loginPollHandler?.removeCallbacks(it) }
        webView.destroy()
    }
}
