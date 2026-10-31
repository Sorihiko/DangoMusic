# Forge 1.12.2

基于 1.8.9 平台层适配，复用 common 中的音乐和界面代码。

## 构建

使用 JDK 8，在当前目录运行：

```bat
gradlew.bat build
```

成品：`build/libs/DangoMusic-版本号+1.12.2.jar`。

Forge 14.23.5.2860，Gradle 7.6.4，ForgeGradle 5.1.77，MCP stable_39-1.12。

## 测试

本地构建通过。使用者已在 1.12.2 多模组环境下实测：网易云登录、播放和歌词显示正常，开启 OptiFine 光影时也正常。

一起听歌未测试；QQ、酷狗及其他环境未验证。
