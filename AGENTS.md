# Sona 项目协作约定

本文件适用于整个仓库。用户当前明确提出的要求优先于这里的默认约定。

## 项目与代码入口

- 项目根目录：`F:\sona`。在此运行 Gradle 和本地发布脚本。
- 单模块 Android 应用，应用包名为 `app.sona.music`。
- 使用 Kotlin、Jetpack Compose、Material 3 Expressive 和 Media3。版本、SDK 与构建配置以当前 Gradle 文件为准。
- 主要源码目录：`app/src/main/java/app/sona/music/`。
- `SonaApp.kt`：页面切换、返回过渡和迷你播放器。
- `MusicViewModel.kt`：界面状态与播放控制；`PlaybackService.kt`：Media3 播放服务及系统媒体控件。
- `PlayerScreen.kt`、`DeskPlayerScreen.kt`：竖屏、横屏播放页。
- `FloatingLyrics*`、`DanmakuLyricsView.kt`、`LyricApp*`：悬浮歌词、绘制和按应用配色。
- `NeteaseApi.kt`：网易云接口；`MusicSettings.kt`：本地设置。

## 协作与修改

- 默认使用简体中文，说明简洁，先说结果。
- 用户说“先讨论”“先排查”“先不改代码”时，只检查和讨论；确认“开始”后再实施。
- 沿用项目现有视觉风格和已经确认的交互；避免增加未要求的解释文案、开关和操作步骤。
- 优先在共享调用路径修复问题，兼顾应用内按钮、系统媒体控件、横竖屏等入口。
- 保持修改范围清晰，不覆盖无关的本地改动；不为小改动引入新依赖或复杂结构。
- 不自动提交、推送或创建 PR，除非用户明确要求。

## 打包与文件位置

- 本机发布打包使用现有脚本：

  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File F:\sona\tools\local-release.ps1
  ```

- APK 只保留在 Android 项目默认目录：`app/build/outputs/apk/<variant>/`。
- 正式签名包位于 `app/build/outputs/apk/release/`，交付时链接脚本实际生成的 APK，不再复制、另存或改名到其他目录。
- 不再创建项目根目录的 `outputs/`、`backups/`、`review/`，也不创建按日期或任务命名的交付文件夹。
- 未经用户明确要求，不额外保存 APK 副本、源码快照、差异补丁、校验清单或验收说明文件。必要的结果在对话中简要报告。
- 构建工具和发布脚本正常生成的日志、签名校验信息、元数据保留在 `build/` 或 `app/build/` 内；不为交付另建目录。
- 保留现有签名身份和本地签名配置。签名密钥、密码、Cookie、令牌及本机 SDK 配置不得提交到仓库或输出到对话。
- 清理文件前，核实绝对路径和删除范围；禁止把项目根目录的 `outputs/` 与 `app/build/outputs/` 混淆。

## 版本号

- 每次打包前，将 `app/build.gradle.kts` 中持久化的 VersionCode 在当前值基础上加 1，并保存到源码。不要只通过临时构建参数覆盖编号。
- VersionName 只有在用户明确要求修改时才调整；日常修复、界面修改和打包均保持原值，不随 VersionCode 自动变化。
- 用户明确指定的版本名称或构建编号优先；不要擅自增加 Beta 序号、日期或其他后缀。

## 验证与交付

- 当前默认要求是修改后打包 APK，用户自行进行真机验收。
- 除非用户另行要求，不运行测试、lint，不启动模拟器、ADB 安装或设备调试。
- 编译、签名通过与真机行为验证分开说明，不把构建成功当作界面、手势或系统兼容性已验证。
- 用户只要求检查、文档或清理时，不额外打包。
