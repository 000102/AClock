# Clock + Todo 应用

一个专为 Android 11 平板设计的时钟 + 待办事项应用，采用 Material You (Material 3) 设计风格。

## 功能特性

### 界面布局
- **左右分栏设计**：左侧 75% 显示数字时钟，右侧 25% 显示待办事项
- **横屏平板优化**：完美适配平板横屏布局
- **自定义背景**：支持从系统相册选择图片作为背景

### 时钟功能
- 实时显示当前时间（时:分:秒）
- 显示当前日期和星期
- 毛玻璃效果容器

### 待办事项功能
- **数据持久化**：使用 Room 数据库本地存储
- **右滑完成**：右滑待办项即可标记为完成
- **空状态提示**：无待办时显示 "真是悠闲的一天，去喝杯茶吧~"
- **添加待办**：点击底部按钮弹出 ModalBottomSheet 录入任务
- **放弃更改拦截**：输入内容未保存时关闭会弹出确认对话框

### 背景图片
- 支持从系统相册选择图片
- **持久化权限**：使用 `takePersistableUriPermission` 确保应用重启后背景不丢失
- 无背景时显示默认渐变背景

## 技术栈

- **UI**: Jetpack Compose + Material 3
- **数据库**: Room
- **数据存储**: DataStore Preferences
- **图片加载**: Coil
- **最低 SDK**: 30 (Android 11)
- **目标 SDK**: 34

## 项目结构

```
ClockTodoApp/
├── .github/workflows/build.yml    # GitHub Actions 自动编译配置
├── app/
│   ├── build.gradle.kts           # 应用级 Gradle 配置
│   ├── proguard-rules.pro         # ProGuard 规则
│   └── src/main/
│       ├── AndroidManifest.xml    # 应用清单
│       ├── java/com/example/clockapp/
│       │   └── MainActivity.kt    # 所有核心逻辑和 UI 代码
│       └── res/                   # 资源文件
├── build.gradle.kts               # 项目级 Gradle 配置
├── settings.gradle.kts            # 项目设置
├── gradle.properties              # Gradle 属性
└── gradlew / gradlew.bat          # Gradle Wrapper 脚本
```

## GitHub Actions 自动编译

项目已配置 GitHub Actions，每次推送代码到 `main` 分支时会自动：
1. 设置 JDK 17
2. 赋予 gradlew 执行权限
3. 运行 `./gradlew assembleDebug`
4. 上传 APK 作为 Artifact

你可以在 GitHub 仓库的 **Actions** 标签页查看编译状态，并在编译完成后下载 APK 文件。

## 本地开发

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

### 构建步骤

1. 克隆仓库
```bash
git clone <your-repo-url>
cd ClockTodoApp
```

2. 使用 Android Studio 打开项目，或命令行构建：
```bash
# Linux/Mac
chmod +x gradlew
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

3. APK 输出位置
```
app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

应用需要以下权限：

- `READ_EXTERNAL_STORAGE` (Android 11 及以下): 读取相册图片
- `READ_MEDIA_IMAGES` (Android 13+): 细粒度的图片读取权限

## 关键代码说明

### 持久化图片权限

```kotlin
// 获取持久化读取权限 - 确保应用重启后仍能访问图片
val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
contentResolver.takePersistableUriPermission(uri, takeFlags)
```

### 毛玻璃效果兼容方案

由于 Android 11 不完全支持原生 blur，使用高 alpha 值的 Surface 模拟：

```kotlin
Surface(
    color = Color.White.copy(alpha = 0.15f), // 半透明模拟毛玻璃
    // ...
)
```

### 横屏布局适配

使用 `Row` + `weight` 实现左右分栏：

```kotlin
Row(modifier = Modifier.fillMaxSize()) {
    // 左侧 75% - 时钟
    ClockSection(modifier = Modifier.weight(0.75f))
    
    // 右侧 25% - 待办
    TodoSection(modifier = Modifier.weight(0.25f))
}
```

## 许可证

MIT License
