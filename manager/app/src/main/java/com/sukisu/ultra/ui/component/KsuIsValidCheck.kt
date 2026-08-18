package com.sukisu.ultra.ui.component

import androidx.compose.runtime.Composable
import com.sukisu.ultra.Natives
import com.sukisu.ultra.ksuApp

@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    // 始终显示内容，不检查 root 状态
    content()
}
