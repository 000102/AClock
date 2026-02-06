# ProGuard 规则文件
# 添加项目特定的 ProGuard 规则

# 保留 Room 注解处理器生成的类
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# 保留 Kotlin 元数据
-keep class kotlin.Metadata { *; }

# 保留 Compose 相关
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 保留 Coil 图片加载
-keep class coil.** { *; }
-dontwarn coil.**

# 保留 DataStore
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**
