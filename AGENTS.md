# LocalAiNotif For HyperOS Agent 开发指引

本仓库为面向小米 15（HyperOS 4.0.3.0 / Android 17）的 LSPosed 本地端侧通知智能模块。详细架构与历史进度见 `docs/设计.md` 与 `docs/交接.md`。

## 关键工程约束

- **技术栈**：纯 Java 17（`javac --release 17`）+ Android Framework 原生 View API + C++17 JNI（MNN-LLM）。**无 Gradle、无 Kotlin、无 AndroidX、无 Compose、无 Material3 / Miuix**。
- **禁止引用 `R.*`**：`build.sh` 中 `javac` 仅编译 `src/` 下源码，未将 `build/gen` 纳入编译路径。所有 UI 布局、Drawable、图标与样式必须采用纯 Java View / Canvas / Drawable 实现。
- **跨进程配置契约**：`MainActivity` 不得直接读写 `SharedPreferences`；所有配置读写必须通过 `ContentResolver.call(content://io.github.internetbugs.localainotif.provider, ...)` 与独立 `:inference` 进程的 `InferenceProvider` 通信，并在后台线程调用、回主线程前检查 `isFinishing() || isDestroyed()`。
- **修改边界**：改动设置页时不要波及 Xposed Hook（`ModuleMain` / `NotificationSummaries` / `SystemUi*` / `PrioritySectionFrame`）、`InferenceProvider` 或 `AndroidManifest.xml`。
- **签名与构建契约（严禁回退或泄漏密钥）**：
  - `build.sh` 兼容 Windows Git Bash 与 Linux（Ubuntu CI），仅清理 `build/`、保留 `dist/` 既有 APK，支持通过 `DIST_DIR` 指定临时输出目录；修改 `build.sh` 时必须保持 Linux 兼容性。
  - **本地签名**：`build.sh` 默认读取本机外部目录 `D:/onedrive/安卓签名密钥/LocalAiNotif/signing-backup.json`（可通过 `SIGNING_DIR` / `SIGNING_CONFIG` 环境变量覆盖），使用正式密钥 `localai-notif-release.p12`（`CN=LocalAiNotif, O=InternetBugs`，证书 SHA-256 `89:03:4F:48:BD:F1:31:45:5E:DE:17:C3:60:87:29:36:72:95:D3:53:3A:73:FA:23:18:15:85:14:30:81:CE:CC`）+ APK v3 轮换链 `localai-notif-v3.lineage`（链接旧 debug 密钥 `29:BB:0E:...`，保障旧版无缝覆盖升级）。缺失配置时直接报错退出，**严禁**回退到 `~/.android/debug.keystore` 或静默新建密钥，**严禁**将任何密钥、口令或 `signing-backup.json` 提交入库或打印到日志/文档。
  - **手动发版（GitHub Actions）**：由 `.github/workflows/release.yml`（仅 `workflow_dispatch` 手动触发，禁止改为自动发版）负责云端构建与发布；签名 Secrets（`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 及 v3 轮换项 `SIGNING_LINEAGE_BASE64`、`LEGACY_KEYSTORE_BASE64`、`LEGACY_KEY_ALIAS`、`LEGACY_KEY_PASSWORD`）已配置在仓库 GitHub Actions Secrets 中。

---

## 设置页面小米 UI（HyperOS）设计规范（必须沿用）

设置页已完成小米澎湃 OS（HyperOS）风格重构并经小米 15 实机验收。后续扩展 M4（减少干扰）、M6（完整配置页）或任何新增子页/弹窗/开关时，**必须直接复用并严格沿用** `src/io/github/internetbugs/localainotif/HyperSettingsUi.java` 与 `src/io/github/internetbugs/localainotif/MainActivity.java` 确立的视觉语言，禁止回退到原生 `android.widget.Switch`、Material 圆形 `RippleDrawable`、投影卡片或居中堆叠布局。

### 1. 深浅色语义色板（`HyperSettingsUi.Palette`）

| Token | 浅色模式（Light） | 深色模式（Dark / OLED） | 用途 |
|---|---|---|---|
| `background` | `#F7F7F7` | `#000000` | 页面画布底色、吸顶栏背景 |
| `cardBackground` | `#FFFFFF` | `#1F1F1F` | 分组/功能卡片表面（扁平无阴影） |
| `cardPressed` | `#EDEDED` | `#2B2B2B` | 卡片按压与焦点态柔和整块高亮 |
| `accent` | `#3482FF` | `#277AF7` | HyperOS 系统蓝（开关开启态、主强调色） |
| `switchOffTrack` | `#E0E0E0` | `#3E3E3E` | 开关关闭态滑轨 |
| `switchThumb` | `#FFFFFF` | `#FFFFFF` | 开关圆形滑块 |
| `textPrimary` | `#E6000000` | `#E6FFFFFF` | 大标题、选项标题、吸顶标题（深色抗光晕） |
| `textSecondary` | `#8C000000` | `#8CFFFFFF` | 选项副标题、分组小标题、底部说明文字 |
| `textTertiary` | `#66000000` | `#66FFFFFF` | 次级辅助信息/图标 |
| `divider` | `#14000000` | `#1AFFFFFF` | 吸顶栏底部分割线、同卡多项内部分割线 |
| 超级小爱流光锚点（默认光效风格） | 暖橙 `#F26D1F` / 玫粉 `#E52B7A` / 紫罗兰 `#8C46F2` / 钴蓝 `#1E78F5` | 暖橙 `#FF9238` / 玫粉 `#FF408A` / 紫罗兰 `#B86BFF` / 电光蓝 `#3694FF` | 默认光效，复刻澎湃超级小爱超级岛霓虹流光；覆盖重要通知、AI 摘要、AI 概括、减少干扰超级岛与全部 5 组设置示意图 |
| 星云极光锚点（可选光效风格） | 天青 `#1CA3EC` / 晴空 `#2B75F0` / 冰紫 `#7458F2` / 霜白微胶囊 | 天青 `#5CE1FF` / 晴空 `#3D8BFF` / 冰紫 `#A17DFF` / 烟熏微胶囊 | 经典冷色调液态玻璃风格，可在「其他 → 光效风格」实时热切换 |

### 2. 版式、对齐与超椭圆几何

- **统一 `28dp` 左侧排版基准线**：全页严格左对齐。大标题、吸顶小标题、分组小标题、卡片内文字（`12dp` 卡片外边距 + `16dp` 卡片内边距 = `28dp`）、底部说明文字的左边缘均对齐在 `28dp` 同一纵轴上。
- **连续曲率超椭圆卡片（`HyperSettingsUi.SquircleDrawable`）**：
  - 卡片统一圆角 `18dp`（采用 `buildSquirclePath` 连续曲率三次贝塞尔近似：`d = min(r * 1.20, min(w, h) * 0.5)`, `k = 0.30`），禁止使用普通圆弧 `GradientDrawable` 或模糊灰阴影。
  - 卡片水平外边距 `12dp`，相邻卡片/分组间距 `12dp`，卡片内边距 `16dp`，单行带副标题最小高度 `72dp`。
- **响应式限宽**：横屏、平板或自由窗口下，滚动内容区与吸顶标题槽同步限宽 `560dp` 并水平居中，确保宽屏下左右基准线保持对齐。

### 3. 字体层级（MiSans 原生可变字重）

通过 `HyperSettingsUi.typeface(weight)`（`Typeface.create(Typeface.DEFAULT, weight, false)`）在 HyperOS 上自动调用系统 MiSans 可变字重：

- **页面大标题（展开态）**：`32sp`，`weight = 500`，`letterSpacing = -0.01f`，`includeFontPadding = false`；竖屏顶距 `44dp`（带返回按钮子页 `66dp`），横屏顶距 `16dp`（带返回按钮 `60dp`），底距 `20dp`。
- **浮动吸顶折叠顶栏（HyperOS 4 样式）**：`56dp` 顶栏 + 底部 `24dp` 柔和纵向渐隐遮罩（`TopBarScrimDrawable`，使用画布底色平滑过渡至透明，**不使用生硬不透明底栏或 `0.5dp` 顶栏分割线**）；带返回按钮时左上角放置 `40dp` 圆形浮起玻璃 `<` 返回胶囊（`BackArrowView`，带柔和弥散阴影与细边、左缘对齐 `12dp` 卡片边）；折叠标题采用 `17.5sp`、`weight = 500` **水平居中**排布，未滚动时保持 `INVISIBLE` 且 `alpha = 0`，滚动经过大标题区间时带轻微上浮与大标题交叉淡入淡出。
- **分组小标题（`SmallTitle`，多分组扩展时使用）**：`13sp`，`weight = 500`，`textSecondary`，置于卡片**外部上方**（左距 `28dp`，顶距 `16dp`，底距 `8dp`）。
- **选项主标题**：`16.5sp`，`weight = 500`，`textPrimary`，`includeFontPadding = false`。
- **选项副标题 / 说明**：`13.5sp`，`weight = 400`，`textSecondary`，上距 `5dp`，行距 `+3dp`。
- **底部说明（Footer）**：`13sp`，`weight = 400`，`textSecondary`，行距 `+3dp`，直接置于卡片下方（左/右 `28dp`，顶距 `14dp`），不额外套无意义的卡片外壳。
- **示意图内微缩文字**：使用 `dpF(...)` 而非 `sp`，防止系统超大字体设置撑破固定比例示意图。

### 4. 核心组件与交互动效

- **HyperOS 胶囊开关（`HyperSettingsUi.HyperSwitchView`）**：
  - 规格：`50dp × 28dp` 胶囊滑轨 + `22dp` 纯白圆形滑块（`3dp` 边距 + 纯硬件绘制微阴影），按压态滑块水平拉伸 `2.5dp`，切换动画 `220ms`（`PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)`）。
  - 交互模式：开关设置 `setDuplicateParentStateEnabled(true)` 与 `IMPORTANT_FOR_ACCESSIBILITY_NO`，由外层整行卡片统一处理点击、按压态高亮、`HapticFeedbackConstants.CLOCK_TICK` 触感反馈，并通过 `AccessibilityDelegate` 向无障碍服务暴露 `android.widget.Switch` 与选中状态。
  - 防闪烁并发保护：IPC 期间用 `mBusy` + `setClickable(false)` 防重入，不临时降低开关透明度；已在目标方向运行中的动画不被极速返回的 IPC 打断重启；保存失败时再平滑回滚并弹出 `Toast`。
  - 尊重无障碍减弱动效：所有动画均检测 `ValueAnimator.areAnimatorsEnabled()`，并在 `onDetachedFromWindow()` 中及时取消。
- **卡片按压反馈**：使用 `HyperSettingsUi.createCardBackground(..., interactive = true)` 生成 `StateListDrawable`（`40ms` 淡入 / `120ms` 淡出，支持 `state_pressed` 与 `state_focused`），不要使用 Material 圆形扩散水波纹。
- **功能示意图卡片（`SummaryPreviewView` / `RecapPreviewView` / `PriorityPreviewView` / `PromotionPreviewView` / `ReducePreviewView`）**：统一收归 `HyperSettingsUi` 内静态类，包裹在 `padding(16, 16, 16, 4)` 的 `FrameLayout` 中并限宽 `320dp` 居中绘 `14dp` 超椭圆画布（`previewStageBg` + `1dp` 细边）；使用超椭圆骨架卡片（图标超椭圆 + 圆角骨架条，不写假通知正文）与 `280ms` `HYPER_EASE` 开关实时联动动效。

### 5. 窗口主题与 Edge-to-Edge 规范

- 在 `MainActivity.onCreate()` 调用 `super.onCreate()` **之前**，根据 `Configuration.UI_MODE_NIGHT_MASK` 动态设置 `Theme_DeviceDefault_NoActionBar` 或 `Theme_DeviceDefault_Light_NoActionBar`，避免系统 ActionBar 闪烁并保证系统 `Toast`/对话框自动匹配深浅色。
- 全量启用 Edge-to-Edge：透明状态栏与导航栏、`setNavigationBarContrastEnforced(false)`；经 `WindowInsetsController` 按深浅色切换 `APPEARANCE_LIGHT_STATUS_BARS | APPEARANCE_LIGHT_NAVIGATION_BARS`；根容器通过 `OnApplyWindowInsetsListener` 读取 `WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()` 为滚动区与吸顶栏设置安全区 padding。

### 6. 后续扩展多配置项的组合约定（M4 / M6）

- **同一分组多项设置**：放进同一张 `18dp` 超椭圆卡片的竖向 `LinearLayout` 中，每行内部按压区域各自响应（首行顶圆角/末行底圆角或对卡片整体裁剪超椭圆轮廓），项间使用 `0.5dp` 高度的 `palette.divider` 分割线并左右各缩进 `16dp`。
- **多个功能分组**：每组上方放外置 `SmallTitle`（`28dp` 左对齐），卡片间保持 `12dp` 垂直间距；子页跳转行右侧配简洁细线右箭头（`textTertiary`），副标题或当前值保持简短直白。
- **文案克制**：禁止堆砌英文全大写眉标、无意义序号或营销式长句；沿用简短、直白的中文系统设置措辞。
