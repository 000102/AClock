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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.lifecycle.viewModelScope // 必须手动导入
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import coil.compose.rememberAsyncImagePainter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ==================== 1. 数据模型与 ViewModel ====================

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
    
    val currentTime = flow { while(true) { emit(System.currentTimeMillis()); delay(1000) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())
    
    val todos = todoDao.getAllActiveTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _bgUri = MutableStateFlow<Uri?>(null)
    val bgUri = _bgUri.asStateFlow()
    
    var showSheet by mutableStateOf(false)
    var showDiscardDialog by mutableStateOf(false)
    var inputText by mutableStateOf("")

    init {
        viewModelScope.launch {
            // 修复私有访问错误：改用 getApplication<Application>()
            getApplication<Application>().dataStore.data.map { it[BACKGROUND_KEY] }.firstOrNull()?.let { 
                _bgUri.value = Uri.parse(it) 
            }
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

// ==================== 2. 主页面 ====================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ClockTodoApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockTodoApp() {
    val viewModel: MainViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(LocalContext.current.applicationContext as Application))
    val bgUri by viewModel.bgUri.collectAsState()
    val time by viewModel.currentTime.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val hazeState = remember { HazeState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { viewModel.setBackground(it) } }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            if (bgUri != null) {
                Image(rememberAsyncImagePainter(bgUri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.3f)))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF141E30), Color(0xFF243B55)))))
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = { IconButton(onClick = { launcher.launch(arrayOf("image/*")) }) { Icon(Icons.Default.Image, null, tint = Color.White) } }
                )
            }
        ) { p ->
            Row(Modifier.padding(p).fillMaxSize().padding(24.dp)) {
                Box(Modifier.weight(0.7f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    GlassBox(hazeState, Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.6f)) {
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
                        Text(timeStr, style = TextStyle(fontSize = 90.sp, fontWeight = FontWeight.ExtraLight, color = Color.White))
                    }
                }
                
                Box(Modifier.weight(0.35f).fillMaxHeight()) {
                    GlassBox(hazeState, Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("待办", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(todos, key = { it.id }) { item ->
                                    SwipeableItem(item, onComplete = { viewModel.complete(item.id) }, onStar = { viewModel.toggleStar(item.id, item.isStarred) })
                                }
                            }
                            FloatingActionButton(onClick = { viewModel.showSheet = true }, containerColor = Color(0xFFFFB800), shape = CircleShape, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Default.Add, null, tint = Color.White) }
                        }
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
                title = { Text("放弃更改？") },
                text = { Text("您输入的内容尚未保存。") },
                confirmButton = { TextButton(onClick = { viewModel.inputText = ""; viewModel.showDiscardDialog = false; viewModel.showSheet = false }) { Text("放弃", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { viewModel.showDiscardDialog = false }) { Text("继续编辑") } }
            )
        }
    }
}

// ==================== 3. 核心修复组件 ====================

@Composable
fun GlassBox(hazeState: HazeState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            // 修复 HazeChild 语法报错：改用直接传参方式，不使用 lambda
            .hazeChild(
                state = hazeState, 
                shape = RoundedCornerShape(28.dp),
                tint = Color.White.copy(alpha = 0.12f),
                blurRadius = 25.dp
            )
            .border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun SwipeableItem(todo: TodoItem, onComplete: () -> Unit, onStar: () -> Unit) {
    val density = LocalDensity.current
    val threshold = with(density) { 75.dp.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth().height(64.dp)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            if (offsetX.value > 0) Text("标记重要", color = Color(0xFFFFB800), modifier = Modifier.align(Alignment.CenterStart))
            else if (offsetX.value < 0) Icon(Icons.Default.Check, null, tint = Color.Green, modifier = Modifier.align(Alignment.CenterEnd))
        }

        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxSize()
                .pointerInput(todo.isStarred) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value > threshold) onStar()
                                else if (offsetX.value < -threshold) onComplete()
                                offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                        }
                    )
                },
            colors = CardDefaults.cardColors(containerColor = if(todo.isStarred) Color(0xFFFFB800).copy(0.2f) else Color.White.copy(0.12f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if(todo.isStarred) Icon(Icons.Default.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.size(20.dp))
                Text(todo.content, color = Color.White, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTodoSheet(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
        // 修复：如果 windowInsets 报错，可以暂时移除该参数，或确保版本正确
    ) {
        Column(Modifier.padding(24.dp).padding(bottom = 32.dp)) {
            Text("新建待办事项", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("想做点什么？") }
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = onSave, enabled = text.isNotBlank()) { Text("添加") }
            }
        }
    }
}
