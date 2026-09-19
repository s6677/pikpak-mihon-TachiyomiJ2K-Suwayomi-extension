# PikPak Cloud for Mihon / TachiyomiJ2K / Suwayomi

## English

Turn your own **PikPak cloud storage** into a private manga source for **Mihon, TachiyomiJ2K, and Suwayomi**.

PikPak Cloud reads manga stored in your own PikPak account through WebDAV. ZIP / CBZ archives are read remotely with HTTP Range requests, so the entire archive does not need to be downloaded before reading. Normal image folders are also supported.

> [!IMPORTANT]
> ## PikPak Premium is required
>
> **PikPak WebDAV is a Premium-only feature.**
>
> A PikPak Premium membership is required to use this extension.
>
> If WebDAV is not available in your PikPak account, this extension cannot be used.

### Before You Install

First, enable WebDAV in PikPak.

In the PikPak app, WebDAV is normally located under:

**Settings → Experimental Features / Labs → WebDAV**

The exact menu name may vary slightly depending on your PikPak version or region.

1. Enable WebDAV.
2. Create WebDAV credentials.
3. PikPak will provide:
   - WebDAV URL
   - WebDAV username
   - WebDAV password
4. Keep these credentials private.
5. You will enter them later in the PikPak Cloud source settings.

> [!WARNING]
> Your WebDAV username and password are private credentials. Do not post them publicly, share them with other users, or include them in GitHub issues or screenshots.

### Install

There are two ways to install PikPak Cloud.

#### Option 1: Extension Store URL

Add this URL to Mihon, TachiyomiJ2K, or a compatible version of Suwayomi:

```text
https://raw.githubusercontent.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/main/index.pb
```

**Android · Mihon / TachiyomiJ2K**

Add the Extension Store URL, find **PikPak Cloud** in the extension list, and install it.

**PC · Suwayomi**

Open **Settings → Extension Stores**, add the same `index.pb` URL, find **PikPak Cloud**, and install it.

#### Option 2: Manual Download

**Android · Mihon / TachiyomiJ2K**

[⬇️ Download PikPak-Cloud.apk](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.apk)

**PC · Suwayomi**

[⬇️ Download PikPak-Cloud.jar](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.jar)

Both installation methods provide the same PikPak Cloud extension.

### PikPak Cloud Settings

After installing the extension, open the **PikPak Cloud source settings**.
<img width="3173" height="389" alt="image" src="https://github.com/user-attachments/assets/60e4e044-7182-4ad2-805e-26546e0e384f" />
<img width="3407" height="341" alt="image" src="https://github.com/user-attachments/assets/3c9ced17-1ae9-4094-a6bb-1120d14b9e1b" />



#### WebDAV URL

Usually keep the default:

```text
https://dav.pikpak.ai/
```

#### WebDAV Username

Enter the username shown on the PikPak WebDAV page.

This is **not** your normal PikPak login email address or phone number.

#### WebDAV Password

Enter the password generated for WebDAV by PikPak.

This is **not** your normal PikPak account password.

#### Manga Root Folder

This tells PikPak Cloud where your manga library is stored.

Recommended:

```text
/comic/
```

Create a folder named `comic` in PikPak and place your manga inside it.

You can also use:

```text
/
```

to scan the first level of your PikPak root directory.

### How to Organize Your Manga

The goal is simple:

**Download manga → put it into your manga folder → refresh PikPak Cloud → read.**

You should not need to reorganize everything into one strict folder format.

#### ZIP / CBZ directly inside the manga root

```text
/comic/
  Phoenix.zip
  Dragon Ball.cbz
  Akira.zip
```

Each ZIP / CBZ file is treated as one manga.

#### Manga stored as an image folder

```text
/comic/
  Akira/
    001.jpg
    002.jpg
    003.jpg
```

The folder is treated as one manga with one chapter.

#### Manga with volume or chapter folders

```text
/comic/
  Berserk/
    Vol.01/
      001.jpg
      002.jpg
    Vol.02/
      001.jpg
      002.jpg
```

`Vol.01` and `Vol.02` are detected as separate chapters / volumes.

#### Manga folder containing multiple ZIP / CBZ volumes

```text
/comic/
  Slam Dunk/
    Vol.01.cbz
    Vol.02.zip
    Vol.03.cbz
```

Each ZIP / CBZ file is detected as a separate chapter / volume.

#### ZIP / CBZ with nested folders

```text
Phoenix.zip
└── Phoenix/
    ├── Vol.01/
    │   ├── 001.jpg
    │   └── 002.jpg
    ├── Vol.02/
    │   ├── 001.jpg
    │   └── 002.jpg
    └── Vol.03/
        ├── 001.jpg
        └── 002.jpg
```

PikPak Cloud scans nested archive folders and tries to remove unnecessary common wrapper folders automatically.

### Smart Folder Detection

PikPak Cloud includes automatic cleanup and sorting rules:

- Natural number sorting: `1, 2, 3, 10` instead of `1, 10, 2, 3`
- Supports names such as `Vol.1`, `Vol.02`, `Chapter 4`, `Ch.05`
- Ignores common junk files such as `.DS_Store`, `__MACOSX`, `Thumbs.db`, and `desktop.ini`
- Tries to avoid treating a few loose cover images as a separate chapter when real chapter folders are present
- Collapses common wrapper folders inside ZIP / CBZ archives when possible

### Supported Formats

**Manga containers**

- ZIP
- CBZ
- Normal image folders

**Image formats**

- JPG
- JPEG
- PNG
- WEBP
- GIF
- AVIF
- JXL

For the widest compatibility across Android and PC/browser environments, **JPG, PNG, and WEBP** are recommended.

**Currently unsupported**

- RAR
- CBR
- 7Z
- PDF
- EPUB

These formats are intentionally not silently downloaded as complete files. One of the main goals of PikPak Cloud is efficient remote reading without unexpectedly downloading a very large archive just to open a few pages.

### Remote ZIP / CBZ Reading

ZIP and CBZ archives are read remotely using HTTP Range requests.

For example, if you have a 1 GB manga ZIP, PikPak Cloud does **not** need to download the entire 1 GB archive before reading.

Instead, it reads the archive directory information, finds the requested page, requests the required byte range, and returns the image to the reader.

ZIP directory information is cached in memory to reduce repeated remote requests while reading.

Mihon, TachiyomiJ2K, and Suwayomi then handle reader-side page display, preloading, caching, navigation, and reading direction.

Normal image folders are fetched one image at a time through WebDAV.

### Privacy

PikPak Cloud does not provide or operate a shared manga server.

Every user connects to **their own PikPak account**.

This project:

- Does not provide manga
- Does not provide shared PikPak accounts
- Does not include PikPak credentials
- Does not require sending credentials to the project author
- Does not require users to share their PikPak account with anyone else

Your WebDAV credentials are entered in the extension settings on your own device.

> [!WARNING]
> Never publish or share your PikPak WebDAV username, password, token, or account credentials.

### Updating

To keep this project simple, this repository currently does not maintain a permanent Android APK signing key.

If a future APK cannot replace an older installed version:

```text
Uninstall the old PikPak Cloud extension
→ Install the new version
```

Your manga files stored in PikPak are not affected.

### Why This Project Exists

This project was originally created for a simple personal use case:

```text
Manga already stored in PikPak
        ↓
Why download another full copy to the phone?
        ↓
Read it directly through Mihon / TachiyomiJ2K / Suwayomi
```

It was later made public in case the same workflow is useful to other PikPak users.

The project provides the **tool only**. It does not provide manga content.

### Project Links

**Extension Store**

```text
https://raw.githubusercontent.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/main/index.pb
```

**Android APK**

[Download PikPak-Cloud.apk](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.apk)

**Suwayomi JAR**

[Download PikPak-Cloud.jar](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.jar)

### Disclaimer

This is an independent community project.

It is not affiliated with, sponsored by, or endorsed by:

- PikPak
- Mihon
- TachiyomiJ2K
- Suwayomi
- Keiyoushi

Users are responsible for the files stored in and accessed from their own PikPak accounts.

---

# 中文说明

把你自己的 **PikPak 云盘**变成 **Mihon、TachiyomiJ2K 和 Suwayomi** 的私人漫画源。

PikPak Cloud 通过 WebDAV 读取你自己 PikPak 账号中的漫画。ZIP / CBZ 会通过 HTTP Range 按需远程读取，因此无需先完整下载整个压缩包。普通图片文件夹也可以直接读取。

> [!IMPORTANT]
> ## 必须使用 PikPak Premium / 会员
>
> **PikPak WebDAV 是会员专享功能。**
>
> 使用本扩展必须拥有可以使用 WebDAV 的 PikPak Premium 会员账号。
>
> 如果你的 PikPak 账号中没有 WebDAV 功能，本扩展无法使用。

## 使用前准备

安装扩展之前，请先在 PikPak 中开启 WebDAV。

当前 PikPak 客户端通常位于：

**设置 → 实验室功能 → WebDAV（会员专享）**

不同版本或地区的 PikPak 菜单名称可能略有不同。

1. 开启 WebDAV。
2. 创建 WebDAV 凭证。
3. PikPak 会提供：
   - WebDAV 地址
   - WebDAV 用户名
   - WebDAV 密码
4. 请妥善保管这些信息。
5. 安装 PikPak Cloud 后，需要把这些信息填写到来源设置中。

> [!WARNING]
> WebDAV 用户名和密码属于私人凭证。不要公开发布、分享给其他人，也不要放进 GitHub Issue 或截图中。

## 安装

PikPak Cloud 有两种安装方式。

### 方法一：添加扩展仓库网址

把下面的网址添加到 Mihon、TachiyomiJ2K 或兼容版本的 Suwayomi：

```text
https://raw.githubusercontent.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/main/index.pb
```

**Android · Mihon / TachiyomiJ2K**

添加扩展仓库网址后，在扩展列表中找到 **PikPak Cloud** 并安装。

**PC · Suwayomi**

进入 **Settings → Extension Stores**，添加同一个 `index.pb` 地址，然后找到 **PikPak Cloud** 并安装。

### 方法二：手动下载

**Android · Mihon / TachiyomiJ2K**

[⬇️ 下载 PikPak-Cloud.apk](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.apk)

**PC · Suwayomi**

[⬇️ 下载 PikPak-Cloud.jar](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.jar)

两种安装方式使用的是同一个 PikPak Cloud 扩展，按自己的习惯选择即可。

## PikPak Cloud 设置

安装完成后，打开 **PikPak Cloud 来源设置**。

### WebDAV 地址

通常保持默认：

```text
https://dav.pikpak.ai/
```

### WebDAV 用户名

填写 PikPak WebDAV 页面显示的用户名。

**这不是 PikPak 平时登录时使用的邮箱或手机号。**

### WebDAV 密码

填写 PikPak 为 WebDAV 生成的密码。

**这不是 PikPak 账号的普通登录密码。**

### 漫画根目录

这个设置用于告诉 PikPak Cloud 你的漫画放在哪里。

推荐：

```text
/漫画/
```

可以在 PikPak 中创建一个名为 `漫画` 的文件夹，然后把漫画直接放进去。

也可以填写：

```text
/
```

这样会直接扫描 PikPak 根目录的第一层。

## 漫画怎么放

目标很简单：

**下载漫画 → 丢进漫画目录 → 刷新 PikPak Cloud → 直接阅读。**

不需要为了这个扩展强制整理成唯一一种目录结构。

### ZIP / CBZ 直接放在漫画根目录

```text
/漫画/
  火之鸟.zip
  龙珠.cbz
  阿基拉.zip
```

每个 ZIP / CBZ 会被识别为一本漫画。

### 普通图片文件夹

```text
/漫画/
  阿基拉/
    001.jpg
    002.jpg
    003.jpg
```

这个文件夹会被识别为一本漫画，并自动生成一个章节。

### 按卷或章节分文件夹

```text
/漫画/
  剑风传奇/
    Vol.01/
      001.jpg
      002.jpg
    Vol.02/
      001.jpg
      002.jpg
```

`Vol.01` 和 `Vol.02` 会自动识别为不同卷 / 章节。

### 漫画文件夹里放多个 ZIP / CBZ

```text
/漫画/
  灌篮高手/
    Vol.01.cbz
    Vol.02.zip
    Vol.03.cbz
```

每个 ZIP / CBZ 会自动识别为不同卷 / 章节。

### ZIP / CBZ 内部存在多层目录

```text
火之鸟.zip
└── 火之鸟/
    ├── Vol.01/
    │   ├── 001.jpg
    │   └── 002.jpg
    ├── Vol.02/
    │   ├── 001.jpg
    │   └── 002.jpg
    └── Vol.03/
        ├── 001.jpg
        └── 002.jpg
```

PikPak Cloud 会扫描压缩包中的多层目录，并尽量自动折叠无意义的公共外层目录。

## 智能目录识别

PikPak Cloud 会自动处理一些常见情况：

- 使用自然数字排序，例如 `1, 2, 3, 10`，而不是 `1, 10, 2, 3`
- 支持 `Vol.1`、`Vol.02`、`Chapter 4`、`Ch.05` 等常见命名
- 自动忽略 `.DS_Store`、`__MACOSX`、`Thumbs.db`、`desktop.ini` 等垃圾文件
- 如果漫画文件夹里只有少量散落封面图，同时存在真正的章节目录，会尽量避免把封面图误识别成独立章节
- ZIP / CBZ 中存在公共包装目录时，会尽量自动折叠

## 支持格式

### 漫画容器

- ZIP
- CBZ
- 普通图片文件夹

### 图片格式

- JPG
- JPEG
- PNG
- WEBP
- GIF
- AVIF
- JXL

为了获得 Android 和 PC / 浏览器环境下最稳定的兼容性，推荐优先使用 **JPG、PNG 和 WEBP**。

### 当前不支持

- RAR
- CBR
- 7Z
- PDF
- EPUB

这些不支持的格式不会为了“强行打开”而偷偷把整个大型文件完整下载下来。PikPak Cloud 的核心目标之一，就是尽量高效地远程阅读，而不是为了看几页漫画先下载几百 MB 或几 GB。

## ZIP / CBZ 远程读取方式

ZIP 和 CBZ 使用 HTTP Range 请求进行远程读取。

例如你有一个：

```text
1 GB 的火之鸟.zip
```

PikPak Cloud 不需要先把整个 1 GB 文件下载完成才能开始阅读。

它会：

```text
读取压缩包目录信息
        ↓
找到当前页面所在位置
        ↓
只请求需要的字节范围
        ↓
把图片交给阅读器
```

ZIP 的目录信息会缓存在内存中，减少连续阅读时重复请求。

之后由 Mihon、TachiyomiJ2K 或 Suwayomi 负责阅读器中的页面显示、预加载、缓存、翻页和阅读方向。

普通图片文件夹则通过 WebDAV 一张一张按需读取。

## 隐私

PikPak Cloud 不提供、也不运营公共漫画服务器。

每个用户连接的都是 **自己的 PikPak 账号**。

本项目：

- 不提供漫画资源
- 不提供共享 PikPak 账号
- 不包含任何 PikPak 用户凭证
- 不要求用户把凭证发送给项目作者
- 不要求用户把自己的 PikPak 账号分享给其他人

WebDAV 用户名和密码只需要填写在你自己设备上的扩展设置中。

> [!WARNING]
> 不要公开或分享你的 PikPak WebDAV 用户名、密码、Token 或账号凭证。

## 更新

为了保持项目简单，目前不维护永久固定的 Android APK 签名。

如果以后新版 APK 无法直接覆盖已经安装的旧版：

```text
卸载旧版 PikPak Cloud 扩展
→ 安装新版
```

PikPak 中原本存放的漫画文件不会受到任何影响。

## 为什么做这个项目

这个项目一开始只是为了一个很简单的个人需求：

```text
漫画本来已经存在 PikPak
        ↓
为什么还要再完整下载一份到手机？
        ↓
直接通过 Mihon / TachiyomiJ2K / Suwayomi 阅读
```

后来觉得这种用法也可能对其他 PikPak 用户有帮助，所以把工具公开出来。

本项目提供的只是 **工具本身**，不提供任何漫画内容。

## 项目链接

**扩展仓库网址**

```text
https://raw.githubusercontent.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/main/index.pb
```

**Android APK**

[下载 PikPak-Cloud.apk](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.apk)

**Suwayomi JAR**

[下载 PikPak-Cloud.jar](https://github.com/s6677/pikpak-mihon-TachiyomiJ2K-Suwayomi-extension/releases/latest/download/PikPak-Cloud.jar)

## 免责声明

这是一个独立的社区项目。

本项目与以下项目或服务没有隶属、赞助或官方合作关系：

- PikPak
- Mihon
- TachiyomiJ2K
- Suwayomi
- Keiyoushi

用户需要自行负责存放在自己 PikPak 账号中并通过本工具访问的文件。
