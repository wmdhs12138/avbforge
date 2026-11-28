package io.github.fu.avbforge

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fu.avbforge.ui.AvbForgeViewModel
import io.github.fu.avbforge.ui.components.M3EFloatingNavBar
import io.github.fu.avbforge.ui.components.NavTabItem
import io.github.fu.avbforge.ui.components.RawTerminalSheet
import io.github.fu.avbforge.ui.screens.AboutScreen
import io.github.fu.avbforge.ui.screens.DeviceInfoScreen
import io.github.fu.avbforge.ui.screens.DsuManagerScreen
import io.github.fu.avbforge.ui.screens.PartitionManagerScreen
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
    val terminalEntries by vm.terminalEntries.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showAbout by remember { mutableStateOf(false) }
    var showTerminal by remember { mutableStateOf(false) }
    var pendingOpenAddKey by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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

    if (showAbout) {
        BackHandler { showAbout = false }
    }

    val navItems = remember {
        listOf(
            NavTabItem("本机信息", "本机信息", Icons.Rounded.PhoneAndroid),
            NavTabItem("分区管理", "分区管理", Icons.Rounded.Layers),
            NavTabItem("DSU管理", "DSU 管理", Icons.Rounded.Storage),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = if (showAbout) 16.dp else 86.dp),
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (showAbout) "关于 AVBForge" else navItems[tab].title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    if (!showAbout) {
                        IconButton(
                            onClick = { if (!state.busy) vm.refreshDevice() },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新状态")
                        }
                    }
                    IconButton(onClick = {
                        onToggleDynamicColor()
                        notify(if (!useDynamicColor) "已切换为系统动态取色" else "已切换为内置 M3E 深海配色")
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
                                if (terminalEntries.isNotEmpty()) {
                                    Badge { Text(terminalEntries.size.coerceAtMost(99).toString()) }
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.Terminal, contentDescription = "控制台")
                        }
                    }
                    IconButton(onClick = { showAbout = !showAbout }) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = "关于",
                            tint = if (showAbout) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Global Busy Progress Banner
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
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = state.statusMessage,
                                style = MaterialTheme.typography.labelMedium,
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

                // Global Error Banner
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
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
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

                if (showAbout) {
                    AboutScreen(
                        onNotify = notify,
                        onBack = { showAbout = false },
                    )
                } else {
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(animationSpec = tween(220)) { it / 8 * dir } + fadeIn(tween(180)))
                                .togetherWith(
                                    slideOutHorizontally(animationSpec = tween(160)) { -it / 10 * dir } + fadeOut(tween(140))
                                )
                        },
                        label = "v2_tab_content",
                    ) { currentTab ->
                        when (currentTab) {
                            0 -> DeviceInfoScreen(state = state)
                            1 -> PartitionManagerScreen(
                                state = state,
                                vm = vm,
                                autoOpenAddKey = pendingOpenAddKey,
                                onAutoOpenAddKeyConsumed = { pendingOpenAddKey = false },
                                onNotify = notify,
                            )
                            2 -> DsuManagerScreen(
                                state = state,
                                vm = vm,
                                onGoPartitions = { tab = 1 },
                                onGoConfigureKey = {
                                    pendingOpenAddKey = true
                                    tab = 1
                                },
                                onNotify = notify,
                            )
                        }
                    }
                }
            }

            if (!showAbout) {
                M3EFloatingNavBar(
                    items = navItems,
                    selectedIndex = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (showTerminal) {
        RawTerminalSheet(
            entries = terminalEntries,
            onClear = { vm.clearTerminalEntries() },
            onDismiss = { showTerminal = false },
            onCopied = { notify("已复制全部原始命令与输出") },
        )
    }
}
