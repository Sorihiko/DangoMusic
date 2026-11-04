<p align="center"><img src="docs/logo.png" alt="Dango Music" width="220"></p>

<h1 align="center">Dango Music</h1>

<p align="center"><em>A Minecraft mod that brings NetEase Cloud Music, QQ Music and Kugou into the game: liquid-glass player, 3D in-world lyrics, song card HUD, Dynamic Island and Listen Together.</em></p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Sorihiko/DangoMusic?style=for-the-badge&label=license&labelColor=555555&color=FF5C8A" alt="License"></a>
  <a href="https://github.com/Sorihiko/DangoMusic/stargazers"><img src="https://img.shields.io/github/stars/Sorihiko/DangoMusic?style=for-the-badge&label=stars&labelColor=555555&color=FF5C8A" alt="Stars"></a>
  <a href="https://github.com/Sorihiko/DangoMusic/releases"><img src="https://img.shields.io/github/v/release/Sorihiko/DangoMusic?style=for-the-badge&label=dango&labelColor=555555&color=FF5C8A" alt="Version"></a>
</p>

在 Minecraft 里听网易云音乐 / QQ 音乐 / 酷狗的 mod：液态玻璃播放器、漂浮在世界里的 3D 歌词、Song card、灵动岛，还能和朋友一起听歌。

<p align="center"><img src="docs/screenshots/lyrics-3d.jpg" alt="3D 歌词" width="100%"></p>

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.jpg" alt="首页"> | <img src="docs/screenshots/playlist.jpg" alt="歌单"> | <img src="docs/screenshots/coverflow.jpg" alt="封面墙"> |

## 功能

- **播放器**（默认按 `M`，主菜单也能打开）：推荐、排行榜、我的歌单、搜索，列表 / 封面墙两种视图
- **三个音源**：网易云 / QQ 音乐 / 酷狗，都在游戏内扫码登录（会员歌曲需要对应平台的会员）
- **歌词**：逐字歌词，3D 歌词漂浮在世界里，带发光、抖动、文闪等效果
- **Song card / 灵动岛**：屏幕上显示正在播放
- **歌手页**：点歌手名看热门歌曲、专辑和简介
- **一起听歌**：分享房间码，所有人同步到同一首歌、同一秒；局域网 / 内网穿透联机时直接经房主的游戏同步，不用 Firebase、不用代理；不在同一个服务器、不是同一个 MC 版本也能经 Firebase 一起听
- 设置都在播放器侧栏的 ⚙ 里，可以绑定快捷键

更新内容见 [1.7.3 更新日志](docs/CHANGELOG-1.7.3.md)。

## 安装

在 [Releases](https://github.com/Sorihiko/DangoMusic/releases) 下载对应版本的 jar，放进 `.minecraft/mods`，进游戏按 `M`。

| Minecraft | Fabric（另需 [Fabric API](https://modrinth.com/mod/fabric-api)） | Forge / NeoForge |
|---|---|---|
| 1.8.9 | — | `+1.8.9.jar`（Forge 11.15.1.2318，或 Lunar 等客户端） |
| 1.20.1 | `+1.20.1-fabric.jar` | `+1.20.1-forge.jar`（Forge 47.x） |
| 1.21.1 | `+1.21.1-fabric.jar` | `+1.21.1-neoforge.jar` |
| 1.21.11 | `+1.21.11-fabric.jar` | `+1.21.11-neoforge.jar` |
| 26.1.2 | `+26.1.2-fabric.jar` | `+26.1.2-neoforge.jar` |
| 26.2 | `+26.2-fabric.jar` | `+26.2-neoforge.jar` |
| 26.3 | `+26.3-fabric.jar` | `+26.3-neoforge.jar` |

26.2 / 26.3 选 Vulkan 时也能正常显示（3D 歌词会盖在手上）。配置和登录信息存在 `.minecraft/config/dangomusic/`。

## 构建

需要 JDK 8、21、25，运行 `build-all.bat`，jar 收集到 `dist/`（JDK 不在默认位置时设置 `DANGO_JDK21` / `DANGO_JDK25`）。

<details>
<summary>启用一起听歌（Firebase）</summary>

1. 在 [Firebase 控制台](https://console.firebase.google.com/) 新建项目，开启 **匿名登录**，创建 **Realtime Database**
2. 把 `firebase.properties.example` 复制成 `common/core/src/main/resources/firebase.properties`，填上 `apiKey` 和 `databaseUrl`
3. 数据库规则（也在 [database.rules.json](database.rules.json)，限制了每个字段的类型和长度）：

```json
{
  "rules": {
    "rooms": {
      "$code": {
        ".read": "auth != null",
        ".write": "auth != null && (!data.exists() || data.child('host').val() === auth.uid || data.child('members').child(data.child('host').val()).child('lastSeen').val() < now - 600000)",
        ".validate": "$code.length <= 16 && newData.hasChildren(['host']) && (newData.child('host').val() === auth.uid || newData.child('host').val() === data.child('host').val())",
        "host": { ".validate": "newData.isString() && newData.val().length <= 128" },
        "hostName": { ".validate": "newData.isString() && newData.val().length <= 64" },
        "protocol": { ".validate": "newData.isNumber()" },
        "memberControl": { ".validate": "newData.isBoolean()" },
        "createdAt": { ".validate": "newData.isNumber()" },
        "members": {
          "$uid": {
            ".write": "auth != null && auth.uid === $uid && root.child('rooms').child($code).exists()",
            ".validate": "$uid.length <= 128 && newData.hasChildren(['lastSeen'])",
            "name": { ".validate": "newData.isString() && newData.val().length <= 64" },
            "failedTrack": { ".validate": "newData.isString() && newData.val().length <= 256" },
            "lastSeen": { ".validate": "newData.isNumber()" },
            "$other": { ".validate": false }
          }
        },
        "state": {
          "trackId": { ".validate": "newData.isNumber()" },
          "source": { ".validate": "newData.isString() && newData.val().length <= 16" },
          "nativeId": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "mediaId": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "albumId": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "name": { ".validate": "newData.isString() && newData.val().length <= 1024" },
          "artists": { ".validate": "newData.isString() && newData.val().length <= 2048" },
          "cover": { ".validate": "newData.isString() && newData.val().length <= 4096" },
          "duration": { ".validate": "newData.isNumber()" },
          "pos": { ".validate": "newData.isNumber()" },
          "playing": { ".validate": "newData.isBoolean()" },
          "at": { ".validate": "newData.isNumber()" },
          "$other": { ".validate": false }
        },
        "req": {
          ".write": "auth != null && data.parent().child('members').child(auth.uid).exists() && data.parent().child('memberControl').val() === true",
          "id": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "by": { ".validate": "newData.isString() && newData.val() === auth.uid" },
          "byName": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "op": { ".validate": "newData.isString() && newData.val().length <= 16" },
          "trackId": { ".validate": "newData.isNumber()" },
          "source": { ".validate": "newData.isString() && newData.val().length <= 16" },
          "nativeId": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "mediaId": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "albumId": { ".validate": "newData.isString() && newData.val().length <= 64" },
          "name": { ".validate": "newData.isString() && newData.val().length <= 1024" },
          "artists": { ".validate": "newData.isString() && newData.val().length <= 2048" },
          "cover": { ".validate": "newData.isString() && newData.val().length <= 4096" },
          "duration": { ".validate": "newData.isNumber()" },
          "pos": { ".validate": "newData.isNumber()" },
          "at": { ".validate": "newData.isNumber()" },
          "tracks": { ".validate": "newData.isString() && newData.val().length <= 600000" },
          "$other": { ".validate": false }
        },
        "$other": { ".validate": false }
      }
    },
    "clock": {
      "$uid": {
        ".read": "auth != null && auth.uid == $uid",
        ".write": "auth != null && auth.uid == $uid",
        ".validate": "newData.isNumber()"
      }
    }
  }
}
```

不配置也能构建：局域网 / 内网穿透联机时照样能经房主的游戏一起听，只是不在同一个服务器时没法一起听；只有连同一个 Firebase 项目的人才能经 Firebase 一起听。

不想用 Firebase、想自己架服务器的，可以看第三方的自建服务端 [dango-party-server](https://github.com/Tea-chabai/dango-party-server)（非官方维护）。
</details>

## 说明

使用各音乐平台的非官方接口，仅供个人学习交流，歌曲版权归各平台和版权方所有。

第三方：[Inter](https://github.com/rsms/inter)（OFL 1.1）、[JLayer](https://central.sonatype.com/artifact/javazoom/jlayer/1.0.1)（LGPL）、[JOrbis / Jogg](https://github.com/ymnk/jorbis)（LGPL 2.0+）、[ZXing](https://github.com/zxing/zxing)（Apache 2.0）、Kotlin 标准库（Apache 2.0）。

许可证：[GPL-3.0](LICENSE)
