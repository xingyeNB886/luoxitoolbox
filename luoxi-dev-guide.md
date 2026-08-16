# 洛茜工具箱（KernelSU 二开）源码修改指南

> 用途：投喂 AI / 新会话快速恢复上下文。本文档包含项目结构、构建环境、核心架构、已实现功能原理、踩过的坑与修复方案，全部来自实际开发验证。

---

## 1. 项目概况

- **基底**：KernelSU v310 的 manager（管理器）App，深度二开为「洛茜工具箱」
- **仓库**：`https://github.com/xingyenb886/luoxitoolbox`（main 分支）
- **包名**：`me.luoxi.toolbox`（namespace 仍为 `me.weishu.kernelsu`，源码目录不变）
- **UI 框架**：Jetpack Compose + **miuix-kmp**（小米风格组件库，`top.yukonga.miuix.kmp.*`）+ Haze 毛玻璃
- **功能定位**：通过 Root(ksu) / Shizuku(adb) 权限管理 `/storage/emulated/0/Android/data/com.tencent.tmgp.pubgmhd/.../LoadingBG` 下的游戏加载图（和平精英），实现批量替换/备份/还原

### 目录结构（只涉及 manager 模块）

```
manager/
├── app/src/main/java/me/weishu/kernelsu/
│   ├── KernelSUApplication.kt      # Application，顶层 ksuApp
│   ├── ui/
│   │   ├── MainActivity.kt         # 单 Activity + HorizontalPager 四页签
│   │   ├── screen/
│   │   │   ├── Home.kt             # 首页：公告、版本卡片、强制更新弹窗
│   │   │   ├── SuperUser.kt        # 文件管理页：选图/裁剪/制作/替换（核心UI）
│   │   │   ├── Module.kt           # 功能页：还原备份、自定义zip还原
│   │   │   ├── Settings.kt         # 设置页
│   │   │   ├── About.kt            # 关于页：Telegram + QQ群入口
│   │   │   └── Permission.kt       # 授权页（Root/Shizuku）
│   │   ├── util/
│   │   │   ├── FileManagerUtils.kt # ★ 核心：shell执行/文件中转/备份还原（单例object）
│   │   │   ├── CloudUpdateManager.kt # 云端公告+强制更新（GitHub拉取）
│   │   │   └── PermissionManager.kt  # Root/Shizuku 权限判定
│   │   └── component/              # 通用组件
│   └── service/
│       ├── IShellService.aidl      # Shizuku UserService 的 Binder 接口
│       └── ShellService.kt         # 以 shell 身份执行命令的实现
├── app/proguard-rules.pro          # ★ 混淆规则（改动敏感，见 §6）
└── build.gradle.kts                # 版本号/包名/签名配置
.github/workflows/build-luoxi-toolbox.yml  # CI：push main 自动构建发布
```

---

## 2. 构建环境（沙箱本地）

| 项 | 值 |
|---|---|
| JDK | **Temurin 21**（`mise exec java@temurin-21 -- ./gradlew ...`） |
| 为什么 | AGP 9.x 的 javac/release 编译要求 21；JDK 25 会触发 prefab/CMake 兼容崩溃，JDK 17 编译 javac 报 "error: warning" 版本错误 |
| 代理 | 沙箱需走 `127.0.0.1:18080`，已写入 `~/.gradle/gradle.properties`（systemProp.http(s).proxyHost/Port） |
| 内存限制 | 沙箱 ~2GB，daemon 易被 cgroup OOM kill。构建用 `-Dorg.gradle.workers.max=2`；必要时把 gradle.properties 的 Xmx 临时降到 1200m、parallel=false，**构建完必须 `git checkout gradle.properties` 还原** |
| 命令 | `cd manager && mise exec java@temurin-21 -- ./gradlew assembleRelease --console=plain -Dorg.gradle.workers.max=2` |
| 产物 | `manager/app/build/outputs/apk/release/*.apk` |
| CI | push main 即触发 GitHub Actions，自动签名（见 §2.1 密钥章节）并发布 Release，tag 为 `luoxi-<run_number>`，APK 直链：`releases/download/luoxi-NN/LuoxiToolbox_1.0.0_1000000-release.apk` |
| 版本号 | 在 `manager/build.gradle.kts`（rootProject extra：managerVersionCode/Name），当前 1.0.0(1000000) |

### 2.1 打包签名密钥（★绝不可删）

签名密钥**只存在于 GitHub 仓库 Secrets 中**（本地源码、CI 产物里都没有密钥本体）：

| Secret 名 | 内容 |
|---|---|
| `KEYSTORE` | keystore 文件（.jks）的 base64 编码全文 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

位置：仓库页面 → Settings → Secrets and variables → Actions。

**为什么不能删**：
1. 删了/换了对 App 是灾难——Android 拒绝覆盖安装不同签名的 APK，用户必须卸载重装（丢全部本地数据），且老版本无法再出更新
2. CI（build-luoxi-toolbox.yml）在构建时解码 KEYSTORE → 写入临时 gradle.properties → 签名 APK，并用 keytool+openssl 算出证书 SHA-256 指纹注入 `BuildConfig.EXPECTED_SIGNATURE`
3. App 运行时（CloudUpdateManager）会校验 APK 签名 vs `BuildConfig.EXPECTED_SIGNATURE`，不一致弹"签名无效"强制窗——换密钥 = 自己触发自己的防护

**注意事项**：
- 密钥值只有仓库管理员在 GitHub 网页上能看到一次性设定，API/Agent 均无法读取值（只能列名字），所以**丢了自己也没法导出**，务必在本地妥善保存一份 keystore 文件和三个密码
- 若真丢了：只能重新生成（`keytool -genkeypair -keystore key.jks -alias xxx -keyalg RSA -keysize 4096 -validity 36500 ...`）+ 更新 4 个 Secrets，代价是所有老用户需卸载重装
- PR 构建不走 Secrets（用 7 天临时密钥），只有 push main 的正式构建才用真密钥签名
- 本地沙箱构建因无密钥属性，产物为未签名 APK，属正常现象

---

## 3. 核心架构与原理（★最重要）

### 3.1 命令执行：FileManagerUtils.exec()

所有文件操作**不走 Java File API**（因为游戏目录在 `/storage/emulated/0/Android/data/` 下，Android 11+ 无权限），而是拼 shell 命令执行：

```kotlin
suspend fun exec(cmd: String): String?  // 返回 stdout；无权限/超时返回 null
```

- 权限判定：`PermissionManager.checkGrantType()` → ROOT / ADB(Shizuku) / BOTH / 无
- Root 路径：libsu `ShellUtils.fastCmd(getRootShell(), cmd)`
- Shizuku 路径：bindUserService 到 `ShellService`（shell uid 执行），Binder 会话级缓存 + Mutex 防并发
- **单命令 15s 超时**（EXEC_TIMEOUT_MS），防 shell 通道卡死
- 判成功习惯：`exec(...) != null`；文件计数用 `ls -A dir | wc -l` 并比对 `"0"`
- shell 输出过滤习惯：`.filter { it.isNotEmpty() && !it.startsWith("ls:/cat:") && !it.contains("No such file") }`

### 3.2 中转目录（血泪教训 ★★★）

**Java 层和 shell 层交换文件必须用 app 外部私有目录**：

```kotlin
fun workDir(): File = File(ksuApp.getExternalFilesDir(null), "luoxi_work")
// 即 /storage/emulated/0/Android/data/me.luoxi.toolbox/files/luoxi_work/
```

- ✅ Java 可读写；✅ shell(root/adb) 可读写；✅ 卸载即清
- **绝不能用** `ksuApp.cacheDir`（/data/data/...）——Shizuku 的 adb 权限访问不到，这是历史上"替换/备份全部失败"的根因
- zip 压缩/解压在 Java 层做（对 workDir 直接操作），shell 只负责 mv/rm/ls/cp

### 3.3 业务目录约定

| 常量 | 路径 | 用途 |
|---|---|---|
| LUOXI_DIR | /storage/emulated/0/luoxi | 根 |
| BACKUP_DIR | luoxi/备份 | 自动备份 zip（文件名 `yy.MM.dd HH:mm:ss.zip`，精确到秒防覆盖） |
| OUTPUT_DIR | luoxi/文件输出 | 制作好的成品文件（等待替换） |
| CROP_DIR | luoxi/裁剪 | 裁剪结果 jpg |
| MARK_FILE | /storage/emulated/0/Android/data/.media_cache_index | 伪装系统文件的"游戏文件名记录"（每行一个文件名，只增不减） |
| LOADING_BG_DIR | .../com.tencent.tmgp.pubgmhd/files/UE4Game/.../ImageDownloadV3/LoadingBG | 游戏加载图目录 |

初始化 `ensureInitFiles()` 幂等创建所有目录；首页 LaunchedEffect 里调用。

### 3.4 替换游戏文件流程（replaceGameFiles）

```
文件输出/* → work/out（中转）
  ↓ 若 work/out 为空 → 直接失败（绝不动游戏目录）★
备份分支：
  游戏目录/* → work/bak → Java 压缩 zip → 备份/时间戳.zip
    任一步失败 → 文件原路移回游戏目录
    游戏目录为空 → 失败（不生成空备份）★
    zip 用 stat 确认非 0 字节后才清 work/bak ★
不备份分支：直接 rm -rf 游戏目录（下策）
→ work/out/* mv 到游戏目录
→ mv 失败且有备份 → 自动从备份回滚 ★
```

### 3.5 还原流程（restoreFromFile）

```
备份zip/自定义zip → Java 解压到 work/restore（文件名防路径穿越：replace('/','_')）
→ 解压出 0 个文件 → 直接失败（绝不清游戏目录）★
→ rm 游戏目录 → work/restore/* mv 进游戏目录 → 清中转（zip 原件保留在备份目录）
```

### 3.6 制作文件（makeFiles）

1. `readRecordedNames()` 读 MARK_FILE 得 N 个游戏文件名
2. 用户已选 M 张裁剪好的图（`SelectedImage(uri)`，uri 可能是 SAF uri 或 file:// 中转文件）
3. **均分复制**：每张 `N/M` 份，余数 `N%M` 随机分给其中几张（`indices.shuffled().take(rem)`）
4. 逐个**字节流复制**到 work/make/ 并用游戏文件名命名（不二次压缩，保画质）
5. `publishToOutput()`：清空 OUTPUT_DIR 后把成品 mv 进去

### 3.7 图片选择与裁剪

- 选择：`ActivityResultContracts.PickMultipleVisualMedia()`（系统图片选择器，多选）
  - 注意历史：曾按用户要求在 `OpenMultipleDocuments`（文件选择器）和 Photo Picker 间来回切换，**当前为 Photo Picker**
- 裁剪弹窗（CropDialog，Canvas 自绘）：
  - 图片按原始比例完整显示，画布宽≤300dp 高≤280dp
  - 裁剪框比例固定 = 本机真实分辨率横屏（shortSide/longSide），**始终钳制在图片内**
  - 默认框 = 图内最大等比框居中（`defaultCropBox`）
  - 手势：`detectDragGestures`，按下时判断命中——四角手柄（26dp 抓取半径）=缩放（对角固定、按拖动方向、受图边界限制），框内=整体移动；状态（mode/corner）用 `var` 存在 pointerInput 外层
  - 确认：用 `decodeSampledBitmap(uri, 4096)` 高清重解码，按比例映射裁剪框坐标后 `Bitmap.createBitmap` 裁剪，JPEG 95 存 workDir，返回 `Uri.fromFile` **替换列表原项**，同时 cp 到 CROP_DIR
  - 状态标记：`uri.scheme == "file"` 表示已裁剪

### 3.8 强制更新（Home.kt）

- `CloudUpdateManager.fetchCloudData()` 从云端（GitHub raw）拉公告+版本号，`extractBetween(文本,"[内部版本号]","[内部版本号]")` 解析
- `showForceUpdate = signatureInvalid || (cloudVersion > 0 && cloudVersion > localVersion)`
- **代码层常开**：已删除设置页"检查更新"开关，逻辑不再读任何 pref——用户无法关闭（这是刻意需求，勿"修复"）
- 签名校验：APK 证书 SHA-256 对比 `BuildConfig.EXPECTED_SIGNATURE`（CI 从 keystore 算出注入），失败弹"签名无效"强制窗

---

## 4. UI 约定（miuix + Compose）

- 页面骨架：`Scaffold(topBar=TopAppBar(hazeEffect), popupHost={})` + `LazyColumn(overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).hazeSource(hazeState), contentPadding=innerPadding)`
- 卡片：`Card { Column(Modifier.padding(18.dp)) { ... } }`
- 按钮：`TextButton(text="…", onClick=…, colors=ButtonDefaults.textButtonColorsPrimary())`
- 弹窗：`SuperDialog(show=MutableState<Boolean>, title=, onDismissRequest=, content=)`；确认按钮在 content 内部
- 列表项：`SuperArrow(title=, startAction={Icon(...)}, onClick=)`（带箭头行）、`SuperSwitch`（开关行）
- 图标：`androidx.compose.material.icons.rounded.*`（如 Groups/ContactPage/Update）
- 文案多为**硬编码中文**（本工具单语言）；通用文案走 `R.string`（values + values-zh-rCN 双份同步加）

### ★ 懒列表分项渲染（修过的坑）

文件管理页**必须拆多个 item**，禁止整页塞一个 item：

```kotlin
item(key = "picker") { ImagePickerCard(...) }
itemsIndexed(images, key = { i, _ -> "img$i" }) { i, img -> SelectedImageItem(...) }
item(key = "make") { MakeFilesCard(...) }
item(key = "replace") { ReplaceFilesCard() }
```

原因：单 item 内容增高会触发 LazyColumn 重新锚定，选图后列表自动跳底、"替换游戏文件"卡片被顶出屏。拆分+固定 key 后滚动稳定。

---

## 5. 踩坑实录（AI 必读）

1. **Kotlin 块注释可嵌套**：注释里写 `$work/out/*`、`'$dir'/*` 这类路径通配符，`/*` 会开启嵌套注释导致"unresolved reference / class not found"的诡异编译错误。写注释避免 `/*` 序列（改成"目录内文件"等中文表述）
2. **内部 cache 目录 Shizuku 不可达**：一切 Java↔shell 交换必须走 getExternalFilesDir（见 §3.2）
3. **空备份陷阱**：游戏目录为空也压缩 → 生成 0 文件 zip → 还原时"成功"却清空了游戏目录。修复：备份前查文件数，还原前查解压数
4. **mv 后必须验证**：`mv` 命令即使 exec 非 null 也要用 `ls | wc -l` 验证源目录已空；zip 入库要 `stat -c '%s'` 验证非 0
5. **备份文件名精确到秒**：`HH:mm` 会在同分钟覆盖
6. **Photo Picker vs 文件选择器**：`PickVisualMedia` 只给相册图（部分机型读不到第三方目录），`OpenMultipleDocuments` 可浏览真实目录但返回的 uri 需 `FLAG_GRANT_READ_URI_PERMISSION`（ActivityResultContracts 已自动处理）。当前用 Photo Picker，若用户再报"读不到图"，切回 OpenMultipleDocuments
7. **file:// URI 可直接 openInputStream**：中转产物 `Uri.fromFile(f)` 与 SAF uri 统一处理，contentResolver 都能读
8. **大图解码**：统一 `decodeSampledBitmap(uri, maxDim)`（inSampleSize 降采样），预览 2048/裁剪重解 4096/缩略图 128；用完 recycle 防 OOM
9. **AGP 9 + JDK**：必须 JDK 21（21 以下 javac 报错，25 prefab 崩）；沙箱 OOM 时降 Xmx/并行度，构建完还原 gradle.properties
10. **git 推送**：push 前先 `git pull --rebase origin main`（CI 或他端可能有新提交）；提交信息中文、说明 why
11. **miuix 组件签名**：`TextButton` 的参数是 `text=String`；`Card` 用 `insideMargin` 不是 contentPadding；SuperDialog 第一参是 `show: MutableState<Boolean>`
12. **gradle.properties 里的临时改动勿入库**：代理/Xmx/parallel 调整只应急，提交前 `git checkout gradle.properties`

---

## 6. ProGuard 注意（manager/app/proguard-rules.pro）

release 开启 minify，以下已 keep，**新增类若被反射/Binder/Manifest 引用必须补规则**：

- `KernelSUApplication(+Kt)`、`Natives`（JNI 按名绑定）、`ui.crash.**`
- `PermissionManager` 系列（Manifest/Shizuku 反射）
- `IShellService`/`ShellService`（跨进程 Binder 按类名反序列化，混淆即失效）
- `rikka.shizuku.**`、`moe.shizuku.**`、`org.lsposed.hiddenapibypass.**`
- `BuildConfig`、native 方法类、Parcelable CREATOR、kotlinx.serialization

经验：release 构建成功 ≠ 运行正常，混淆问题只在真机运行时暴露；改了 keep 规则务必真机回归 Shizuku 路径。

---

## 7. 常见任务速查

| 任务 | 位置 |
|---|---|
| 改应用名/包名 | manager/app/build.gradle.kts（defaultManagerName/PackageName）+ CI 的 KSU_PACKAGE_NAME |
| 改版本号 | manager/build.gradle.kts rootProject extra |
| 加设置项 | Settings.kt（prefs 名 "settings"，SharedPreferences） |
| 加关于页链接 | About.kt Card 内 SuperArrow + uriHandler.openUri |
| 改公告/强更文案 | 云端文件（GitHub raw），解析在 CloudUpdateManager.kt |
| 清缓存范围 | FileManagerUtils.clearCacheDirs()（输出+裁剪两目录） |
| 新增 shell 操作 | FileManagerUtils 里 suspend fun + exec()，勿在 UI 层直接拼命令 |

## 8. 当前功能清单（v1.0.0）

- 首页：公告、工作箱版本、**屏幕分辨率**显示、强制更新（不可关）
- 文件管理页：图片多选（Photo Picker）→ 逐张裁剪（真分辨率比例、四角缩放、框内拖动、限图片内）→ 制作文件（按记录文件名均分+余数随机，字节复制，命名即游戏文件名）→ 替换游戏文件（备份/不备份，失败自动回滚）→ 清理缓存（输出+裁剪）
- 功能页：还原备份（备份目录 zip / 自定义 zip，含 SAF 选择后 cp 到中转再解压）
- 设置：常规（无检查更新开关、无发送日志）；关于：Telegram + QQ 群 1/2（qm.qq.com/q/TEuQTWTu48、qm.qq.com/q/9XnN0A6PbW）
