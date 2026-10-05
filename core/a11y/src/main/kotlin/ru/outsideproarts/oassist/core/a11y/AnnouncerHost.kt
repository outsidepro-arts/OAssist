package ru.outsideproarts.oassist.core.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp

/**
 * Скрытые live-region узлы, куда уходят объявления экранных чтецов.
 *
 * Видимый текст ответа живым регионом не помечается: иначе TalkBack перечитывал бы
 * весь накопленный текст на каждой дельте. Читается ровно новая фраза.
 */
@Composable
fun AnnouncerHost(
    announcer: FlowAnnouncer,
    modifier: Modifier = Modifier,
) {
    var polite by remember { mutableStateOf("") }
    var assertive by remember { mutableStateOf("") }

    LaunchedEffect(announcer) {
        announcer.utterances.collect { u ->
            if (u.assertive) assertive = u.text else polite = u.text
        }
    }

    Box(modifier = modifier.size(1.dp)) {
        UtteranceNode(text = polite, polite = true)
        UtteranceNode(text = assertive, polite = false)
    }
}

@Composable
private fun UtteranceNode(text: String, polite: Boolean) {
    Box(
        Modifier
            .size(1.dp)
            .clearAndSetSemantics {
                contentDescription = text
                liveRegion = if (polite) LiveRegionMode.Polite else LiveRegionMode.Assertive
            },
    )
}
