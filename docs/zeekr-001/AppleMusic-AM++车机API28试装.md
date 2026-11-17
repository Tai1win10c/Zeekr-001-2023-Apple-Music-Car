# Apple Music 4.9.7.2 · Android 9 车机试装记录

2026-11-16。接续[原交接](交接文档-AM++车机适配.md)。目标设备：极氪 001，8155/arm64，Android 9（API 28，用户确认 ADB 与第三方侧载已开放）。

## 当前实现

- 正式版兼容索引不启用 1472；只有 AM++ 调试包对原包名 `com.apple.android.music` 和精确版本 `4.9.7.2 (1472)` 启用这一适配。车机预览不启动其他未经移植的 AM++ 功能。
- 播放器横屏可用区域达到 640 × 320 dp 时，保留左栏原生歌曲／队列与播放按钮，右栏使用真正的 Apple Music 子 Fragment。右栏跟随原生换歌、播放时钟、歌词获取和歌词解析；沿用原生封面背景，增加右栏半透明底色与边缘淡出。右栏保留自己的 Fragment ID，避免重建时将其当作普通歌词页恢复到左栏。窄屏及竖屏沿用原版布局。
- 6.5.3 同属 Activity 页面族，但其 `w1/F1` 控制器约定不能直接套在 1472 的 `l0.u1/D1` 上。新适配先校验成员，再使用 1472 的原生 Fragment 事务和媒体会话连接；`P1()` 和底部弹层绑定同时接入，保留原生展开／收起事件。
- 暂无 Android 12 的逐行模糊、手机玻璃折射、第三方歌词与来源菜单、网络本地化等。在 API 28 上补齐新版混淆名也无法获得 `RenderEffect`。如要补逐行模糊，应基于 1472 原生歌词适配器另做旧系统渲染，用真机帧耗验证。
- 原交接资料、反编译及 `verification_relocation.json` 保留用于后续能力。旧 `apply_relocations.py` 针对 6.5.3 复制骨架，不适用于当前车机档案。

## 复现构建

在 `lab/ampp` 执行：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
python3 scripts/verify-host-profile.py ../am4972/Apple_Music-4.9.7.2.apk \
  --profile host-applemusic/src/main/resources/host-profiles/4.9.7.2-1472.json
./gradlew :app:assembleDebug :app:testDebugUnitTest :host-applemusic:testDebugUnitTest
scripts/build-zeekr-1472-embedded.sh \
  ../tools/NPatch-1.1.0-857.jar app/build/outputs/apk/debug/app-debug.apk \
  ../am4972/Apple_Music-4.9.7.2.apk ../am4972/config.arm64_v8a.apk ../am4972/config.xxhdpi.apk \
  ../am4972/builds/zeekr-1472-api28-preview-07
```

输出目录必须不存在。NPatch [v1.1.0 build 857](https://github.com/7723mod/NPatch/releases/tag/v1.1.0) 离线嵌入模块，由 Apple Music 进程内自带的运行时加载。无需安装 NPatch Manager 或系统 Hook，也无需变更车辆配置。

## 离线验收与产物

- 原始 1472 包的成员、构造器及布局通过 41 项检查，6.5.1 的已有检查仍通过。应用侧 JVM 回归 281 项通过；宿主侧用已有 JUnit 运行时直接执行档案与成员契约等 27 项通过。宿主模块全量 Gradle 测试受 Robolectric 依赖下载超时阻断。
- 试装 ZIP：`lab/am4972/builds/AppleMusic-AMpp-4.9.7.2-ZEEKR-API28-preview-07.zip`。ZIP 内含三个 APK 与校验文件；同组 APK 通过 API 28 验签及 4 KiB 对齐。嵌入模块与输入一致，外层宿主 DEX 和 APK 内嵌的宿主副本一致。Root 与以太网的宿主修改记录见下文。
- 已完成实车只读参数核对。Android 9 / API 28、arm64 和系统默认横屏配置符合，包管理器未发现 Apple Music。03 已安装并通过启动与双栏钩子注册检查，随后显示 Root 检测黑页。04 已于 2026-11-17 在实车覆盖更新，启动后进入主界面。[车机应用开发参数](极氪001车机应用开发参数.md)

## 实车安装前复核

2026-11-16。试装包名为 `com.apple.android.music`，版本为 4.9.7.2 / 1472，最低 API 28。三个 APK 的校验和一致于交付清单，且均通过 API 28 的 APK 签名 v3 校验。试装证书 SHA-256 为 `08b00b38cd98762bf261952c2c1014c09208ac2c278fd3085421994a516c3e23`，Apple 原包证书为 `88ba590ec2e1ea33c4458daf59489faee2cef297a9b4071e18cf82ef531100aa`。

当前车机通过 USB 连通，API 28，首选 ABI 为 `arm64-v8a`，当前 Android 用户为 0。安装前两种包管理器查询都未发现同名包。中控逻辑密度为 160 dpi，base 内有该密度的资源，密度分包提供其他密度资源。实际 App 窗口和显示效果以启动测试为准。

## 首轮实车安装与启动

2026-11-16，普通应用方式向 Android 用户 0 原子安装 base、arm64 和密度分包。Android 包管理器确认版本 4.9.7.2 / 1472 和全部三份分包。后续更新保留原包名、版本码和试装证书。以已装 base 的 SHA-256 确认更新目标为上一版试装包。

- 01 可安装，启动超时。应用线程栈指向 NPatch 的签名兼容路径。它在安装文件访问钩子时再次进入 `File.getPath()`，宿主 Application 还未创建。
- 02 关闭签名兼容后越过卡点。启动报宿主 `AppleMusicApplication` 类找不到，Android 9 实际使用的外层 APK 中缺少原始宿主 DEX。
- 03 保留签名兼容关闭，并通过 NPatch `--injectdex` 在外层 APK 中保留宿主 DEX。与原包逐字节匹配四个宿主 DEX。更新成功，`am start -W` 返回 `Status: ok`，Apple Music 自身窗口报告 `HAS_DRAWN`。应用日志确认 `4.9.7.2 car preview: native dual-pane hooks registered`。
- 测试只涉及 Apple Music 的安装、启动和诊断。待实测普通账户登录、媒体播放、完整播放器窗口和同步歌词。

## Root 提示与离线修复

- 03 在车机上显示 Apple Music 的本地 Root 错误页。已定位到 `AppleMusicApplication.e()Z`。宿主初始化和活动启动均查询它，活动用这一结果决定是否显示 `rooted_title` 和 `rooted_message`。这台车机报告 `test-keys`，对非模拟器机型，此条件本身即可命中该检查。
- 04 在 Mac 上对精确原包的 `classes.dex` 做静态修改，仅把该方法的 true 返回改为 false，并重新计算 DEX 完整性字段。以原始 DEX SHA-256 和原指令校验限制修改版本。其余宿主 APK 条目保持一致，外层和内嵌宿主均验证有这处修改。对应脚本为 `lab/ampp/scripts/patch-zeekr-1472-host.py`。
- 04 的包名、版本码和试装证书与 03 一致。三个分包通过 API 28 验签，41 项宿主成员检查通过。这处修改只针对本地启动门槛，登录、播放、服务端检查、DRM 与双栏效果待实车测试。
- 2026-11-17 恢复 USB ADB 后，先核对 Android 用户、平台和已装 base 的 SHA-256，确认目标为 03，再用 `install-multiple -r` 同批覆盖三个分包。安装返回 `Success`，`am start -W` 返回 `Status: ok`。应用从 SplashActivity 进入 `MainContentActivity`，越过 03 的本地 Root 错误页。日志确认原生双栏钩子已注册。
- 04 主界面显示“你已离线，关闭飞行模式或接入 Wi-Fi”。以太网诊断和 05 更新记录见下文。

## 以太网内容加载

- 2026-11-17，04 的 Apple Music 包已获 `INTERNET`、`ACCESS_NETWORK_STATE` 等联网权限。车机默认网络为已连接的 Ethernet，具备 `INTERNET`、`VALIDATED` 与 `FOREGROUND` 能力。
- 1472 的一般连接检查 `p7.c.f(Context)` 支持 Ethernet。内容入口和部分 ViewModel 还走一层 Wi-Fi／蜂窝偏好判断，导致车机默认网络可用时显示离线。
- 05 在 Mac 只修改精确宿主 DEX。常规内容入口、首页、推荐、详情与在线歌词统一使用内容连接策略。检查有效的默认网络，直接接受 Wi-Fi 与 Ethernet；遇到蜂窝网络仍使用原应用的蜂窝数据偏好。Wi-Fi 专用判断、后台下载代码和系统网络配置保持原样。三个分包同证书验签，41 项宿主检查通过。
- 通过对车机已装 base 校验，确认 04 后覆盖更新 05。三个分包同批安装成功，`am start -W` 返回 `Status: ok` 并到达 `MainContentActivity`。页面由离线错误视图切换为内容加载，随后观察到进入 Apple Music 设置。账号登录、实际音频播放和双栏逐行同步仍待验证。

## 展开歌曲信息崩溃

- 05 在两次展开歌曲信息时发生 `ArrayIndexOutOfBoundsException: length=2; index=2`。栈指向宿主 `PlayerLyricsViewFragment$h.onAnimationUpdate`。该动画逐点插值原生四点渐变，而先前适配把底部渐变换成了两点。
- 06 保留宿主的颜色与渐变位置数组，只调整淡出范围。Root 与 Ethernet 的 APK 静态修复一并保留。40 项宿主成员检查和应用侧 281 项回归通过，三个分包通过 API 28 验签。
- 2026-11-17 已用同证书覆盖更新 06 并成功启动。日志确认双栏钩子已注册。Apple Music 媒体会话达到 Playing，位置从 0 前进至约 50 秒。此次观察期间未再记录该异常。仍待用户从原入口复测并确认展开布局与歌词同步。

## 展开后的全屏双栏

- 2026-11-17 用户确认 06 的歌词已可见，并给出全屏播放参考图。展开时需要左侧封面、歌名与原生播放控件，右侧歌词获得更大比例。06 实车展开窗口为 1920 × 1200，但原生播放器外层在右侧只有 643 px，双栏宽度约 322 / 321 px。
- 07 在应用内响应播放器状态，展开时去掉外层 15:28 窄栏约束并使用 Apple Music 窗口全宽，收起或隐藏时恢复原始约束。左栏占 44%，右栏占 56%。宽屏同步歌词资源从 24sp 调为 36sp，沿用原生封面背景、歌词动画和播放控件。
- 07 经同包名同证书覆盖安装并启动。实车展开宽高 1920 × 1200，原生播放器左栏 845 px，右侧歌词 1075 px，确认有逐字歌词行渲染。42 项宿主成员检查和应用单元测试通过，三个分包通过 API 28 验签。参考图的逐行模糊仍待后续 API 28 实现验证。

## 方形封面与歌词分栏

- 2026-11-17，用户实拍 07 显示，带竖版动态封面的歌曲会将人物画面铺入背景层，遮盖了独立方形封面的预期位置。原生歌曲界面会在 `DETAIL_TALL` 可用时隐藏方形封面，并启动单独的背景层切换。
- 08 在符合条件的应用横屏中关闭 `DETAIL_TALL` 的视频与预览帧选择。沿用原生 `DETAIL_SQUARE` 与静态封面回退。展开时仍是 44% / 56% 双栏，歌词区增加左右各 24dp 边距并加深靠右的渐变。收起后恢复原生窄栏宽度，首次展开测到歌词容器尺寸后补做原生歌词布局初始化。
- `08` 试装 ZIP（见本仓库 Release `preview-08`）包含三个 APK 与校验文件。45 项宿主检查、281 项应用单元测试通过。分包与 07 同包名、版本码和证书，均通过 API 28 验签及对齐检查。
- 用户确认车辆已停稳并授权后，覆盖更新到 08 并启动。用户用同一首《LIKE ME》确认展开后封面与歌词位置正常。实车观测到展开时的 1920 × 1200 播放页、643 × 643 方形封面与 1027 px 歌词视口，逐字歌词已渲染。收起后恢复 643 px 窄栏，播放会话进入 Playing，复测期间无新的 Apple Music 崩溃记录。

## 实车验收

可以先离线复查 APK，或在兼容的 Android 9 测试设备上验证安装与启动。实车上的桌面入口、实际横屏布局、车机音频和歌词同步需要在车辆停稳时验证。如果车机现有安装器支持同一应用的多个 APK 一起安装，可以在车机上手动试装，ADB 用于获取精确参数及排查问题。

用户于 2026-11-16 确认实车边界。连接 ADB 后，第一步仅核对设备、API、ABI、屏幕尺寸和密度，以及当前驾驶员是否已经安装 Apple Music。也可由用户提供这些参数，已确认的 API 28 不需要重复提供。

```sh
adb devices -l
# 将 car_serial 设为上面列表中确认属于车机的标识。
adb -s "$car_serial" shell getprop ro.build.version.sdk
adb -s "$car_serial" shell getprop ro.product.cpu.abilist
adb -s "$car_serial" shell wm size
adb -s "$car_serial" shell wm density
adb -s "$car_serial" shell am get-config
car_user=$(adb -s "$car_serial" shell am get-current-user)
adb -s "$car_serial" shell pm list packages --user "$car_user" com.apple.android.music
```

实车禁止修改车机系统、固件、系统设置、系统应用和车辆控制服务。确认车机、兼容条件及已有应用后，仅在用户明确同意试装时执行下列命令。三个 APK 必须同一批次。若已装 Apple 原签名版本，当前试装包不能直接覆盖，先保留已装应用及其数据。

```sh
build='./builds/zeekr-1472-api28-preview-08'
adb -s "$car_serial" install-multiple "$build/base.apk" "$build/config.arm64_v8a.apk" "$build/config.xxhdpi.apk"
```

1. 从车机桌面启动 Apple Music。桌面图标或启动异常时，停在应用层排查。试装模块正常加载时，应用日志会出现 `4.9.7.2 car preview: native dual-pane hooks registered`。
2. 登录正常账号，播放一首有逐行／逐字歌词的歌曲。展开播放器，确认左栏封面、进度、上下曲与队列可用；右栏有同一首歌的原生歌词。
3. 暂停再恢复，拖动进度、点击歌词定位、切换不同歌词类型，确认歌词和声音位置一致。试无歌词歌曲、展开／收起、切后台再返回；条件允许时测视频封面和低网速。
4. 如有异常，记录错误发生时的 screenWidthDp、screenHeightDp、应用截图和目标歌曲类型。需要日志时，仅收集与该次 Apple Music 故障有关的片段。回退时先请用户确认处理范围及应用数据影响。
