# FitLog

FitLog 是一款 Android 力量训练记录助手，采用轻像素风格。训练功能可离线使用，无需登录。

## 主要功能

- 记录自由训练和模板训练，支持动作 GIF、组数、重量、次数、休息计时与训练历史。
- 创建训练模板和循环计划，查看训练统计与动作趋势。
- 邮箱登录后可添加好友、私聊、发布动态，并选择是否共享训练信息。
- 支持本地 JSON 导入导出、主动云端备份恢复，以及应用内检查更新。

## 使用方法

1. 在 Android 8.0 或更高版本的设备上安装 [FitLog APK](https://jrqmjpnitwfuqlbpvrzu.supabase.co/storage/v1/object/public/fitlog-updates/FitLog-13.apk)。
2. 在「动作」中浏览动作、创建模板和计划；在「主页」选择自由训练或模板并开始记录。
3. 在「好友」中查看好友和动态，在「我的」中登录账号、管理个人资料及进入设置。
4. 在「我的 → 设置」中导出本地 JSON，或登录后使用云端备份和恢复。聊天记录保存在本机，换设备前需主动备份；本地 JSON 不包含图片文件。
5. 在主页检查更新，下载后按系统提示覆盖安装。

## 本地构建

使用 Android Studio 打开项目，配置 JDK 21 和 Android SDK 36，连接设备后运行 `app`；也可在项目根目录执行：

```bash
./gradlew :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。如需连接社交和云端服务，在 `.local-tools/social.properties` 中配置 `SUPABASE_URL` 和 `SUPABASE_PUBLISHABLE_KEY`，并在对应 Supabase 项目执行 `supabase/migrations/` 中的迁移。
