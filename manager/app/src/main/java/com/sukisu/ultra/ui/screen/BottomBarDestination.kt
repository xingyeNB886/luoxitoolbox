package com.sukisu.ultra.ui.screen

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ModuleScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SuperUserScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SettingScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FunctionScreenDestination
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import com.sukisu.ultra.R

enum class BottomBarDestination(
    val direction: DirectionDestinationSpec,
    @StringRes val label: Int,
    val iconSelected: ImageVector,
    val iconNotSelected: ImageVector,
    val rootRequired: Boolean,
) {
    Home(HomeScreenDestination, R.string.home, Icons.Filled.Home, Icons.Outlined.Home, false),
    Function(FunctionScreenDestination, R.string.function, Icons.Filled.Widgets, Icons.Outlined.Widgets, false),
    SuperUser(SuperUserScreenDestination, R.string.superuser, Icons.Filled.Security, Icons.Outlined.Security, false),
    Module(ModuleScreenDestination, R.string.module, Icons.Filled.Apps, Icons.Outlined.Apps, false),
    Settings(SettingScreenDestination, R.string.settings, Icons.Filled.Settings, Icons.Outlined.Settings, false),
}
