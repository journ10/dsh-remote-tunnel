package com.dshremote.app

import com.dshremote.app.chat.ChatItem
import com.dshremote.app.chat.chatItems
import com.dshremote.app.chat.toolDiffs
import com.dshremote.app.composer.goalFromProjections
import com.dshremote.app.composer.planActiveFromProjections
import com.dshremote.app.json.Json
import com.dshremote.app.json.objOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatParserTest {
    @Test
    fun parsesDeliverables() {
        val event = Json.parse(
            """{"seq":7,"type":"deliverables/presented","data":{"callId":"c1","files":[{"path":"out/report.md","description":"Report"}]}}""",
        )
        val items = chatItems(event)
        assertEquals(1, items.size)
        val deliverables = items.single() as ChatItem.Deliverables
        assertEquals("deliverables-c1", deliverables.id)
        assertEquals("out/report.md", deliverables.files.single().path)
        assertEquals("Report", deliverables.files.single().description)
    }

    @Test
    fun parsesGoalProjection() {
        val root = Json.parse(
            """{"result":{"value":{"values":{"goal":{"goal":{"objective":"Finish Android","phase":"paused"}}}}}}""",
        )
        val goal = goalFromProjections(root.objOrNull()!!)
        assertEquals("Finish Android", goal?.objective)
        assertEquals("paused", goal?.phase)
    }

    @Test
    fun parsesPlanProjection() {
        val active = Json.parse("""{"result":{"value":{"values":{"plan":{"active":true}}}}}""").objOrNull()!!
        val pending = Json.parse("""{"result":{"value":{"values":{"plan":{"active":false,"pending":true}}}}}""").objOrNull()!!
        assertEquals(true, planActiveFromProjections(active))
        assertEquals(true, planActiveFromProjections(pending))
    }

    @Test
    fun rebuildsTurnChangesFromHistory() {
        val records = listOf(
            Json.parse("""{"event":{"seq":1,"type":"turn/start","data":{}}}"""),
            Json.parse("""{"event":{"seq":2,"type":"tool/result","data":{"message":{"toolCallId":"c1","isError":false,"content":[{"type":"text","text":"ok"}]},"meta":{"diffs":[{"path":"src/A.kt","newText":"a"}]}}}}"""),
            Json.parse("""{"event":{"seq":3,"type":"turn/end","data":{}}}"""),
        )
        val changes = chatItems(records).filterIsInstance<ChatItem.Changes>().single()
        assertEquals("src/A.kt", changes.diffs.single().path)
        assertEquals("changes-hist-3", changes.id)
    }

    @Test
    fun marksUserImagesAndFilesInHistory() {
        val event = Json.parse(
            """{"seq":8,"type":"user/message","data":{"source":{"kind":"user"},"content":[{"type":"text","text":"look"},{"type":"image","mediaType":"image/png","data":"x"},{"type":"file","receiptId":"r"}]}}""",
        )
        val message = chatItems(event).single() as ChatItem.Message
        assertEquals("look\n[图片] [文件]", message.text)
    }

    @Test
    fun parsesToolDiffPaths() {
        val result = Json.parse(
            """{"seq":5,"type":"tool/result","data":{"message":{"toolCallId":"call-2","isError":false,"content":[{"type":"text","text":"ok"}]},"meta":{"diffs":[{"path":"src/App.kt","newText":"new"}]}}}""",
        )
        val tool = chatItems(result).single() as ChatItem.Tool
        assertEquals("src/App.kt", tool.diffs.single().path)
        assertEquals("src/App.kt", toolDiffs(result).single().path)
    }

    @Test
    fun parsesToolCallAndResultMergeIds() {
        val call = Json.parse(
            """{"seq":3,"type":"assistant/message","data":{"message":{"content":[{"type":"text","text":"running"},{"type":"tool-call","id":"call-1","name":"bash","arguments":"{}"}]}}}""",
        )
        val result = Json.parse(
            """{"seq":4,"type":"tool/result","data":{"message":{"toolCallId":"call-1","isError":false,"content":[{"type":"text","text":"ok"}]}}}""",
        )
        val callItems = chatItems(call)
        assertTrue(callItems.any { it is ChatItem.Tool })
        val resultItems = chatItems(result)
        val tool = resultItems.single() as ChatItem.Tool
        assertEquals("call-1", tool.callId)
        assertEquals("ok", tool.result)
    }
}
