# AM++ 车机适配 · 交接文档

> 后续进展见 [Android 9 / API 28 车机试装记录](AppleMusic-AM++车机API28试装.md)。本页保留原始研究交接。

> 收口时点：2026-11-16（晚）。本文档是「把 AM++ 移植到 Apple Music 4.9.7.2 底座」这条线的完整交接，目标 = 让接手者（未来的我 / 他人）**不重复任何已完成的逆向工作**。

---

## 0. 一句话状态

底座方向已验证正确（4.9.7.2 = legacy-activity 族，与 6.5.x 同族），profile 骨架已建、验收脚本已跑通，**核心 hook 块 14 条映射已确认**。剩余工作 = ① 应用这 14 条映射；② 处理 20 条「未找到」符号（9 个 okhttp3 重打包簇 + 11 个移除/重构类）；③ 补完歌词玻璃块 + indexed 块（两块的**部分发现已记录在本文档 §5**，可续做）。

---

## 1. 项目目标（为什么做这件事）

- 极氪 001 车机：SoC 8155 / msmnile，**Android 9（API 28）**，已 root。
- 目标：把 **AM++**（Apple Music 增强模块，GitHub 开源）移植到 **Apple Music 4.9.7.2**（`com.apple.android.music`，versionCode **1472**，2025-07-31）底座上。
- **为什么是 4.9.7.2**：它是最后一个 `minSdk 24（Android 7.0+）` 的版本，**原生支持 API 28**；下一个 5.0.0（2025-09）把 minSdk 抬到 29，6.5.x+ 全系 minSdk 30（Android 11），车机装不上。
- 卖点：车机宽屏双栏（歌词常驻右栏）+ 歌词玻璃模糊 + 歌词同步，理想惠及所有 Android 7.0+ 老车机。
- 禁用一切注入式方案（Xposed/frida/NPatch 运行时 hook）；用**项目自带 npatch 静态嵌入**（`7723mod/NPatch`，GPL-3.0，v1.1.0，`jar-v1.1.0-857-release.jar`）。
- 构建链路：`构建 AM++ 模块 APK → 取 4.9.7.2 宿主包 → java -jar npatch.jar --embed 模块.apk 宿主.apk → 车机侧载`。

---

## 2. 关键结构事实（最高价值，接手必读）

### 2.1 底座归属：legacy-activity 族（已确认）

AM++ 把 Apple Music 版本分两族：
- **`legacy-activity`（6.5.x）**：目标 = `PlayerActivity` / `PlayerBottomSheetBehavior` / 双栏资源 / 歌词同步 / 旧设置桥。
- **`fragment-content`（7.0.0）**：目标 = `FragmentDualPanePreflight` 等。

**4.9.7.2 的类结构 = `legacy-activity` 族，与 6.5.x 同族**（`PlayerActivity`、`PlayerBottomSheetBehavior`、`PlayerLyricsViewFragment` 全同名）。因此 **6.5.3-1599.json 是最佳模板**，整套 hook 逻辑可复用，不是从零写。

### 2.2 hook 解析机制：DexKit + indexed + 语义字典（非裸字符串匹配）

- `indexed` 字段 = 稳定标识符（`LYRICS_FRAGMENT`、`PLAYER_ACTIVITY`…）→ **每版本各自的混淆名**。
- `hookTargets` = 79 个语义签名（参数类型 / 返回值 / 被调方法名）。
- 解析用 **DexKit**（构建产物 `libdexkit.so`）+ `TargetSymbols.kt` 里的 `contract` 语义谓词 + `fallbackName` 结构兜底。
- 语义字典：`host-applemusic/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt`（2510 行，每个稳定符号有 `profileId`/`profileOwner`/`contract`/`fallbackName`）。

### 2.3 4.9.7.2 的混淆特征（两层，关键！）

1. **ProGuard 单字母重命名 + `renamed from` 注释**：jadx 反编译后大量类带 `/* JADX INFO: renamed from: X, reason: collision with root package name */`。**这些注释能直接反查原混淆名**——这是重定位速度快的根本原因。
2. **R8 库重打包**：整库（尤其 **okhttp3**）被搬到短包/`p####` 包（`Sd`/`Xd`/`Qb`/`Pb`/`Wd`/`p252s5`/`p420w8`/`p382u8`…），且方法名全单字母、**无 `renamed from` 注释** → 这是最难的一簇（见 §4.2）。

反编译产物：`lab/am4972/jadx_out/sources/`（14,794 个 .java，12,523 类，仅 223 个混淆类解不开）。

### 2.4 验收脚本（唯一裁决标准）

`scripts/verify-host-profile.py` 只读解析 APK dex，逐条核对 profile 里每个类名/方法签名/字段名。
- 研究版 profile（`productionEnabled=false`）必须用 `--profile` 显式传入，且 `packageName/versionName/versionCode` 必须与二进制 manifest 一致。
- 首跑结果（骨架）：**129 检查，33 通过，96 失败**。33 通过 = 11 个明文类（`PlayerActivity`/`PlayerLyricsViewFragment`/`PlayerLyricsViewModel`/`LyricsLineVector` 等）1:1 命中。

---

## 3. 已完成的工作清单

| 项 | 状态 | 产物/位置 |
|---|---|---|
| 底座调研 + minSdk 门禁 | ✅ | `docs/AppleMusic-AM++车机适配调研.md`（含 §8 minSdk 阻断证据、§9 可行性、§10 类级对照、§11 机制、§12 验收首跑） |
| 底座下载 + 反编译 | ✅ | `lab/am4972/`（`Apple_Music-4.9.7.2.apk` 39.9MB + `config.arm64_v8a.apk` + `config.xxhdpi.apk` + `jadx_out/`） |
| 构建环境 | ✅ | JDK 21（openjdk@21）、jadx 1.5.6、Android SDK platform android-37.0、build-tools 37.0.0、Gradle 9.7.1（wrapper） |
| AM++ 模块构建 | ✅ | `lab/ampp/app/build/outputs/apk/debug/app-debug.apk`（16MB，v1.6.9，minSdk 26） |
| profile 骨架 | ✅ | `lab/ampp/host-applemusic/src/main/resources/host-profiles/4.9.7.2-1472.json`（从 6.5.3 复制，`productionEnabled=false`） |
| 验收脚本首跑 | ✅ | 129 检查 / 96 失败（权威 TODO） |
| 方法论验证 | ✅ | `androidx.lifecycle.G`（6.5.x LiveData）→ 4.9.7.2 是 `MutableLiveData`（基类 `H`），而 4.9.7.2 的 `G` 是无关反射工具类 |
| 技能沉淀 | ✅ | 技能 `android-hook-module-host-adaptation`（完整 8 步流程 + 4 个坑） |
| 核心 hook 块重定位 | ✅ 完成 | 14 确认 + 20 未找到，见 §4；原始 JSON 在 `lab/am4972/verification_relocation.json` |
| 歌词玻璃块重定位 | ⏸ 部分 | 部分发现见 §5.1，未完成 |
| indexed+misc 块重定位 | ⏸ 部分 | 部分发现见 §5.2，未完成 |

---

## 4. 核心 hook 块完整成果（task-1，34 条，最硬核产出）

> 来源：`lab/am4972/verification_relocation.json`（子代理已存盘）。**以下 14 条「确认」可直接应用进 profile；20 条「未找到」按 §4.2 分类处理。**

### 4.1 已确认映射（14 条，old 6.5.3 → new 4.9.7.2）

| # | 符号 | 6.5.3（old） | 4.9.7.2（new） | 证据 |
|---|---|---|---|---|
| 1 | PLAYER_ACTIVITY_ROOT | `PlayerActivity.k1()` 返回内部类 `$m` | `PlayerActivity.a2()` 返回 `$k`（`m`→`StackedBottomNavigationHolder`，返回基类 `k`） | `PlayerActivity.java:976` `a2()` 三元选 Flat/StackedBottomNavigationHolder |
| 2 | PLAYER_CONTROLLER | `player.fragment.v0` | `player.fragment.l0` | `C1443l0.java:96` `renamed from: ...l0`，`u1(BagConfig)` 含窗格枚举 `n.SONG`/`n.LYRICS` |
| 3 | METADATA_TO_ITEM_CONVERTER | `player.P` | `common.MetadataToContentItemFactory` | `MetadataToContentItemFactory.java:147` `b(PlaybackItem, MediaMetadataCompat, boolean)` |
| 4 | LYRICS_AVAILABILITY_OWNER | `player.e1.d(v3/v, PlaybackItem, dg/e)` | `player.K0.l(MediaMetadataCompat, PlaybackItem, p258sb.d)`（另：可用性谓词 `i(PlaybackItem)`→`g(PlaybackItem)`） | `K0.java:252` `l(...)` 委托 `MetadataToContentItemFactory.b` |
| 5 | EDITORIAL_VIDEO_OWNER | `player.f1.e(Song, float, Flavor[])` | `player.L0.e(Song, float, Flavor[])`（类 `f1`→`L0`，方法 `e` 不变） | `L0.java:275`；`PlayerSongViewFragment.java:1473` 调 `L0.f22088b` |
| 6 | LYRICS_CURRENT_ITEM_FIELD | `player.fragment.m`（字段 `c:BaseContentItem`） | `player.fragment.e`（字段 `c`→`S`） | `AbstractC1428e.java:41` `renamed from: ...fragment.e`；`:47` `BaseContentItem f22629S` |
| 7 | 全局元数据分发 | `player.e.onMediaMetadataChanged(v3/v)` | `player.c.onMetadataChanged(MediaMetadataCompat)` | `C1406c.java:31` `renamed from: player.c, extends MediaControllerCompat.a`；`:115` 调 `K0.k(...)` |
| 8 | EXO_AUDIO_SESSION_ID | `ExoMediaPlayer.onAudioSessionId(I)` | 不变 | `ExoMediaPlayer.java:2367` |
| 9 | LOCAL_MEDIA_PLAYER_AUDIO_VARIANT | `LocalMediaPlayerController.onPlaybackAudioVariantChanged(...)` | 不变 | `LocalMediaPlayerController.java:984` |
| 10 | LIBRARY_EPOXY_BUILD | `LibraryMainContentEpoxyController.buildModels(H, List, List, a, z6.b)` | `buildModels(v, List, List, a, Y3/d)`（参数类型 `H`→`v`、`z6/b`→`Y3/d`） | `LibraryMainContentEpoxyController.java:772` |
| 11 | 底部弹层行为 | `StaticCollapsedBottomSheetBehavior.h(CoordinatorLayout, View, MotionEvent)` | 不变（泛型 V 即 View） | `StaticCollapsedBottomSheetBehavior.java:29` |
| 12 | MEDIA_ENTITY_TO_SONG_CONVERTER | `A8.D.b(Song, Bundle)` | `h9.H.m(Song, Bundle)`（方法 `b`→`m`） | `h9/H.java:236` 参数/返回完全一致 |
| 13 | CJK_UNICODE_BLOCK_HELPER | `utils.E0$a.a(CharSequence, Set)` | `utils.f0$a.a(CharSequence, Set)`（类 `E0`→`f0`） | `utils/f0.java:20` 含 `UnicodeBlock.of(...)` + CJK/HIRAGANA/KATAKANA/THAI 常量 |
| 14 | PLAYER_ACTIVITY_BEHAVIOR_FIELD | `PlayerActivity.c1` | `PlayerActivity.S0` | `PlayerActivity.java:138` `BottomSheetBehavior<FrameLayout> f19305S0` |

### 4.2 未找到符号（20 条）—— 分类与应对

**A 类：okhttp3 重打包簇（9 条，最难）**。6.5.3 用 `Gi/A`(Request)、`Gi/A$a`(Builder)、`Gi/D`(Response)、`Gi/t`(Headers)、`Li/f`(Chain)、`w8/a`(拦截器)、`u8/E`(catalog query suspend) 这些混淆名；4.9.7.2 里 okhttp3 被 **R8 重打包到 `Sd`/`Xd`/`Qb`/`Pb`/`Wd`/`p252s5`/`p420w8`/`p382u8` 等短包，方法名全单字母、无 `renamed from` 注释**。

- task-2 已部分定位（见 §5.2）：`Sd/D`=Response、`Sd/x`=OkHttpClient、`Sd/j`=Interceptor、`Xd/f`=Chain（字段 `Wd.e f11649a`）、`p252s5/a|b|e`=拦截器实现（`D a(f)`=intercept）、`p420w8/a`=w8.a、`p382u8/a`=u8.a。
- **应对**：这 9 条对应 AM++ 的「内容 HTTP 本地化 / 曲库查询」能力，属**外围增强**（非双栏/歌词核心）。若续做需完整逆向 okhttp3 重打包结构（工作量单独成块）；**短期可先禁用该能力**（从 profile 移除 `content_http_interceptor`/`catalog_query` + 相关 `indexed` 符号），不影响车机双栏 + 歌词两大卖点。

**B 类：移除/重构/迁移（11 条）**。这些类在 4.9.7.2 里**已不存在或语义完全不同**，不是改个名字能救的：

| 符号 | 4.9.7.2 现状 | 应对 |
|---|---|---|
| `fragment/e`（LYRICS_CHROME） | 同名类已改为歌词基类 `AbstractC1428e`（持有当前歌词项） | 禁用或重新定位 chrome 语义 |
| `fragment/d0`（歌词来源菜单 onClick） | 无 sourceMenu 相关类 | 禁用 |
| `player/R0`（版本指纹 canary） | 4.9.7.2 的 `R0` 是无关的 `r0` 类 | 删除该 canary 检查 |
| `common/I.t(CollectionItemView)`（artwork resolver） | `common/I` 已不存在 | 禁用或换 `common/L.q` 语义 |
| `Dg/c`（Compose observeAsState） | `Dg` 包消失 | 禁用 |
| `z0/p0`（Compose 策略） | `z0` 包重洗为 DrawableCompat | 禁用 |
| `library3/LibraryComposeContentFragment` | `library3/` 目录为空，类已移除 | 禁用 |
| `Kd/b`（堆叠导航菜单 View） | `Kd/b` 已变为泛型接口（非 View） | 禁用或换位 |
| `K5/a`（storefront 语言数组） | `K5/a` 已变为 CollectionItemView 适配器 | 禁用 |
| `player/A.a0(...)`（CJK 卡拉OK动画） | `player/A` 已变为 MediaPlayerController 接口 | 禁用 |
| `androidx.lifecycle.G` 别名（LiveData） | LiveData 基类=`H`，`MutableLiveData` 明文 | **已解**（见 §2.4 方法论，别名改 `H`） |

> **重要结论**：6.5.x 的 hook 目标有相当一部分在 4.9.7.2 里**根本不存在**（被移除/重构）。这部分不是「重定位」，而是「**该能力在 4.9.7.2 上降级/禁用**」。所以 4.9.7.2 的 profile 不能简单等价于 6.5.3——它是「legacy-activity 族的子集」：核心（播放器/歌词/双栏/底部弹层）保留，外围（okhttp3 本地化、Compose 观察、卡拉OK动画、storefront 语言）需裁剪。

---

## 5. 其余两块的**部分**发现（未完成，可续做，别重来）

### 5.1 歌词玻璃块（lyricBlur，56 项，task-0 部分发现）

契约位置：`6.5.3-1599.json` 的 `lyricBlur` 字段（9 类 + 63 方法 + 22 字段）。已定位：

| 角色 | 6.5.3 | 4.9.7.2 候选 | 证据 |
|---|---|---|---|
| fragment | `PlayerLyricsViewFragment`（明文，1:1） | 不变 | `player/fragment/PlayerLyricsViewFragment.java:107 extends AbstractC1428e` |
| **lineAdapter（歌词行 adapter）** | `player.U0` | **`player.w0 extends N0<j>`** | `w0.java:50`；`S(List, int, Pair[])`=lineCommit（`w0.java:579`） |
| **wordAdapter（词 adapter）** | `player.A` | **`player.C1479q extends N0<a>`** | `C1479q.java:83`；内层 `a extends N0.b`、`c/e extends AnimatorListenerAdapter` |
| **wordHolder** | `player.A$a` | `C1479q.a`（待确认） | `C1479q.java:247` |
| **wordModel** | `PlayerLyricsViewModel$e` | `PlayerLyricsViewModel.e` | 字段 `PlayerLyricsViewModel f22459Z0`（renamed from Z0） |
| fragment 回调 | 内层 `d/l/n`（Function3） | `PlayerLyricsViewFragment` 字段 `P0`(ttml.e)/`Q0`(d)/`R0`(l) | `PlayerLyricsViewFragment.java:150-159` |
| 歌词基类 | — | `AbstractC1428e`（字段 `S`=BaseContentItem、`T`=Bitmap） | `AbstractC1428e.java:41,47` |
| **taggedAnimator（词动画）** | `w6.c` | **`V3`（卡拉OK词抬升动画，含 `V3.b`/`V3.c`/`V3.d`）** | `C1479q.java:1114` `KARAOKE_WORD_LIFT_TAG`；`V3/b.java:80`、`V3/d.java:17` |
| **mediaBrowser** | `E3.x` | **`p308w4.D7.y`（候选）** | `p308w4/D7.java:56` `class y implements p326x7.d, p258sb.d, p258sb.g...` |
| **wordModel 字段** | `PlayerLyricsViewModel$e` | `PlayerLyricsViewModel.e`：`a`(int)/`b`(int)/`c`(String)/`d`(int) | `PlayerLyricsViewModel.java`（内层 `e`，`renamed from: a,b,c,d`） |
| lineAdapter 同步指标 | `U0` 的 synchronizedMetrics | `w0` 的 `TreeSet<Integer>` 字段 `A`/`B` | `w0.java:104,107` |

**待续**：`adapterBase`（`player.i1`）1 个类 + 63 方法 + 22 字段的逐个映射未完成。已确认的类间关系：`w0 extends N0<j>`（lineAdapter，`S(List,int,Pair[])`=lineCommit）、`C1479q extends N0<a>`（wordAdapter，内层 `a extends N0.b`=wordHolder，`c/e/o extends AnimatorListenerAdapter`，`f/g implements AnimatorUpdateListener`）。线索：`PlayerLyricsViewFragment` 的方法 `F2/I2/J2/K2/L2`（SongInfoPtr 相关，`fragment/PlayerLyricsViewFragment.java:1114,1291,1306,1321,1433`）+ `H2(PlaybackStateCompat)`/`t1`/`h2`（`1276,1636`）是歌词加载/提交/seek 路径。

### 5.2 indexed+misc 块（task-2 部分发现）

| 符号 | 4.9.7.2 发现 | 证据 |
|---|---|---|
| SETTINGS_FRAGMENT（`settings.fragment.SettingsFragment`） | `settings.fragment.S`（被 `SettingsActivity` 承载） | `settings/activity/SettingsActivity.java:187` `new S()`；`settings/fragment/S.java:70` |
| CJK_UNICODE_BLOCK_HELPER | `utils.f0$a`（与 task-1 #13 互相印证） | `utils/f0.java:20` |
| LiveData 别名 | 基类 `androidx.lifecycle.H`（`MutableLiveData extends H`） | `androidx/lifecycle/H.java` |
| STACKED_NAVIGATION_MENU（`Kd.b`） | `Kd/b` 已变 `interface b<T> extends h<T>, a<T>`（非 View） | `Kd/b.java:5` |
| SIM 检查（`Oa.c`） | `Oa/c.java:7 class c implements Qa.b` | 另见 `p212p7/c.e(Context)` 含 `getSimState()` |
| storefront 语言（`K5.a`） | `K5/a.java:9 class a extends c` | 另见 `p250s3/a.b(Context)` 含 `zh-CN`/`zh-TW` |
| okhttp3 簇（`w8/a`、`u8/E`、`Gi/*`、`Li/f`） | `Sd/D`(Response)、`Sd/x`(OkHttpClient)、`Sd/j`(Interceptor)、`Xd/f`(Chain)、`p252s5/a|b|e`、`p420w8/a`、`p382u8/a` | 见 §4.2 A 类 |

---

## 6. 待办（恢复路径，按优先级）

1. **应用 §4.1 的 14 条确认映射**到 `4.9.7.2-1472.json`（indexed.classes / indexed.methods / indexed.fields / verification.methods / verification.fields 对应更新）。→ 重跑 `verify-host-profile.py`，预期失败数 **96 → 约 70**。
2. **处理 §4.2 的 20 条未找到**：B 类 11 条「移除/重构」→ 从 profile 删除对应 hookTargets/indexed 符号（能力降级），并在 `knownDegradations` 登记；A 类 9 条 okhttp3 → 短期先禁用，长期单独成块逆向。
3. **补完歌词玻璃块**（§5.1，续 task-0 的思路，`adapterBase`/`mediaBrowser`/`taggedAnimator` + 63 方法 + 22 字段）。
4. **补完 indexed+misc 块**（§5.2 已定位的写回，未定位的续找）。
5. 全绿后：`productionEnabled=true` → 补 `evidence`/`knownDegradations` → 构建模块 → npatch 嵌入 → 车机真机测试（用户负责）。

**恢复入口命令**：
```bash
cd ~/Documents/Hermes/zeekr/lab/ampp
python3 scripts/verify-host-profile.py ../am4972/Apple_Music-4.9.7.2.apk \
    --profile host-applemusic/src/main/resources/host-profiles/4.9.7.2-1472.json
```

---

## 7. 关键文件清单

| 文件 | 说明 |
|---|---|
| `docs/AppleMusic-AM++车机适配调研.md` | 调研全记录（§8-§12 为技术核心） |
| `docs/交接文档-AM++车机适配.md` | 本文档 |
| `lab/am4972/jadx_out/sources/` | 4.9.7.2 反编译产物（14,794 .java） |
| `lab/am4972/verification_relocation.json` | task-1 的 34 条映射原始 JSON |
| `lab/am4972/Apple_Music-4.9.7.2.apk` | 底座 base APK（39.9MB） |
| `lab/ampp/host-applemusic/src/main/resources/host-profiles/4.9.7.2-1472.json` | 4.9.7.2 profile 骨架（待填） |
| `lab/ampp/host-applemusic/src/main/resources/host-profiles/6.5.3-1599.json` | 模板（legacy-activity 族） |
| `lab/ampp/host-applemusic/src/main/java/dev/amenhancer/module/hook/TargetSymbols.kt` | 语义字典（2510 行） |
| `lab/ampp/scripts/verify-host-profile.py` | 验收脚本（唯一裁决） |
| `lab/ampp/docs/host-adaptation-guide.md` / `host-adaptation-checklist.md` | 官方适配指南/清单 |
| `lab/ampp/app/build/outputs/apk/debug/app-debug.apk` | AM++ 模块构建产物 |

---

## 8. 风险 / 坑（避免重复踩）

1. **混淆名跨版本不通用，且同名可能是不同类**。铁证：`androidx.lifecycle.G` 在 6.5.x 是 LiveData，在 4.9.7.2 是无关反射工具类（LiveData 基类是 `H`）。**永远语义重核，不按名字复用**。
2. **4.9.7.2 有两层混淆**：ProGuard 单字母（有 `renamed from` 注释可反查）+ R8 库重打包（okhttp3，无注释）。后者最难。
3. **「未找到」≠ 白做**：20 条未找到里 11 条是「类在 4.9.7.2 已移除/重构」，结论本身就是价值（该能力需降级，不是继续找名字）。
4. **验收脚本是唯一裁决**，任何映射都要它跑绿才算数，不要信「看着像」。
5. **研究版 profile 必须 `--profile` 显式传**，且版本三元组必须与二进制 manifest 一致，否则脚本直接退出。
6. **构建环境**：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；SDK `~/Library/Android/sdk`；build-tools 37.0.0 由 AGP 构建时自动拉取。
7. **6.5.1 的 minSdk 30 降级不可行**（`WindowManager.getCurrentWindowMetrics()` 裸调无门控），不要回头尝试。
