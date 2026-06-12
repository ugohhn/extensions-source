package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat

// ══════════════════════════════════════════════════════════════════════
// DualInputPreference：一个对话框里同时显示账号+密码两个输入框
// ══════════════════════════════════════════════════════════════════════

class DualInputPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : DialogPreference(context, attrs) {

    // 回调：用户点确定后触发，传入 (username, password)
    var onCredentialsConfirmed: ((username: String, password: String) -> Unit)? = null

    // 暂存当前输入值，供 DialogFragment 读写
    internal var pendingUsername: String = ""
    internal var pendingPassword: String = ""

    init {
        dialogLayoutResource = R.layout.preference_dual_input
        positiveButtonText = "确定"
        negativeButtonText = "取消"
    }
}

// ══════════════════════════════════════════════════════════════════════
// DualInputPreferenceDialog：对话框 Fragment
// ══════════════════════════════════════════════════════════════════════

class DualInputPreferenceDialog : PreferenceDialogFragmentCompat() {

    private var editUsername: EditText? = null
    private var editPassword: EditText? = null

    override fun onBindDialogView(view: android.view.View) {
        super.onBindDialogView(view)
        editUsername = view.findViewById(R.id.edit_username)
        editPassword = view.findViewById(R.id.edit_password)

        val pref = preference as DualInputPreference
        editUsername?.setText(pref.pendingUsername)
        editPassword?.setText(pref.pendingPassword)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (!positiveResult) return
        val pref = preference as DualInputPreference
        val username = editUsername?.text?.toString()?.trim() ?: ""
        val password = editPassword?.text?.toString() ?: ""
        pref.pendingUsername = username
        pref.pendingPassword = password
        pref.onCredentialsConfirmed?.invoke(username, password)
    }

    companion object {
        fun newInstance(key: String): DualInputPreferenceDialog {
            return DualInputPreferenceDialog().apply {
                arguments = android.os.Bundle(1).apply {
                    putString(ARG_KEY, key)
                }
            }
        }
    }
}
