# PikPak Cloud for Mihon / TachiyomiJ2K / Suwayomi

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
3. PikPak 会显示服务器地址、WebDAV 用户名和 WebDAV 密码。请不要把这些凭证分享给别人。  
   PikPak will show the server URL, WebDAV username, and WebDAV password. Keep them private.
4. 在 PikPak Cloud 来源设置里填入这些信息，再填写漫画根目录。  
   Enter those values in PikPak Cloud source settings, then set your manga root folder.

PikPak 官方目前说明 WebDAV 面向 Premium 用户。  
PikPak currently documents WebDAV as a Premium feature.

## 四个设置项 / The four settings

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
这是 PikPak 里存放漫画 ZIP/CBZ 的文件夹路径，例如 `/漫画/`。  
This is the PikPak folder containing your manga ZIP/CBZ files, for example `/漫画/`.

插件只列出这个目录第一层的 ZIP/CBZ，每个压缩包作为一本漫画。填 `/` 可以扫描 PikPak 根目录第一层。  
The extension lists ZIP/CBZ files directly inside this folder. Each archive becomes one manga. Use `/` to scan the first level of the PikPak root.

## 压缩包结构 / Archive structure

Example:

```
/漫画/
  火之鸟.zip
    火之鸟/
      Vol.01/
        001.jpg
        002.jpg
      Vol.02/
        001.jpg
```

ZIP/CBZ pages are read remotely with HTTP Range access. The whole archive does not need to be downloaded before reading.

## 更新 / Updating

为了保持这个项目简单，本仓库不维护固定 APK 签名。以后如果新版不能覆盖旧版，请卸载旧扩展后重新安装。  
To keep the project simple, this repository does not maintain a permanent APK signing key. If a future build cannot replace an old one, uninstall the old extension and install the new build.

## Privacy / 隐私

WebDAV credentials stay in each user's own extension settings. Never publish or share them.

## Disclaimer

Independent community project. Not affiliated with PikPak, Mihon, TachiyomiJ2K, Suwayomi, or Keiyoushi.
