# 飞牛相册 APK 构建包（GitHub Actions 云端构建）

这个包**只包含两个目录**，专门用来在你的 GitHub 上云端编译出安卓 APK：
- `android/` —— 安卓 App 工程（WebView 壳 + 原生网络桥接，直连飞牛相册，NAS 零部署）
- `.github/workflows/build.yml` —— 一键构建工作流

> 你不需要装 Android Studio、不需要装任何构建工具。编译在 GitHub 的服务器上完成。

---

## 操作步骤（全程在 GitHub 网页上点，不用命令行）

1. **解压** `apk-build.zip`，得到 `apk-build` 文件夹（里面有 `android/` 和 `.github/`）。

2. **建仓库**：打开 https://github.com → 登录 → 右上角 **＋ → New repository**
   - Repository name 随意（如 `feiniu-album`）
   - 选 **Private**（私有，免费）或 Public 都行
   - **不要**勾「Add a README file」（保持空仓库）
   - 点 **Create repository**

3. **上传文件夹**：进到空仓库页面
   - 点 **Add file → Upload files**
   - 把电脑上的 **`apk-build` 整个文件夹**直接拖进上传区（GitHub 网页支持拖文件夹，会自动保留 `android/` 和 `.github/` 的目录结构）
   - 拖完点绿色 **Commit changes**

4. **一键构建**：仓库顶部点 **Actions** 标签
   - 左侧看到工作流 **Build APK**，点进去
   - 点 **Run workflow → 选分支 main → Run workflow**（若页面提示"enable workflows"，先点启用）
   - 等约 3–5 分钟（首次要下载 Gradle/SDK 稍慢），状态变 **✅ 绿勾**

5. **下载 APK**：点进这次运行记录 → 页面底部 **Artifacts** 区下载 `feiniu-album-apk`（是个 zip）
   - 解压得到 `app-debug.apk`

6. **装到手机**：把 `app-debug.apk` 传到安卓手机（数据线/微信/网盘）→ 点开安装
   - 首次会提示「允许安装未知来源应用」，按提示开启即可（APK 是你自己构建的，安全）

---

## 手机上使用

打开「飞牛相册」App → 添加服务器（飞牛直连）→ 地址填 `http://NAS的IP:5666`（或 FN Connect 远程地址）→ 登录飞牛账号 → 进相册。

---

## 常见问题

| 现象 | 解决 |
|---|---|
| Actions 页面提示 "enable workflows" | 点一下启用，再 Run workflow |
| 构建红叉 | 点进运行看日志。最常见是上传时目录结构不对（仓库根应有 `android/` 和 `.github/`，不是把它们塞进多余子层）。 |
| 找不到 Artifacts | 构建必须**成功（绿勾）**后才会出现；红叉没有产物。 |
| 手机安装被拦 | 安卓默认拦截未知来源，点「设置→允许此来源」即可。 |
| 出现 "Node.js 20 is deprecated" 警告 | **只是提示，不影响构建**，可忽略；本工作流已改用 v5 版 actions 消除大部分提示。 |
| 地址留空导致连不上 | 手机 App 里**必须填** `http://NAS的IP:5666`；只有 Docker 同源部署时才留空。 |
| 照片墙全是灰块 | 已修复：图片改由原生层下载（见下方「修复记录」）。 |

---

## 修复记录（v1.1）

本版本针对首轮构建失败与真机连不上做了修复：

**1. 构建工作流 `.github/workflows/build.yml`**
- `actions/checkout` / `setup-java` 升级到 v5，消除「Node.js 20 deprecated」告警；
- 用命令行 `gradle assembleDebug` 替换 `gradle/gradle-build-action@v3`（该 action 已停更，是构建红叉的高概率原因）；
- `setup-java` 开启 `cache: gradle`，构建更快。

**2. 登录流程（index.html）**
- 服务器地址自动规范化：不写 `http://` 会自动补全，自动去掉结尾 `/` 与误填的 `/p`；
- 表单提示改为「手机 App 请填 NAS 地址」，不再误导用户留空；
- 未登录时**强制清空旧照片缓存**并始终显示「立即登录」按钮（之前登录态过期会显示上一次的照片，让人以为已登录）。

**3. 图片显示（关键修复）**
- 原生 App 里 `file://` 页面直接请求 `http://NAS` 的图片会被 Android WebView 的混合内容策略拦掉，导致照片墙全是灰块；
- 现在图片统一通过 JS 桥接由**原生层下载**再回传给 WebView（`shouldInterceptRequest` + `httpB64` 双保险）；
- 人物 / 相册封面图同样改走原生通道；下载与分享也修正为先用原生取回文件再导出。

**4. 其他**
- 打开 `fnos.net` 等外网登录域名时自动交给系统浏览器，避免 WebView 内登录受限；
- `android/gradle.properties` 开启并行构建与缓存。

> 注：本包由开发者在无法直连 GitHub 的环境中整理，工作流为标准 CI 模板；若运行报错，把 Actions 日志发回即可协助排查。
