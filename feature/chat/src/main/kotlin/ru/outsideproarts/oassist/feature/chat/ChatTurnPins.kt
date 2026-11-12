package ru.outsideproarts.oassist.feature.chat

import ru.outsideproarts.oassist.core.model.CURRENT_TURN_ID
import ru.outsideproarts.oassist.core.model.PinChange
import ru.outsideproarts.oassist.core.model.parseMessageId

/**
 * Запись закреплений сообщений диалога в БД через общую очередь записи ходов
 * [ChatTurnSaver]: запись встаёт после дозаписи ещё не записанных ходов, поэтому
 * флаг не теряется при закреплении во время отложенной записи хода. Сбой записи
 * разделяет судьбу очереди ходов (состояние ошибки и «Повторить»), а не роняет
 * стрим. Адреса текущего хода сюда не приходят — они применяются к ходу при
 * его записи (`StoredTurn.withPinChanges`). `false` от записи (ход стёрт или
 * payload повреждён) — идемпотентный no-op: в стёртом ходе закреплять нечего,
 * живая история флаг уже несёт.
 */
internal class ChatTurnPins(
    private val saver: ChatTurnSaver,
    private val chatId: String?,
    private val setPin: suspend (chatId: String, turnId: String, messageIndex: Int, pinned: Boolean, note: String?) -> Boolean,
) {
    /** Поставить закрепление/открепление в очередь записи; false — писать нечего. */
    fun queue(change: PinChange): Boolean {
        val ref = parseMessageId(change.messageId) ?: return false
        if (ref.turnId == CURRENT_TURN_ID) return false
        val id = chatId ?: return false
        saver.save(currentId = id, create = { id }, append = { savedId ->
            setPin(savedId, ref.turnId, ref.index, change.pinned, change.note)
        })
        return true
    }
}
