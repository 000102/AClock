package com.example.clockapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// --- 数据库与逻辑部分 (保持稳定) ---
val Context.dataStore by preferencesDataStore(name = "settings")

@Entity(tableName = "todos")
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val isStarred: Boolean = false
)

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE isCompleted = 0 ORDER BY isStarred DESC, createdAt DESC")
    fun getAllActiveTodos(): Flow<List<TodoItem>>
    @Insert suspend fun insert(todo: TodoItem): Long
    @Query("UPDATE todos SET isCompleted = 1 WHERE id = :id") suspend fun markAsCompleted(id: Long)
    @Query("UPDATE todos SET isStarred = :starred WHERE id = :id") suspend fun setStarred(id: Long, starred: Boolean)
}

@Database(entities = [TodoItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "todo_db").fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

class ClockApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val todoDao = (application as ClockApplication).database.todoDao()
    private val BACKGROUND_KEY = stringPreferencesKey("bg_uri")
    val currentTime = flow { while(true) { emit(System.currentTimeMillis()); delay(1000) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())
    val todos = todoDao.getAllActiveTodos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _bgUri = MutableStateFlow<Uri?>(null)
    val bgUri = _bgUri.asStateFlow()
    var showSheet by mutableStateOf(false)
    var showDiscardDialog by mutableStateOf(false)
    var inputText by mutableStateOf("")

    init {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data.map { it[BACKGROUND_KEY] }.firstOrNull()?.let { _bgUri.value = Uri.parse(it) }
        }
    }
    fun setBackground(uri: Uri) {
        _bgUri.value = uri
        viewModelScope.launch { 
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getApplication<Application>().dataStore.edit { it[BACKGROUND_KEY] = uri.toString() } 
        }
    }
    fun saveTodo() {
        if (inputText.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { todoDao.insert(TodoItem(content = inputText.trim())) }
        inputText = ""; showSheet = false
    }
    fun complete(id: Long) = viewModelScope.launch(Dispatchers.IO) { todoDao.markAsCompleted(id) }
    fun toggleStar(id: Long, current: Boolean) = viewModelScope.launch(Dispatchers.IO) { todoDao.setStarred(id, !current) }
}

// --- UI 核心 ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { ClockTodoApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockTodoApp() {
    val viewModel: MainViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(LocalContext.current.applicationContext as Application))
    val bgUri by viewModel.bgUri.collectAsState()
    val time by viewModel.currentTime.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { viewModel.setBackground(it) } }

    Box(Modifier.fillMaxSize()) {
        // 背景层
        if (bgUri != null) {
            Image(rememberAsyncImagePainter(bgUri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF141E30), Color(0xFF243B55)))))
        }

        Row(Modifier.fillMaxSize().padding(24.dp).statusBarsPadding()) {
            // 左侧：时间和日期 (占 3/4)
            Column(Modifier.weight(0.75f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                GlassContainer(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.7f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 放大时间显示
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)), style = TextStyle(fontSize = 130.sp, fontWeight = FontWeight.Thin, color = Color.White))
                        // 找回日期显示
                        Text(SimpleDateFormat("MM月dd日 EEEE", Locale.CHINESE).format(Date(time)), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Light, color = Color.White.copy(0.8f)))
                        Text(SimpleDateFormat("ss", Locale.getDefault()).format(Date(time)), style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraLight, color = Color(0xFFFFB800)))
                    }
                }
            }

            // 右侧：待办列表 (占 1/4)
            Column(Modifier.weight(0.25f).fillMaxHeight().padding(start = 16.dp)) {
                GlassContainer(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("待办事项", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White))
                            IconButton(onClick = { launcher.launch(arrayOf("image/*")) }) { Icon(Icons.Default.Image, null, tint = Color.White.copy(0.5f), modifier = Modifier.size(20.dp)) }
                        }
                        Spacer(Modifier.height(16.dp))
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(todos, key = { it.id }) { item ->
                                TodoSwipeItem(item, onComplete = { viewModel.complete(item.id) }, onStar = { viewModel.toggleStar(item.id, item.isStarred) })
                            }
                        }
                        FloatingActionButton(onClick = { viewModel.showSheet = true }, containerColor = Color(0xFFFFB800), shape = CircleShape, modifier = Modifier.align(Alignment.CenterHorizontally).size(56.dp)) { Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                    }
                }
            }
        }

        if (viewModel.showSheet) {
            AddTodoSheet(
                text = viewModel.inputText,
                onTextChange = { viewModel.inputText = it },
                onDismiss = { if (viewModel.inputText.isNotBlank()) viewModel.showDiscardDialog = true else viewModel.showSheet = false },
                onSave = { viewModel.saveTodo() }
            )
        }

        if (viewModel.showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showDiscardDialog = false },
                title = { Text("继续编辑？") },
                text = { Text("当前有未保存的内容。") },
                confirmButton = { TextButton(onClick = { viewModel.showDiscardDialog = false }) { Text("返回", color = Color(0xFFFFB800)) } },
                dismissButton = { TextButton(onClick = { viewModel.inputText = ""; viewModel.showDiscardDialog = false; viewModel.showSheet = false }) { Text("放弃", color = Color.White.copy(0.6f)) } }
            )
        }
    }
}

// --- 核心修复：Android 11 强制模糊容器 ---
@Composable
fun GlassContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.15f)) // 基础半透明层
            .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(28.dp))
            .blur(20.dp), // 针对 Android 11+ 的 Compose 级模糊，调低半径让其“清晰一点”
        contentAlignment = Alignment.Center
    ) {
        // 实际上层内容不能被模糊，所以需要叠加
        Box(Modifier.background(Color.Transparent), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

// --- 核心修复：找回原本的绿色对勾圆圈和星星 ---
@Composable
fun TodoSwipeItem(todo: TodoItem, onComplete: () -> Unit, onStar: () -> Unit) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    Box(Modifier.fillMaxWidth().height(60.dp)) {
        // 底层图形逻辑
        Box(Modifier.fillMaxSize()) {
            if (offsetX.value > 50f) { // 右划：显示绿色圆圈对勾
                Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(32.dp).background(Color(0xFF4CAF50), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            } else if (offsetX.value < -50f) { // 左划：显示黄色星星
                Icon(Icons.Default.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(28.dp))
            }
        }

        // 上层卡片 (修复：确保文字颜色为纯白，防止不可见)
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxSize()
                .pointerInput(todo.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value > 150f) onComplete()
                                else if (offsetX.value < -150f) onStar()
                                offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                        }
                    )
                },
            color = if (todo.isStarred) Color(0xFFFFB800).copy(0.2f) else Color.White.copy(0.1f),
            shape = RoundedCornerShape(12.dp),
            border = if (todo.isStarred) borderStroke(1.dp, Color(0xFFFFB800).copy(0.5f)) else null
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (todo.isStarred) Icon(Icons.Default.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = todo.content, style = TextStyle(color = Color.White, fontSize = 16.sp), maxLines = 1)
            }
        }
    }
}

private fun borderStroke(width: androidx.compose.ui.unit.Dp, color: Color) = androidx.compose.foundation.BorderStroke(width, color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTodoSheet(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = Color(0xFF1C1C1E)
    ) {
        Column(Modifier.padding(24.dp).navigationBarsPadding().imePadding()) {
            Text("添加待办", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = Color.White),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Color(0xFFFFB800),
                    unfocusedBorderColor = Color.White.copy(0.3f)
                ),
                placeholder = { Text("请输入文本", color = Color.White.copy(0.4f)) }
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
                enabled = text.isNotBlank()
            ) {
                Text("添加", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
