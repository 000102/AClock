package com.example.clockapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import kotlin.math.abs
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
    private val TODO_BOX_HIDDEN_KEY = booleanPreferencesKey("todo_box_manually_hidden")

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

    private val _isBoxManuallyHidden = MutableStateFlow(false)
    val isBoxManuallyHidden = _isBoxManuallyHidden.asStateFlow()

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
        viewModelScope.launch {
            getApplication<Application>().dataStore.data
                .map { it[TODO_BOX_HIDDEN_KEY] ?: false }
                .collect { _isBoxManuallyHidden.value = it }
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

    fun setBoxManuallyHidden(hidden: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[TODO_BOX_HIDDEN_KEY] = hidden
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

enum class TodoBoxState {
    HIDDEN,      // 隐藏在屏幕外
    NORMAL,      // 正常显示（仅横屏）
    EXPANDED     // 全屏展开
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
    val isBoxManuallyHidden by viewModel.isBoxManuallyHidden.collectAsState()
    val hazeState = remember { HazeState() }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 待办盒子状态
    var boxState by remember { mutableStateOf(TodoBoxState.NORMAL) }
    
    // 根据屏幕方向和手动隐藏状态决定初始状态
    LaunchedEffect(isLandscape, isBoxManuallyHidden) {
        boxState = when {
            !isLandscape -> TodoBoxState.HIDDEN // 竖屏自动隐藏
            isBoxManuallyHidden -> TodoBoxState.HIDDEN // 横屏但用户手动隐藏
            else -> TodoBoxState.NORMAL // 横屏正常显示
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { it?.let(viewModel::setBackground) }

    // 盒子滑动偏移
    val boxOffsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 展开进度 (0f = 正常, 1f = 全屏展开) - 使用Animatable实现即时跟随
    val expandProgress = remember { Animatable(0f) }

    // 时间缩放和透明度
    val timeScale = 1f - expandProgress.value * 0.3f
    val timeAlpha = 1f - expandProgress.value

    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(boxState) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        // 只在非待办盒子区域才触发背景选择
                        val boxVisible = boxState != TodoBoxState.HIDDEN
                        val inBoxArea = when (boxState) {
                            TodoBoxState.EXPANDED -> true // 全屏时整个区域都是盒子
                            TodoBoxState.NORMAL -> {
                                // 正常时判断是否在右侧盒子区域
                                val boxStartX = screenWidthPx * 0.75f
                                offset.x >= boxStartX
                            }
                            TodoBoxState.HIDDEN -> false
                        }
                        
                        if (!boxVisible || !inBoxArea) {
                            launcher.launch(arrayOf("image/*"))
                        }
                    }
                )
            }
            .pointerInput(boxState) {
                // 从右边缘滑动显示盒子
                if (boxState == TodoBoxState.HIDDEN) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            // 只在右侧50dp内响应
                            val edgeThreshold = with(density) { 50.dp.toPx() }
                            if (offset.x >= screenWidthPx - edgeThreshold) {
                                scope.launch {
                                    boxOffsetX.snapTo(screenWidthPx)
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (boxOffsetX.value > 0) {
                                change.consume()
                                scope.launch {
                                    boxOffsetX.snapTo((boxOffsetX.value + dragAmount).coerceAtLeast(0f))
                                }
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (boxOffsetX.value < screenWidthPx * 0.5f) {
                                    // 滑动超过一半，显示盒子
                                    boxOffsetX.animateTo(0f, tween(500))
                                    boxState = TodoBoxState.NORMAL
                                    viewModel.setBoxManuallyHidden(false)
                                } else {
                                    // 没滑够，收回
                                    boxOffsetX.animateTo(screenWidthPx, tween(500))
                                }
                            }
                        }
                    )
                }
            }
    ) {

        /* 背景层 */
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            if (bgUri != null) {
                Image(
                    rememberAsyncImagePainter(bgUri),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(0.15f))
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0F2027), Color(0xFF203A43))
                        )
                    )
                )
            }
        }

        /* 主内容 */
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp)
                .statusBarsPadding()
        ) {
            /* 时间区 - 在待办盒子下层 */
            Column(
                Modifier
                    .then(
                        if (boxState == TodoBoxState.HIDDEN) {
                            // 盒子隐藏时，时间居中
                            Modifier.fillMaxSize()
                        } else {
                            // 盒子可见时，时间占左侧3/4
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.75f)
                        }
                    )
                    .graphicsLayer {
                        scaleX = timeScale
                        scaleY = timeScale
                        alpha = timeAlpha
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(time)),
                        style = TextStyle(
                            fontSize = 180.sp,
                            fontWeight = FontWeight.Thin,
                            color = Color.White
                        )
                    )
                    Text(
                        SimpleDateFormat(":ss", Locale.getDefault())
                            .format(Date(time)),
                        style = TextStyle(
                            fontSize = 50.sp,
                            fontWeight = FontWeight.ExtraLight,
                            color = Color.White.copy(0.8f)
                        ),
                        modifier = Modifier.padding(
                            bottom = 32.dp,
                            start = 12.dp
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    SimpleDateFormat(
                        "yyyy年MM月dd日 EEEE",
                        Locale.CHINESE
                    ).format(Date(time)),
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White.copy(0.8f)
                    )
                )
            }

            /* 待办区 - 在上层 */
            TodoBoxContent(
                boxState = boxState,
                expandProgress = expandProgress,
                boxOffsetX = boxOffsetX.value,
                todos = todos,
                hazeState = hazeState,
                viewModel = viewModel,
                onStateChange = { newState ->
                    scope.launch {
                        when (newState) {
                            TodoBoxState.EXPANDED -> {
                                boxState = TodoBoxState.EXPANDED
                                expandProgress.animateTo(1f, tween(500))
                            }
                            TodoBoxState.NORMAL -> {
                                // 从展开收起到正常状态，先向左收起再从右侧弹出
                                if (boxState == TodoBoxState.EXPANDED) {
                                    expandProgress.animateTo(0f, tween(500))
                                    boxOffsetX.animateTo(-screenWidthPx, tween(500))
                                    boxOffsetX.snapTo(screenWidthPx)
                                    boxState = TodoBoxState.NORMAL
                                    boxOffsetX.animateTo(0f, tween(500))
                                } else {
                                    boxState = TodoBoxState.NORMAL
                                }
                            }
                            TodoBoxState.HIDDEN -> {
                                if (isLandscape) {
                                    viewModel.setBoxManuallyHidden(true)
                                }
                                expandProgress.snapTo(0f)
                                boxState = TodoBoxState.HIDDEN
                                boxOffsetX.snapTo(0f)
                            }
                        }
                    }
                },
                isLandscape = isLandscape
            )
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
                    "已完成！",
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
                viewModel.showSheet = false
            },
            onSave = { viewModel.saveTodo() }
        )
    }
}

@Composable
fun TodoBoxContent(
    boxState: TodoBoxState,
    expandProgress: Animatable<Float, *>,
    boxOffsetX: Float,
    todos: List<TodoItem>,
    hazeState: HazeState,
    viewModel: MainViewModel,
    onStateChange: (TodoBoxState) -> Unit,
    isLandscape: Boolean
) {
    if (boxState == TodoBoxState.HIDDEN && boxOffsetX == 0f) {
        return // 完全隐藏时不渲染
    }

    val scope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    
    // 记录拖拽类型
    var dragType by remember { mutableStateOf<String?>(null) }
    
    // 计算实际偏移
    val actualOffsetX = when (boxState) {
        TodoBoxState.HIDDEN -> boxOffsetX
        else -> dragOffsetX.value
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(actualOffsetX.roundToInt(), 0) }
    ) {
        // 计算盒子宽度 - 根据expandProgress
        val boxWidthFraction = 0.25f + 0.75f * expandProgress.value

        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(boxWidthFraction)
                .align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(32.dp * (1f - expandProgress.value)))
                .hazeChild(
                    state = hazeState,
                    shape = RoundedCornerShape(32.dp * (1f - expandProgress.value)),
                    tint = Color.White.copy(0.1f),
                    blurRadius = 25.dp
                )
                .then(
                    if (expandProgress.value < 1f) {
                        Modifier.border(
                            1.dp,
                            Color.White.copy(0.15f),
                            RoundedCornerShape(32.dp * (1f - expandProgress.value))
                        )
                    } else Modifier
                )
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragType = null
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                
                                scope.launch {
                                    // 根据首次滑动方向确定拖拽类型
                                    if (dragType == null && abs(dragAmount) > 5f) {
                                        dragType = if (dragAmount < 0) "expand" else "hide"
                                    }
                                    
                                    when (boxState) {
                                        TodoBoxState.NORMAL -> {
                                            when (dragType) {
                                                "expand" -> {
                                                    // 左滑展开
                                                    val delta = -dragAmount / (screenWidthPx * 0.75f)
                                                    val newProgress = (expandProgress.value + delta).coerceIn(0f, 1f)
                                                    expandProgress.snapTo(newProgress)
                                                }
                                                "hide" -> {
                                                    // 右滑隐藏
                                                    if (isLandscape) {
                                                        val newOffset = (dragOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)
                                                        dragOffsetX.snapTo(newOffset)
                                                    }
                                                }
                                            }
                                        }
                                        TodoBoxState.EXPANDED -> {
                                            // 全屏状态只能右滑收起
                                            if (dragAmount > 0) {
                                                val delta = dragAmount / (screenWidthPx * 0.75f)
                                                val newProgress = (expandProgress.value - delta).coerceIn(0f, 1f)
                                                expandProgress.snapTo(newProgress)
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            },
                            onDragEnd = {
                                scope.launch {
                                    when (boxState) {
                                        TodoBoxState.NORMAL -> {
                                            when (dragType) {
                                                "expand" -> {
                                                    if (expandProgress.value > 0.3f) {
                                                        // 展开到全屏
                                                        expandProgress.animateTo(1f, tween(300))
                                                        onStateChange(TodoBoxState.EXPANDED)
                                                    } else {
                                                        // 回弹到正常
                                                        expandProgress.animateTo(0f, tween(300))
                                                    }
                                                }
                                                "hide" -> {
                                                    if (dragOffsetX.value > screenWidthPx * 0.5f && isLandscape) {
                                                        // 隐藏
                                                        dragOffsetX.animateTo(screenWidthPx, tween(300))
                                                        onStateChange(TodoBoxState.HIDDEN)
                                                    } else {
                                                        // 回弹
                                                        dragOffsetX.animateTo(0f, tween(300))
                                                    }
                                                }
                                                else -> {
                                                    // 回弹
                                                    expandProgress.animateTo(0f, tween(300))
                                                    dragOffsetX.animateTo(0f, tween(300))
                                                }
                                            }
                                        }
                                        TodoBoxState.EXPANDED -> {
                                            if (expandProgress.value < 0.7f) {
                                                // 收起到正常
                                                expandProgress.animateTo(0f, tween(300))
                                                delay(300)
                                                dragOffsetX.snapTo(screenWidthPx)
                                                onStateChange(TodoBoxState.NORMAL)
                                                dragOffsetX.animateTo(0f, tween(500))
                                            } else {
                                                // 回弹到全屏
                                                expandProgress.animateTo(1f, tween(300))
                                            }
                                        }
                                        else -> {}
                                    }
                                    dragType = null
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (boxState == TodoBoxState.EXPANDED) {
                                    scope.launch {
                                        expandProgress.animateTo(0f, tween(300))
                                        delay(300)
                                        dragOffsetX.snapTo(screenWidthPx)
                                        onStateChange(TodoBoxState.NORMAL)
                                        dragOffsetX.animateTo(0f, tween(500))
                                    }
                                }
                            }
                        )
                    }
            ) {
                Text(
                    "待办事项",
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Spacer(Modifier.height(20.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (todos.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "悠闲的一天，去喝杯茶吧~",
                                style = TextStyle(
                                    color = Color.White.copy(0.25f),
                                    fontSize = 15.sp
                                )
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(todos, key = { it.id }) { item ->
                                OriginalSwipeItem(
                                    todo = item,
                                    onComplete = {
                                        viewModel.complete(item.id)
                                    },
                                    onStar = {
                                        viewModel.toggleStar(
                                            item.id,
                                            item.isStarred
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // 添加按钮 - 根据展开进度平滑切换
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    if (expandProgress.value > 0.5f) {
                        // 全屏时的长条按钮
                        Button(
                            onClick = { viewModel.showSheet = true },
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .align(Alignment.Center)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFB800)
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("添加", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else {
                        // 正常时的圆形按钮
                        FloatingActionButton(
                            onClick = { viewModel.showSheet = true },
                            containerColor = Color(0xFFFFB800),
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Icon(Icons.Default.Add, null, tint = Color.White)
                        }
                    }
                }
            }
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
            (abs(offsetX.value) / 150f)
                .coerceIn(0f, 1.2f)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        
        // 左滑背景 - 星标图标
        if (offsetX.value < 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFB800),
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                            alpha = iconScale.coerceIn(0f, 1f)
                        }
                )
            }
        }
        
        // 右滑背景 - 完成图标（绿色圆圈+对勾）
        if (offsetX.value > 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                            alpha = iconScale.coerceIn(0f, 1f)
                        }
                        .background(Color(0xFF4CAF50), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .wrapContentHeight()
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
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (todo.isStarred) {
                    Icon(
                        Icons.Default.Star,
                        null,
                        tint = Color(0xFFFFB800),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    todo.content,
                    color = Color.White,
                    fontSize = 17.sp,
                    style = TextStyle(lineHeight = 24.sp)
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
        BackHandler(enabled = !sheetState.isVisible || sheetState.targetValue == SheetValue.Expanded) { 
            onDismiss() 
        }

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
                            "想做点什么？",
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
