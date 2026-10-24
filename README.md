# 3D 车况（原生）：基于民间 4.5UI 开发的原生卡片

比亚迪车机上的 3D 车况应用。放进车机的多窗口（双画中画）或悬浮窗口里，一台 3D 车模跟着真实车况动。

车况来自 4.5UI（宿主应用 BydModule）。画面用 Java + OpenGL ES 3.0 直接画，不经过浏览器内核；不碰它、车况也没变的时候一帧都不画，不占处理器。

## 车模跟着车况动

- 车门、车窗、引擎盖、尾门、天窗、遮阳帘、充电口盖跟着开合。开门时镜头转过去对着，全关上再转回来。
- 车灯：近光、远光（地上照出光束）、日行灯、示宽灯、尾灯、刹车灯（紧急制动时快闪）、倒车灯、转向灯和双闪、前后雾灯、氛围灯（颜色、亮度跟着车机，支持音乐律动、动态多彩）。上车时迎宾灯亮一下。
- 四个轮子旁边是胎压标签（胎压、胎温）。胎压异常时镜头拉近对着那个轮子。
- 挂 D 挡时镜头转到车顶视角，四个轮子的胎压都在画面里。这个角度可以在设置里改。

## 车况信息

- 顶栏：挡位、车速、电源，移动信号、Wi-Fi、蓝牙、定位、热点。
- 车况图标：开着或有异常时才出现，比如没系安全带、座椅加热通风、空调、后排空调、除霜、驾驶模式、能量回收、保电、低压供电异常、钥匙电池电量低。
- 下面一行字：告警、提醒、充电状态按严重程度排。点一下，镜头转到对应的轮子、车门、车窗或充电口。
- 底部数据：从电量、油量、续航、温度、里程、能耗、空调、踏板等数值里选 1～4 项。
- 全部车况：宿主信息、全部车况字段、媒体和系统数据，一页列全。
- 俯视时车顶上有一个空调键，点一下开关空调。

## 换车模、车牌、贴纸

默认车模是 2016 Exotic Rides W70。想换成自己的车：

1. 在网页卡片「3D 示例（抗锯齿开关）」的"调整车模"里导入车模，标好车门、车窗、车灯，需要的话挂上车牌、贴上贴纸。
2. 点"导出车模包"，得到一个 .glb 文件。
3. 拷到车机上，在这个应用的设置里点"导入车模包"。

车模包的配置写在 GLB 的 `asset.extras.d3car` 里，车牌、贴纸在这里照样显示。网页卡片不在这个仓库里。

## 设置

- 车模：导入车模包、恢复默认。
- 外观：车漆颜色和质感（原厂、亮面、金属、珠光、缎面、哑光、磨砂金属、糖果、电镀、变色龙），轮毂、刹车卡钳颜色。
- 背景与视角：背景（影棚 / 暖调 / 冷调，或者自己的图片）、默认视角、挂 D 挡后的视角。
- 底部数据、深浅色（跟随车机 / 浅色 / 深色）、胎压标签、进场动画、全屏时收起状态栏。
- 性能：画质（低 / 标准 / 高 / 极致）、帧率（30 / 60）、抗锯齿。

## 没有车机时

在模拟器或普通安卓设备上（没装 4.5UI，或者不是比亚迪车机），应用会用内置的调试数据：一台停着的车、三首调试歌。设置最下面的"调试面板"里能改任意一项车况，看车模和界面怎么变。

## 在车上用

要有：比亚迪车机（Android 9 以上），装好 4.5UI（宿主应用 BydModule，包名 `com.wzw.bydmodule`）。实车用过的 4.5UI 版本是 2.2.1.6 到 2.2.1.18，更早的版本可能还没有给外部应用的接口。

1. 把 APK 装到车机上，比如 `adb install -r xxx.apk`。
2. 在比亚迪「系统加速 → 应用加速管理」里给它打开"允许在后台运行"。不开的话，车机内存紧张时宿主的授权页一盖上来，系统就会把应用结束，授权申请跟着作废。
3. 在 4.5UI 的应用中心里把它加进多窗口。小格子、大格子各有一份名单，只加进大格子的名单时，拖不到小格子里。
4. 打开后点"申请授权"，在宿主弹出的确认页里允许，并勾上"记住选择"。

## 接口文档

- uiplus Android 接口（这个应用用的）：https://u223v6sxpn.apifox.cn
- uiplus JS 接口（网页卡片用的）：https://1yquh73bv5.apifox.cn

应用绑定宿主的 `com.wzw.bydmodule/.uiplus.UiplusExternalService`，开会话、申请授权，再按方法名发请求、收订阅推送；方法名和参数同网页卡片的 `window.uiplus`。对接时踩过的坑（授权页、宿主后端晚好、会话超时等）写在 `src/` 里 `Uiplus.java` 的开头。

## 从源码打包

不用 Gradle、Android Studio，脚本直接调 Android 的构建工具。要装：

- Node.js 18 以上；
- JDK 17（`javac`、`java`、`keytool`）；
- Android SDK 里的 build-tools（测过 34，要带 aapt、aapt2、zipalign、`lib/d8.jar`、`lib/apksigner.jar`、`core-lambda-stubs.jar`）和 `platforms;android-30`（用它的 android.jar）。用 sdkmanager 装：`sdkmanager "build-tools;34.0.0" "platforms;android-30"`。

设好 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）和 `JAVA_HOME`，脚本自己去找工具；也可以直接设 `ANDROID_BUILD_TOOLS`（build-tools 目录）和 `ANDROID_JAR`（android.jar）。JDK 在 PATH 里也行。

```
node build.mjs            # → out/3D 车况（原生） v<版本>.apk
node build.mjs --install  # 打完用 adb install -r 装上（adb 要在 PATH 里）
```

中间产物在 `build/`。版本号在 `AndroidManifest.xml`：`versionCode` 每发一版加 1，`versionName` 是显示的版本。

## 签名

第一次打包会在这个目录生成签名钥匙 `release.keystore`（密码 android），之后一直用它。**要备份，别外传。**

- 宿主按应用的签名认人，授权记在签名上。换了钥匙，车上要重新授权。
- 安卓不让不同签名的包覆盖安装：车上装着别人签的同名应用（包名一样）时，要先卸载才能装你签的。
- 改了代码自己发布时，最好把包名改掉（`AndroidManifest.xml` 的 `package`、`src/` 下的目录、每个文件的 `package` 那一行），免得和原版冲突。

## 代码结构

工程是 `AndroidManifest.xml`、`res/`、`src/`（包名 `com.x3card.car3d`）；`assets/` 里是打进 APK 的资源，已经做好，直接用；`build.mjs`、`apkbuild.mjs` 是打包脚本，只用 Node 自带的模块。

`src/com/x3card/car3d/`：

| 文件 | 做什么 |
|---|---|
| `MainActivity.java` | 入口：连宿主、授权、订阅、设置、导入车模包、全屏 |
| `Uiplus.java` | 宿主给外部应用的接口（Binder），文件开头有完整说明 |
| `DebugHost.java` / `DebugPanel.java` | 没有车机时的调试数据和调试面板 |
| `CarData.java` / `CarStat.java` | 订阅车况、解码；车况图标、底部数据 |
| `CarPage.java` | 建车、车况怎么动、灯、镜头、手势 |
| `CarModel.java` / `GeoCache.java` / `Settings.java` | 车模存取、配置校验、默认车模的内置配置；解好的几何缓存；设置存取 |
| `Lamp.java` / `Plates.java` / `WrapArt.java` / `WrapCut.java` / `Wraps.java` | 车灯的点亮层和路面光束、车牌、贴纸 |
| `Stage.java` / `Mirror.java` / `Post.java` / `SceneView.java` / `Bg.java` / `Background.java` | 出帧和省电、地面反射、辉光和环境光遮蔽、画布、背景渐变、背景图片 |
| `Hud.java` / `Tags.java` / `CarUi.java` / `Sheet.java` / `Panel.java` / `InfoPanel.java` / `Busy.java` / `SvgPath.java` | 界面：顶栏和文字、胎压标签、按钮和提示、设置面板、全部车况、导入进度、图标路径 |
| `InfoView.java` / `FullActivity.java` | 连不上宿主、没授权时的说明和授权按钮；全屏页 |
| `gl/` | 小 3D 引擎，照 three.js r149 一对一搬成 Java。着色器原文用 three.js 自己的；读 GLB 照 GLTFLoader，网格、材质的编号和网页版一样，车模包的配置按编号引用 |

`assets/`：three.js r149 的着色器原文、两张预先算好的环境图（`.rgbe`）、默认车模 `w70.glb`、数字字体 Outfit、车牌字样（国标 GA 36 附录 B 描成的矢量）、车况字段目录、贴纸的 8 种字体，以及它们的许可文件。

## 许可

- 资源的许可：`assets/LICENSE-car.txt`、`assets/LICENSE-outfit.txt`、`assets/LICENSE-wrap-fonts.txt`。
- 默认车模 W70 是 CC BY-NC-SA 4.0：**不能商用**，改了要按同样的许可发出去，署名见 `assets/LICENSE-car.txt`。
- 照着 three.js、meshoptimizer 搬过来的代码：`LICENSE-third-party.txt`（MIT）。
