<a id="top"></a>

<h1 align="center">Zeekr 001 (2023) Apple Music Car · AM++</h1>

<p align="center">
  面向 <strong>2023 款极氪 001（8155 / Android 9 API 28 / arm64-v8a）</strong> 车机的 Apple Music <code>4.9.7.2 (1472)</code> 独立侧载适配版。<br>
  基于开源项目 <a href="https://github.com/Zennmn/AM-plus-plus">AM++ (Zennmn/AM-plus-plus)</a> 构建，当前公开里程碑：<strong>preview-08</strong>。
</p>

## 项目说明

**官方不开发的，我们自己来开发。**

老款 8155 极氪 001 的 Android 9 车机早已停止了很多新体验与三方适配，虽然我们是老韭菜，但感谢大 AI 时代，让个人车主也有能力亲手把想要的体验一点点做出来——让陪伴我们的老车机焕发第二春。

## 为什么选择本适配版（与直接安装原版 APK 有何不同？）

直接往 2023 款极氪 001 车机安装原版 Apple Music APK 会遇到无法启动、提示离线、播放器局促在右下角窄栏、没有双栏歌词等一系列问题。基于 AM++ 的宿主拦截框架与静态补丁，本版在 **preview-08** 已实现以下专属车机特性：

1. **全屏原生双栏播放器（左 44% 封面控制 + 右 56% 实时歌词）**（[`Zeekr1472DualPane.kt`](host-applemusic/src/main/java/dev/amenhancer/module/hook/Zeekr1472DualPane.kt)）：
   - 原版 4.9.7.2 在横屏下外层播放器容器被限制在右侧窄栏（仅 ~643 px），展开也只能切进单栏视图。
   - 本版在展开全屏播放页时自动占满中控 **`1920 × 1200`** 横屏视野：左侧保留独立方形封面、进度条与播放控制，右侧嵌入 **Apple Music 原生实时歌词视图**，收起时自动还原回右下角原生窄栏。
2. **原生歌词动画特效、滚动与动态流光背景**：
   - 右栏完整保留 Apple Music 4.9.7.2 的**原生逐字/逐行卡拉 OK 歌词动画特效与平滑滚动**，将横屏歌词字号放大至 **`36sp`**，并增加了左右柔和渐变边距与顶部封面流光动态背景（`LyricsBackgroundLayerView`）。
   - 修复部分歌曲因包含竖版动态封面（`DETAIL_TALL`）导致背景人物铺满画面、遮盖独立方形封面的问题，在横屏车机上稳定回退并展示 **`DETAIL_SQUARE` 独立方形封面**。
3. **车机专项兼容性与联网修复**（[`scripts/patch-zeekr-1472-host.py`](scripts/patch-zeekr-1472-host.py)）：
   - **绕过本地 Root 拦截**：修复老车机 `test-keys` 系统属性触发原版 Apple Music Root 检测页无法进入主页的问题。
   - **修复车载以太网误判离线**：修正原版在车机内部以太网（Ethernet）环境下把曲库页与在线歌词误判为“已离线”的网络策略限制。
4. **免 Root / 免装额外框架管理器**：
   - 通过 [NPatch v1.1.0 build 857](https://github.com/7723mod/NPatch/releases/tag/v1.1.0) 内嵌注入打包，直接作为普通 Android 应用同批侧载安装，无需车机 Root，也不需要安装 LSPosed 或 NPatch Manager，不修改任何车机系统分区或系统文件。

## 关于未实现特性的说明与高通 8295 平台展望

- **为什么暂时无法实现 Liquid Glass（液态玻璃）与逐行歌词高斯模糊？**
  - 原版 AM++ 中非常惊艳的 **Liquid Glass 液态玻璃底栏**（依赖 Android 13+ 图形管线与渲染能力）和 **双向逐行实时模糊**（依赖 Android 12+ 系统级 `RenderEffect` API），以及后续新版 Apple Music 客户端本身的 API 要求，均已超出 2023 款 8155 车机底层 **Android 9（API 28）** 的系统架构支持范围——受限于老车机硬件与系统底座，这部分高级视觉特效在 Android 9 上确实无法直接开启，敬请理解。
- **如果你的车机是 8295 及以上平台（Android 12 / 13+）**：
  - 更高版本的车机系统底层已具备 `RenderEffect` 等现代渲染能力，如后续有相应需求与测试条件，欢迎在 Issue 交流，我也许可以进一步开发适配 **Liquid Glass 液态玻璃**、**双向歌词实时模糊** 以及更多原生高版本 Apple Music 特性。

## 快速安装（Preview-08）

从 GitHub [Releases (`preview-08`)](https://github.com/Tai1win10c/Zeekr-001-2023-Apple-Music-Car/releases/tag/preview-08) 下载三个拆分 APK，使用 ADB 同批安装：

```sh
adb install-multiple base.apk config.arm64_v8a.apk config.xxhdpi.apk
```

> 注：如车机已安装官方 Apple 签名版客户端，需先卸载后再安装此内嵌签名套件。试装包签名 SHA-256：`08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`。更多开发细节与实车验证记录见 [`docs/zeekr-001/`](docs/zeekr-001/AppleMusic-AM++车机API28试装.md)。

## 关于原版 AM++

本仓库基于 [Zennmn/AM-plus-plus](https://github.com/Zennmn/AM-plus-plus) 进行车机端定向裁剪与适配。原版 AM++ 是一个面向 Android 的 Apple Music 增强模块，提供平板双栏、歌词模糊、自定义 TTML 歌词与液态玻璃等特性；本项目在其开源底座之上补充了面向 Android 9 车机宿主的 `4.9.7.2 (1472)` 适配与独立侧载方案。

## 许可证与致谢

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源，第三方许可声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。感谢以下开源项目与原作者的工作：

- [AM++ (Zennmn/AM-plus-plus)](https://github.com/Zennmn/AM-plus-plus)：原版模块与架构底座。
- [NPatch](https://github.com/7723mod/NPatch)：免 Root 内嵌打包工具。
- [AMLyricBlur](https://github.com/a23bc/amlyricblur) / [AndroidLiquidGlass (Backdrop)](https://github.com/Kyant0/AndroidLiquidGlass)：原版 AM++ 上游使用的渲染参考与依赖库。

## 支持项目

本项目完全免费开源。如果这个极氪 001 车机适配版对你的用车体验有所帮助，欢迎请我喝杯咖啡（赞赏完全自愿，不影响任何功能使用）。

<p align="center">
  <a href="docs/images/zeekr-alipay-support.jpg">
    <img src="docs/images/zeekr-alipay-support.jpg" alt="支付宝赞赏码" width="320">
  </a>
</p>

<p align="right">(<a href="#top">返回顶部</a>)</p>
