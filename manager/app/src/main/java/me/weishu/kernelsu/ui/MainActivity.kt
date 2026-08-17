package me.weishu.kernelsu.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.MutableStateFlow
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.BottomBar
import me.weishu.kernelsu.ui.component.MainPagerState
import me.weishu.kernelsu.ui.component.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.rememberMainPagerState
import me.weishu.kernelsu.ui.navigation3.HandleDeepLink
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.navigation3.rememberNavigator
import me.weishu.kernelsu.ui.screen.AboutScreen
import me.weishu.kernelsu.ui.screen.AppProfileScreen
import me.weishu.kernelsu.ui.screen.AppProfileTemplateScreen
import me.weishu.kernelsu.ui.screen.ExecuteModuleActionScreen
import me.weishu.kernelsu.ui.screen.FlashIt
import me.weishu.kernelsu.ui.screen.HomePager
import me.weishu.kernelsu.ui.screen.ModulePager
import me.weishu.kernelsu.ui.screen.ModuleRepoDetailScreen
import me.weishu.kernelsu.ui.screen.ModuleRepoScreen
import me.weishu.kernelsu.ui.screen.PermissionScreen
import me.weishu.kernelsu.ui.screen.SettingPager
import me.weishu.kernelsu.ui.screen.SuperUserPager
import me.weishu.kernelsu.ui.screen.TemplateEditorScreen
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.util.getFileName
import me.weishu.kernelsu.ui.webui.WebUIActivity
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val intentState = MutableStateFlow(0)

    // 洛茜工具箱：语言切换需在 attachBaseContext 应用 Locale，保证 Activity 资源用选定语言
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(me.weishu.kernelsu.ui.util.LuoxiLanguage.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // 洛茜工具箱：不再做 isManager 检查（Natives.isManager 总是 false），
        // 也不再调用 install()（修补镜像功能已移除，统一引导到 Permission 页面）。
        val isManager = false

        setContent {
            val context = LocalActivity.current ?: this
            val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
            var colorMode by remember { mutableIntStateOf(prefs.getInt("color_mode", 0)) }
            var keyColorInt by remember { mutableIntStateOf(prefs.getInt("key_color", 0)) }
            val keyColor = remember(keyColorInt) { if (keyColorInt == 0) null else Color(keyColorInt) }

            val darkMode = when (colorMode) {
                2, 5 -> true
                0, 3 -> isSystemInDarkTheme()
                else -> false
            }

            DisposableEffect(prefs, darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }

                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        "color_mode" -> colorMode = prefs.getInt("color_mode", 0)
                        "key_color" -> keyColorInt = prefs.getInt("key_color", 0)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            val navigator = rememberNavigator(Route.Main)
            CompositionLocalProvider(LocalNavigator provides navigator) {
                KernelSUTheme(colorMode = colorMode, keyColor = keyColor) {

                    HandleDeepLink(
                        intentState = intentState.collectAsState(),
                    )

                    ZipFileIntentHandler(
                        intentState = intentState,
                        isManager = isManager,
                    )
                    ShortcutIntentHandler(
                        intentState = intentState,
                    )

                    Scaffold {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            onBack = {
                                when (val top = navigator.current()) {
                                    is Route.TemplateEditor -> {
                                        if (!top.readOnly) {
                                            navigator.setResult("template_edit", true)
                                        } else {
                                            navigator.pop()
                                        }
                                    }

                                    else -> navigator.pop()
                                }
                            },
                            entryProvider = entryProvider {
                                entry<Route.Main> { MainScreen() }
                                entry<Route.About> { AboutScreen() }
                                entry<Route.AppProfileTemplate> { AppProfileTemplateScreen() }
                                entry<Route.TemplateEditor> { key -> TemplateEditorScreen(key.template, key.readOnly) }
                                entry<Route.AppProfile> { key -> AppProfileScreen(key.packageName) }
                                entry<Route.ModuleRepo> { ModuleRepoScreen() }
                                entry<Route.ModuleRepoDetail> { key -> ModuleRepoDetailScreen(key.module) }
                                // 洛茜工具箱：修补镜像功能已移除，Install/Flash 全部重定向到 Permission 页面
                                entry<Route.Install> { PermissionScreen() }
                                entry<Route.Permission> { PermissionScreen() }
                                entry<Route.Flash> { PermissionScreen() }
                                entry<Route.ExecuteModuleAction> { key -> ExecuteModuleActionScreen(key.moduleId) }
                                entry<Route.Home> { MainScreen() }
                                entry<Route.SuperUser> { MainScreen() }
                                entry<Route.Module> { MainScreen() }
                                entry<Route.Settings> { MainScreen() }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Increment intentState to trigger LaunchedEffect re-execution
        intentState.value += 1
    }
}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@Composable
fun MainScreen() {
    val navController = LocalNavigator.current
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val mainPagerState = rememberMainPagerState(pagerState)
    // 洛茜工具箱：所有页面解锁访问（不再因 isManager=false 而隐藏）
    val isFullFeatured = true
    var userScrollEnabled by remember(isFullFeatured) { mutableStateOf(isFullFeatured) }
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = MiuixTheme.colorScheme.surface,
        tint = HazeTint(MiuixTheme.colorScheme.surface.copy(0.8f))
    )

    // 洛茜工具箱：首次进入免责声明弹窗（同意后存 prefs 不再弹）
    val context = LocalContext.current
    val disclaimerShow = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("disclaimer_agreed", false)) {
            disclaimerShow.value = true
        }
    }

    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navController)

    // 免责声明弹窗
    DisclaimerDialog(disclaimerShow)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        Scaffold(
            bottomBar = {
                BottomBar(hazeState, hazeStyle)
            },
        ) { innerPadding ->
            HorizontalPager(
                modifier = Modifier.hazeSource(state = hazeState),
                state = mainPagerState.pagerState,
                beyondViewportPageCount = 3,
                userScrollEnabled = userScrollEnabled,
            ) {
                when (it) {
                    0 -> HomePager(navController, innerPadding.calculateBottomPadding())
                    1 -> SuperUserPager(navController, innerPadding.calculateBottomPadding())
                    2 -> ModulePager(navController, innerPadding.calculateBottomPadding())
                    3 -> SettingPager(navController, innerPadding.calculateBottomPadding())
                }
            }
        }
    }
}


/**
 * 洛茜工具箱：首次进入免责声明弹窗。
 * 同意后写入 prefs "disclaimer_agreed=true"，不再弹出；未同意时不可关闭（点返回键无效）。
 * 内容：本软件通过修改游戏调用的图片文件实现自定义加载图，由此造成的文件损失等后果由用户自行承担。
 */
@Composable
private fun DisclaimerDialog(show: androidx.compose.runtime.MutableState<Boolean>) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    top.yukonga.miuix.kmp.extra.SuperDialog(
        show = show,
        title = "免责声明",
        onDismissRequest = { /* 未同意不可关闭，拦截返回 */ },
        content = {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
            ) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = "本软件通过修改游戏调用的图片文件，以此达到将游戏加载图替换为自定义图片的目的。\n\n" +
                            "使用本软件造成的任何图片文件损失、游戏异常或其他后果，均由使用者自行承担，本软件不承担任何责任。\n\n" +
                            "点击「同意」即表示您已阅读并理解上述内容。",
                    fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(androidx.compose.ui.unit.Dp(16f))
                )
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    top.yukonga.miuix.kmp.basic.TextButton(
                        text = "同意",
                        onClick = {
                            val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("disclaimer_agreed", true).apply()
                            show.value = false
                        },
                        colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}


@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}

/**
 * Handles ZIP file installation from external apps (e.g., file managers).
 * - In normal mode: Shows a confirmation dialog before installation
 * - In safe mode: Shows a Toast notification and prevents installation
 */
@SuppressLint("StringFormatInvalid", "LocalContextGetResourceValueCall")
@Composable
private fun ZipFileIntentHandler(
    intentState: MutableStateFlow<Int>,
    isManager: Boolean,
) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    var zipUri by remember { mutableStateOf<Uri?>(null) }
    val isSafeMode = Natives.isSafeMode
    val clearZipUri = { zipUri = null }
    val navigator = LocalNavigator.current

    val installDialog = rememberConfirmDialog(
        onConfirm = {
            zipUri?.let { uri ->
                navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri))))
            }
            clearZipUri()
        },
        onDismiss = clearZipUri
    )

    fun getDisplayName(uri: Uri): String {
        return uri.getFileName(context) ?: uri.lastPathSegment ?: "Unknown"
    }

    val intentStateValue by intentState.collectAsState()
    LaunchedEffect(intentStateValue) {
        val currentIntent = activity.intent
        val uri = currentIntent?.data ?: return@LaunchedEffect

        if (!isManager || uri.scheme != "content" || currentIntent.type != "application/zip") {
            return@LaunchedEffect
        }

        if (isSafeMode) {
            Toast.makeText(
                context,
                context.getString(R.string.safe_mode_module_disabled), Toast.LENGTH_SHORT
            )
                .show()
        } else {
            zipUri = uri
            installDialog.showConfirm(
                title = context.getString(R.string.module),
                content = context.getString(
                    R.string.module_install_prompt_with_name,
                    "\n${getDisplayName(uri)}"
                )
            )
        }
    }
}

@Composable
private fun ShortcutIntentHandler(
    intentState: MutableStateFlow<Int>,
) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val intentStateValue by intentState.collectAsState()
    val navigator = LocalNavigator.current
    LaunchedEffect(intentStateValue) {
        val intent = activity.intent
        val type = intent?.getStringExtra("shortcut_type") ?: return@LaunchedEffect
        when (type) {
            "module_action" -> {
                val moduleId = intent.getStringExtra("module_id") ?: return@LaunchedEffect
                navigator.push(Route.ExecuteModuleAction(moduleId))
            }

            "module_webui" -> {
                val moduleId = intent.getStringExtra("module_id") ?: return@LaunchedEffect
                val webIntent = Intent(context, WebUIActivity::class.java)
                    .setData("kernelsu://webui/$moduleId".toUri())
                context.startActivity(webIntent)
            }

            else -> return@LaunchedEffect
        }
    }
}
