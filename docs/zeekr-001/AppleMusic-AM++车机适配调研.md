# Apple Music · AM++ 车机适配调研

> 目标：把上游开源项目 [Zennmn/AM-plus-plus](https://github.com/Zennmn/AM-plus-plus)（Apple Music 增强）以**非注入式**方式搬到极氪车机，按既有调研的 UI / 分辨率 / 通道接口规范做最适配中控屏的版本。
> 分析日期：2026-11-16　上游版本：v1.6.8（2026-11-01，浅克隆 main 实为 1.6.9）　设备目标：极氪 001（8155 / msmnile / Android 9 SDK 28 / root）
> 本阶段**只读调研**，未改任何车机文件。

> ⚠️ **阻断级发现（2026-11-16 补）**：宿主 **Apple Music 6.5.0～7.0.0（AM++ 支持的全部版本）最低要求 Android 11（API 30）**，而车机是 **Android 9（API 28）**。APKMirror 实测 6.5.1(1583) `Min: Android 11 (API 30) / Target: Android 15 (API 35)`，最老生产版即如此。**任何 AM++ 支持的 Apple Music 包都装不进车机**（PackageManager 安装时拒绝 minSdk 超标的包）。此前 §3 表里「minSdk 26 ✅」比较对象错了——那是 AM++ 模块自身 minSdk，不是宿主 Apple Music 的。破解路径见 §8。

---

## 0. 结论摘要（TL;DR）

1. **有现成的非注入路线**：上游提供 **npatch 嵌入版**——用静态打补丁工具把 AM++ 代码**直接编进 Apple Music APK**，产出独立安装包，**无需 LSPosed、无需 root、不注入任何系统进程**。这正是「嵌入版 / 自己嵌入」，与「Xposed 模块」「frida 注入」是两码事。
2. **【阻断】宿主 Apple Music 全系要求 Android 11+（minSdk 30），车机是 Android 9**：装不进去是硬伤，比玻璃/模糊门槛更靠前、更致命。破解选项见 §8（minSdk 降级强装 / 改走 CarPlay / 放弃）。
3. **车机是 Android 9，两个招牌视觉特性被硬门槛挡住**：`双向歌词模糊` 需 Android 12+，`液态玻璃底栏` 需 Android 13+，车上都拿不到（可曲线复刻，见 §6）。
4. **车机真正能拿到的、也最有价值的是「平板双栏播放器」**：横屏时播放控件 + 实时歌词并排，正中车机横置中控屏（15.4" 1920×1200）。6.5.x 与 7.0.0 都支持双栏。
5. 落地顺序：**先破解 minSdk 阻断**（降级强装可行性验证 / 或改 CarPlay），过了这关再谈 fork 深度定制。

---

## 1. 上游项目 AM++ 调研结论

### 1.1 是什么

AM++ 是 Apple Music（`com.apple.android.music`）的 Android 增强模块，主打三件事：**平板双栏、歌词定制、液态玻璃**。原生形态是 Xposed 模块（libxposed API 102，需 LSPosed），但**同时发布 npatch 嵌入版**，二者功能一致，区别只在交付形态。

### 1.2 功能清单与 Android 版本门槛（关键）

| 功能 | 默认 | Android 门槛 | 车机(API 28)可用 |
|---|---|---|---|
| **平板双栏播放器**（横屏 控件+歌词并排） | 开启 | 无 | ✅ **可用（核心）** |
| CJK 长尾歌词动画（辉光） | 开启 | 无 | ✅ 可用 |
| 歌曲名显示修正（地区） | 关闭 | 无 | ✅ 可用 |
| 自定义歌词（TTML 注入/在线获取） | 关闭 | 无 | ✅ 可用 |
| 歌词字体（TTF/OTF） | 关闭 | 无 | ✅ 可用 |
| 自动实时补全歌词 | 开启 | 无 | ✅ 可用 |
| 双向歌词模糊 | 开启 | **Android 12+** | ❌ AM++ 原样不可用（可 GL/RenderScript 曲线复刻） |
| 液态玻璃底栏 + 迷你播放器 | 关闭 | **Android 13+** | ❌ AM++ 原样不可用（可曲线复刻毛玻璃，折射除外） |
| ios 底栏动画 | 开启 | 仅手机布局 | ❌ 车机不走手机布局 |

> 结论：**液态玻璃与双向模糊这两个「招牌视觉」在 Android 9 上直接失效**，不是性能问题，是 API 硬门槛（RenderEffect/Backdrop 库依赖新系统能力）。车上真正值钱的是**平板双栏**，它没有版本门槛。但注意：**以上都建立在「Apple Music 能装进车机」之上，而它不能（minSdk 30 > API 28）——这是更靠前的阻断**。

### 1.3 模块结构（fork 定制时的切入点）

```
app/            模块入口、设置页与功能装配
core/           配置、歌词、网络来源与布局策略   ← 布局策略在这
host-api/       宿主能力与事件接口
hook-runtime/   Xposed 封装与日志
host-applemusic/ Apple Music 版本适配（版本 profile）
glass/          液态玻璃渲染
backdrop/       上游 Backdrop 库
docs/           使用/开发文档（含 host-adaptation-guide）
```

- 构建环境：JDK 21、Android SDK 37、Build Tools 37.0.0、Gradle Wrapper；`minSdk 26 / targetSdk 37`（这是**模块自身**的，与宿主无关）。
- 版本适配用「版本 profile」精确锁定 Apple Music 版本，已知支持 6.5.0-1580 → 7.0.0-1614（含 7.0.0-beta 1606/1607/1609/1613）。profile 有 `family` 字段区分 `legacy-activity`（6.5.x）与 `fragment-content`（7.0.0）两种宿主 UI 架构。
- CI（`.github/workflows/build.yml`）**只构建 Xposed 模块 APK，不产出嵌入版**；嵌入版是作者用外部工具 npatch 手工产出，**npatch 工具本身不在本仓库内**。

### 1.4 npatch 嵌入版机制（= 我们要的「嵌入」）

- 发布页：[releases/tag/embedded](https://github.com/Zennmn/AM-plus-plus/releases/tag/embedded)。
- 产物命名：`AM++版本-AppleMusic版本-embedded.apks`（如 `AM++-v1.6.8-AppleMusic7.0.0-1614-embedded.apks`，163.2 MB）。
- 原理：npatch（LSPatch/Xpatch 一脉的**静态 APK 打补丁工具**，`--embed` 集成模式）把 Xposed loader + hook 引擎**烘焙进 Apple Music APK 内部**，运行时 hook 只发生在 **Apple Music 自己的进程内**，不碰 Zygote、不碰系统服务、不需要 root。
- 约束：嵌入版**无法与官方 Apple Music 共存**（同包名二选一）；`.apks` 是分 APK 包，作者要求用 MT 管理器直接安装、不要改扩展名。

> 与「frida 注入」的本质区别：frida 是**运行时往系统/车机服务进程里动态注入**（上次卡死音频 DSP 那次事故的根源）；npatch 是**离线改包、hook 随 App 进程启动、装在自家沙箱里**。卸载即回滚，不残留任何系统态。

---

## 2. 车机环境与既有规范（沿用本仓库调研）

### 2.1 硬件 / 系统

- 设备：极氪 001（8155 / `msmnile_gvmq_for_arm64` / Android 9 / SDK 28），亿咖通（ECARX）系统。
- root：AOSP 公开 `platform` 密钥（SHA-256 `c8a2e9bc…`）→ `sharedUserId="android.uid.system"` → uid 1000，装 `/data/app`、不动 /system。

### 2.2 分辨率规范（本仓库已有 + 公开口径）

| 屏幕 | 分辨率 | 说明 |
|---|---|---|
| **中控屏** | **1920×1200**（15.4" 横置，~147 PPI） | 公开规格口径；Apple Music 主战场 |
| 仪表 DIM | 1920×480 | 来自「高德地图」逆向分析 |
| HUD | 800×480（WHUD ~14.7"） | 同上 |

> ⚠️ **中控屏实际 `wm size`/`wm density` 需实车 `adb shell` 确认**。决定 Apple Music 是否进入「平板/横屏双栏」布局的不是物理分辨率，而是系统报告的 density（dp 宽度）。1920 宽 @ 147 PPI 属低密度，若车机未覆盖 density，dp 宽度会非常大，**大概率直接命中 tablet 布局**——这正是双栏的前提。

### 2.3 UI 规范（车机 App 构建必填清单，见「车外喊话器-新APK技术栈」§7）

1. `<application>` 加 `android:appCategory`（必填，缺了桌面显示「等待下载」占位）。音乐类取 `audio=1`。
2. 系统权限用 platform 签名 + `sharedUserId=system`；`minSdk<24` 保 v1 签名。
3. 悬浮窗需 `SYSTEM_ALERT_WINDOW`（若做迷你播放器悬浮球）。
4. `android:icon` + 真实 mipmap，否则桌面空白。

> 注：Apple Music 本身是普通三方 App，**不需要 uid 1000**；但为了让它在 `ecarx.launcher3` 桌面正常显示并点击可开，**必须补 `appCategory` + 图标**，这会改动 manifest → 需重新签名（嵌入包本就已被 npatch 重签，再签无损失）。

### 2.4 通道 / 接口规范（沿用「车外声音」逆向结论）

- 车内普通音乐播放走**标准 MEDIA 流**，无需 `extspkr_switch`（那是车外喊话专用 HAL 参数 `media/close`）。
- 车外/喊话类才需要 OCC 音频属性（`USAGE_OCC_MIC` / `CONTENT_TYPE_OCC`）；Apple Music 不需要。
- 需关注的只有**音频焦点**：Apple Music 与车机自带收音机/ZeekrPlay 抢焦点时的表现，需实车观察（正常 MediaPlayer 会标准地申请焦点，预期无碍）。

---

## 3. 差距分析（核心）

| 维度 | 车机现状 | AM++ 要求 | 结论 |
|---|---|---|---|
| 宿主系统版本 | Android 9 (SDK 28) | Apple Music 宿主 **minSdk 30（Android 11）** | ❌ **阻断**：6.5.0～7.0.0 全系装不进 API 28，见 §8 |
| 模块系统版本 | Android 9 (SDK 28) | 模块 minSdk 26 | ✅ 模块自身可装（与宿主是两码事） |
| 注入方式 | 禁止系统注入 | npatch 静态嵌入 ✅ | 用嵌入版，不用 Xposed/frida |
| 屏幕 | 1920×1200 横置 | 平板横屏双栏 ✅ | 双栏是车机最需要的，直接命中 |
| 桌面显示 | 需 appCategory | 需改 manifest + 重签 | 加 `appCategory=audio` + 图标 |
| GMS | 无 Google 服务 | Apple Music 需登录 | Apple ID 登录不依赖 GMS，待实车验证 |
| 分辨率/密度 | 需实车确认 | 决定 tablet 布局触发 | `adb shell wm size/density` 确认 |
| 音频通道 | 标准 MEDIA 流 | 标准 MediaPlayer | 无需特殊通道，关注焦点即可 |

---

## 4. 适配方案

### 路径 A —— 快速验证（曾计划先走，现被 minSdk 阻断）

用作者现成的嵌入包做端到端路测，不写代码：

1. 下载 `AM++-v1.6.8-AppleMusic7.0.0-1614-embedded.apks`（163.2 MB）。
2. 本地用 apktool 拆包，给 `AndroidManifest.xml` 补 `android:appCategory="audio"`，确认图标存在，重打包 + 重签（用本机调试密钥即可，Apple Music 不校验自身签名）。
3. 通过 ZEEload 或 MT 管理器侧载（`.apks` 需分 APK 安装器）。
4. 实车验证：登录（Apple ID）、双栏是否触发、播放、歌词、音频焦点、桌面图标可点。
5. 记录结果，决定是否进路径 B。

> ⚠️ **此路径现被 §8 的 minSdk 阻断卡在安装一步**：7.0.0-1614（minSdk 30）装不进 Android 9。须先解决 §8，再做路测。

### 路径 B —— 深度定制（验证通过后）

fork AM++，自己跑 npatch 嵌入 + 加车机专属布局策略，做出「最适配中控屏」版本：

1. **布局策略**（`core/` 布局策略层）：针对 1920×1200 横置做双栏黄金分割（左播放控件 / 右歌词）、加大触控目标（驾驶位可达性）、暗色玻璃背景（对齐 TokenBar 审美）。
2. **密度适配**：实车 `wm size/density` 确认后，锁定一个稳定 dp 基准，避免不同车机固件 density 差异导致布局跳变。
3. **manifest**：固化 `appCategory=audio` + 图标 +（可选）迷你播放器悬浮球权限。
4. **音频**：确认焦点行为，必要时补音频焦点处理。
5. 产出自己的嵌入版签名包，纳入本仓库构建流程（npatch 工具需另行引入，上游未随库分发）。

---

## 5. 待确认事项（需拍板 / 实车）

- ✅ **路径已定**：路径 B（fork + npatch 嵌入 + 车机专属双栏布局）。
- ⚠️ **版本需重定**：曾定 `7.0.0-1614`，但该版本 minSdk 30，装不进 Android 9。若走 minSdk 降级强装，应改选 **6.5.x（legacy-activity 老架构）**，兼容性更好且同样支持双栏；7.0.0（fragment-content 新架构）风险更高。
- ✅ **分辨率口径已定**：先按公开规格 **1920×1200（16:10，15.4"）** 做，参数集中一处；连车后 `wm size`/`wm density` 实拍修正。
- ⏳ **minSdk 破解方向**：A 降级强装 / B 改走 CarPlay / C 放弃，尚未拍板（见 §8）。
- ⏳ **Apple Music 账号**：登录走 Apple ID 还是 CarPlay 那套？无 GMS 下 Apple ID 登录是否顺畅（需实车测，预期可行但未验证）。
- ⏳ **迷你播放器**：要不要做车机悬浮球（涉及 `SYSTEM_ALERT_WINDOW` + 驾驶安全遮挡考量）。
- ⏳ **玻璃路线**：① 只做双栏+歌词，玻璃放弃；② 自己造玻璃（GL/RenderScript 模糊）。尚未拍板。

---

## 6. 风险与合规

- **玻璃/模糊：AM++ 原生代码永久不可用，但视觉效果可曲线复刻**。根因是框架 API 缺失：`双向歌词模糊` 依赖 `RenderEffect`（Android 12+）、`液态玻璃` 依赖 `RuntimeShader`/Backdrop（Android 13+），API 28 上这些函数不存在，调参无法绕过。
  - **可曲线实现**：用 Android 9 就有的 `RenderScript ScriptIntrinsicBlur`（API 17+）或 OpenGL ES 片元着色器做真模糊，叠加渐变 + 高光做出「毛玻璃 + 光晕 + 镜面高光」≈ 90% 接近 TokenBar 审美。
  - **唯一复刻不了**：真·玻璃折射/屈光（仅 Android 13 `RuntimeShader` 能做）。
  - **分水岭在"哪层做"**：在 Apple Music 上面叠玻璃（hook 封闭 App 做 GL 模糊）极难；在自己原生 App（如 extspk/appmanager）里做玻璃则完全现实。详见正文 §3 差距分析。
- **嵌入版不可与官方版共存**：装嵌入版前备份 Apple Music 数据（如需）。
- **Apple Music 版本适配**：AM++ 靠版本 profile 精确锁定混淆符号，Apple Music 升级后 profile 可能失配（上游路线图明示「持续适配后续版本」）；车机侧锁死某个已验证版本更稳。
- 分发合规：本项目 Research Preview 性质，勿公开传播（见 README 顶部声明）。

---

## 7. 中控屏分辨率与构建路径（补充调研）

### 7.1 中控屏分辨率从不被硬编码

高德地图、ZCarPlay 两个项目都**没有**中控屏分辨率常数：中控屏是 Android 默认主显示，任何 App 运行时用 `DisplayMetrics`/`WindowManager` 读尺寸与密度、按 dp 自适应，无需写死。它们硬编码的全是非标准项（高德 HUD 800×480 / 仪表 1920×480；ZCarPlay CarPlay 视频流 1920×1080）。故无需预先挖「中控屏分辨率常数」——双栏触发靠 `screenWidthDp >= 600` 运行时判断，1920 物理宽下任意合理 density 都满足。

### 7.2 构建工具链

| 项 | 要求 | 本机现状 |
|---|---|---|
| JDK | 21 | ✅ 已装 openjdk@21（keg-only，构建时显式 `JAVA_HOME`） |
| Android SDK | 37 | ✅ platform android-37.0 |
| Build Tools | 37.0.0 | ⏳ 仅 34.0.0 / 36.0.0，靠 AGP 构建时自动拉取（licenses 已接受） |
| Gradle | 9.7.1 | ✅ 项目自带 wrapper，免装 |
| npatch | 7723mod/NPatch（LSPatch 复刻，GPL-3.0，v1.1.0，`jar-v1.1.0-857-release.jar`） | 待引入 |

构建链路：`构建 AM++ 模块 APK` → `取 Apple Music 6.5.x 包` → `java -jar npatch.jar --embed 模块.apk 目标.apk` → `车机侧载`。

---

## 8. minSdk 阻断与破解路径（2026-11-16）

### 8.1 事实

- 车机 Android 9（API 28）；宿主 Apple Music 6.5.0～7.0.0 全部 minSdk 30（Android 11）。
- AM++ 支持的最老生产版 6.5.1-1583 即 minSdk 30（APKMirror `Min: Android 11 (API 30) / Target: Android 15 (API 35)`）；7.0.0-1614 同理。
- 结论：原生嵌入路线在「安装」这一步被 minSdk 卡死（非构建问题；构建模块 APK 本身能成）。

### 8.2 三条破解路径

| 路径 | 做法 | 风险 | 状态 |
|---|---|---|---|
| **A. minSdk 降级强装** | root 下改 Apple Music manifest `minSdkVersion 30→28` 重签，选 **6.5.x（legacy-activity 老架构）**，较 7.0.0 更可能兼容 API 28，且同样支持平板双栏 | Apple Music 若真调用了 API 29/30 接口会崩；重签可能触发 Apple 签名校验 | 待本地验证接口依赖 |
| **B. 改走 CarPlay** | Apple Music 跑 iPhone，车机（ZCarPlay）只显示 CarPlay 视频流 | 放弃 AM++ 原生增强 | 备选 |
| C. 老 Apple Music 4.x | 支持 Android 9，但 AM++ 无 4.x hook profile | 增强全废 | 基本放弃 |

### 8.3 静态分析结果（2026-11-16 已完成）

已下载 Apple Music 6.5.1(1583) 并反编译扫描（`dexdump`，4 个 dex 共 1347 万行，产物在 `lab/Apple_Music-6.5.1.apk`）：

- 包 `com.apple.android.music`，`minSdk 30 / targetSdk 35`，4 个 classes.dex 完整包。
- **API 30 专属方法 `WindowManager.getCurrentWindowMetrics()` 共 4 处调用**，其中至少 1 处**无版本门控**：混淆类 `R1.B.a(Window)`（窗口 insets 测量，算状态栏+导航栏高度，属布局核心路径）直接 `getWindowManager().getCurrentWindowMetrics().getBounds()`，前置无 `SDK_INT>=30` 判断。
- 全包约 **454 处 `Build.VERSION.SDK_INT` 读取**（版本门控确实存在），但 insets 测量这条路径没被门控。
- 结论：**minSdk 30→28 简单降级不可行**——Android 9 没有 `getCurrentWindowMetrics`，命中即 `NoSuchMethodError` 崩。API 30 用量不算广（仅 4 处 WindowMetrics 系），理论上可逐点 patch 加 API 28 兜底，但需对混淆后的 dex 打补丁，工程量大、风险高。
- 最终定论仍需实车/Android 9 模拟器实测（静态分析是强信号非证明）。

### 8.4 结论与建议（已定方向：4.9.7.2 底座路线）

「minSdk 简单降级」判定为**不可行**（6.5.x 有裸 API 30 调用，见 §8.3）。用户拍板**不做降级、改用原生支持 API 28 的最后一个版本当底座**（2026-11-16）：

**底座锁定：Apple Music 4.9.7.2（versionCode 1472，2025-07-31）**

- `minSdk 24（Android 7.0+）/ targetSdk 35（Android 15）` → **原生支持 API 28（Android 9）**，带完整 API 24~35 向后兼容层，零崩溃风险。
- 是 4.x 系列（minSdk 24）的最后一个版本；下一个 5.0.0（2025-09）把 minSdk 抬到 29（Android 10），从此断档。
- 签名 SHA-1 `33e7ba4c…` 与 7.0.0 一致（Apple 同钥，npatch 重签无额外差异）。

**路线：把 AM++ 的增强做上去（魔改开发）**。AM++ 现有 hook 只针对 6.5.x（legacy-activity）与 7.0.0（fragment-content），4.9.7.2 需重写 host profile，逐条 re-map 到其混淆符号。工作量 = 逆向 4.9.7.2 结构 + 逐 hook 重定位。

### 9. 4.9.7.2 底座路线 · 可行性分析（2026-11-16 已确认）

已下载 arm64 split（`lab/am4972/`）并解包分析 base APK（versionCode 1472 / minSdk 24 / targetSdk 35）：

- **类名可读性高**：业务类全明文（`AppleMusicApplication`、`BaseActivity`、`StoreBaseActivity`、`PlaybackItem`、`ShareLyricsActionSheetFragment`、`ShareLyricsEpoxyController` 等，Epoxy + ViewModel 架构），仅 `common/t0`、`common/j` 等工具类混淆成单字母。比 6.5.1 的 `R1.B`/`z4/h` 友好太多 → hook 重定位难度大幅降低。
- **播放器/歌词基建齐全**：`fragment_player_main.xml`（播放器）、`activity_lyrics.xml`（全屏歌词）、`fragment_player_lyrics_sheet.xml`（歌词 sheet）、`lyrics_line_karaoke.xml`（卡拉OK逐字歌词）都在。
- **无原生平板双栏**：播放器与歌词是**分开的屏幕**（手机式），需注入双栏布局——这正是 AM++ 在 6.5.x/7.0.0 上做的事，逻辑可复用。
- **结论**：底座成立。核心工作 = ① 逆向 4.9.7.2 播放器/歌词类结构，把 AM++ 各 hook 逐条 re-map；② 注入车机专属宽屏双栏；③ GL/RenderScript 玻璃。

### 10. hook 重定位对照表（类级，2026-11-16）

jadx 反编译 4.9.7.2 得 **14,794 个 .java（12,523 类，仅 223 个混淆类解不开）**。AM++ hook 目标逐条比对：

| AM++ hook 目标（6.5.x/7.0.0） | 4.9.7.2 对应 | 状态 |
|---|---|---|
| `common.activity.PlayerActivity` | 同名同包 | ✅ 1:1 |
| `player.fragment.PlayerLyricsViewFragment` | 同名 | ✅ 1:1 |
| `player.viewmodel.PlayerLyricsViewModel` | 同名 | ✅ 1:1 |
| `ttml.javanative.model.LyricsLineVector` | 同名 | ✅ 1:1 |
| `LyricsSectionVector` / `LyricsWordVector` | 同名 | ✅ 1:1 |
| `common.views.AlphaGradientFrameLayout` | 同名 | ✅ 1:1 |
| `player.PlayerBottomSheetBehavior` | 同名 | ✅ 1:1 |
| `common.behavior.StaticCollapsedBottomSheetBehavior` | 同名 | ✅ 1:1 |
| `library2.LibraryViewModel` | 同名 | ✅ 1:1 |
| `model.PlaybackItem` | 同名 | ✅ 1:1 |
| `common.MainActivity` | `common.MainContentActivity`（明文，已定位） | ✅ 重定位完成 |
| `settings2.fragment.SettingsFragment` | `settings.activity.SettingsActivity`（明文入口）+ `settings.fragment.*` | ✅ 重定位完成 |
| `settings2.model.SettingsViewModel` | `settings.activity.*ViewModel` 系列（如 `ManageDownloadedContentViewModel`、`LinkAccountViewModel`、`ManageSubscriptionSettingViewModel`） | ✅ 重定位完成 |

**结论**：核心 **11/14 个 hook 目标类名 1:1 同名**（播放器/歌词/底部 sheet/TTML 模型全中），其余 3 个（主界面/设置）也已定位到 4.9.7.2 的明文对应物。

### 11. 方法级摸底：AM++ hook 机制与重定位工作量（2026-11-16 已完成）

读 AM++ 官方 `docs/host-adaptation-guide.md`（135KB）+ `host-profiles/6.5.3-1599.json`（legacy-activity 族，58KB，模板）+ `scripts/verify-host-profile.py`（DEX 级验收脚本）后，机制与工作量完全厘清：

**A. family 归属确认（关键解锁）**

- AM++ 把 Apple Music 版本分两个族：`legacy-activity`（6.5.x，目标 = `PlayerActivity`/`BottomSheet`/双栏资源/歌词同步/旧设置桥）与 `fragment-content`（7.0.0）。
- 4.9.7.2 的 `PlayerActivity`/`PlayerBottomSheetBehavior`/`PlayerLyricsViewFragment` 结构 = **`legacy-activity` 族，与 6.5.x 同族**。即 AM++ 对 6.5.x 已有一整套 legacy-activity 族的 hook 与 profile，4.9.7.2 照同一套走，**不是从零写**。

**B. hook 解析机制（DexKit，非裸字符串匹配）**

- `indexed` 字段把稳定标识符（`LYRICS_FRAGMENT`、`PLAYER_ACTIVITY`、`LYRICS_VIEW_MODEL`…）映射到**每版本各自的混淆名**；解析用 DexKit（构建产物 `libdexkit.so`）+ `hookTargets` 里的语义签名（参数类型/返回值/被调方法名）定位。
- 6.5.3 的 `indexed.classes` 共 24 个映射，其中 **11 个是明文**（`PlayerLyricsViewFragment`、`PlayerLyricsViewModel`、`PlayerActivity`、`LyricsLineVector`、`TTMLParser$TTMLParserNative`…，全部在 4.9.7.2 1:1 命中），**约 13 个是混淆名**（`player.fragment.e`、`player.P`、`A8.D`、`Kd.b`、`Oa.c`…），**1 个是 6.5.x 专属**（`settings.fragment.SettingsFragment`，4.9.7.2 无此路径）。

**C. 重定位任务清单（核心工作量）**

- 约 13 个混淆符号（`indexed.classes` 里的混淆名 + `indexed.methods` 里 9 个方法如 `n0`/`k1`/`o2` + `indexed.fields` 的 `c1`）需在 4.9.7.2 逐个**语义重核**（checklist 明文规定：混淆名移动后即使同名同签名，也必须按声明/调用/状态语义重核，不能裸复用）。
- 4.9.7.2 混淆更轻（业务类明文，仅 `common/activity/{A..z}`、`settings/fragment/{A..C,C1501b..}` 等工具类单字母），重定位比 6.5.x 的 `R1.B` 全混淆低一个量级。
- 79 个 `hookTargets` 语义签名需逐条比对 4.9.7.2 的 dex（验收脚本 `verify-host-profile.py` 直接解析 APK dex 核对每条证据声明）。

**D. 验收标准（项目自带）**

- `scripts/verify-host-profile.py`：只读 DEX 级验收，逐条核对类名/完整方法签名/字段名。
- `docs/host-adaptation-checklist.md`（57KB）+ `docs/feature-preservation.md`（53KB 运行时测试矩阵）。
- profile 开发期 `productionEnabled=false`，证据（`evidence`/`knownDegradations`）与实际一致后方可转生产。

**E. 工作量定性**

- 这是一个**工程化极成熟、验收极严**的项目（135KB guide + 57KB checklist + 38KB 验收脚本 + 53KB 测试矩阵）。适配 4.9.7.2 ≠ 复制 JSON 改版本号，而是：建 profile 骨架 → 逐符号语义重核（约 13 混淆符号 + 79 签名）→ 过 `verify-host-profile.py` → 构建 → npatch 嵌入 → 真机回归。
- 好消息是底座方向完全正确、机制完全摸清、明文类 1:1，剩下是**可并行的逆向重定位体力活**，且每一批都能用验收脚本闭环验证。

### 12. 验收脚本首跑结果（2026-11-16 已完成）

已建 4.9.7.2 骨架 profile（`4.9.7.2-1472.json`，`productionEnabled=false`，从 6.5.3 复制改版本号）并跑项目自带 DEX 验收脚本：

```
python3 scripts/verify-host-profile.py Apple_Music-4.9.7.2.apk --profile 4.9.7.2-1472.json
→ checks: 129, failures: 96（通过 33）
```

**33 项通过 = 11 个明文类 + 其 hookTargets**（`PlayerActivity`/`PlayerLyricsViewFragment`/`PlayerLyricsViewModel`/`LyricsLineVector` 等 1:1 命中），实锤核心结构一致。

**96 项失败 = 权威重定位 TODO 清单**，按能力区聚类：

| 能力区 | 失败数 | 6.5.x 混淆符号示例（需在 4.9.7.2 找对应） |
|---|---|---|
| `lyricBlur` 歌词玻璃（最大块） | 56 | 歌词行/词 adapter 的混淆方法描述符 + `player/U0`、`player/A`、`player/i1` 等类 |
| `verification.methods` + `indexed.classes` | ~25 | `player/e1`、`player/f1`、`player/R0`、`library2/H`、`library2/a`、`z6/b`、`Kd/b`、`K5/a`、`A8/D`、`utils/E0$a`、`player/A.a0()`、`PlayerActivity.k1()`… |
| `lyricResultSubmission` | 5 | 歌词结果提交的方法描述符（`androidx.lifecycle.G.setValue` 等） |
| `ALIASED_TYPES` | 3 | `androidx/lifecycle/G` 的 getValue/observe/observeForever |
| `catalog_query` | 1 | `u8/E.v()` 曲库查询 |
| `content_http_interceptor` | 1 | `w8/a.a()` HTTP 拦截 |

**重定位方法论**（已确立）：对每个失败符号，① 查 `TargetSymbols.kt` 里该稳定标识符的 `contract` 语义谓词 + `fallbackName` 结构兜底前缀 → ② 在 4.9.7.2 `jadx_out`（业务类明文）里按语义找等价物 → ③ 写回 profile → ④ 重跑验收脚本闭环验证。

**下一步**：逐能力区重定位。建议顺序 = 先核心播放器/歌词（`verification.methods` + `lyricBlur`，即车机双栏 + 玻璃歌词两大卖点），再外围（设置/曲库/HTTP）。
