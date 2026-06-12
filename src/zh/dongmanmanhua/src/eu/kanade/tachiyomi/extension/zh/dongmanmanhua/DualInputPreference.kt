package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceScreen

// ══════════════════════════════════════════════════════════════════════
// 在 setupPreferenceScreen 里调用此函数添加双行输入框 Preference
// ══════════════════════════════════════════════════════════════════════

internal fun addDualInputPreference(
    screen: PreferenceScreen,
    onCredentialsConfirmed: (username: String, password: String) -> Unit,
) {
    val ctx = screen.context
    Preference(ctx).apply {
        key = DongmanManhua.PREF_LOGIN_DUAL
        title = "账号密码登录"
        summary = "点击输入账号和密码"
        setOnPreferenceClickListener {
            val view = LayoutInflater.from(ctx)
                .inflate(R.layout.preference_dual_input, null)
            val editUsername = view.findViewById<EditText>(R.id.edit_username)
            val editPassword = view.findViewById<EditText>(R.id.edit_password)
            AlertDialog.Builder(ctx)
                .setTitle("账号密码登录")
                .setView(view)
                .setPositiveButton("确定") { _, _ ->
                    val username = editUsername.text.toString().trim()
                    val password = editPassword.text.toString()
                    onCredentialsConfirmed(username, password)
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
    }.also(screen::addPreference)
}
