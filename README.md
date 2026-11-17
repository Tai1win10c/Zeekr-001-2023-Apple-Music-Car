<a id="top"></a>

<h1 align="center">Zeekr 001 (2023) Apple Music Car · AM++</h1>

<p align="center">
  面向 <strong>2023 款极氪 001（8155 / Android 9 API 28 / arm64-v8a）</strong> 车机的 Apple Music <code>4.9.7.2 (1472)</code> 独立侧载适配版。<br>
  基于开源项目 <a href="https://github.com/Zennmn/AM-plus-plus">AM++ (Zennmn/AM-plus-plus)</a> 构建，当前公开里程碑：<strong>preview-08</strong>。
</p>

## 项目说明

**官方不开发的，我们自己来开发。**

老款 8155 极氪 001 的 Android 9 车机早已停止了很多新体验与三方适配，虽然我们是老韭菜，但感谢大 AI 时代，让个人车主也有能力亲手把想要的体验一点点做出来——让陪伴我们的老车机焕发第二春。

## 极氪 001 车机版特性（Preview-08）

- **免 Root / 免管理器独立侧载**：通过 [NPatch v1.1.0 build 857](https://github.com/7723mod/NPatch/releases/tag/v1.1.0) 内嵌打包，直接作为普通应用同批安装，无需在车机另装框架，也不修改任何车机系统文件。
- **车机兼容性修复**（[`scripts/patch-zeekr-1472-host.py`](scripts/patch-zeekr-1472-host.py)）：
  - 绕过车机 `test-keys` 系统标识触发的本地 Root 启动限制提示。
  - 修复车载以太网（Ethernet）在线场景下首页与在线歌词误判为“已离线”的问题。
  - 放大横屏（`w640dp`）同步歌词字号至 `36sp`，便于中控观看。
- **横屏原生 44% / 56% 双栏播放器**（[`Zeekr1472DualPane.kt`](host-applemusic/src/main/java/dev/amenhancer/module/hook/Zeekr1472DualPane.kt)）：
  - 展开全屏播放页时，左侧保留歌曲封面、进度条与播放控制（44%），右侧嵌入 Apple Music 4.9.7.2 原生实时逐字歌词视图（56%）。
  - 禁用横屏下竖版动态 `DETAIL_TALL` 背景遮盖问题，恢复 `DETAIL_SQUARE` 独立方形封面展示。
- **实车适配与调研文档**：详见 [`docs/zeekr-001/`](docs/zeekr-001/AppleMusic-AM++车机API28试装.md)。

## 快速安装（Preview-08）

从 GitHub [Releases (`preview-08`)](https://github.com/Tai1win10c/Zeekr-001-2023-Apple-Music-Car/releases/tag/preview-08) 下载三个拆分 APK，使用 ADB 同批安装：

```sh
adb install-multiple base.apk config.arm64_v8a.apk config.xxhdpi.apk
```

> 注：如车机已安装官方 Apple 签名版客户端，需先卸载后再安装此内嵌签名套件。试装包签名 SHA-256：`08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`。

## 关于原版 AM++

本仓库基于 [Zennmn/AM-plus-plus](https://github.com/Zennmn/AM-plus-plus) 进行车机端定向裁剪与适配。原版 AM++ 是一个面向 Android 的 Apple Music 增强模块（支持 libxposed 与 NPatch 嵌入模式），提供平板横屏双栏、逐行歌词模糊、自定义 TTML 歌词与液态玻璃等特性；由于极氪 001 原生车机仍运行 Android 9（API 28），本项目在其框架基础上专门补齐了 4.9.7.2 低版本宿主适配与车机兼容性补丁。

如需从源码自行构建模块或重新生成内嵌试装包，可参考 [`docs/zeekr-001/AppleMusic-AM++车机API28试装.md`](docs/zeekr-001/AppleMusic-AM++车机API28试装.md)。

## 许可证与致谢

本项目遵循 [GNU General Public License v3.0](LICENSE) 开源，第三方许可声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。感谢以下开源项目与原作者的工作：

- [AM++ (Zennmn/AM-plus-plus)](https://github.com/Zennmn/AM-plus-plus)：原版模块与架构底座。
- [NPatch](https://github.com/7723mod/NPatch)：免 Root 内嵌打包工具。
- [AMLyricBlur](https://github.com/a23bc/amlyricblur) / [AndroidLiquidGlass (Backdrop)](https://github.com/Kyant0/AndroidLiquidGlass)：原版 AM++ 上游使用的渲染参考与依赖库。

## 支持项目

本项目完全免费开源。如果这个极氪 001 车机适配版对你的用车体验有所帮助，欢迎请我喝杯咖啡（赞赏完全自愿，不影响任何功能使用）。

> *收款码预留位（待补充）*

<p align="right">(<a href="#top">返回顶部</a>)</p>
