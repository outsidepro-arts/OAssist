package ru.outsideproarts.oassist.core.a11y

/**
 * Инварианты доступности OAssist. Соблюдаются на каждом экране, проверяются в DoD.
 *
 * 1. У каждого интерактивного элемента — имя (текст или contentDescription).
 * 2. Роль и состояние: [oassistRole], [oassistStateDescription] для переключателей и статусов.
 * 3. Заголовки секций — [oassistHeading]; панели/оверлеи — [oassistPaneTitle].
 * 4. Touch target ≥ 48dp — [oassistTouchTarget].
 * 5. Контраст AA: 4.5:1 для текста, 3:1 для UI-границ, в обеих темах.
 * 6. Порядок фокуса = порядок чтения ([oassistTraversalIndex] где Compose спорит).
 * 7. Динамический контент — [oassistLiveRegion]; ошибки — assertive; транзиенты — [Announcer].
 * 8. У каждого жеста — альтернатива (кнопка/меню) и [oassistCustomAction].
 * 9. Запрещено: жест как единственный путь; захардкоженные тексты в объявлениях;
 *    иконка без имени; элемент без фокуса (clickable без semantics-имени).
 */
object AccessibilityInvariants {
    const val MIN_TOUCH_TARGET_DP = 48
    const val MIN_CONTRAST_TEXT = 4.5f
    const val MIN_CONTRAST_UI = 3.0f

    /** Имена custom-действий, которые обязаны быть у сообщений в ленте чата. */
    val MESSAGE_ACTIONS_REQUIRED = listOf("copy", "edit", "delete", "regenerate", "stop", "speak")

    /** Имена custom-действий у событий timeline работы агента. */
    val TIMELINE_ACTIONS_REQUIRED = listOf("details", "retry")
}
