package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

// ══════════════════════════════════════════════════════════════════════
// 双输入框登录入口：用 SwitchPreferenceCompat 触发，点击弹 AlertDialog
// SwitchPreferenceCompat 构造函数接受 Context，框架支持
// ══════════════════════════════════════════════════════════════════════

internal fun addDualInputPreference(
    screen: PreferenceScreen,
    onCredentialsConfirmed: (username: String, password: String) -> Unit,
) {
    val ctx = screen.context
    SwitchPreferenceCompat(ctx).apply {
        key = DongmanManhua.PREF_LOGIN_DUAL
        title = "账号密码登录"
        summary = "点击输入账号和密码"
        setDefaultValue(false)
        setOnPreferenceChangeListener { _, _ ->
            showDualInputDialog(ctx, onCredentialsConfirmed)
            false // 不保存开关状态，始终复位
        }
    }.also(screen::addPreference)
}

private fun showDualInputDialog(
    ctx: Context,
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

    val labelUsername = TextView(ctx).apply { text = "账号（手机号或邮箱）" }
    val editUsername = EditText(ctx).apply {
        hint = "请输入账号"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        maxLines = 1
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
    }

    container.addView(labelUsername)
    container.addView(editUsername)
    container.addView(labelPassword)
    container.addView(editPassword)

    AlertDialog.Builder(ctx)
        .setTitle("账号密码登录")
        .setView(container)
        .setPositiveButton("确定") { _: DialogInterface, _: Int ->
            onConfirmed(
                editUsername.text.toString().trim(),
                editPassword.text.toString(),
            )
        }
        .setNegativeButton("取消", null)
        .show()
}
