# 飞牛相册 · 安卓原生 App（无需 NAS 部署）

这是一个**真正的安卓 App**：打开后输入你飞牛的 NAS 地址（或 FN Connect 远程访问地址），
登录飞牛账号，就能直接浏览飞牛自带相册（AI 人物、智能分类、时间线、收藏全部同步）。

它和「Docker 部署的 PWA 版」的区别：**这个版本不需要在 NAS 上装任何东西**。
原因是原生 App 用系统 WebView 加载相册页面，并用原生代码发网络请求，
而原生请求**不受浏览器 CORS 限制**，可直接带着登录 Cookie 调飞牛接口。

---

## ⚠️ 关于「构建 APK」要先说清楚

APK 必须用 Google 的 Android 构建工具链（`aapt2` / `d8` / `android.jar` 等）来编，
这些工具只能从 Google 的服务器下载。本项目是在**无法联网下载该工具链的沙箱环境里开发的**，
因此我**不能直接在对话里把 APK 字节文件生成给你**。

但下面给你两条**你几乎不用装软件**就能拿到 APK 的路：

- **方案 A（推荐，电脑零安装）**：用 **GitHub Actions 云端构建**——GitHub 的服务器能访问 Google 源，
  等于「我替你在云端把 APK 编出来」，你只需有个免费 GitHub 账号、点几下按钮下载。
- **方案 B（备选）**：在你自己电脑上用 Android Studio / 命令行构建（你电脑能联网下载，只是装不了 Studio 那个大软件时用轻量命令行）。

---

## 方案 A：GitHub Actions 云端一键构建（推荐，零安装）

> 前提：一个免费 GitHub 账号（https://github.com ，没有就注册，完全免费）。
> Actions 免费额度对个人足够（私有仓库每月 2000 分钟，编一次 APK 约 3–5 分钟）。

1. **新建仓库**：登录 GitHub → 右上角 **＋ → New repository** → 名字随意（如 `feiniu-album`）
   → 选 **Private 或 Public** 都行 → 勾选「Add a README」不用 → **Create repository**。
2. **上传代码**：把本交付包里的 **`feiniu-album` 整个文件夹**上传到仓库根目录。
   - 最简单：进仓库 → **Add file → Upload files** → 把 `feiniu-album` 文件夹里所有内容（含 `android/`、`.github/`、PWA 文件等）拖进去 → Commit。
   - 或用 git：`git clone` 仓库后把文件复制进去 `git add . && git commit && git push`。
   - **关键**：确保仓库里有 `android/` 目录和 `.github/workflows/build.yml` 文件。
3. **一键构建**：进仓库 → 顶部 **Actions** 标签 → 看到工作流 **Build APK** →
   点 **Run workflow → 选分支 main → Run workflow**。
4. **等几分钟**：状态变绿勾（约 3–5 分钟，首次要下载 Gradle/SDK 稍慢）。
5. **下载 APK**：点进该次运行 → 底部 **Artifacts** 区下载 `feiniu-album-apk`（是个 zip）
   → 解压得到 `app-debug.apk`。
6. **装到手机**：把 `app-debug.apk` 传到安卓手机（数据线/微信/网盘都行），点开安装。
   - 首次安装会提示「允许安装未知来源应用」，按提示开启即可（APK 是你自己构建的，安全）。

> 以后想重新构建（比如我更新了相册页面）：把新文件再上传/推一遍，重跑 workflow 即可。

---

## 方案 B：在你电脑本地构建（备选）

### B1. Android Studio（若你电脑能装）

1. 装 **Android Studio**（免费）：https://developer.android.com/studio
   - 首次启动引导安装 **Android SDK（API 34）** 和 **Gradle**，默认下一步即可。
2. 打开 Android Studio → **Open** 本目录 `android/` → 首次点 **Sync Now** 等几分钟。
3. 菜单 **Build → Build APK(s)** → 完成后底部 **locate** 找到：
   `android/app/build/outputs/apk/debug/app-debug.apk`。

### B2. 轻量命令行（不想装 Studio 的大软件时）

需要本机有 **JDK 17** + **Android SDK command-line-tools**，然后：

```bash
# 1. 装好 SDK 平台（已配 ANDROID_HOME 的前提下）
sdkmanager "platforms;android-34" "build-tools;34.0.0"
# 2. 在 android/ 目录用 Gradle 构建（需本机已装 gradle，或用 gradle-wrapper）
cd android
gradle assembleDebug        # 或：./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 手机上使用（每次都这样）

1. 打开「飞牛相册」App。
2. 点「添加服务器」：
   - 类型选 **飞牛直连**；
   - 名称随便填（如「我家 NAS」）；
   - **地址**填你飞牛的访问地址，二选一：
     - 家里局域网：`http://192.168.x.x:5666`（换成你 NAS 的实际 IP 和端口）；
     - 在外/远程：填 **FN Connect 远程访问地址**（飞牛设置里开启后给的地址，通常 `https://xxxx.fnossync.com`）。
   - 保存。
3. 点「去登录 / 立即登录」→ 在飞牛登录页输入飞牛账号密码 → 登录。
4. 登录成功后自动进入相册，时间线、人物、智能分类都能用了。
5. 以后打开 App 就是已登录状态，直接看相册。

---

## 常见问题

| 现象 | 解决 |
|---|---|
| 安装时提示「危险/未知来源」 | 安卓默认拦截，点「设置→允许此来源安装」即可，APK 是你自己构建的。 |
| 添加服务器后一直「未登录」 | 地址填错或端口不对。局域网确认是 `:5666`；远程确认 FN Connect 地址能在浏览器打开。 |
| 能登录但相册空白 | 该飞牛账号下相册还没索引，或飞牛「相册」应用未开启。 |
| 远程访问很慢/连不上 | 优先用飞牛自带 FN Connect；也可装 Tailscale 组网后用内网 IP。 |
| GitHub Actions 构建红叉 | 点进运行看日志：常见是 `.github/workflows/build.yml` 没上传到仓库根，或 `android/` 目录缺文件。 |

---

## 和 Docker/PWA 版怎么选

- **本安卓原生版**：NAS 零部署，手机装 APK，输地址即用。推荐大多数安卓用户。
- **Docker + PWA 版**（上层目录 `deploy/`）：在 NAS 上跑一个反代，手机用 Chrome「添加到主屏幕」。
  适合想用 iPhone（Safari 添加到主屏幕）、或不想装 APK 的场景。

> iOS 说明：苹果不允许随意安装第三方 APK，且 WKWebView 对跨域 Cookie 限制更严，
> 因此 iOS 走 PWA 路线（见上层 `GUIDE.md` + `deploy/`），体验同样独立全屏。
