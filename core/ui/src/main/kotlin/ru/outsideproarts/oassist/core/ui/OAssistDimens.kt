package ru.outsideproarts.oassist.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp

object OAssistDimens {
    val ScreenPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(R.dimen.oassist_space_large)

    val ItemSpacing: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(R.dimen.oassist_space_small)

    val SectionSpacing: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(R.dimen.oassist_space_xlarge)

    val IconSize: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(R.dimen.oassist_icon)

    val OptionRowSpacing: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(R.dimen.oassist_space_medium)

    val DenseSpacing: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(R.dimen.oassist_space_xsmall)

    val HairlineSpacing: Dp
        @Composable
        @ReadOnlyComposable
        get() = dimensionResource(R.dimen.oassist_space_xxsmall)

    val ScreenContentPadding: PaddingValues
        @Composable
        get() = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = ScreenPadding,
            bottom = ScreenPadding,
        )
}
