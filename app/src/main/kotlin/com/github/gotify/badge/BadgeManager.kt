package com.github.gotify.badge

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.gotify.R
import org.tinylog.kotlin.Logger

/**
 * 管理应用角标的类
 */
class BadgeManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE)
    
    /**
     * 获取当前未读消息数量
     */
    fun getUnreadCount(): Int {
        return prefs.getInt(KEY_UNREAD_COUNT, 0)
    }
    
    /**
     * 增加未读消息数量
     * @param count 要增加的数量
     */
    fun incrementUnreadCount(count: Int = 1) {
        val currentCount = getUnreadCount()
        val newCount = currentCount + count
        setUnreadCount(newCount)
    }
    
    /**
     * 设置未读消息数量
     * @param count 未读消息数量
     */
    fun setUnreadCount(count: Int) {
        prefs.edit().putInt(KEY_UNREAD_COUNT, count).apply()
        updateBadge(count)
    }
    
    /**
     * 清除未读消息数量
     */
    fun clearUnreadCount() {
        setUnreadCount(0)
    }
    
    /**
     * 更新应用图标上的角标
     * @param count 未读消息数量
     */
    private fun updateBadge(count: Int) {
        try {
            // 检查是否为HarmonyOS设备
            if (isHarmonyOS()) {
                updateBadgeHarmonyOS(count)
                return
            }
            // 检查是否为华为EMUI设备
            if (isHuaweiDevice()) {
                updateBadgeHuawei(count)
                return
            }
            
            // 使用ShortcutManager API设置角标（Android 8.0及以上）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updateBadgeOreo(count)
            } else {
                // 对于较旧的Android版本，我们可以尝试使用不同的启动器特定实现
                // 这里我们使用ShortcutManagerCompat作为通用方法
                updateBadgeLegacy(count)
            }
        } catch (e: Exception) {
            Logger.error(e, "Failed to update badge count")
        }
    }
    
    /**
     * 为Android 8.0及以上设备更新角标
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateBadgeOreo(count: Int) {
        try {
            val shortcutManager = context.getSystemService(Context.SHORTCUT_SERVICE) as ShortcutManager
            
            if (count > 0) {
                // 创建或更新快捷方式
                val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                    .setShortLabel(context.getString(R.string.app_name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
                    .build()
                
                // 设置角标数量
                ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
                
                // 使用原生API设置角标
                shortcutManager.setDynamicShortcuts(shortcutManager.dynamicShortcuts)
            } else {
                // 清除角标
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            }
        } catch (e: Exception) {
            Logger.error(e, "Failed to update badge for Oreo+: ${e.message}")
        }
    }
    
    /**
     * 为Android 8.0以下设备更新角标
     * 注意：这种方法在不同的启动器上可能有不同的效果
     */
    private fun updateBadgeLegacy(count: Int) {
        try {
            if (count > 0) {
                // 创建或更新快捷方式
                val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                    .setShortLabel(context.getString(R.string.app_name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
                    .build()
                
                // 设置角标数量
                ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
            } else {
                // 清除角标
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            }
        } catch (e: Exception) {
            Logger.error(e, "Failed to update badge for legacy devices: ${e.message}")
        }
    }
    
    /**
     * 检查是否为华为设备
     */
    private fun isHuaweiDevice(): Boolean {
        return try {
            val manufacturer = Build.MANUFACTURER
            manufacturer.equals("huawei", ignoreCase = true) || 
            manufacturer.equals("honor", ignoreCase = true)
        } catch (e: Exception) {
            Logger.error(e, "Failed to check device manufacturer")
            false
        }
    }

    /**
     * 检查是否为HarmonyOS
     */
    private fun isHarmonyOS(): Boolean {
        return try {
            val systemClass = Class.forName("com.huawei.system.BuildEx")
            val method = systemClass.getMethod("getOsBrand")
            val osBrand = method.invoke(systemClass) as String
            osBrand.equals("harmony", ignoreCase = true)
        } catch (e: Exception) {
            Logger.error(e, "Failed to check HarmonyOS")
            false
        }
    }
    
    /**
     * 华为设备专用角标更新方法
     */
    private fun updateBadgeHuawei(count: Int) {
        try {
            // 华为设备使用反射调用EMUI的角标API
            val launcherClassName = "com.huawei.android.launcher.LauncherProvider"
            val bundleClass = Class.forName("android.os.Bundle")
            val bundle = bundleClass.newInstance()
            
            val method = bundleClass.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType)
            method.invoke(bundle, "badgenumber", count)
            
            val contentResolver = context.contentResolver
            val uri = android.net.Uri.parse("content://com.huawei.android.launcher.settings/badge/")
            
            val methodCall = contentResolver.javaClass.getMethod(
                "call",
                android.net.Uri::class.java,
                String::class.java,
                String::class.java,
                bundleClass
            )
            
            methodCall.invoke(
                contentResolver,
                uri,
                "change_badge",
                null,
                bundle
            )
            
            Logger.info("Huawei badge updated to $count")
        } catch (e: Exception) {
            Logger.error(e, "Failed to update Huawei badge: ${e.message}")
            // 如果华为专用方法失败，尝试使用通用方法
            updateBadgeOreo(count)
        }
    }

    /**
     * HarmonyOS专用角标更新方法
     */
    private fun updateBadgeHarmonyOS(count: Int) {
        try {
            // HarmonyOS 4.2使用新的角标API
            val bundleClass = Class.forName("ohos.utils.PacMap")
            val bundle = bundleClass.getConstructor().newInstance()
            
            val methodPut = bundleClass.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType)
            methodPut.invoke(bundle, "badgeNumber", count)
            
            val contentResolver = context.contentResolver
            val uri = android.net.Uri.parse("content://com.hihonor.android.launcher.settings/badge/")
            
            val methodCall = contentResolver.javaClass.getMethod(
                "call",
                android.net.Uri::class.java,
                String::class.java,
                String::class.java,
                bundleClass
            )
            
            methodCall.invoke(
                contentResolver,
                uri,
                "changeBadge",
                null,
                bundle
            )
            
            Logger.info("HarmonyOS badge updated to $count")
        } catch (e: Exception) {
            Logger.error(e, "Failed to update HarmonyOS badge: ${e.message}")
            // 如果HarmonyOS方法失败，尝试使用华为EMUI方法
            updateBadgeHuawei(count)
        }
    }
    
    companion object {
        private const val BADGE_PREFS = "gotify_badge_prefs"
        private const val KEY_UNREAD_COUNT = "unread_count"
        private const val SHORTCUT_ID = "gotify_main"
    }
}