package app.brucia.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Height of the activity window in dp, for capping dialog heights. Read it before
 * `Dialog { }`: inside the dialog content LocalWindowInfo describes the dialog window.
 * (Replaces Configuration.screenHeightDp, which Compose lint discourages.)
 */
@Composable
internal fun windowHeightDp(): androidx.compose.ui.unit.Dp =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
