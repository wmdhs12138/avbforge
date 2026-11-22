package io.github.fu.avbforge

import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.components.LiveTerminalSheet
import io.github.fu.avbforge.ui.components.M3EFloatingNavBar
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
            val prefs = remember { getSharedPreferences("avbforge_prefs", Context.MODE_PRIVATE) }
            var useDynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamic_color", false)) }

            AVBForgeTheme(useDynamicColor = useDynamicColor) {
                AvbForgeApp(
                    useDynamicColor = useDynamicColor,
                    onToggleDynamicColor = {
                        useDynamicColor = !useDynamicColor
                        prefs.edit().putBoolean("dynamic_color", useDynamicColor).apply()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvbForgeApp(
    useDynamicColor: Boolean,
    onToggleDynamicColor: () -> Unit,
    vm: AvbForgeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showTerminal by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val notify: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    var lastNotifiedStatus by remember { mutableStateOf(state.statusMessage) }
    LaunchedEffect(state.statusMessage, state.busy) {
        if (!state.busy && state.statusMessage != lastNotifiedStatus && state.statusMessage != "就绪") {
            lastNotifiedStatus = state.statusMessage
            notify(state.statusMessage)
        }
    }

    val navItems = remember {
        listOf(
            NavTabItem("主页", "AVBForge", Icons.Rounded.Home),
            NavTabItem("自签名", "自签名引导", Icons.Rounded.VerifiedUser),
            NavTabItem("DSU", "DSU 体验", Icons.Rounded.SystemUpdate),
            NavTabItem("密钥", "密钥库", Icons.Rounded.Key),
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 86.dp),
            )
        },
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = navItems[tab].title,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { if (!state.busy) vm.refreshDevice() }, enabled = !state.busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新状态")
                    }
                    IconButton(onClick = {
                        onToggleDynamicColor()
                        notify(if (!useDynamicColor) "已切换为系统莫奈动态取色" else "已切换为内置 M3E 蓝绿主题")
                    }) {
                        Icon(
                            Icons.Rounded.Palette,
                            contentDescription = "切换主题配色",
                            tint = if (useDynamicColor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                AnimatedVisibility(
                    visible = state.busy,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                                val animatedProgress by animateFloatAsState(
                                    targetValue = (p.current.toFloat() / p.total.toFloat()).coerceIn(0f, 1f),
                                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                                    label = "progress",
                                )
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
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

                AnimatedVisibility(
                    visible = state.errorBanner != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    state.errorBanner?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { vm.clearError() }) {
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
                    targetState = tab,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(animationSpec = tween(240)) { it / 8 * dir } + fadeIn(tween(200)))
                            .togetherWith(
                                slideOutHorizontally(animationSpec = tween(180)) { -it / 10 * dir } + fadeOut(tween(150))
                            )
                    },
                    label = "tab_content",
                ) { currentTab ->
                    when (currentTab) {
                        0 -> DashboardScreen(state, vm, notify)
                        1 -> ForgeScreen(state, vm, notify)
                        2 -> DsuScreen(state, vm, notify)
                        3 -> KeysScreen(state, vm, notify)
                    }
                }
            }

            M3EFloatingNavBar(
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
