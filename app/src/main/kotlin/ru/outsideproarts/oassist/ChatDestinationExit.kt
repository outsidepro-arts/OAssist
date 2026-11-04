package ru.outsideproarts.oassist

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

internal fun observeChatDestinationExit(
    navController: NavController,
    chatEntry: NavBackStackEntry,
    onExit: () -> Unit,
): () -> Unit {
    val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
        if (controller.currentBackStackEntry !== chatEntry) onExit()
    }
    navController.addOnDestinationChangedListener(listener)
    return { navController.removeOnDestinationChangedListener(listener) }
}
