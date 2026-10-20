package com.dshremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dshremote.app.transport.AndroidPlatform

/**
 * Android 侧的测试入口 Activity。
 *
 * 把 commonMain 里的 [App] 挂到 Activity 上，用于在真机 / 模拟器上验证共享 UI。
 * 清单声明写在 src/androidMain/AndroidManifest.xml，由 :androidApp 合并后生效。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidPlatform.appContext = applicationContext
        // 配合 App() 里的 safeContentPadding()，让内容正确避开状态栏 / 手势条
        enableEdgeToEdge()
        setContent {
            App()
        }
    }
}
