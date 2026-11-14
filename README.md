# 拾页 · Shiye

漫画与小说放在同一个书架的本机阅读器，Android 端。一个 `WebView` 外壳包住单文件前端，
没有后端、没有账号，数据都留在设备本地。

## 功能

- 首页：最近十本混排，漫画与小说同架，横滑换书
- 小说阅读：卷页 / 平移 / 覆盖 / 淡入 / 无动画五种翻页，可拖着翻、松手落定
- 漫画阅读：单页 / 双页，点中间出进度与亮度，退出记住页码
- 本机 TXT、EPUB 导入即排；内置公版书库，下载后整本离线读
- 书源可自行添加、逐条启用与批量校验，连不上的原因写在列表里
- 阅读统计只认真实进度：翻到最后一页才算读完

## 技术栈

原生 Android（Java）+ `WebView`。没有 Gradle，直接用 Android SDK 的
`aapt2` / `d8` / `apksigner` 构建，见 `build-apk.sh`。

```
AndroidManifest.xml
build-apk.sh              构建脚本（需本机 Android SDK 与 JDK 21）
src/shiye/reader/MainActivity.java   WebView 外壳、文件选择、返回键处理
assets/index.html         前端本体（单文件，HTML/CSS/JS 全内联）
res/                      图标与样式
```

## 构建

脚本以自身所在目录为工程根目录，产物落在同级的 `shiye.apk`。构建前设置：

```bash
export JAVA_HOME=/path/to/jdk
export SHIYE_KS_PASS=你自己的签名口令
./build-apk.sh
```

`SDK` 默认读 `$LOCALAPPDATA/Android/Sdk`，按本机 Android SDK 位置调整。
**本仓库不包含密钥，也不包含任何明文口令**；首次运行会用 `keytool` 生成新的
keystore，且已被 `.gitignore` 排除。要发布 APK，请用自己的密钥重新签名。

## 关于演示素材

`assets/index.html` 引用的封面与漫画页是开发时用的演示素材，**出于版权原因没有随源码公开**。
克隆后直接构建，界面能跑起来但图片位置是空的；请换成自己有权使用的图片，或接入自己的内容源。
书源数据同理，仓库里没有附带任何第三方书源列表。

## 相关

- 在线可交互原型与发布页：<https://blacktimeishere.pages.dev/apps/shiye>
- 作者博客：<https://blacktimeishere.pages.dev/>

## 许可证

[MIT](LICENSE)
