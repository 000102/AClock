package com.example.clockapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.activity.compose.setContent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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
import com.skydoves.cloudy.cloudy
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
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme

// ==================== DataStore 扩展 ====================
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// ==================== Room 数据库实体 ====================
@Entity(tableName = "todos")
data class TodoItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val isStarred: Boolean = false
)

// ==================== Room DAO ====================
@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE isCompleted = 0 ORDER BY isStarred DESC, createdAt DESC")
    fun getAllActiveTodos(): Flow<List<TodoItem>>
    
    @Query("SELECT * FROM todos WHERE isCompleted = 0 ORDER BY isStarred DESC, createdAt DESC")
    suspend fun getAllActiveTodosList(): List<TodoItem>
    
    @Insert
    suspend fun insert(todo: TodoItem): Long
    
    @Query("UPDATE todos SET isCompleted = 1 WHERE id = :id")
    suspend fun markAsCompleted(id: Long)
    
    @Query("UPDATE todos SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)
    
    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)
    
    @Query("SELECT COUNT(*) FROM todos WHERE isCompleted = 0")
    suspend fun getActiveCount(): Int
}

// ==================== Room 数据库 ====================
@Database(entities = [TodoItem::class], version = 2, exportSchema = false)
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
class ClockApplication : Application() {
    lateinit var database: AppDatabase
        private set
    
    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
    }
}

// ==================== ViewModel ====================
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val todoDao = (application as ClockApplication).database.todoDao()
    private val dataStore = application.dataStore
    
    private val _currentTime = MutableStateFlow(System.currentTimeMillis())
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()
    
    val todos: Flow<List<TodoItem>> = todoDao.getAllActiveTodos()
    
    private val _backgroundUri = MutableStateFlow<Uri?>(null)
    val backgroundUri: StateFlow<Uri?> = _backgroundUri.asStateFlow()
    
    private val _showBottomSheet = MutableStateFlow(false)
    val showBottomSheet: StateFlow<Boolean> = _showBottomSheet.asStateFlow()
    
    private val _showDiscardDialog = MutableStateFlow(false)
    val showDiscardDialog: StateFlow<Boolean> = _showDiscardDialog.asStateFlow()
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    
    private val BACKGROUND_URI_KEY = stringPreferencesKey("background_uri")
    
    init {
        viewModelScope.launch {
            while (true) {
                _currentTime.value = System.currentTimeMillis()
                delay(1000)
            }
        }
        
        viewModelScope.launch {
            loadSavedBackgroundUri()
        }
    }
    
    private suspend fun loadSavedBackgroundUri() {
        dataStore.data.map { preferences ->
            preferences[BACKGROUND_URI_KEY]
        }.first()?.let { uriString ->
            try {
                val uri = Uri.parse(uriString)
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
    
    fun setBackgroundUri(uri: Uri?) {
        _backgroundUri.value = uri
        
        viewModelScope.launch {
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        takeFlags
                    )
                    
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
                dataStore.edit { preferences ->
                    preferences.remove(BACKGROUND_URI_KEY)
                }
            }
        }
    }
    
    fun addTodo(content: String) {
        if (content.isBlank()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            todoDao.insert(TodoItem(content = content.trim()))
        }
    }
    
    fun completeTodo(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            todoDao.markAsCompleted(id)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    getApplication(),
                    "已完成一项待办",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    fun toggleStar(id: Long, currentStarred: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            todoDao.setStarred(id, !currentStarred)
        }
    }
    
    fun showAddTodoSheet() {
        _showBottomSheet.value = true
    }
    
    fun hideAddTodoSheet() {
        _showBottomSheet.value = false
        _inputText.value = ""
    }
    
    fun updateInputText(text: String) {
        _inputText.value = text
    }
    
    fun showDiscardDialog() {
        _showDiscardDialog.value = true
    }
    
    fun hideDiscardDialog() {
        _showDiscardDialog.value = false
    }
    
    fun tryCloseBottomSheet(): Boolean {
        return if (_inputText.value.isNotBlank()) {
            _showDiscardDialog.value = true
            false
        } else {
            _showBottomSheet.value = false
            true
        }
    }
    
    fun discardChanges() {
        _inputText.value = ""
        _showDiscardDialog.value = false
        _showBottomSheet.value = false
    }
}

// ==================== 主题配置 ====================
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBB86FC),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFFCF6679),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EE),
    secondary = Color(0xFF03DAC6),
    tertiary = Color(0xFFB00020),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF5F5F5),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
)

@Composable
fun ClockTodoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

// ==================== MainActivity ====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupFullScreen()
        
        setContent {
            ClockTodoTheme {
                ClockTodoApp()
            }
        }
    }
    
    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LOW_PROFILE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullScreen()
        }
    }
}

// ==================== UI Composables ====================

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
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.setBackgroundUri(it)
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        BackgroundImage(uri = backgroundUri)
        
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
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                ClockSection(
                    currentTime = currentTime,
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.75f)
                )
                
                TodoSection(
                    todos = todos,
                    onAddClick = { viewModel.showAddTodoSheet() },
                    onComplete = { viewModel.completeTodo(it) },
                    onStar = { id, starred -> viewModel.toggleStar(id, starred) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.25f)
                )
            }
        }
        
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
        
        if (showDiscardDialog) {
            DiscardChangesDialog(
                onConfirm = { viewModel.discardChanges() },
                onDismiss = { viewModel.hideDiscardDialog() }
            )
        }
    }
}

@Composable
fun BackgroundImage(uri: Uri?) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (uri != null) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = uri,
                    contentScale = ContentScale.Crop
                ),
                contentDescription = "背景图片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0D1B2A),
                                Color(0xFF1B263B),
                                Color(0xFF415A77)
                            )
                        )
                    )
            )
        }
    }
}

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
        CloudyFrostedGlassContainer(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f),
            cornerRadius = 24.dp,
            blurRadius = 25
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = dateFormat.format(Date(currentTime)),
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = timeFormat.format(Date(currentTime)),
                    style = TextStyle(
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White.copy(alpha = 0.95f),
                        letterSpacing = 2.sp
                    )
                )
            }
        }
    }
}

@Composable
fun CloudyFrostedGlassContainer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    blurRadius: Int = 25,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.08f))
                .cloudy(
                    radius = blurRadius,
                    key1 = Unit 
                )
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    
                    drawRect(
                        color = Color.White.copy(alpha = 0.2f),
                        topLeft = Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(size.width, 1f)
                    )
                    
                    drawRect(
                        color = Color.White.copy(alpha = 0.1f),
                        topLeft = Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(1f, size.height)
                    )
                    
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.15f),
                        topLeft = Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            cornerRadius.toPx(),
                            cornerRadius.toPx()
                        ),
                        style = Stroke(width = 1f)
                    )
                }
        )
        
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
fun TodoSection(
    todos: List<TodoItem>,
    onAddClick: () -> Unit,
    onComplete: (Long) -> Unit,
    onStar: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        CloudyFrostedGlassContainer(
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 20.dp,
            blurRadius = 20
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "待办事项",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                if (todos.isEmpty()) {
                    EmptyTodoState(
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    TodoListWithSwipe(
                        todos = todos,
                        onComplete = onComplete,
                        onStar = onStar,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                FloatingActionButton(
                    onClick = onAddClick,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 16.dp),
                    containerColor = Color(0xFFFFB800),
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

@Composable
fun EmptyTodoState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "真是悠闲的一天，\n去喝杯茶吧~",
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            lineHeight = 28.sp
        )
    }
}

@Composable
fun TodoListWithSwipe(
    todos: List<TodoItem>,
    onComplete: (Long) -> Unit,
    onStar: (Long, Boolean) -> Unit,
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
            SwipeableTodoItem(
                todo = todo,
                onComplete = { onComplete(todo.id) },
                onStar = { onStar(todo.id, todo.isStarred) }
            )
        }
    }
}

@Composable
fun SwipeableTodoItem(
    todo: TodoItem,
    onComplete: () -> Unit,
    onStar: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 120.dp.toPx() }
    
    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = offsetX,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "offset"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        if (animatedOffsetX > 0) {
            StarBackground(
                progress = (animatedOffsetX / swipeThreshold).coerceIn(0f, 1f),
                isStarred = todo.isStarred,
                modifier = Modifier.fillMaxSize()
            )
        } else if (animatedOffsetX < 0) {
            CheckBackground(
                progress = (animatedOffsetX.absoluteValue / swipeThreshold).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize()
            )
        }
        
        TodoItemCardWithStar(
            todo = todo,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                offsetX > swipeThreshold -> {
                                    onStar()
                                    offsetX = 0f
                                }
                                offsetX < -swipeThreshold -> {
                                    onComplete()
                                    offsetX = 0f
                                }
                                else -> {
                                    offsetX = 0f
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val newOffset = offsetX + dragAmount
                            offsetX = newOffset.coerceIn(-swipeThreshold * 1.5f, swipeThreshold * 1.5f)
                        }
                    )
                }
        )
    }
}

@Composable
fun StarBackground(
    progress: Float,
    isStarred: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        AnimatedVisibility(
            visible = progress > 0.1f,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier.padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(40.dp * progress.coerceIn(0.5f, 1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isStarred) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "取消收藏",
                            tint = Color(0xFFFFB800).copy(alpha = progress),
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val centerX = size.width / 2
                            val centerY = size.height / 2
                            val outerRadius = size.width / 2
                            val innerRadius = outerRadius * 0.4f
                            
                            val path = Path()
                            for (i in 0 until 10) {
                                val angle = kotlin.math.PI / 2 + i * kotlin.math.PI / 5
                                val radius = if (i % 2 == 0) outerRadius else innerRadius
                                val x = centerX + (kotlin.math.cos(angle) * radius).toFloat()
                                val y = centerY - (kotlin.math.sin(angle) * radius).toFloat()
                                if (i == 0) {
                                    path.moveTo(x, y)
                                } else {
                                    path.lineTo(x, y)
                                }
                            }
                            path.close()
                            
                            drawPath(
                                path = path,
                                color = Color(0xFFFFB800).copy(alpha = progress)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = if (isStarred) "取消标记" else "标记重点",
                    color = Color(0xFFFFB800).copy(alpha = progress),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun CheckBackground(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd
    ) {
        AnimatedVisibility(
            visible = progress > 0.1f,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier.padding(end = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "完成",
                    color = Color(0xFFFFB800).copy(alpha = progress),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Box(
                    modifier = Modifier
                        .size(44.dp * progress.coerceIn(0.5f, 1f))
                        .clip(CircleShape)
                        .background(Color(0xFFFFB800)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "完成",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TodoItemCardWithStar(
    todo: TodoItem,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (todo.isStarred) {
                Color(0xFFFFB800).copy(alpha = 0.15f)
            } else {
                Color.White.copy(alpha = 0.2f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (todo.isStarred) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        val outerRadius = size.width / 2
                        val innerRadius = outerRadius * 0.4f
                        
                        val path = Path()
                        for (i in 0 until 10) {
                            val angle = kotlin.math.PI / 2 + i * kotlin.math.PI / 5
                            val radius = if (i % 2 == 0) outerRadius else innerRadius
                            val x = centerX + (kotlin.math.cos(angle) * radius).toFloat()
                            val y = centerY - (kotlin.math.sin(angle) * radius).toFloat()
                            if (i == 0) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                        }
                        path.close()
                        
                        drawPath(
                            path = path,
                            color = Color(0xFFFFB800)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            
            Text(
                text = todo.content,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

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
    val colorScheme = MaterialTheme.colorScheme
    
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
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "新建待办",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface
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
                        contentDescription = "关闭",
                        tint = colorScheme.onSurface
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.surfaceVariant
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                        color = colorScheme.onSurface
                    ),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                text = "输入待办事项...",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
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
                    Text("取消", color = colorScheme.primary)
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onSave()
                        }
                    },
                    enabled = inputText.isNotBlank(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary
                    )
                ) {
                    Text("保存")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun DiscardChangesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        title = {
            Text(
                text = "是否放弃更改？",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                color = colorScheme.onSurface
            )
        },
        text = {
            Text(
                "您有未保存的内容，确定要放弃吗？",
                color = colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = colorScheme.error
                )
            ) {
                Text("放弃")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = colorScheme.primary
                )
            ) {
                Text("继续编辑")
            }
        }
    )
}
