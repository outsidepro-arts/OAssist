package ru.outsideproarts.oassist.core.agent

/**
 * Мост к оверлей-окну ассистента: на время координатных жестов панель скрывается,
 * чтобы тапы/свайпы доставались приложению под ней, а не панели (кнопка закрытия
 * при этом недоступна — жесты длятся секунды, не минуты).
 */
interface OverlayControl {

    /** Скрыть панель на время [block] и вернуть после (в т.ч. при исключении). */
    suspend fun <T> withOverlayHidden(block: suspend () -> T): T

    companion object {
        @Volatile
        private var instance: OverlayControl? = null

        fun register(control: OverlayControl) {
            instance = control
        }

        fun unregister(control: OverlayControl) {
            if (instance === control) instance = null
        }

        /** Null — оверлей не открыт (обычный режим: жесты идут без обёртки). */
        fun get(): OverlayControl? = instance
    }
}
