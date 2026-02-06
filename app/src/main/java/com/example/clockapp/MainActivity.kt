package com.example.clockapp

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DismissDirection
import androidx.compose.material3.DismissValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismiss
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDismissState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================== DataStore 扩展 ====================
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// ==================== Room 数据库实体 ====================

/**
 * 待办事项实体类
 * 使用 Room 注解定义数据库表结构
 */
@Entity(tableName = "todos")
data class TodoItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false
)

// ==================== Room DAO ====================

/**
 * 待办事项数据访问对象
 * 定义所有数据库操作
 */
@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE isCompleted = 0 ORDER BY createdAt DESC")
    fun getAllActiveTodos(): Flow<List<TodoItem>>
    
    @Query("SELECT * FROM todos WHERE isCompleted = 0 ORDER BY createdAt DESC")
    suspend fun getAllActiveTodosList(): List<TodoItem>
    
    @Insert
    suspend fun insert(todo: TodoItem): Long
    
    @Query("UPDATE todos SET isCompleted = 1 WHERE id = :id")
    suspend fun markAsCompleted(id: Long)
    
    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)
    
    @Query("SELECT COUNT(*) FROM todos WHERE isCompleted = 0")
    suspend fun getActiveCount(): Int
}

// ==================== Room 数据库 ====================

/**
 * 应用数据库类
 * 包含待办事项表
 */
@Database(entities = [TodoItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "clock_todo_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==================== Application 类 ====================

/**
 * 应用入口类
 * 初始化全局资源
 */
class ClockApplication : Application() {
    lateinit var database: AppDatabase
        private set
    
    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
    }
}

// ==================== ViewModel ====================

/**
 * 应用主 ViewModel
 * 管理所有业务逻辑和状态
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val todoDao = (application as ClockApplication).database.todoDao()
    private val dataStore = application.dataStore
    
    // 当前时间状态
    private val _currentTime = MutableStateFlow(System.currentTimeMillis())
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()
    
    // 待办事项列表
    val todos: Flow<List<TodoItem>> = todoDao.getAllActiveTodos()
    
    // 背景图片 URI
    private val _backgroundUri = MutableStateFlow<Uri?>(null)
    val backgroundUri: StateFlow<Uri?> = _backgroundUri.asStateFlow()
    
    // 底部弹窗显示状态
    private val _showBottomSheet = MutableStateFlow(false)
    val showBottomSheet: StateFlow<Boolean> = _showBottomSheet.asStateFlow()
    
    // 放弃更改对话框显示状态
    private val _showDiscardDialog = MutableStateFlow(false)
    val showDiscardDialog: StateFlow<Boolean> = _showDiscardDialog.asStateFlow()
    
    // 输入框内容
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    
    // DataStore 键
    private val BACKGROUND_URI_KEY = stringPreferencesKey("background_uri")
    
    init {
        // 启动时钟更新
        viewModelScope.launch {
            while (true) {
                _currentTime.value = System.currentTimeMillis()
                delay(1000) // 每秒更新一次
            }
        }
        
        // 加载保存的背景图片 URI
        viewModelScope.launch {
            loadSavedBackgroundUri()
        }
    }
    
    /**
     * 从 DataStore 加载保存的背景图片 URI
     */
    private suspend fun loadSavedBackgroundUri() {
        dataStore.data.map { preferences ->
            preferences[BACKGROUND_URI_KEY]
        }.first()?.let { uriString ->
            try {
                val uri = Uri.parse(uriString)
                // 验证权限是否仍然有效
                val hasPermission = try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.close()
                    true
                } catch (e: Exception) {
                    false
                }
                if (hasPermission) {
                    _backgroundUri.value = uri
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 设置背景图片 URI 并获取持久化权限
     * 【重要】调用 takePersistableUriPermission 确保应用重启后权限不丢失
     */
    fun setBackgroundUri(uri: Uri?) {
        _backgroundUri.value = uri
        
        viewModelScope.launch {
            if (uri != null) {
                try {
                    // 【关键】获取持久化读取权限
                    // 这是 Android 11 上保持图片访问权限的关键
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        takeFlags
                    )
                    
                    // 保存 URI 到 DataStore
                    dataStore.edit { preferences ->
                        preferences[BACKGROUND_URI_KEY] = uri.toString()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(
                        getApplication(),
                        "无法获取图片持久化权限",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // 清除背景
                dataStore.edit { preferences ->
                    preferences.remove(BACKGROUND_URI_KEY)
                }
            }
        }
    }
    
    /**
     * 添加待办事项
     */
    fun addTodo(content: String) {
        if (content.isBlank()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            todoDao.insert(TodoItem(content = content.trim()))
        }
    }
    
    /**
     * 将待办事项标记为已完成
     */
    fun completeTodo(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            todoDao.markAsCompleted(id)
            // 显示 Toast 需要在主线程
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    getApplication(),
                    "已完成一项待办",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    /**
     * 显示底部弹窗
     */
    fun showAddTodoSheet() {
        _showBottomSheet.value = true
    }
    
    /**
     * 隐藏底部弹窗
     */
    fun hideAddTodoSheet() {
        _showBottomSheet.value = false
        _inputText.value = ""
    }
    
    /**
     * 更新输入框文本
     */
    fun updateInputText(text: String) {
        _inputText.value = text
    }
    
    /**
     * 显示放弃更改对话框
     */
    fun showDiscardDialog() {
        _showDiscardDialog.value = true
    }
    
    /**
     * 隐藏放弃更改对话框
     */
    fun hideDiscardDialog() {
        _showDiscardDialog.value = false
    }
    
    /**
     * 检查是否可以关闭弹窗
     * 如果有未保存的内容，显示确认对话框
     */
    fun tryCloseBottomSheet(): Boolean {
        return if (_inputText.value.isNotBlank()) {
            _showDiscardDialog.value = true
            false
        } else {
            _showBottomSheet.value = false
            true
        }
    }
    
    /**
     * 放弃更改并关闭弹窗
     */
    fun discardChanges() {
        _inputText.value = ""
        _showDiscardDialog.value = false
        _showBottomSheet.value = false
    }
}

// ==================== MainActivity ====================

/**
 * 主 Activity
 * 应用的入口点
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                ClockTodoApp()
            }
        }
    }
}

// ==================== UI Composables ====================

/**
 * 应用主界面
 * 采用左右分栏布局适配平板横屏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockTodoApp() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            (context.applicationContext as ClockApplication)
        )
    )
    
    val currentTime by viewModel.currentTime.collectAsState()
    val todos by viewModel.todos.collectAsState(initial = emptyList())
    val backgroundUri by viewModel.backgroundUri.collectAsState()
    val showBottomSheet by viewModel.showBottomSheet.collectAsState()
    val showDiscardDialog by viewModel.showDiscardDialog.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    
    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // 获取持久化权限
            viewModel.setBackgroundUri(it)
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // 背景图片
        BackgroundImage(uri = backgroundUri)
        
        // 主内容区域
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    actions = {
                        IconButton(
                            onClick = {
                                // 打开图片选择器，只选择图片
                                imagePickerLauncher.launch(arrayOf("image/*"))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = "更换背景",
                                tint = Color.White
                            )
                        }
                    }
                )
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
            // 横屏平板布局：左右分栏
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // 左侧 75% - 时钟区域
                ClockSection(
                    currentTime = currentTime,
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.75f)
                )
                
                // 右侧 25% - 待办事项区域
                TodoSection(
                    todos = todos,
                    onAddClick = { viewModel.showAddTodoSheet() },
                    onComplete = { viewModel.completeTodo(it) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.25f)
                )
            }
        }
        
        // 添加待办底部弹窗
        if (showBottomSheet) {
            AddTodoBottomSheet(
                inputText = inputText,
                onInputChange = { viewModel.updateInputText(it) },
                onDismiss = { viewModel.tryCloseBottomSheet() },
                onSave = {
                    viewModel.addTodo(inputText)
                    viewModel.hideAddTodoSheet()
                },
                onShowDiscardDialog = { viewModel.showDiscardDialog() }
            )
        }
        
        // 放弃更改确认对话框
        if (showDiscardDialog) {
            DiscardChangesDialog(
                onConfirm = { viewModel.discardChanges() },
                onDismiss = { viewModel.hideDiscardDialog() }
            )
        }
    }
}

/**
 * 背景图片组件
 * 显示用户选择的背景图，如果没有则显示默认渐变背景
 */
@Composable
fun BackgroundImage(uri: Uri?) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (uri != null) {
            // 使用 Coil 加载图片
            Image(
                painter = rememberAsyncImagePainter(
                    model = uri,
                    contentScale = ContentScale.Crop
                ),
                contentDescription = "背景图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // 添加暗色遮罩以提高文字可读性
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )
        } else {
            // 默认渐变背景
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF1A237E),
                                Color(0xFF3949AB),
                                Color(0xFF5C6BC0)
                            )
                        )
                    )
            )
        }
    }
}

/**
 * 时钟显示区域
 * 占屏幕左侧 75%
 */
@Composable
fun ClockSection(
    currentTime: Long,
    modifier: Modifier = Modifier
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        // 毛玻璃效果容器
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.15f), // 半透明白色模拟毛玻璃
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 日期
                Text(
                    text = dateFormat.format(Date(currentTime)),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 时间
                Text(
                    text = timeFormat.format(Date(currentTime)),
                    style = TextStyle(
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 4.sp
                    )
                )
            }
        }
    }
}

/**
 * 待办事项区域
 * 占屏幕右侧 25%
 */
@Composable
fun TodoSection(
    todos: List<TodoItem>,
    onAddClick: () -> Unit,
    onComplete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 毛玻璃效果容器
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.15f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 标题
                Text(
                    text = "待办事项",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // 待办列表或空状态
                if (todos.isEmpty()) {
                    EmptyTodoState(
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    TodoList(
                        todos = todos,
                        onComplete = onComplete,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // 添加按钮
                FloatingActionButton(
                    onClick = onAddClick,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加待办",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * 空状态显示
 * 当没有待办事项时显示
 */
@Composable
fun EmptyTodoState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "真是悠闲的一天，\n去喝杯茶吧~",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            lineHeight = 28.sp
        )
    }
}

/**
 * 待办事项列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoList(
    todos: List<TodoItem>,
    onComplete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = todos,
            key = { it.id }
        ) { todo ->
            val dismissState = rememberDismissState(
                confirmValueChange = { dismissValue ->
                    if (dismissValue == DismissValue.DismissedToStart ||
                        dismissValue == DismissValue.DismissedToEnd
                    ) {
                        onComplete(todo.id)
                        true
                    } else {
                        false
                    }
                }
            )
            
            SwipeToDismiss(
                state = dismissState,
                directions = setOf(DismissDirection.EndToStart, DismissDirection.StartToEnd),
                background = {
                    val direction = dismissState.dismissDirection
                    val color = when (direction) {
                        DismissDirection.StartToEnd -> Color(0xFF4CAF50)
                        DismissDirection.EndToStart -> Color(0xFF4CAF50)
                        null -> Color.Transparent
                    }
                    
                    val alignment = when (direction) {
                        DismissDirection.StartToEnd -> Alignment.CenterStart
                        DismissDirection.EndToStart -> Alignment.CenterEnd
                        null -> Alignment.Center
                    }
                    
                    val icon = Icons.Default.Delete
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(color)
                            .padding(horizontal = 20.dp),
                        contentAlignment = alignment
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = "完成",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                dismissContent = {
                    TodoItemCard(todo = todo)
                }
            )
        }
    }
}

/**
 * 单个待办事项卡片
 */
@Composable
fun TodoItemCard(todo: TodoItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.25f)
        )
    ) {
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/**
 * 添加待办底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTodoBottomSheet(
    inputText: String,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShowDiscardDialog: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val scope = rememberCoroutineScope()
    
    // 拦截返回键
    BackHandler {
        if (inputText.isNotBlank()) {
            onShowDiscardDialog()
        } else {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = {
            if (inputText.isNotBlank()) {
                onShowDiscardDialog()
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "新建待办",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onShowDiscardDialog()
                        } else {
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 输入框
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                text = "输入待办事项...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onShowDiscardDialog()
                        } else {
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        }
                    }
                ) {
                    Text("取消")
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onSave()
                        }
                    },
                    enabled = inputText.isNotBlank()
                ) {
                    Text("保存")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 放弃更改确认对话框
 */
@Composable
fun DiscardChangesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "是否放弃更改？",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text("您有未保存的内容，确定要放弃吗？")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("放弃")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("继续编辑")
            }
        }
    )
}
