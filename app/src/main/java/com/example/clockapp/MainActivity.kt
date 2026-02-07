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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/* ---------- 数据层 ---------- */

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
    @Query(
        "SELECT * FROM todos WHERE isCompleted = 0 " +
                "ORDER BY isStarred DESC, createdAt DESC"
    )
    fun getAllActiveTodos(): Flow<List<TodoItem>>

    @Insert
    suspend fun insert(todo: TodoItem)

    @Query("UPDATE todos SET isCompleted = 1 WHERE id = :id")
    suspend fun markAsCompleted(id: Long)

    @Query("UPDATE todos SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)
}

@Database(entities = [TodoItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "todo_db"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

class ClockApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}

/* ---------- ViewModel ---------- */

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val todoDao =
        (application as ClockApplication).database.todoDao()

    private val BACKGROUND_KEY = stringPreferencesKey("bg_uri")

    val currentTime = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        System.currentTimeMillis()
    )

    val todos = todoDao.getAllActiveTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _bgUri = MutableStateFlow<Uri?>(null)
    val bgUri = _bgUri.asStateFlow()

    var showSheet by mutableStateOf(false)
    var showDiscardDialog by mutableStateOf(false)
    var inputText by mutableStateOf("")
    var snackbarVisible by mutableStateOf(false)

    init {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data
                .map { it[BACKGROUND_KEY] }
                .firstOrNull()
                ?.let { _bgUri.value = Uri.parse(it) }
        }
    }

    fun setBackground(uri: Uri) {
        _bgUri.value = uri
        viewModelScope.launch {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            getApplication<Application>().dataStore.edit {
                it[BACKGROUND_KEY] = uri.toString()
            }
        }
    }

    fun saveTodo() {
        val text = inputText.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                todoDao.insert(TodoItem(content = text))
            }
            inputText = ""
            showSheet = false
        }
    }

    fun complete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            todoDao.markAsCompleted(id)
        }
        viewModelScope.launch {
            snackbarVisible = true
            delay(1000)
            snackbarVisible = false
        }
    }

    fun toggleStar(id: Long, current: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            todoDao.setStarred(id, !current)
        }
    }
}

/* ---------- Activity ---------- */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ClockTodoApp()
            }
        }
    }
}

/* ---------- UI ---------- */

// 模糊背景Modifier - 兼容所有Android版本
@Composable
fun Modifier.blurredBackground(
    hazeState: HazeState,
    backgroundColor: Color = Color.White.copy(0.15f),
    blurRadius: Float = 20f
): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12+ 使用Haze库的原生RenderEffect
        this.hazeChild(
            state = hazeState,
            style = dev.chrisbanes.haze.HazeStyle(
                backgroundColor = backgroundColor,
                blurRadius = blurRadius.dp
            )
        )
    } else {
        // Android 11及以下使用多层半透明叠加模拟毛玻璃效果
        this
            .drawBehind {
                // 绘制多层渐变模拟模糊
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.7f),
                            backgroundColor.copy(alpha = 0.85f),
                            backgroundColor.copy(alpha = 0.95f)
                        ),
                        center = center,
                        radius = size.maxDimension * 0.8f
                    )
                )
                // 叠加纯色层增强毛玻璃感
                drawRect(backgroundColor.copy(alpha = 0.75f))
            }
            .graphicsLayer {
                // 添加轻微的层次感
                shadowElevation = 8f
                alpha = 0.98f
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockTodoApp() {
    val viewModel: MainViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory
            .getInstance(LocalContext.current.applicationContext as Application)
    )

    val bgUri by viewModel.bgUri.collectAsState()
    val time by viewModel.currentTime.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val hazeState = remember { HazeState() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { it?.let(viewModel::setBackground) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (bgUri != null) {
            Image(
                painter = rememberAsyncImagePainter(bgUri),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .haze(state = hazeState),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = { launcher.launch(arrayOf("image/*")) },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .blurredBackground(
                            hazeState = hazeState,
                            backgroundColor = Color.White.copy(0.15f),
                            blurRadius = 20f
                        )
                        .border(
                            1.dp,
                            Color.White.copy(0.3f),
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Image,
                        "背景",
                        tint = Color.White
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val sdf = remember {
                    SimpleDateFormat("HH:mm", Locale.getDefault())
                }
                Text(
                    text = sdf.format(Date(time)),
                    style = TextStyle(
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Spacer(Modifier.height(8.dp))
                val dateSdf = remember {
                    SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA)
                }
                Text(
                    text = dateSdf.format(Date(time)),
                    fontSize = 18.sp,
                    color = Color.White.copy(0.7f)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
                    .heightIn(min = 240.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .blurredBackground(
                        hazeState = hazeState,
                        backgroundColor = Color.White.copy(0.15f),
                        blurRadius = 20f
                    )
                    .border(
                        1.dp,
                        Color.White.copy(0.3f),
                        RoundedCornerShape(20.dp)
                    )
            ) {
                if (todos.isEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "悠闲的一天,去喝杯茶吧~",
                            fontSize = 16.sp,
                            color = Color.White.copy(0.6f),
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        IconButton(
                            onClick = { viewModel.showSheet = true },
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(0.2f))
                        ) {
                            Icon(Icons.Default.Add, null, tint = Color.White)
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(todos, key = { it.id }) { todo ->
                            OriginalSwipeItem(
                                todo = todo,
                                onComplete = { viewModel.complete(todo.id) },
                                onStar = {
                                    viewModel.toggleStar(
                                        todo.id,
                                        todo.isStarred
                                    )
                                }
                            )
                        }

                        item {
                            IconButton(
                                onClick = { viewModel.showSheet = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(0.2f))
                                    .wrapContentSize(Alignment.Center)
                            ) {
                                Icon(Icons.Default.Add, null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        /* Snackbar */
        if (viewModel.snackbarVisible) {
            Card(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "已完成!",
                    Modifier.padding(
                        horizontal = 24.dp,
                        vertical = 12.dp
                    ),
                    color = Color.White
                )
            }
        }

        /* BottomSheet 常驻 */
        OriginalAddTodoSheet(
            visible = viewModel.showSheet,
            text = viewModel.inputText,
            onTextChange = { viewModel.inputText = it },
            onDismiss = {
                if (viewModel.inputText.isNotBlank()) {
                    viewModel.showDiscardDialog = true
                } else {
                    viewModel.showSheet = false
                }
            },
            onSave = { viewModel.saveTodo() }
        )

        /* 放弃确认 */
        if (viewModel.showDiscardDialog) {
            AlertDialog(
                onDismissRequest = {
                    viewModel.showDiscardDialog = false
                    viewModel.showSheet = true
                },
                title = { Text("继续编辑吗?") },
                text = { Text("您输入的内容尚未保存。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.showDiscardDialog = false
                        viewModel.showSheet = true
                    }) {
                        Text("返回编辑", color = Color(0xFFFFB800))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.inputText = ""
                        viewModel.showDiscardDialog = false
                        viewModel.showSheet = false
                    }) {
                        Text("放弃内容", color = Color.White.copy(0.5f))
                    }
                }
            )
        }
    }
}

/* ---------- Swipe Item ---------- */

@Composable
fun OriginalSwipeItem(
    todo: TodoItem,
    onComplete: () -> Unit,
    onStar: () -> Unit
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val iconScale by remember {
        derivedStateOf {
            (kotlin.math.abs(offsetX.value) / 150f)
                .coerceIn(0f, 1.2f)
        }
    }

    Box(Modifier.fillMaxWidth().height(64.dp)) {
        
        // 左滑背景 - 完成(绿色圆圈+对勾)
        if (offsetX.value > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier
                        .size((48 * iconScale).dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size((24 * iconScale).dp)
                    )
                }
            }
        }
        
        // 右滑背景 - 星标(星号)
        if (offsetX.value < 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFB800),
                    modifier = Modifier
                        .size((40 * iconScale).dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
            }
        }

        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxSize()
                .pointerInput(todo) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo(offsetX.value + dragAmount)
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value > 150f -> onComplete()
                                    offsetX.value < -150f -> onStar()
                                }
                                offsetX.animateTo(
                                    0f,
                                    spring(Spring.DampingRatioMediumBouncy)
                                )
                            }
                        }
                    )
                },
            color = if (todo.isStarred)
                Color(0xFFFFB800).copy(0.15f)
            else
                Color.White.copy(0.12f),
            shape = RoundedCornerShape(14.dp),
            border = if (todo.isStarred)
                BorderStroke(
                    1.dp,
                    Color(0xFFFFB800).copy(0.4f)
                )
            else null
        ) {
            Row(
                Modifier.fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (todo.isStarred) {
                    Icon(
                        Icons.Default.Star,
                        null,
                        tint = Color(0xFFFFB800),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    todo.content,
                    color = Color.White,
                    fontSize = 17.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/* ---------- Bottom Sheet ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalAddTodoSheet(
    visible: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    LaunchedEffect(visible) {
        if (visible) sheetState.show()
        else sheetState.hide()
    }

    if (sheetState.isVisible) {
        BackHandler { onDismiss() }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = Color(0xFF1C1C1E)
        ) {
            Column(
                Modifier.padding(24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "新建待办事项",
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFB800),
                        unfocusedBorderColor = Color.White.copy(0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    placeholder = {
                        Text(
                            "想做点什么?",
                            color = Color.White.copy(0.4f)
                        )
                    }
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color.White.copy(0.6f))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onSave,
                        enabled = text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFB800)
                        )
                    ) {
                        Text("添加", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
