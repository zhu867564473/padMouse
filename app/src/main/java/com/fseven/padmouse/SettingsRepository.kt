package com.fseven.padmouse

import android.content.Context

/**
 * M6 设置持久化：系统动作绑定写入 SharedPreferences。
 *
 * 只保存与默认值不同的绑定；读取时缺失或格式错误的项回落到
 * [DefaultBindings.SYSTEM_ACTIONS]，避免升级后出现空绑定。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 按 [SystemAction] 枚举顺序返回当前绑定（含默认值补齐）。 */
    fun loadSystemBindings(): Map<SystemAction, ActionBinding> {
        val result = LinkedHashMap<SystemAction, ActionBinding>()
        for (action in SystemAction.values()) {
            val fallback = DefaultBindings.SYSTEM_ACTIONS[action] ?: continue
            result[action] = ActionBinding.decode(prefs.getString(keyOf(action), null)) ?: fallback
        }
        return result
    }

    fun saveSystemBinding(action: SystemAction, binding: ActionBinding) {
        prefs.edit().putString(keyOf(action), binding.encode()).apply()
    }

    /** 清除全部自定义绑定，恢复 [DefaultBindings.SYSTEM_ACTIONS]。 */
    fun resetSystemBindings() {
        val editor = prefs.edit()
        for (action in SystemAction.values()) editor.remove(keyOf(action))
        editor.apply()
    }

    private fun keyOf(action: SystemAction): String = "system_action_${action.name.lowercase()}"

    companion object {
        private const val PREFS_NAME = "padmouse_settings"
    }
}
