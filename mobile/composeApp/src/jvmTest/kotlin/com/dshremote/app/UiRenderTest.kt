package com.dshremote.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dshremote.app.chat.ChatItem
import com.dshremote.app.sessions.SessionRow
import com.dshremote.app.transport.pairingTarget
import com.dshremote.app.ui.*
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test

/** Opt-in, offline rendering of the production composables, using sample data only. */
class UiRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun captureSharedSurfaces() {
        val destination = System.getenv("DSH_UI_CAPTURE_DIR") ?: return
        val directory = File(destination).apply { mkdirs() }
        val cases = listOf(
            Capture("home-light", 375, 812, false, "home"),
            Capture("home-dark", 375, 812, true, "home"),
            Capture("chat-light", 375, 812, false, "chat"),
            Capture("chat-dark", 375, 812, true, "chat"),
            Capture("review-light", 375, 812, false, "review"),
            Capture("pairing-light", 375, 812, false, "pairing"),
            Capture("tools-light", 375, 812, false, "tools"),
            Capture("home-tablet", 1024, 768, false, "home"),
            Capture("chat-landscape", 812, 375, false, "chat"),
            Capture("home-landscape", 812, 375, false, "home"),
            Capture("home-large-text", 375, 812, false, "home", 1.5f),
            Capture("chat-large-text", 375, 812, true, "chat", 1.5f),
            Capture("instances-light", 375, 812, false, "instances"),
            Capture("instances-dark", 375, 812, true, "instances"),
            Capture("instances-small", 320, 568, false, "instances"),
            Capture("instances-landscape", 812, 375, false, "instances"),
            Capture("instances-large-text", 375, 812, false, "instances", 1.5f),
            Capture("pairing-add", 375, 812, false, "pairing-add"),
        )
        SwingUtilities.invokeAndWait {
            for (case in cases) {
                val scene = ImageComposeScene(case.width, case.height, Density(1f, case.fontScale)) {
                    DSHRemoteTheme(dark = case.dark) { PreviewContent(case.page) }
                }
                try {
                    repeat(3) { scene.render(it * 16_666_667L).close() }
                    scene.render(50_000_001L).use { image ->
                        image.encodeToData()!!.use { data -> File(directory, "${case.name}.png").writeBytes(data.bytes) }
                    }
                } finally { scene.close() }
            }
        }
    }
}

private data class Capture(val name: String, val width: Int, val height: Int, val dark: Boolean, val page: String, val fontScale: Float = 1f)
private val sampleSessions = listOf(
    SessionRow("one", "优化移动端工作区体验", "/workspace/dsh-remote", true, false, workspaceId = "app", workspaceTitle = "dsh-remote", groupedByWorkspace = true),
    SessionRow("two", "梳理跨平台客户端架构", "/workspace/dsh-remote", false, false, workspaceId = "app", workspaceTitle = "dsh-remote", groupedByWorkspace = true),
    SessionRow("three", "检查发布前的代码改动", "/workspace/harness", false, false, workspaceId = "harness", workspaceTitle = "deepseek-harness", groupedByWorkspace = true),
    SessionRow("four", "为文件预览添加深色主题", "/workspace/harness", false, false, workspaceId = "harness", workspaceTitle = "deepseek-harness", groupedByWorkspace = true),
    SessionRow("five", "整理下一版的交付清单", null, false, false, groupedByWorkspace = true),
)
private val sampleChanges = ChatItem.Changes("changes", listOf(
    ChatItem.ToolDiff("composeApp/Theme.kt", "val gap = 8\nval size = 12", "val gap = 16\nval size = 16\nval radius = 20"),
    ChatItem.ToolDiff("composeApp/ChatScreen.kt", "Text(\"会话\")", "ChatHeader(session)\nChatComposer(input)"),
))

@Composable
private fun PreviewContent(page: String) {
    when (page) {
        "home" -> SessionHomeContent(sampleSessions, "studio.local", false, null, {}, {}, {}, {}, {}, {}, {})
        "pairing" -> PairingContent("", {}, false, null, {}, {})
        "pairing-add" -> PairingContent("", {}, false, null, {}, {}, {})
        "instances" -> Surface(Modifier.fillMaxSize(), color = DshSurfaces.base) {
            Box(Modifier.fillMaxSize().padding(top = 24.dp, bottom = 16.dp), contentAlignment = Alignment.BottomCenter) {
                InstancesContent(
                    listOf(
                        pairingTarget("https://studio.local:8443"),
                        pairingTarget("https://laptop.local:8443"),
                        pairingTarget("http://studio.local:8080"),
                    ),
                    "https://studio.local:8443", false, {}, {}, {},
                )
            }
        }
        "tools" -> Surface(Modifier.fillMaxSize(), color = DshSurfaces.sidebar) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("工作区工具", style = MaterialTheme.typography.headlineSmall)
                Text("dsh-remote", style = MaterialTheme.typography.bodySmall)
                ToolAction(DshSymbol.Files, "文件", "浏览工作区文件与交付产物") {}
                ToolAction(DshSymbol.Terminal, "终端", "查看输出，运行工作区命令") {}
                ToolAction(DshSymbol.Clock, "后台任务", "1 个运行中 · 3 个任务") {}
                ToolAction(DshSymbol.Globe, "浏览器", "打开网页或本地服务") {}
                ToolAction(DshSymbol.Branch, "子会话", "查看 2 个协作会话") {}
            }
        }
        else -> Scaffold(topBar = { ChatHeader(sampleSessions.first(), {}, {}) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                ChatViewTabs(page == "review", 2) {}
                Column(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (page != "review") {
                        MessageBubble(ChatItem.Message("user", ChatItem.Message.Role.User, "帮我优化移动端的任务体验，让进度和代码变更更容易查看。")) {}
                        ToolCard(ChatItem.Tool("tool", "tool", "读取项目文件", "read Theme.kt", "完成", false))
                        MessageBubble(ChatItem.Message("assistant", ChatItem.Message.Role.Assistant, "已调整工作台和对话布局。\n\n你可以按工作区找到任务，并在「变更」中逐项审阅代码。")) {}
                    } else Text("审阅本次工作", style = MaterialTheme.typography.titleLarge)
                    ChangesCard(sampleChanges) {}
                    if (page == "review") DeliverablesCard(ChatItem.Deliverables("files", listOf(ChatItem.DeliverableFile("dist/dsh-remote.apk", "Android 安装包")))) {}
                }
                Box(Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
                    ChatComposer("", {}, "DeepSeek-V4.1", "标准权限", "queue", false, false, false, false, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }
}
