package ru.outsideproarts.oassist.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import ru.outsideproarts.oassist.core.a11y.oassistRole
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.ui.OAssistDimens

/**
 * Диалог подтверждения опасного действия.
 * Закрытие без выбора (назад/тап вне) трактуется как отказ — fail-closed,
 * иначе стрим [AgentLoop.stream] завис бы в ожидании решения.
 */
@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { onDecision(ApprovalDecision.DENY) },
        title = {
            Text(
                text = stringResource(R.string.chat_approval_title, request.toolName),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = request.summary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OAssistDimens.DenseSpacing),
            ) {
                TextButton(
                    onClick = { onDecision(ApprovalDecision.ALLOW_ONCE) },
                    modifier = Modifier.oassistTouchTarget().oassistRole(Role.Button),
                ) {
                    Text(stringResource(R.string.chat_approval_allow_once))
                }
                TextButton(
                    onClick = { onDecision(ApprovalDecision.ALWAYS_ALLOW) },
                    modifier = Modifier.oassistTouchTarget().oassistRole(Role.Button),
                ) {
                    Text(stringResource(R.string.chat_approval_always))
                }
                TextButton(
                    onClick = { onDecision(ApprovalDecision.DENY) },
                    modifier = Modifier.oassistTouchTarget().oassistRole(Role.Button),
                ) {
                    Text(stringResource(R.string.chat_approval_deny))
                }
            }
        },
        modifier = modifier,
    )
}
