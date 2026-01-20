package de.temporaerhaus.inventory.client.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.temporaerhaus.inventory.client.R
import de.temporaerhaus.inventory.client.ui.theme.TPHInventoryTheme

@Composable
fun MarkAsSeenButton(
    needsSaving: Boolean,
    isSaving: Boolean,
    saved: Boolean,
    onMarkAsSeen: () -> Unit,
    autoSave: Boolean,
    modifier: Modifier = Modifier,
) {
    val animationProgress = remember { Animatable(0f) }
    var launched by remember { mutableStateOf(false) }
    val enabled = !isSaving && !saved
    val buttonColor: Color = Color.Black
    val progressColor: Color = Color.DarkGray

    LaunchedEffect(autoSave) {
        if (autoSave && !launched) {
            launched = true
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 5_000,
                    easing = LinearEasing
                )
            )
            onMarkAsSeen()
            animationProgress.snapTo(0f)
            launched = false
        } else if (!autoSave && launched) {
            animationProgress.stop()
            animationProgress.snapTo(0f)
            launched = false
        }
    }

    val brush = if (enabled) Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to progressColor,
            animationProgress.value to progressColor,
            animationProgress.value to buttonColor,
            1.0f to buttonColor
        ),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    ) else Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.Gray,
                1.0f to Color.Gray
            ),
            start = Offset.Zero,
            end = Offset(Float.POSITIVE_INFINITY, 0f)
        )

    Box(
        modifier = modifier
            .clip(ButtonDefaults.shape)
            .background(brush = brush)
    ) {
        Button(
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = Color.DarkGray.copy(alpha = 0.5f)
            ),
            enabled = enabled && !launched,
            onClick = { onMarkAsSeen() },
            modifier = Modifier.wrapContentWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                when {
                    isSaving -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    saved -> Icon(
                        painter = painterResource(R.drawable.check_circle_24),
                        contentDescription = "Checked",
                        tint = Color.White
                    )
                    else -> Icon(
                        painter = painterResource(R.drawable.check_24),
                        contentDescription = "Check mark",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    text = when {
                        isSaving -> "Saving..."
                        needsSaving -> "Save & mark as seen"
                        saved -> "Marked as seen"
                        else -> "Mark as seen"
                    },
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Preview(name = "Default", showBackground = true)
@Composable
fun MarkAsSeenButtonDefaultPreview() {
    TPHInventoryTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            MarkAsSeenButton(
                needsSaving = false,
                isSaving = false,
                saved = false,
                onMarkAsSeen = {},
                autoSave = false
            )
        }
    }
}

@Preview(name = "Needs Saving", showBackground = true)
@Composable
fun MarkAsSeenButtonNeedsSavingPreview() {
    TPHInventoryTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            MarkAsSeenButton(
                needsSaving = true,
                isSaving = false,
                saved = false,
                onMarkAsSeen = {},
                autoSave = false
            )
        }
    }
}

@Preview(name = "Saving", showBackground = true)
@Composable
fun MarkAsSeenButtonSavingPreview() {
    TPHInventoryTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            MarkAsSeenButton(
                needsSaving = false,
                isSaving = true,
                saved = false,
                onMarkAsSeen = {},
                autoSave = false
            )
        }
    }
}

@Preview(name = "Saved", showBackground = true)
@Composable
fun MarkAsSeenButtonSavedPreview() {
    TPHInventoryTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            MarkAsSeenButton(
                needsSaving = false,
                isSaving = false,
                saved = true,
                onMarkAsSeen = {},
                autoSave = false
            )
        }
    }
}
