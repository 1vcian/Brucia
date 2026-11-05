package app.brucia.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp

/**
 * App-wide button defaults. Material3 1.4 changed the OutlinedButton label to
 * onSurfaceVariant (grey in the Brucia scheme) and its border to outlineVariant, and
 * moved disabled button colors to onSurfaceVariant. These defaults keep the earlier
 * Brucia look: outlined labels in primary (Ember, or the device accent), a 1 dp
 * `outline` border in every state, disabled content onSurface at 38 % and disabled
 * filled containers onSurface at 12 %.
 *
 * Screens import [Button], [OutlinedButton] and [TextButton] from this package instead
 * of androidx.compose.material3; `ButtonThemeUsageTest` checks that.
 */
object BruciaButtonDefaults {
    private const val DISABLED_CONTENT_ALPHA = 0.38f
    private const val DISABLED_CONTAINER_ALPHA = 0.12f

    @Composable
    fun buttonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): ButtonColors {
        val cs = MaterialTheme.colorScheme
        return ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor.takeOrElse { cs.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA) },
            disabledContentColor = disabledContentColor.takeOrElse { cs.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA) },
        )
    }

    @Composable
    fun outlinedButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): ButtonColors {
        val cs = MaterialTheme.colorScheme
        return ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor.takeOrElse { cs.primary },
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor.takeOrElse { cs.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA) },
        )
    }

    @Composable
    fun textButtonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
    ): ButtonColors {
        val cs = MaterialTheme.colorScheme
        return ButtonDefaults.textButtonColors(
            containerColor = containerColor,
            contentColor = contentColor.takeOrElse { cs.primary },
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor.takeOrElse { cs.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA) },
        )
    }

    /** Same border enabled and disabled, as Material3 1.2 drew it. */
    @Composable
    fun outlinedButtonBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = BruciaButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = BruciaButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BruciaButtonDefaults.outlinedButtonBorder(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = BruciaButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)
