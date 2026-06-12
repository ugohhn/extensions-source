package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

private const val EXT_PACKAGE = "eu.kanade.tachiyomi.extension.zh.dongmanmanhua"
private const val EYE_DEBUG_TAG = "DongmanEye"

internal fun addDualInputPreference(
    screen: PreferenceScreen,
    source: DongmanManhua,
    onCredentialsConfirmed: (username: String, password: String) -> Unit,
) {
    val ctx = screen.context
    SwitchPreferenceCompat(ctx).apply {
        key = DongmanManhua.PREF_LOGIN_DUAL
        title = "账号密码登录"
        summary = "点击输入账号和密码"
        setDefaultValue(false)
        setOnPreferenceChangeListener { _, _ ->
            showDualInputDialog(ctx, source, onCredentialsConfirmed)
            false
        }
    }.also(screen::addPreference)
}

private fun isNightMode(ctx: Context): Boolean {
    return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}

private fun updateEyeButtonIcon(
    ctx: Context,
    button: ImageButton,
    passwordVisible: Boolean,
) {
    val primaryName = if (passwordVisible) "eye_open" else "eye_hide"
    val fallbackName = if (passwordVisible) "ic_eye_open" else "ic_eye_closed"

    val hostPkg = ctx.packageName
    val appPkg = ctx.applicationContext.packageName

    val hostPrimaryId = ctx.resources.getIdentifier(primaryName, "drawable", hostPkg)
    val hostFallbackId = ctx.resources.getIdentifier(fallbackName, "drawable", hostPkg)
    val hostLauncherId = ctx.resources.getIdentifier("ic_launcher", "mipmap", hostPkg)

    val extCtx = try {
        ctx.createPackageContext(EXT_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
    } catch (e: Exception) {
        Log.e(EYE_DEBUG_TAG, "createPackageContext failed", e)
        null
    }

    val extPkg = extCtx?.packageName ?: "null"
    val extPrimaryId = extCtx?.resources?.getIdentifier(primaryName, "drawable", EXT_PACKAGE) ?: 0
    val extFallbackId = extCtx?.resources?.getIdentifier(fallbackName, "drawable", EXT_PACKAGE) ?: 0
    val extLauncherId = extCtx?.resources?.getIdentifier("ic_launcher", "mipmap", EXT_PACKAGE) ?: 0

    val chosenId = when {
        extPrimaryId != 0 -> extPrimaryId
        extFallbackId != 0 -> extFallbackId
        hostPrimaryId != 0 -> hostPrimaryId
        hostFallbackId != 0 -> hostFallbackId
        else -> 0
    }

    val chosenCtx = when {
        extPrimaryId != 0 || extFallbackId != 0 -> extCtx
        hostPrimaryId != 0 || hostFallbackId != 0 -> ctx
        else -> null
    }

    val drawable = if (chosenId != 0 && chosenCtx != null) {
        try {
            chosenCtx.getDrawable(chosenId)
        } catch (e: Exception) {
            Log.e(EYE_DEBUG_TAG, "getDrawable failed chosenId=$chosenId", e)
            null
        }
    } else {
        null
    }

    button.setImageDrawable(drawable)

    button.imageTintList = ColorStateList.valueOf(
        if (isNightMode(ctx)) Color.WHITE else Color.DKGRAY,
    )

    val msg = "hostPkg=$hostPkg appPkg=$appPkg extPkg=$extPkg host=$hostPrimaryId/$hostFallbackId launcher=$hostLauncherId ext=$extPrimaryId/$extFallbackId launcher=$extLauncherId chosen=$chosenId drawable=${drawable != null}"
    Log.d(EYE_DEBUG_TAG, msg)
    Toast.makeText(ctx, "DongmanEye $msg", Toast.LENGTH_LONG).show()

    button.post {
        val sizeMsg = "button ${button.width}x${button.height} visible=$passwordVisible drawable=${button.drawable != null}"
        Log.d(EYE_DEBUG_TAG, sizeMsg)
        Toast.makeText(ctx, "DongmanEye $sizeMsg", Toast.LENGTH_SHORT).show()
    }
}


private fun showDualInputDialog(
    ctx: Context,
    source: DongmanManhua,
    onConfirmed: (username: String, password: String) -> Unit,
) {
    val dp8 = (8 * ctx.resources.displayMetrics.density).toInt()
    val dp16 = dp8 * 2
    val dp40 = (40 * ctx.resources.displayMetrics.density).toInt()

    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp16, dp8, dp16, dp8)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    val savedUsername = source.preferences.getString(DongmanManhua.PREF_LOGIN_USERNAME, "").orEmpty()
    val savedPassword = source.preferences.getString(DongmanManhua.PREF_LOGIN_PASSWORD, "").orEmpty()

    val labelUsername = TextView(ctx).apply {
        text = "账号（手机号或邮箱）"
    }

    val editUsername = EditText(ctx).apply {
        hint = "请输入账号"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        maxLines = 1
        setText(savedUsername)
    }

    val labelPassword = TextView(ctx).apply {
        text = "密码"
        setPadding(0, dp8, 0, 0)
    }

    var passwordVisible = false

    val editPassword = EditText(ctx).apply {
        hint = "请输入密码"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        maxLines = 1
        setText(savedPassword)
        setPadding(paddingLeft, paddingTop, dp40, paddingBottom)
        if (savedPassword.isNotEmpty()) setSelection(savedPassword.length)
    }

    val eyeButton = ImageButton(ctx).apply {
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = FrameLayout.LayoutParams(dp40, dp40).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp8, dp8, dp8, dp8)

        updateEyeButtonIcon(ctx, this, passwordVisible)

        setOnClickListener {
            passwordVisible = !passwordVisible

            val cursorPos = editPassword.selectionEnd
            editPassword.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            editPassword.setSelection(cursorPos.coerceAtLeast(0))
            editPassword.setPadding(editPassword.paddingLeft, editPassword.paddingTop, dp40, editPassword.paddingBottom)

            updateEyeButtonIcon(ctx, this, passwordVisible)
        }
    }

    val passwordRow = FrameLayout(ctx).apply {
        addView(
            editPassword,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(eyeButton)
    }

    container.addView(labelUsername)
    container.addView(editUsername)
    container.addView(labelPassword)
    container.addView(passwordRow)

    AlertDialog.Builder(ctx)
        .setTitle("账号密码登录")
        .setView(container)
        .setPositiveButton("确定") { _: DialogInterface, _: Int ->
            val username = editUsername.text.toString().trim()
            val password = editPassword.text.toString()

            when {
                username.isBlank() -> Toast.makeText(ctx, "请填写账号", Toast.LENGTH_SHORT).show()
                password.isBlank() -> Toast.makeText(ctx, "请填写密码", Toast.LENGTH_SHORT).show()
                else -> {
                    val alreadyLoggedIn = source.cachedCookie?.isNotEmpty() == true
                    if (alreadyLoggedIn && username == savedUsername && password == savedPassword) {
                        Toast.makeText(ctx, "已登录，无需重复操作", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    onConfirmed(username, password)
                }
            }
        }
        .setNegativeButton("取消", null)
        .show()
}
