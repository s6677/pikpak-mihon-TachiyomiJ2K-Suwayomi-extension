# PikPak Cloud for Mihon / TachiyomiJ2K / Suwayomi

<!-- EASY-INSTALL-START -->
## 🚀 安装 / Install

### 方法一：添加扩展仓库网址 / Option 1: Extension Store URL

把下面这个网址添加到 Mihon、TachiyomiJ2K 或兼容版本的 Suwayomi：

Add this URL to Mihon, TachiyomiJ2K, or a compatible Suwayomi Extension Store:

```text
https://raw.githubusercontent.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/main/index.pb
```

### 方法二：直接下载 / Option 2: Manual Download

**Android · Mihon / TachiyomiJ2K**

[⬇️ 下载 PikPak-Cloud.apk / Download APK](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.apk)

**PC · Suwayomi**

[⬇️ 下载 PikPak-Cloud.jar / Download JAR](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.jar)

两种方式功能相同，用户自己选择即可。  
Both installation methods provide the same extension. Choose whichever you prefer.

<!-- EASY-INSTALL-END -->

把你自己的 PikPak 云盘变成 Mihon / TachiyomiJ2K / Suwayomi 的私人漫画源。  
Turn your own PikPak storage into a private manga source for Mihon, TachiyomiJ2K, and Suwayomi.

**本项目不提供漫画、不共享 PikPak 账号，也不包含任何用户凭证。**  
**This project does not provide manga, shared PikPak accounts, or user credentials.**

## 扩展仓库地址 / Extension Store URL

```
https://raw.githubusercontent.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/main/index.pb
```

Android: add this URL to Mihon / TachiyomiJ2K Extension Stores.  
PC: add the same URL to a compatible Suwayomi Extension Store.

## 使用前准备 / Before use

1. 打开 PikPak，进入 **设置 → 访问与集成 → WebDAV**。  
   In PikPak, open **Settings → Access & Integrations → WebDAV**.
2. 启用 WebDAV，并创建一组 WebDAV 凭证。  
   Enable WebDAV and create WebDAV credentials.
3. 在 PikPak Cloud 来源设置里填写 WebDAV 地址、用户名、密码和漫画根目录。  
   Enter the WebDAV URL, username, password, and manga root folder in PikPak Cloud settings.

## 漫画根目录 / Manga Root Folder

推荐创建 `/漫画/`，然后把下载来的漫画直接丢进去。  
We recommend creating `/漫画/` and dropping downloaded manga into it directly.

v0.11 会自动识别这些常见结构：

```
/漫画/
  火之鸟.zip
  龙珠.cbz

  阿基拉/
    001.jpg
    002.jpg

  剑风传奇/
    Vol.01/
      001.jpg
      002.jpg
    Vol.02/
      001.jpg
      002.jpg

  灌篮高手/
    Vol.01.cbz
    Vol.02.zip
```

- 根目录里的 ZIP/CBZ = 一本漫画
- 根目录里的文件夹 = 一本漫画
- 漫画文件夹里直接放图片 = 自动作为一个章节
- 漫画文件夹里的 Vol.xx / Chapter xx 等子目录 = 自动作为章节
- 漫画文件夹里多个 ZIP/CBZ = 自动作为卷/章节
- ZIP/CBZ 内多层目录会自动扫描，公共包装目录会尽量折叠
- 文件名使用自然数字排序，例如 1, 2, 3, 10，而不是 1, 10, 2
- 自动忽略 __MACOSX、.DS_Store、Thumbs.db、desktop.ini 等常见垃圾文件
- 如果漫画根目录有 1-3 张封面图，同时还有真实章节目录，会尽量把这些封面图忽略为章节

English:

- ZIP/CBZ directly under the manga root = one manga
- A folder directly under the manga root = one manga
- Images directly inside a manga folder = one chapter
- Vol.xx / Chapter xx subfolders = chapters automatically
- Multiple ZIP/CBZ files inside a manga folder = volumes/chapters automatically
- Nested ZIP/CBZ folders are scanned and common wrapper folders are collapsed when possible
- Natural numeric sorting is used: 1, 2, 3, 10 instead of 1, 10, 2
- Common junk files/folders such as __MACOSX, .DS_Store, Thumbs.db and desktop.ini are ignored

## 支持格式 / Supported formats

Archives: ZIP, CBZ

Images: JPG, JPEG, PNG, WEBP, GIF, AVIF, JXL

RAR/CBR, 7Z, PDF and EPUB are not handled by this version. The extension does not silently download an entire unsupported archive.

## WebDAV 设置 / WebDAV settings

**WebDAV 地址 / WebDAV URL**  
通常保持默认 `https://dav.pikpak.ai/`。  
Usually keep the default `https://dav.pikpak.ai/`.

**WebDAV 用户名 / WebDAV Username**  
填 PikPak WebDAV 页面显示的用户名，不是登录邮箱或手机号。  
Use the username shown on the PikPak WebDAV page, not your login email or phone number.

**WebDAV 密码 / WebDAV Password**  
填 WebDAV 页面生成的密码，不是 PikPak 账号登录密码。  
Use the WebDAV-generated password, not your PikPak account password.

**漫画根目录 / Manga Root Folder**  
例如 `/漫画/`。填写 `/` 表示直接使用 PikPak 根目录。  
Example: `/漫画/`. Use `/` to use the PikPak root itself.

## Remote reading

ZIP/CBZ pages are read remotely with HTTP Range access, so the whole archive does not need to be downloaded before reading.

Normal image folders are fetched one image at a time through WebDAV.

## 更新 / Updating

为了保持这个项目简单，本仓库不维护固定 APK 签名。以后如果新版不能覆盖旧版，请卸载旧扩展后重新安装。  
To keep the project simple, this repository does not maintain a permanent APK signing key. If a future build cannot replace an old one, uninstall the old extension and install the new build.

## Privacy / 隐私

WebDAV credentials stay in each user's own extension settings. Never publish or share them.

## Disclaimer

Independent community project. Not affiliated with PikPak, Mihon, TachiyomiJ2K, Suwayomi, or Keiyoushi.