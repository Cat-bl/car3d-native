# 3D 车况（原生）：基于民间 4.5UI 开发的原生卡片（v0.2）

比亚迪车机上的 3D 车况应用：放进车机的多窗口（双画中画）或悬浮窗口里用，一台 3D 车模跟着真实车况动——车门、车窗、引擎盖、尾门、天窗开合，各种车灯（大灯、日行灯、转向灯、刹车灯、雾灯、氛围灯……）亮灭，胎压标签，挂 D 挡转到车顶视角，车况图标、底部数据、全部车况，车顶上的空调键。

- 默认车模是 2016 Exotic Rides W70。换车模：在网页版卡片「3D 示例」的"调整车模"里导入自己的车模、调好车门车灯等，点"导出车模包"得到一个 .glb，拷到车机上在这个应用的设置里"导入车模包"（配置在 GLB 的 `asset.extras.d3car` 里）。网页版不在这个包里。
- 车况来自宿主（见下面"对接宿主"）。
- 用 Java + OpenGL ES 3.0 直接画，不用浏览器内核；最低 Android 9，车机是 Android 10。

主要文件（`src/com/x3card/car3d/`）：

| 文件 | 做什么 |
|---|---|
| `MainActivity.java` | 入口：连宿主、授权、订阅、设置、导入车模包、全屏 |
| `Uiplus.java` | 宿主的外部应用接口（Binder），文件开头有完整说明 |
| `CarData.java` / `CarStat.java` | 订阅车况、解码；车况图标、底部数据 |
| `CarPage.java` | 建车、车况怎么动、灯、镜头、手势 |
| `CarModel.java` / `Settings.java` | 车模存取、配置校验、默认车模的内置配置；设置存取 |
| `Lamp.java` / `Plates.java` / `WrapArt.java` / `WrapCut.java` / `Wraps.java` | 车灯的点亮层和路面光束、车牌、贴纸 |
| `Stage.java` / `Mirror.java` / `Post.java` / `SceneView.java` / `Bg.java` / `Background.java` | 出帧和省电、地面反射、辉光和环境光遮蔽、画布、背景渐变、背景图片 |
| `Hud.java` / `Tags.java` / `CarUi.java` / `Sheet.java` / `Panel.java` / `InfoPanel.java` / `Busy.java` / `SvgPath.java` | 界面：顶栏和文字、胎压标签、按钮和提示、设置面板、全部车况、导入进度、图标路径 |
| `InfoView.java` / `FullActivity.java` | 连不上宿主、没授权时的说明和授权按钮；全屏页 |
| `gl/` | 小 3D 引擎，照 three.js r149 一对一搬（着色器原文用 three 自己的，在 assets 的 `three-shaders.json`），GLB 读取照 GLTFLoader（网格、材质编号和网页版一样，车模包的配置按编号引用） |

assets 里：three.js r149 的着色器原文、两张预先算好的环境图（`.rgbe`）、默认车模 `w70.glb`、数字字体 Outfit、车牌字样（国标 GA 36 附录 B 描成的矢量）、车况字段目录 `fields.json`、贴纸的 8 种字体，和它们的许可文件。

## 目录

- `AndroidManifest.xml`、`res/`、`src/`：工程本身（包名 `com.x3card.car3d`）。
- `assets/`：打进 APK 的资源，已经做好，直接用：
  - `LICENSE-car.txt`（1 KB）
  - `LICENSE-outfit.txt`（4 KB）
  - `LICENSE-wrap-fonts.txt`（50 KB）
  - `Outfit.ttf`（108 KB）
  - `actions.json`（55 KB）
  - `env-neutral.rgbe`（3.0 MB）
  - `env-studio.rgbe`（3.0 MB）
  - `fields.json`（23 KB）
  - `plate-font.json`（179 KB）
  - `three-shaders.json`（163 KB）
  - `w70.glb`（2.0 MB）
  - `wrap-fonts.json`（1 KB）
  - `wrapfont-bebas.ttf`（17 KB）
  - `wrapfont-greatvibes.ttf`（56 KB）
  - `wrapfont-kaushan.ttf`（51 KB）
  - `wrapfont-marker.ttf`（37 KB）
  - `wrapfont-pacifico.ttf`（44 KB）
  - `wrapfont-racing.ttf`（33 KB）
  - `wrapfont-russo.ttf`（10 KB）
  - `wrapfont-yellowtail.ttf`（18 KB）
- `build.mjs`、`apkbuild.mjs`：打包脚本（只用 Node 自带的模块）。
- `LICENSE-third-party.txt`：照着搬过来的第三方代码的许可。

## 打包

不用 Gradle、Android Studio，脚本直接调 Android 的构建工具。要装：

- Node.js 18 以上；
- JDK 17（`javac`、`java`、`keytool`）；
- Android SDK 里的 build-tools（34 测过，要带 aapt、aapt2、zipalign、`lib/d8.jar`、`lib/apksigner.jar`、`core-lambda-stubs.jar`）和 `platforms;android-30`（用它的 android.jar）。
  用 sdkmanager 装：`sdkmanager "build-tools;34.0.0" "platforms;android-30"`。

告诉脚本工具在哪：设 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）和 `JAVA_HOME`；也可以直接设 `ANDROID_BUILD_TOOLS`（build-tools 目录）、`ANDROID_JAR`（android.jar）。JDK 在 PATH 里也行。

```
node build.mjs            → out/3D 车况（原生） v0.2.apk
node build.mjs --install  → 打完用 adb install -r 装上（adb 要在 PATH 里）
```

中间产物在 `build/`。改版本：`AndroidManifest.xml` 的 `versionCode`（每发一版加 1）和 `versionName`。

## 签名

第一次打包会在这个目录生成签名钥匙 `release.keystore`（密码 android），之后一直用它，**要备份、别外传**：

- 宿主按应用的签名认人，授权记在签名上。换了钥匙，车上要重新授权。
- 安卓不让不同签名的包覆盖安装：车上装着别人签的同名应用（包名一样）时，要先卸载才能装你签的。
- 改了代码自己发布时，最好把包名改掉（`AndroidManifest.xml` 的 `package`、`src/` 下的目录和每个文件的 `package` 那一行），免得和原版冲突。

## 对接宿主

车况、歌这些数据来自宿主应用 BydModule（包名 `com.wzw.bydmodule`，网页卡片也是它提供的）给外部安卓应用开的 Binder 接口：绑定 `com.wzw.bydmodule/.uiplus.UiplusExternalService`，开会话、申请授权，再按方法名发请求、收订阅推送；方法名、参数和网页卡片的 `window.uiplus` 一样。接口、消息格式、授权的坑都写在 `src/` 里 `Uiplus.java` 的开头。

接口文档：uiplus Android 接口 https://u223v6sxpn.apifox.cn （网页卡片的 uiplus JS 接口是 https://1yquh73bv5.apifox.cn ）。

在车上用：

1. 装上以后，先在比亚迪「系统加速 → 应用加速管理」里给这个应用打开"允许在后台运行"。不然车机内存紧张时，宿主的授权页一盖上来，系统就把这个应用结束了，授权申请跟着作废。
2. 在宿主的应用中心里把它加进多窗口（小格子、大格子各有一份名单，只在大格子的名单里时拖不到小格子），打开后点"申请授权"，在宿主弹出的确认页里允许（勾上"记住选择"）。
3. 实车用过的宿主版本是 2.2.1.6 到 2.2.1.14；更早的宿主可能还没有这个接口。

## 许可

- 资源的许可在 `assets/LICENSE-car.txt`、`assets/LICENSE-outfit.txt`、`assets/LICENSE-wrap-fonts.txt`。
- 默认车模 W70 是 CC BY-NC-SA 4.0：**不能商用**，改了要按同样的许可发出去，署名见 `assets/LICENSE-car.txt`。
- 照着 three.js、meshoptimizer 搬过来的代码见 `LICENSE-third-party.txt`（MIT）。
