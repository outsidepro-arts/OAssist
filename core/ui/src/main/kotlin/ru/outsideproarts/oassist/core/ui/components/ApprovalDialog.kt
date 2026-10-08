package ru.outsideproarts.oassist.core.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import ru.outsideproarts.oassist.core.a11y.oassistHeading
import ru.outsideproarts.oassist.core.a11y.oassistPaneTitle
import ru.outsideproarts.oassist.core.a11y.oassistTouchTarget
import ru.outsideproarts.oassist.core.model.ApprovalDecision
import ru.outsideproarts.oassist.core.model.ApprovalRequest
import ru.outsideproarts.oassist.core.ui.OAssistDimens
import ru.outsideproarts.oassist.core.ui.R
import ru.outsideproarts.oassist.core.ui.toolGroupLabel

/**
 * Содержимое диалога подтверждения действия (по группе разрешений).
 * Без галочки — однократно / на сессию / отклонить; с галочкой «Запомнить навсегда» —
 * постоянные «Разрешить»/«Отклонить» для всей группы.
 *
 * A11y при появлении: accessibility-фокус уходит на заголовок (следующий свайп —
 * по кнопкам решения), `paneTitle` даёт диалогу имя, `announceForAccessibility`
 * объявляет запрос в очереди TalkBack (TYPE_ANNOUNCEMENT не прерывает речь).
 */
@Composable
fun ApprovalDialogContent(
    request: ApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rememberForever by remember { mutableStateOf(false) }
    val groupLabel = toolGroupLabel(request.groupKey)
    val title = stringResource(R.string.approval_title, groupLabel)
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(request.callId) {
        focusRequester.requestFocus()
        view.announceForAccessibility(
            view.context.getString(R.string.approval_announce, request.summary),
        )
    }

    Column(modifier = modifier.oassistPaneTitle(title)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .oassistHeading(),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(OAssistDimens.ItemSpacing),
        ) {
            Text(
                text = request.summary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.approval_group_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (request.screenLocked) {
                Text(
                    text = stringResource(R.string.approval_locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OAssistCheckboxRow(
                checked = rememberForever,
                onCheckedChange = { rememberForever = it },
                label = stringResource(R.string.approval_remember),
                stateLabelOn = stringResource(R.string.state_checked),
                stateLabelOff = stringResource(R.string.state_unchecked),
            )
            Text(
                text = stringResource(R.string.approval_remember_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rememberForever) {
                TextButton(
                    onClick = { onDecision(ApprovalDecision.ALWAYS_ALLOW) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.approval_allow_forever))
                }
                TextButton(
                    onClick = { onDecision(ApprovalDecision.ALWAYS_DENY) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.approval_deny))
                }
            } else {
                TextButton(
                    onClick = { onDecision(ApprovalDecision.ALLOW_ONCE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.approval_allow_once))
                }
                TextButton(
                    onClick = { onDecision(ApprovalDecision.ALLOW_SESSION) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.approval_allow_session))
                }
                TextButton(
                    onClick = { onDecision(ApprovalDecision.DENY) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .oassistTouchTarget(),
                ) {
                    Text(stringResource(R.string.approval_deny))
                }
            }
        }
    }
}

/**
 * Диалог подтверждения для activity-хостов (чат, ConsentActivity).
 * Закрытие без выбора (назад/тап вне) трактуется как однократный отказ — fail-closed,
 * иначе стрим `AgentLoop.stream` завис бы в ожидании решения.
 */
@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { onDecision(ApprovalDecision.DENY) },
        text = {
            ApprovalDialogContent(
                request = request,
                onDecision = onDecision,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {},
        modifier = modifier,
    )
}

/**
 * Инлайн-панель подтверждения для окна оверлея (Service без activity-токена:
 * `AlertDialog` там кидает BadTokenException). Визуально — карточка поверх pill.
 */
@Composable
fun ApprovalPanel(
    request: ApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = OAssistDimens.PanelElevation,
    ) {
        ApprovalDialogContent(
            request = request,
            onDecision = onDecision,
            modifier = Modifier.padding(OAssistDimens.ScreenContentPadding),
        )
    }
}
