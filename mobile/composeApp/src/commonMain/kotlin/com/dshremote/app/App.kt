package com.dshremote.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dshremote.app.events.*
import com.dshremote.app.sessions.SessionRow
import com.dshremote.app.transport.*
import com.dshremote.app.ui.ErrorNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun App() {
    val services = remember { platformServices() }
    DSHRemoteTheme { AppConnections(services) }
}

@Composable
internal fun AppConnections(services: PlatformServices) {
    var target by remember { mutableStateOf<PairingTarget?>(null) }
    var connections by remember { mutableStateOf<List<PairingTarget>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        connections = services.connections.loadAll()
        val next = services.connections.load()
        if (target?.origin != next?.origin) revision++
        target = next
        loaded = true
    }

    fun change(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
                reload()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "更新实例失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(services) {
        try {
            reload()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "读取实例失败，请重试"
        }
    }

    val current = target
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val message = error
            if (message != null) ErrorNotice(message) { change {} }
            else CircularProgressIndicator()
        }
        pairing || current == null -> PairingScreen(
            services = services,
            onCancel = if (current != null) ({ pairing = false }) else null,
            onPaired = { paired ->
                change {
                    // PairingScreen has saved and activated this instance.
                    // A re-pair of the same origin also needs a fresh connection scope.
                    revision++
                    target = paired
                    pairing = false
                }
            },
        )
        else -> {
            val connectedRevision = revision
            InstanceWorkspace(
                services, current, revision,
                onSettings = { showSettings = true },
                onUnauthorized = {
                    // Ignore an old screen reporting failure after a switch or re-pair.
                    if (target?.origin == current.origin && revision == connectedRevision) {
                        change {
                            services.connections.remove(current.origin)
                            showSettings = false
                        }
                    }
                },
            )
        }
    }
    if (showSettings && current != null && !pairing) {
        SettingsDialog(
            connections = connections,
            activeOrigin = current.origin,
            busy = busy,
            onDismiss = { showSettings = false },
            onSwitch = { instance ->
                change {
                    services.connections.setActive(instance.origin)
                    showSettings = false
                }
            },
            onAdd = { showSettings = false; revision++; pairing = true },
            onRemove = { instance ->
                change {
                    services.connections.remove(instance.origin)
                    if (instance.origin == current.origin) showSettings = false
                }
            },
        )
    }
    if (loaded && error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("实例操作失败") },
            text = { Text(error.orEmpty()) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
}

@Composable
internal fun InstanceWorkspace(
    services: PlatformServices,
    target: PairingTarget,
    revision: Int,
    onSettings: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    key(target.origin, revision) {
        ConnectedInstance(services, target, onSettings, onUnauthorized)
    }
}

/** Every session, dialog, coroutine and socket belongs to exactly one active instance. */
@Composable
private fun ConnectedInstance(
    services: PlatformServices,
    target: PairingTarget,
    onSettings: () -> Unit,
    onUnauthorized: () -> Unit,
) {
    val sessionStack = remember { mutableStateListOf<SessionRow>() }
    var approval by remember { mutableStateOf<ApprovalPrompt?>(null) }
    var question by remember { mutableStateOf<QuestionPrompt?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember { DshClient(services.transport, services.cookies) }
    val repository = remember { RemoteSessionsRepository(client, target) }

    LaunchedEffect(Unit) {
        while (true) {
            val closed = CompletableDeferred<Unit>()
            val handle = try {
                client.openEvents(target, object : EventStreamListener {
                    override fun onApproval(prompt: ApprovalPrompt) {
                        scope.launch { approval = prompt }
                    }
                    override fun onQuestion(nextQuestion: QuestionPrompt) {
                        scope.launch { question = nextQuestion }
                    }
                    override fun onCancelled(eventId: String) {
                        scope.launch {
                            if (approval?.eventId == eventId) approval = null
                            if (question?.eventId == eventId) question = null
                        }
                    }
                    override fun onClosed(message: String) { closed.complete(Unit) }
                })
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                delay(5_000)
                continue
            }
            try {
                closed.await()
            } finally {
                handle.close()
            }
            approval = null
            question = null
            delay(3_000)
        }
    }
    val selected = sessionStack.lastOrNull()
    if (selected != null) {
        ChatScreen(
            client = client, target = target, session = selected,
            onBack = { if (sessionStack.isNotEmpty()) sessionStack.removeAt(sessionStack.lastIndex) },
            onOpenChild = { sessionStack.add(it) },
            onUnauthorized = onUnauthorized,
        )
    } else {
        SessionHomeScreen(
            repository = repository, connectionLabel = target.host,
            onOpen = { sessionStack.clear(); sessionStack.add(it) },
            onUnauthorized = onUnauthorized, onSettings = onSettings,
        )
    }
    approval?.let { prompt ->
        ApprovalDialog(
            prompt = prompt,
            onAllow = {
                approval = null
                scope.launch { runCatching { client.answerApproval(target, prompt, true) } }
            },
            onReject = {
                approval = null
                scope.launch { runCatching { client.answerApproval(target, prompt, false) } }
            },
        )
    }
    question?.let { prompt ->
        QuestionDialog(
            prompt = prompt,
            onSubmit = { drafts ->
                question = null
                scope.launch { runCatching { client.answerQuestion(target, prompt, drafts) } }
            },
            onCancel = {
                question = null
                scope.launch { runCatching { client.rejectQuestion(target, prompt) } }
            },
        )
    }
}
