package com.dshremote.app.composer

import com.dshremote.app.json.JValue
import com.dshremote.app.json.Json
import com.dshremote.app.json.arrOrNull
import com.dshremote.app.json.get
import com.dshremote.app.json.objOrNull
import com.dshremote.app.json.strOrNull
import com.dshremote.app.transport.DshClient
import com.dshremote.app.transport.PairingTarget

data class ModelSelection(val provider: String, val model: String, val reasoningEffort: String? = null)

data class ModelOption(
    val provider: String,
    val providerName: String,
    val id: String,
    val name: String,
    val description: String?,
    val defaultEffort: String?,
)

data class ModelGroup(val id: String, val name: String, val models: List<ModelOption>)

data class ModelCatalog(val default: ModelSelection, val groups: List<ModelGroup>) {
    val options: List<ModelOption> get() = groups.flatMap { it.models }
}

data class PermissionOption(val value: String, val name: String)
data class PermissionCatalog(val options: List<PermissionOption>, val defaultPreset: String)

data class CommandItem(val name: String, val description: String, val hint: String?, val isSkill: Boolean)

suspend fun DshClient.modelCatalog(target: PairingTarget): ModelCatalog {
    val value = resultValue(rpcValue(target, "session/modelCatalog", "{}")) ?: return ModelCatalog(ModelSelection("", ""), emptyList())
    val default = value.get("default")?.objOrNull()
    val groups = value.get("groups")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
        val group = element.objOrNull() ?: return@mapNotNull null
        val provider = group.get("id")?.strOrNull() ?: return@mapNotNull null
        val providerName = group.get("name")?.strOrNull() ?: provider
        val models = group.get("models")?.arrOrNull()?.values.orEmpty().mapNotNull { modelElement ->
            val model = modelElement.objOrNull() ?: return@mapNotNull null
            val id = model.get("id")?.strOrNull() ?: return@mapNotNull null
            val reasoning = model.get("reasoning")?.objOrNull()
            ModelOption(
                provider = provider,
                providerName = providerName,
                id = id,
                name = model.get("name")?.strOrNull() ?: id,
                description = model.get("description")?.strOrNull()?.takeUnless { it.isBlank() },
                defaultEffort = reasoning?.get("defaultEffort")?.strOrNull()?.takeUnless { it.isBlank() },
            )
        }
        if (models.isEmpty()) null else ModelGroup(provider, providerName, models)
    }
    return ModelCatalog(
        default = ModelSelection(
            default?.get("provider")?.strOrNull().orEmpty(),
            default?.get("model")?.strOrNull().orEmpty(),
            default?.get("reasoningEffort")?.strOrNull()?.takeUnless { it.isBlank() },
        ),
        groups = groups,
    )
}

suspend fun DshClient.permissionCatalog(target: PairingTarget): PermissionCatalog {
    val value = resultValue(rpcValue(target, "permissionPresets/catalog", "{}"))
    val options = value?.get("options")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
        val obj = element.objOrNull() ?: return@mapNotNull null
        val id = obj.get("value")?.strOrNull() ?: return@mapNotNull null
        PermissionOption(id, obj.get("name")?.strOrNull() ?: id)
    }
    val default = value?.get("defaultPreset")?.strOrNull()?.takeUnless { it.isBlank() }
        ?: options.firstOrNull()?.value.orEmpty()
    return PermissionCatalog(options, default)
}

suspend fun DshClient.currentModel(target: PairingTarget, sessionId: String): ModelSelection? {
    val projections = rpcValue(
        target,
        "session/projections",
        """{"request":{"sessionId":${Json.string(sessionId)}}}""",
    )
    val selection = projections.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?.get("values")?.objOrNull()?.get("modelSelection")?.objOrNull()
    val chosen = selection?.get("next")?.objOrNull() ?: selection?.get("lastUsed")?.objOrNull() ?: return null
    val provider = chosen.get("provider")?.strOrNull() ?: return null
    val model = chosen.get("model")?.strOrNull() ?: return null
    return ModelSelection(provider, model, chosen.get("reasoningEffort")?.strOrNull()?.takeUnless { it.isBlank() })
}

suspend fun DshClient.currentPermission(target: PairingTarget, sessionId: String): String? {
    val projections = rpcValue(
        target,
        "session/projections",
        """{"request":{"sessionId":${Json.string(sessionId)}}}""",
    )
    return projections.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?.get("values")?.objOrNull()?.get("permissions")?.objOrNull()
        ?.get("currentValue")?.strOrNull()?.takeUnless { it.isBlank() }
}

suspend fun DshClient.selectModel(target: PairingTarget, sessionId: String, selection: ModelSelection) {
    val request = Json.obj(
        "sessionId" to Json.string(sessionId),
        "provider" to Json.string(selection.provider),
        "model" to Json.string(selection.model),
        *if (selection.reasoningEffort != null) arrayOf("reasoningEffort" to Json.string(selection.reasoningEffort)) else emptyArray(),
    )
    rpcValue(target, "session/selectModel", """{"request":$request}""")
}

suspend fun DshClient.selectPermission(target: PairingTarget, sessionId: String, preset: String) {
    executeCommand(target, sessionId, "/permission $preset")
}

suspend fun DshClient.listCommands(target: PairingTarget, sessionId: String): List<CommandItem> {
    val root = rpcValue(
        target,
        "commands/list",
        """{"agentId":${Json.string(sessionId)}}""",
    )
    val value = root.get("result")?.objOrNull()?.get("value")
    val array = value?.arrOrNull()?.values ?: emptyList()
    return array.mapNotNull { element ->
        val obj = element.objOrNull() ?: return@mapNotNull null
        val name = obj.get("name")?.strOrNull() ?: return@mapNotNull null
        CommandItem(
            name = name,
            description = obj.get("description")?.strOrNull().orEmpty(),
            hint = obj.get("input")?.objOrNull()?.get("hint")?.strOrNull()?.takeUnless { it.isBlank() },
            isSkill = false,
        )
    }
}

suspend fun DshClient.listSkills(target: PairingTarget, sessionId: String): List<CommandItem> {
    val value = resultValue(
        rpcValue(target, "skills/list", """{"request":{"sessionId":${Json.string(sessionId)}}}"""),
    )
    return value?.get("skills")?.arrOrNull()?.values.orEmpty().mapNotNull { element ->
        val obj = element.objOrNull() ?: return@mapNotNull null
        val name = obj.get("name")?.strOrNull() ?: return@mapNotNull null
        CommandItem(name, obj.get("description")?.strOrNull().orEmpty(), null, isSkill = true)
    }
}

suspend fun DshClient.executeCommand(target: PairingTarget, sessionId: String, line: String): String? {
    val args = Json.obj(
        "agentId" to Json.string(sessionId),
        "line" to Json.string(line),
        "submittedAttachments" to "[]",
    )
    val root = rpcValue(target, "commands/execute", args)
    val result = root.get("result")?.objOrNull()?.get("value")?.objOrNull()
        ?.get("result")?.objOrNull()
    val text = result?.get("text")?.strOrNull()?.takeUnless { it.isBlank() }
    if (result?.get("kind")?.strOrNull() == "error") error(text ?: "命令执行失败")
    return text
}

private fun resultValue(root: JValue.Obj): JValue.Obj? =
    root.get("result")?.objOrNull()?.get("value")?.objOrNull()
