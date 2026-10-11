package ru.outsideproarts.oassist.feature.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import ru.outsideproarts.oassist.core.agent.ApprovalUiBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.ui.components.ApprovalDialog
import ru.outsideproarts.oassist.core.ui.theme.OAssistTheme

/**
 * Translucent-активность для двух задач из фона (без своего UI у сервиса):
 * 1. запрос системных runtime-разрешений ([MODE_PERMISSIONS]) — тул просит разрешение
 *    в момент действия, результат возвращается в [PermissionRequestBridge];
 * 2. диалог подтверждения действия ([MODE_APPROVAL]) — notification-режим оверлея,
 *    решение возвращается в [ApprovalUiBridge].
 * Окно показывается и поверх экрана блокировки (`windowShowWhenLocked`).
 */
class ConsentActivity : AppCompatActivity() {

    private var decided = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val id = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val outcome = when {
            id == null -> null
            results.values.all { it } -> PermissionRequestOutcome.GRANTED
            else -> PermissionRequestOutcome.DENIED
        }
        if (id != null && outcome != null) PermissionRequestBridge.complete(id, outcome)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_PERMISSIONS -> startPermissionRequest()
            MODE_APPROVAL -> showApprovalDialog()
            else -> finish()
        }
    }

    private fun startPermissionRequest() {
        val permissions = intent?.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (permissions.isNullOrEmpty()) {
            intent?.getStringExtra(EXTRA_REQUEST_ID)?.let {
                PermissionRequestBridge.complete(it, PermissionRequestOutcome.UNAVAILABLE)
            }
            finish()
            return
        }
        runCatching { permissionLauncher.launch(permissions) }
            .onFailure {
                intent?.getStringExtra(EXTRA_REQUEST_ID)?.let { id ->
                    PermissionRequestBridge.complete(id, PermissionRequestOutcome.UNAVAILABLE)
                }
                finish()
            }
    }

    private fun showApprovalDialog() {
        val request = ApprovalRequest(
            callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty(),
            toolName = intent?.getStringExtra(EXTRA_TOOL_NAME).orEmpty(),
            argumentsJson = intent?.getStringExtra(EXTRA_ARGS_JSON).orEmpty(),
            summary = intent?.getStringExtra(EXTRA_SUMMARY).orEmpty(),
            groupKey = intent?.getStringExtra(EXTRA_GROUP_KEY).orEmpty(),
            screenLocked = intent?.getBooleanExtra(EXTRA_SCREEN_LOCKED, false) ?: false,
        )
        if (request.callId.isBlank()) {
            finish()
            return
        }
        val soundsOn = intent?.getBooleanExtra(EXTRA_SOUND_ENABLED, true) ?: true
        runCatching {
            ru.outsideproarts.oassist.core.speech.SoundHandle.get()
                ?.playIfEnabled(
                    ru.outsideproarts.oassist.core.speech.AgentSound.PERMISSION_REQUEST,
                    soundsOn,
                )
        }
        ComposeView(this).apply {
            setContent {
                OAssistTheme {
                    ApprovalDialog(
                        request = request,
                        onDecision = { decision ->
                            decided = true
                            if (decision == ApprovalDecision.DENY ||
                                decision == ApprovalDecision.ALWAYS_DENY
                            ) {
                                runCatching {
                                    ru.outsideproarts.oassist.core.speech.SoundHandle.get()
                                        ?.playIfEnabled(
                                            ru.outsideproarts.oassist.core.speech.AgentSound.PERMISSION_DENIED,
                                            soundsOn,
                                        )
                                }
                            }
                            ApprovalUiBridge.resolve(request.callId, decision)
                            finish()
                        },
                    )
                }
            }
        }.also { setContentView(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Закрытие без решения (назад/система) — fail-closed отказ, иначе стрим зависнет.
        val callId = intent?.getStringExtra(EXTRA_CALL_ID) ?: return
        if (!decided) {
            ApprovalUiBridge.resolve(callId, ApprovalDecision.DENY)
        }
        ApprovalUiBridge.clear(callId)
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_PERMISSIONS = "permissions"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_TOOL_NAME = "tool_name"
        private const val EXTRA_ARGS_JSON = "args_json"
        private const val EXTRA_SUMMARY = "summary"
        private const val EXTRA_GROUP_KEY = "group_key"
        private const val EXTRA_SCREEN_LOCKED = "screen_locked"
        /** Флаг звукового сопровождения ASSISTANT-режима (дефолт — включено). */
        const val EXTRA_SOUND_ENABLED = "sound_enabled"

        const val MODE_PERMISSIONS = "permissions"
        const val MODE_APPROVAL = "approval"

        fun permissionIntent(context: Context, requestId: String, permissions: List<String>): Intent =
            Intent(context, ConsentActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_PERMISSIONS)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_PERMISSIONS, permissions.toTypedArray())

        fun approvalIntent(context: Context, request: ApprovalRequest): Intent =
            Intent(context, ConsentActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_APPROVAL)
                .putExtra(EXTRA_CALL_ID, request.callId)
                .putExtra(EXTRA_TOOL_NAME, request.toolName)
                .putExtra(EXTRA_ARGS_JSON, request.argumentsJson)
                .putExtra(EXTRA_SUMMARY, request.summary)
                .putExtra(EXTRA_GROUP_KEY, request.groupKey)
                .putExtra(EXTRA_SCREEN_LOCKED, request.screenLocked)
    }
}
