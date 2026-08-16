package me.weishu.kernelsu.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.navigation3.Navigator
import me.weishu.kernelsu.ui.util.FileManagerUtils
import me.weishu.kernelsu.ui.util.PermissionManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * 初始化状态
 */
private enum class InitState {
    /** 检测中 */
    CHECKING,

    /** 没有权限（按钮变暗 + 提示） */
    NO_PERMISSION,

    /** 未初始化 */
    NOT_INITIALIZED,

    /** 已初始化 */
    INITIALIZED
}

/**
 * 文件管理页（原超级用户页）
 */
@Composable
fun SuperUserPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = colorScheme.surface,
        tint = HazeTint(colorScheme.surface.copy(0.8f))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(hazeState) {
                    style = hazeStyle
                    blurRadius = 30.dp
                    noiseFactor = 0f
                },
                color = Color.Transparent,
                title = stringResource(R.string.file_manager),
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp)
                .hazeSource(state = hazeState),
            contentPadding = innerPadding,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    InitCard()
                    ImagePickerCard()
                }
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }
}

/**
 * 板块一：初始化
 *
 * - 无权限：按钮变暗 + 提示
 * - 有权限：检测 luoxi 目录和标记文件，任一存在视为初始化过，缺的自动补上
 * - 都不存在：显示"你似乎还没初始化" + 初始化按钮
 */
@Composable
private fun InitCard() {
    var state by remember { mutableStateOf(InitState.CHECKING) }
    var initializing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh(): InitState = withContext(Dispatchers.IO) {
        val grant = PermissionManager.checkGrantType()
        if (!grant.isWorking) return@withContext InitState.NO_PERMISSION

        val check = FileManagerUtils.checkInitState() ?: return@withContext InitState.NO_PERMISSION
        val (dirExists, markExists) = check
        when {
            dirExists && markExists -> InitState.INITIALIZED
            // 有一个就证明初始化过，缺的自动补上
            dirExists || markExists -> {
                FileManagerUtils.ensureInitFiles()
                InitState.INITIALIZED
            }

            else -> InitState.NOT_INITIALIZED
        }
    }

    LaunchedEffect(Unit) {
        state = refresh()
        // 权限变化时重新检测（用户去首页授权后回来自动刷新）
        PermissionManager.permissionChanges().collect {
            state = refresh()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = if (state == InitState.INITIALIZED) {
                    "已初始化"
                } else {
                    "你似乎还没初始化"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (state) {
                    InitState.CHECKING -> "正在检查初始化状态…"
                    InitState.NO_PERMISSION -> "需要先在首页授权 Root 或 Shizuku 权限才能初始化"
                    InitState.NOT_INITIALIZED -> "点击下方按钮完成初始化"
                    InitState.INITIALIZED -> "初始化已完成，可以正常使用"
                },
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            if (state == InitState.NOT_INITIALIZED || state == InitState.NO_PERMISSION) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    TextButton(
                        text = if (initializing) "初始化中…" else "初始化",
                        enabled = state == InitState.NOT_INITIALIZED && !initializing,
                        onClick = {
                            scope.launch {
                                initializing = true
                                state = withContext(Dispatchers.IO) {
                                    if (FileManagerUtils.ensureInitFiles()) {
                                        InitState.INITIALIZED
                                    } else {
                                        InitState.NO_PERMISSION
                                    }
                                }
                                initializing = false
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    }
}

/**
 * 板块二：选择图片
 *
 * - 打开系统图片选择器（Photo Picker），可多选
 * - 选中图片在按钮下方缓存显示（预览 + 位置）
 * - 每项右上角有删除按钮
 * - 仅保存在内存中，退出应用自动清除
 */
@Composable
private fun ImagePickerCard() {
    var images by remember { mutableStateOf(listOf<Uri>()) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            images = images + uris
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Text(
                text = "选择图片",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "选择的图片会缓存在下方显示，退出应用后自动清除",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
            ) {
                TextButton(
                    text = "选择图片",
                    onClick = {
                        launcher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }

            // 已选图片缓存列表
            images.forEach { uri ->
                Spacer(Modifier.height(8.dp))
                SelectedImageItem(
                    uri = uri,
                    onRemove = { images = images - uri }
                )
            }
        }
    }
}

/**
 * 单个已选图片项：预览图 + 位置 + 右上角删除按钮
 */
@Composable
private fun SelectedImageItem(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        decodeSampledBitmap(uri, 128)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.1f))
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = "图片",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uri.toString(),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 2
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "删除",
                    tint = colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

/**
 * 解码 URI 图片为采样后的小图（仅用于预览，避免 OOM）
 */
private fun decodeSampledBitmap(uri: Uri, maxSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize &&
            bounds.outHeight / (sample * 2) >= maxSize
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ksuApp.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (e: Exception) {
        null
    }
}
