package ru.outsideproarts.oassist.core.agent

internal data class UiNodeFingerprint(
    val node: UiNode,
    val windowId: Int,
    val packageName: String?,
    val resourceId: String?,
    val enabled: Boolean,
    val longClickable: Boolean,
    val visible: Boolean,
)

internal data class UiObservedNode<T>(val identity: T, val fingerprint: UiNodeFingerprint)

/** Caller serializes all access, including disposal of retained identities. */
internal class UiObservationRegistry<T>(private val dispose: (T) -> Unit) {
    private var snapshotId: String? = null
    private var nodes: List<UiObservedNode<T>> = emptyList()
    private val advertisedActions = mutableMapOf<Int, List<UiNodeAction>>()

    fun publish(id: String, observed: List<UiObservedNode<T>>) {
        clear()
        snapshotId = id
        nodes = observed
    }

    fun clear() {
        nodes.forEach { dispose(it.identity) }
        nodes = emptyList()
        advertisedActions.clear()
        snapshotId = null
    }

    fun expected(id: String?, index: Int): UiObservedNode<T>? =
        if (id != null && id == snapshotId) nodes.getOrNull(index) else null

    fun matches(id: String?, index: Int, identity: T, fingerprint: UiNodeFingerprint): Boolean {
        val expected = expected(id, index) ?: return false
        return expected.identity == identity && expected.fingerprint == fingerprint
    }

    fun advertise(id: String?, index: Int, actions: List<UiNodeAction>): Boolean {
        if (expected(id, index) == null) return false
        val prior = advertisedActions[index]
        if (prior != null && prior != actions) return false
        advertisedActions[index] = actions.toList()
        return true
    }

    fun action(id: String?, index: Int, actionIndex: Int, current: List<UiNodeAction>): UiNodeAction? {
        if (expected(id, index) == null || advertisedActions[index] != current) return null
        return current.getOrNull(actionIndex)
    }
}
