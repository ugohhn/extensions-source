package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.text.InputType
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

private const val EXTENSION_PACKAGE = "eu.kanade.tachiyomi.extension.zh.dongmanmanhua"

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

    val resId = ctx.resources.getIdentifier(primaryName, "drawable", EXTENSION_PACKAGE)
        .takeIf { it != 0 }
        ?: ctx.resources.getIdentifier(fallbackName, "drawable", EXTENSION_PACKAGE)

    if (resId != 0) {
        button.setImageResource(resId)
    } else {
        // 兜底：资源名或包名没找到时，至少显示系统眼睛图标，避免按钮看不见。
        button.setImageResource(android.R.drawable.ic_menu_view)
    }

    button.imageTintList = ColorStateList.valueOf(
        if (isNightMode(ctx)) Color.WHITE else Color.DKGRAY,
    )
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
