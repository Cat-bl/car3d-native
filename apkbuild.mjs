// 原生 APK 的打包核心：不装 Gradle，直接调 Android 构建工具（aapt2 → javac → d8 → 打包 → zipalign → 签名）。
// 仓库里由 _apk/build.mjs 调；导出给别人的独立源码包（_apk/export.mjs）里原样带一份，由包里的 build.mjs 调。只用 Node 内置模块。
// 工具位置：环境变量 ANDROID_BUILD_TOOLS（build-tools 目录）、ANDROID_JAR（android.jar）、JAVA_HOME；没设的从 ANDROID_HOME / ANDROID_SDK_ROOT 里找
// （build-tools 取版本最高的，android.jar 优先 android-30、没有取最高的），再没有用调用方给的默认值；JDK 都没有就用 PATH 里的 java。
// 资源：ASSETS 是 APK 的 assets 根目录（Windows 上的 aapt2 把子目录写成反斜杠，安卓找不到，所以只放根目录）；noCompress 是不压缩的扩展名
// （字体、车模这类系统直接映射着读，不用整个解压进内存）。
// 签名钥匙 KS 没有就生成一把（密码 android）：宿主按签名 SHA-256 认应用，换了钥匙要重新授权，已经装着的也没法覆盖安装。
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const exe = (n) => n + (process.platform === 'win32' ? '.exe' : '');
function newest(dir) {
  try { return fs.readdirSync(dir).sort((a, b) => b.localeCompare(a, undefined, { numeric: true })).map((d) => path.join(dir, d)).find((p) => fs.statSync(p).isDirectory()); }
  catch (e) { return null; }
}
/** 找构建工具：{ BT, JAR, JDK }，再给出 run / bin / bt */
export function tools(def = {}) {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  let BT = process.env.ANDROID_BUILD_TOOLS, JAR = process.env.ANDROID_JAR;
  if (!BT && sdk) BT = newest(path.join(sdk, 'build-tools'));
  if (!JAR && sdk) {
    const p30 = path.join(sdk, 'platforms', 'android-30', 'android.jar'), hi = newest(path.join(sdk, 'platforms'));
    JAR = fs.existsSync(p30) ? p30 : hi && fs.existsSync(path.join(hi, 'android.jar')) ? path.join(hi, 'android.jar') : null;
  }
  BT = BT || def.BT; JAR = JAR || def.JAR;
  const JDK = process.env.JAVA_HOME || (def.JDK && fs.existsSync(def.JDK) ? def.JDK : null);
  const miss = [];
  if (!BT || !fs.existsSync(path.join(BT, exe('aapt2')))) miss.push('Android build-tools（设 ANDROID_BUILD_TOOLS 或 ANDROID_HOME）');
  if (!JAR || !fs.existsSync(JAR)) miss.push('android.jar（设 ANDROID_JAR 或 ANDROID_HOME，要装 platforms;android-30）');
  if (miss.length) { console.error('找不到：' + miss.join('、')); process.exit(1); }
  const bin = (n) => (JDK ? path.join(JDK, 'bin', exe(n)) : n);
  const bt = (n) => path.join(BT, exe(n));
  function run(cmd, args, opt) {
    const r = spawnSync(cmd, args, Object.assign({ encoding: 'utf8' }, opt));
    if (r.status !== 0) {
      console.error('失败：' + path.basename(cmd) + ' ' + args.join(' ').slice(0, 300));
      console.error((r.stdout || '') + (r.stderr || '') + (r.error ? String(r.error) : ''));
      process.exit(1);
    }
    return r.stdout || '';
  }
  return { BT, JAR, JDK, bin, bt, run };
}
function files(dir, ext) {
  const out = [];
  (function walk(d) { fs.readdirSync(d, { withFileTypes: true }).forEach((e) => { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else if (p.endsWith(ext)) out.push(p); }); })(dir);
  return out;
}
/** 应用名（res/values/strings.xml 的 app_name）、版本（清单的 versionName）、包名 */
export function meta(SRC) {
  const man = fs.readFileSync(path.join(SRC, 'AndroidManifest.xml'), 'utf8');
  const label = (fs.readFileSync(path.join(SRC, 'res', 'values', 'strings.xml'), 'utf8').match(/name="app_name">([^<]+)</) || [0, path.basename(SRC)])[1].trim();
  return { label, ver: (man.match(/android:versionName="([^"]+)"/) || [0, ''])[1], pkg: (man.match(/package="([^"]+)"/) || [0, ''])[1] };
}

/**
 * 打包：SRC 工程目录（AndroidManifest.xml、res/、src/）、OUT 中间产物目录、APK_DIR 放成品的目录、KS 签名钥匙、
 * ASSETS 现成的 assets 目录（或者给 prepare(ASSETS) 现做）、noCompress、tk = tools() 的结果、install 打完用 adb 装上
 */
export async function build({ SRC, OUT, APK_DIR, KS, ASSETS, prepare, noCompress = [], tk, install }) {
  const { BT, JAR, bin, bt, run } = tk;
  fs.rmSync(OUT, { recursive: true, force: true });
  ['gen', 'classes', 'dex'].forEach((d) => fs.mkdirSync(path.join(OUT, d), { recursive: true }));
  const t0 = Date.now();
  // 0. 资源
  if (prepare) { ASSETS = path.join(OUT, 'assets'); fs.mkdirSync(ASSETS, { recursive: true }); await prepare(ASSETS); }
  // Windows 上的 aapt2 / aapt / zipalign 打不开带中文的路径（工程放在中文目录、用户名是中文都会碰到）：都在工程目录里用相对路径调，
  // android.jar 在中文路径下时先拷一份进 OUT
  const rel = (p) => path.relative(SRC, p) || '.', ascii = (p) => /^[ -~]*$/.test(p);
  let JAR_N = JAR;
  if (!ascii(JAR)) { JAR_N = path.join(OUT, 'android.jar'); fs.copyFileSync(JAR, JAR_N); }
  // 1. 资源：编译、链接出不带代码的 APK（带上 assets），顺便生成 R.java
  run(bt('aapt2'), ['compile', '--dir', rel(path.join(SRC, 'res')), '-o', rel(path.join(OUT, 'res.zip'))], { cwd: SRC });
  run(bt('aapt2'), ['link', '-o', rel(path.join(OUT, 'base.apk')), '-I', JAR_N === JAR ? JAR : rel(JAR_N), '--manifest', 'AndroidManifest.xml']
    .concat(ASSETS && fs.existsSync(ASSETS) ? ['-A', rel(ASSETS)] : [], ...noCompress.map((e) => ['-0', e]), ['--java', rel(path.join(OUT, 'gen')), '--auto-add-overlay', rel(path.join(OUT, 'res.zip'))]), { cwd: SRC });
  // 2. Java → class（按 Java 8 编译，只链接 android.jar；lambda 要的 LambdaMetafactory 在 build-tools 的 core-lambda-stubs.jar 里，d8 再把它脱糖）
  const java = files(path.join(SRC, 'src'), '.java').concat(files(path.join(OUT, 'gen'), '.java'));
  run(bin('javac'), ['-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', JAR + path.delimiter + path.join(BT, 'core-lambda-stubs.jar'), '-Xlint:-options', '-d', path.join(OUT, 'classes')].concat(java));
  // 3. class → dex
  run(bin('java'), ['-cp', path.join(BT, 'lib', 'd8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '28', '--lib', JAR, '--output', path.join(OUT, 'dex')]
    .concat(files(path.join(OUT, 'classes'), '.class')));
  // 4. dex 放进 APK（aapt add 按相对路径放，所以在 dex 目录里执行）
  run(bt('aapt'), ['add', path.join('..', 'base.apk'), 'classes.dex'], { cwd: path.join(OUT, 'dex') });
  // 5. 对齐、签名
  run(bt('zipalign'), ['-p', '-f', '4', 'base.apk', 'aligned.apk'], { cwd: OUT });
  if (!fs.existsSync(KS)) {
    run(bin('keytool'), ['-genkeypair', '-keystore', KS, '-storepass', 'android', '-keypass', 'android', '-alias', 'x3card', '-keyalg', 'RSA', '-keysize', '2048',
      '-validity', '10000', '-dname', 'CN=3xCard,O=3xCard,C=CN']);
    console.log('生成了签名钥匙：' + path.relative(process.cwd(), KS) + '（不要提交、要备份；丢了要在宿主里重新授权）');
  }
  const { label, ver } = meta(SRC);
  fs.mkdirSync(APK_DIR, { recursive: true });
  const apk = path.join(APK_DIR, label.replace(/[\\/:*?"<>|]/g, '_') + (ver ? ' v' + ver : '') + '.apk');
  run(bin('java'), ['-jar', path.join(BT, 'lib', 'apksigner.jar'), 'sign', '--ks', KS, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, path.join(OUT, 'aligned.apk')]);
  console.log('已打包 → ' + path.relative(process.cwd(), apk) + '（' + (fs.statSync(apk).size / 1024).toFixed(1) + ' KB，' + ((Date.now() - t0) / 1000).toFixed(1) + ' 秒）');
  if (install) {
    const r = spawnSync('adb', ['install', '-r', apk], { encoding: 'utf8' });
    console.log((r.stdout || '') + (r.stderr || ''));
    if (r.status !== 0) process.exit(1);
  }
  return apk;
}
