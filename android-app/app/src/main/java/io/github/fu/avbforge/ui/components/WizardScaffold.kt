package io.github.fu.avbforge.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fu.avbforge.ui.UiState

enum class ActiveWizard {
    NONE,
    FIRST_TIME_SELF_SIGN,
    UPDATE_KSU,
    DSU_STUDIO,
}

/**
 * Full-screen focused step-by-step wizard shell:
 * - Top bar with Close button, wizard title, step pill ("第 X / N 步"), optional actions & live logs icon
 * - Animated step progress bar
 * - Inline busy progress & error banner so the user never misses engine feedback
 * - Smooth directional horizontal slide between steps
 * - Sticky bottom navigation bar with Back / Next buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScaffold(
    wizardTitle: String,
    stepIndex: Int,
    totalSteps: Int,
    stepTitles: List<String>,
    state: UiState,
    onClearError: () -> Unit,
    onOpenLogs: () -> Unit,
    onExit: () -> Unit,
    onBack: () -> Unit,
    onNext: (() -> Unit)? = null,
    nextLabel: String = "下一步",
    nextEnabled: Boolean = true,
    nextIcon: ImageVector = Icons.AutoMirrored.Rounded.ArrowForward,
    showBottomBar: Boolean = true,
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: @Composable (currentStep: Int) -> Unit,
) {
    BackHandler {
        if (stepIndex > 0) onBack() else onExit()
    }

    val progressFraction by animateFloatAsState(
        targetValue = ((stepIndex + 1).toFloat() / totalSteps.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "wizard_step_progress",
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭向导")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = wizardTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text = "第 ${stepIndex + 1} / $totalSteps 步 · ${stepTitles.getOrElse(stepIndex) { "" }}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    actions = {
                        topBarActions()
                        IconButton(onClick = onOpenLogs) {
                            Icon(Icons.Rounded.Terminal, contentDescription = "实时日志")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(5.dp)
                        .clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { if (stepIndex > 0) onBack() else onExit() },
                            shape = CircleShape,
                            modifier = Modifier.heightIn(min = 50.dp),
                        ) {
                            Icon(
                                if (stepIndex > 0) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (stepIndex > 0) "上一步" else "退出")
                        }

                        if (onNext != null) {
                            M3EPrimaryButton(
                                text = nextLabel,
                                icon = nextIcon,
                                onClick = onNext,
                                enabled = nextEnabled && !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Inline Busy Banner
            AnimatedVisibility(
                visible = state.busy,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val p = state.progress
                        if (p != null && p.total > 0) {
                            val anim by animateFloatAsState(
                                targetValue = (p.current.toFloat() / p.total.toFloat()).coerceIn(0f, 1f),
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                                label = "wiz_busy_prog",
                            )
                            LinearProgressIndicator(
                                progress = { anim },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(CircleShape),
                            )
                        }
                    }
                }
            }

            // Inline Error Banner
            AnimatedVisibility(
                visible = state.errorBanner != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                state.errorBanner?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClearError) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = stepIndex,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(240)) { it / 6 * dir } + fadeIn(tween(200)))
                        .togetherWith(
                            slideOutHorizontally(tween(180)) { -it / 8 * dir } + fadeOut(tween(140))
                        )
                },
                label = "wizard_step_content",
            ) { currentStep ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content(currentStep)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * Airy step title header placed at the top of each wizard page.
 */
@Composable
fun WizardStepIntro(
    stepNumber: Int,
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = "STEP 0$stepNumber",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Thorough, human-readable callout box used inside wizards to present critical warnings or guides inline.
 */
@Composable
fun WizardCalloutCard(
    title: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    bullets: List<String> = emptyList(),
    body: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = contentColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }

            if (!body.isNullOrBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.90f),
                )
            }

            if (bullets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bullets.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = contentColor, style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.92f),
                            )
                        }
                    }
                }
            }

            if (extra != null) {
                extra()
            }
        }
    }
}
