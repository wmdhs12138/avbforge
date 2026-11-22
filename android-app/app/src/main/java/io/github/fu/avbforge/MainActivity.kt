package io.github.fu.avbforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.components.FloatingPillNavBar
import io.github.fu.avbforge.ui.components.LiveTerminalSheet
import io.github.fu.avbforge.ui.components.NavTabItem
import io.github.fu.avbforge.ui.screens.DashboardScreen
import io.github.fu.avbforge.ui.screens.DsuScreen
import io.github.fu.avbforge.ui.screens.ForgeScreen
import io.github.fu.avbforge.ui.screens.KeysScreen
import io.github.fu.avbforge.ui.theme.AVBForgeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AVBForgeTheme {
                AvbForgeApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvbForgeApp(vm: AvbForgeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showTerminal by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notify: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    val navItems = remember {
        listOf(
            NavTabItem("体检", Icons.Rounded.Shield),
            NavTabItem("密钥", Icons.Rounded.Key),
            NavTabItem("工坊", Icons.Rounded.Build),
            NavTabItem("DSU", Icons.Rounded.SystemUpdate),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 84.dp),
            )
        },
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = {
                    Text(
                        text = "AVBForge",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = { showTerminal = true }) {
                        BadgedBox(
                            badge = {
                                if (state.logs.isNotEmpty()) {
                                    Badge { Text(state.logs.size.coerceAtMost(99).toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Terminal, contentDescription = "实时日志")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Smooth Animated Progress Bar
                AnimatedVisibility(
                    visible = state.busy,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val p = state.progress
                    if (p != null && p.total > 0) {
                        val animatedProgress by animateFloatAsState(
                            targetValue = (p.current.toFloat() / p.total.toFloat()).coerceIn(0f, 1f),
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "progress",
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(CircleShape),
                        )
                    }
                }

                // Expressive Status / Error Pill Banner
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (state.errorBanner != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.errorBanner ?: state.statusMessage,
                            color = if (state.errorBanner != null) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.errorBanner != null) {
                            IconButton(onClick = { vm.clearError() }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "关闭提示",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                // Directional Expressive Screen Transitions
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(animationSpec = tween(260)) { it / 6 * dir } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally(animationSpec = tween(220)) { -it / 8 * dir } + fadeOut(tween(180))
                            )
                    },
                    label = "tab_content",
                ) { currentTab ->
                    when (currentTab) {
                        0 -> DashboardScreen(state, vm, notify)
                        1 -> KeysScreen(state, vm, notify)
                        2 -> ForgeScreen(state, vm, notify)
                        3 -> DsuScreen(state, vm, notify)
                    }
                }
            }

            // Floating M3 Expressive Pill Navigation Bar
            FloatingPillNavBar(
                items = navItems,
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showTerminal) {
        LiveTerminalSheet(
            logs = state.logs,
            onClear = { vm.clearLogs() },
            onDismiss = { showTerminal = false },
        )
    }
}
