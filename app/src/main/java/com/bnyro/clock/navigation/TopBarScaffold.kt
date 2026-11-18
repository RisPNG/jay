package com.bnyro.clock.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bnyro.clock.presentation.components.ClickableIcon
import androidx.compose.material3.FabPosition
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarScaffold(
    title: String,
    onClickSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable (RowScope.() -> Unit) = {},
    fab: @Composable () -> Unit = {},
    fabPosition: FabPosition = FabPosition.End,
    content: @Composable (PaddingValues) -> Unit
) {
    val density = LocalDensity.current
    var fabHeight by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = { Text(title) }, actions = {
                actions()
                onClickSettings?.let { onClick ->
                    ClickableIcon(imageVector = Icons.Default.Settings, onClick = onClick)
                }
            })
        },
        floatingActionButton = {
            Box(Modifier.onSizeChanged { fabHeight = it.height }) {
                fab()
            }
        },
        floatingActionButtonPosition = fabPosition
    ) { padding ->
        val contentPadding = remember(padding, density) {
            object : PaddingValues by padding {
                override fun calculateBottomPadding() =
                    padding.calculateBottomPadding() +
                        if (fabHeight > 0) with(density) { fabHeight.toDp() } + 16.dp else 0.dp
            }
        }
        content(contentPadding)
    }
}