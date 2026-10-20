package com.dshremote.app.composer

import com.dshremote.app.json.JValue
import com.dshremote.app.json.get
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget
import com.dshremote.app.json.Json

data class GoalState(val objective: String, val phase: String?)

suspend fun DshClient.currentGoal(target: PairingTarget, sessionId: String): GoalState? {
    val root = rpcValue(
        target,
        "session/projections",
        """{"request":{"sessionId":${Json.string(sessionId)}}}""",
    )
    return goalFromProjections(root)
}

suspend fun DshClient.subagentModes(target: PairingTarget, parentSessionId: String): Map<String, String> {
    val root = rpcValue(
        target,
        "session/projections",
        """{"request":{"sessionId":${Json.string(parentSessionId)}}}""",
    )
    val values = root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?.get("values")?.objOrNull() ?: return emptyMap()
    return values.get("subagentCatalog")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
        val item = element.objOrNull() ?: return@mapNotNull null
        val id = item.get("id")?.strOrNull() ?: return@mapNotNull null
        id to (item.get("mode")?.strOrNull()?.takeUnless { it.isBlank() } ?: "unknown")
    }.toMap()
}

suspend fun DshClient.currentPlanActive(target: PairingTarget, sessionId: String): Boolean {
    val root = rpcValue(
        target,
        "session/projections",
        """{"request":{"sessionId":${Json.string(sessionId)}}}""",
    )
    return planActiveFromProjections(root)
}

internal fun planActiveFromProjections(root: JValue.Obj): Boolean {
    val values = root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?.get("values")?.objOrNull() ?: return false
    val plan = values.get("plan")?.objOrNull() ?: return false
    val pending = (plan.get("pending") as? JValue.Bool)?.value ?: false
    val active = (plan.get("active") as? JValue.Bool)?.value ?: false
    return if (pending) !active else active
}

internal fun goalFromProjections(root: JValue.Obj): GoalState? {
    val values = root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?.get("values")?.objOrNull() ?: return null
    val goal = values.get("goal")?.objOrNull() ?: return null
    val detail = goal.get("goal")?.objOrNull() ?: goal
    val objective = detail.get("objective")?.strOrNull()?.takeUnless { it.isBlank() } ?: return null
    return GoalState(objective, detail.get("phase")?.strOrNull()?.takeUnless { it.isBlank() })
}
