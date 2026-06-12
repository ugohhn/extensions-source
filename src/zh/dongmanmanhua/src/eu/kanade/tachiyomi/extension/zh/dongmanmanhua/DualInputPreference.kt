package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.EditText
import androidx.preference.Preference

// ══════════════════════════════════════════════════════════════════════
// DualInputPreference：点击后弹出同时含账号+密码输入框的 AlertDialog
// 不依赖 DialogPreference，只用基础 AlertDialog
// ══════════════════════════════════════════════════════════════════════

class DualInputPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    var onCredentialsConfirmed: ((username: String, password: String) -> Unit)? = null

    override fun onClick() {
        val view = LayoutInflater.from(context).inflate(R.layout.preference_dual_input, null)
        val editUsername = view.findViewById<EditText>(R.id.edit_username)
        val editPassword = view.findViewById<EditText>(R.id.edit_password)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val username = editUsername.text.toString().trim()
                val password = editPassword.text.toString()
                onCredentialsConfirmed?.invoke(username, password)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
