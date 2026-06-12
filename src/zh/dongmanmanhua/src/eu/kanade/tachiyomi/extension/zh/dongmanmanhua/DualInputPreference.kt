package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

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

private fun showDualInputDialog(
    ctx: Context,
    source: DongmanManhua,
    onConfirmed: (username: String, password: String) -> Unit,
) {
    val dp8 = (8 * ctx.resources.displayMetrics.density).toInt()
    val dp16 = dp8 * 2

    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp16, dp8, dp16, dp8)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    // 读取上次登录成功后保存的账号密码
    val savedUsername = source.preferences.getString(DongmanManhua.PREF_LOGIN_USERNAME, "").orEmpty()
    val savedPassword = source.preferences.getString(DongmanManhua.PREF_LOGIN_PASSWORD, "").orEmpty()

    val labelUsername = TextView(ctx).apply { text = "账号（手机号或邮箱）" }
    val editUsername = EditText(ctx).apply {
        hint = "请输入账号"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        maxLines = 1
        setText(savedUsername)
    }
    val labelPassword = TextView(ctx).apply {
        text = "密码"
        setPadding(0, dp8, 0, 0)
    }
    val editPassword = EditText(ctx).apply {
        hint = "请输入密码"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        maxLines = 1
        setText(savedPassword)
    }

    container.addView(labelUsername)
    container.addView(editUsername)
    container.addView(labelPassword)
    container.addView(editPassword)

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
                    // 已登录且账号密码与上次一致，跳过重复登录
                    val alreadyLoggedIn = source.cachedCookie?.isNotEmpty() == true
                    if (alreadyLoggedIn && username == savedUsername && password == savedPassword) {
                        Toast.makeText(ctx, "已登录，无需重复操作", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // 登录成功后由 loginWithPassword 回调负责保存账号密码
                    onConfirmed(username, password)
                }
            }
        }
        .setNegativeButton("取消", null)
        .show()
}
