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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
    HIDDEN,      // 完全隐藏
    NORMAL,      // 正常显示（横屏25%）
    EXPANDED,    // 全屏展开
    TRANSITIONING // 过渡状态
}

@Composable
fun rememberResponsiveFontSizes(): ResponsiveFontSizes {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    
    val scaleFactor = (screenWidthDp / 1080f).coerceIn(0.6f, 1.5f)
    
    return remember(screenWidthDp) {
        ResponsiveFontSizes(
            clockHourMinute = (180 * scaleFactor).sp,
            clockSecond = (50 * scaleFactor).sp,
            clockSecondOffset = (32 * scaleFactor).dp,
            dateText = (28 * scaleFactor).sp,
            todoTitle = (22 * scaleFactor).sp,
            todoItem = (17 * scaleFactor).sp,
            emptyText = (15 * scaleFactor).sp,
            buttonText = (16 * scaleFactor).sp
        )
    }
}

data class ResponsiveFontSizes(
    val clockHourMinute: androidx.compose.ui.unit.TextUnit,
    val clockSecond: androidx.compose.ui.unit.TextUnit,
    val clockSecondOffset: androidx.compose.ui.unit.Dp,
    val dateText: androidx.compose.ui.unit.TextUnit,
    val todoTitle: androidx.compose.ui.unit.TextUnit,
    val todoItem: androidx.compose.ui.unit.TextUnit,
    val emptyText: androidx.compose.ui.unit.TextUnit,
    val buttonText: androidx.compose.ui.unit.TextUnit
)

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
    
    val fontSizes = rememberResponsiveFontSizes()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isPortrait = !isLandscape

    // 核心状态
    var boxState by remember { mutableStateOf(TodoBoxState.HIDDEN) }
    val boxOffsetX = remember { Animatable(0f) }
    val expandProgress = remember { Animatable(0f) }
    
    // 新增：横屏时间平移动画状态
    val timeOffsetX = remember { Animatable(0f) }
    
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    // 初始化
    LaunchedEffect(Unit) {
        boxState = when {
            isPortrait -> TodoBoxState.HIDDEN
            isBoxManuallyHidden -> TodoBoxState.HIDDEN
            else -> TodoBoxState.NORMAL
        }
        
        when (boxState) {
            TodoBoxState.HIDDEN -> {
                boxOffsetX.snapTo(screenWidthPx)
                // 横屏初始化时如果盒子隐藏，时间移到屏幕中央
                if (isLandscape) {
                    timeOffsetX.snapTo(screenWidthPx * 0.125f)
                }
            }
            else -> boxOffsetX.snapTo(0f)
        }
        
        when (boxState) {
            TodoBoxState.EXPANDED -> expandProgress.snapTo(1f)
            else -> expandProgress.snapTo(0f)
        }
    }

    // 屏幕方向/设置变化监听
    LaunchedEffect(isLandscape, isBoxManuallyHidden) {
        if (boxState == TodoBoxState.TRANSITIONING) return@LaunchedEffect
        
        val targetState = when {
            isPortrait -> TodoBoxState.HIDDEN
            isBoxManuallyHidden -> TodoBoxState.HIDDEN
            else -> TodoBoxState.NORMAL
        }
        
        if (boxState != targetState && boxState != TodoBoxState.EXPANDED) {
            boxState = TodoBoxState.TRANSITIONING
            when (targetState) {
                TodoBoxState.HIDDEN -> {
                    launch { boxOffsetX.animateTo(screenWidthPx, tween(400)) }
                    launch { expandProgress.animateTo(0f, tween(400)) }
                    // 横屏收回待办盒子时，时间平移到屏幕中央
                    if (isLandscape) {
                        launch { timeOffsetX.animateTo(screenWidthPx * 0.125f, tween(400)) }
                    }
                    boxState = TodoBoxState.HIDDEN
                }
                TodoBoxState.NORMAL -> {
                    launch { boxOffsetX.animateTo(0f, tween(400)) }
                    launch { expandProgress.animateTo(0f, tween(400)) }
                    // 横屏展开待办盒子时，时间回到左侧原位
                    if (isLandscape) {
                        launch { timeOffsetX.animateTo(0f, tween(400)) }
                    }
                    boxState = TodoBoxState.NORMAL
                }
                else -> {}
            }
        }
    }

    // 视觉属性
    val timeAlpha = 1f - expandProgress.value
    val timeScale = 1f - expandProgress.value * 0.3f
    val boxWidthFraction = 0.25f + 0.75f * expandProgress.value
    
    val isEffectivelyHidden = boxState == TodoBoxState.HIDDEN && boxOffsetX.value > screenWidthPx * 0.9f

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { it?.let(viewModel::setBackground) }

    // 边缘手势处理 - 使用通用 detectDragGestures 以解决竖屏上滑冲突
    val edgeGestureModifier = Modifier.pointerInput(boxState, isPortrait) {
        if (boxState != TodoBoxState.EXPANDED && boxState != TodoBoxState.TRANSITIONING) {
            detectDragGestures(
                onDragStart = { offset ->
                    val edgeThreshold = with(density) { 50.dp.toPx() }
                    // 只有从右边缘开始才处理
                    if (offset.x >= screenWidthPx - edgeThreshold) {
                        if (boxOffsetX.value < screenWidthPx) {
                            scope.launch { boxOffsetX.snapTo(screenWidthPx) }
                        }
                    }
                },
                onDrag = { change, dragAmount ->
                    val (dx, dy) = dragAmount
                    // 核心修复：竖屏防误触
                    // 如果垂直移动距离大于水平移动距离，则认为是上/下滑，忽略
                    if (abs(dy) > abs(dx)) return@detectDragGestures

                    change.consume()
                    scope.launch {
                        val newValue = (boxOffsetX.value + dx).coerceIn(0f, screenWidthPx)
                        boxOffsetX.snapTo(newValue)
                        
                        if (isPortrait) {
                            val progress = 1f - (boxOffsetX.value / screenWidthPx)
                            expandProgress.snapTo(progress.coerceIn(0f, 1f))
                        }
                    }
                },
                onDragEnd = {
                    scope.launch {
                        val showThreshold = screenWidthPx * 0.93f
                        
                        if (boxOffsetX.value < showThreshold) {
                            // 展开逻辑
                            if (isPortrait) {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { boxOffsetX.animateTo(0f, tween(400)) }
                                launch { expandProgress.animateTo(1f, tween(500)) }
                                boxState = TodoBoxState.EXPANDED
                            } else {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { boxOffsetX.animateTo(0f, tween(400)) }
                                // 横屏展开待办盒子时，时间回到左侧原位
                                launch { timeOffsetX.animateTo(0f, tween(400)) }
                                boxState = TodoBoxState.NORMAL
                                viewModel.setBoxManuallyHidden(false)
                            }
                        } else {
                            // 收回逻辑
                            boxState = TodoBoxState.TRANSITIONING
                            launch { boxOffsetX.animateTo(screenWidthPx, tween(400)) }
                            if (isLandscape) {
                                // 横屏收回待办盒子时，时间平移到屏幕中央
                                launch { timeOffsetX.animateTo(screenWidthPx * 0.125f, tween(400)) }
                                viewModel.setBoxManuallyHidden(true)
                            }
                            boxState = TodoBoxState.HIDDEN
                        }
                    }
                }
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(edgeGestureModifier)
            .pointerInput(boxState) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val boxVisible = boxState != TodoBoxState.HIDDEN && boxState != TodoBoxState.TRANSITIONING
                        val inBoxArea = when (boxState) {
                            TodoBoxState.EXPANDED -> true
                            TodoBoxState.NORMAL -> {
                                val boxStartX = screenWidthPx * 0.75f
                                offset.x >= boxStartX
                            }
                            else -> false
                        }
                        
                        if (!boxVisible || !inBoxArea) {
                            launcher.launch(arrayOf("image/*"))
                        }
                    }
                )
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
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.15f)))
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

        /* 主内容 - 时间显示区 (独立Padding) */
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp) // 这里保留Padding给时间
                .statusBarsPadding()
        ) {
            Column(
                Modifier
                    .then(
                        // 修复竖屏排版：竖屏始终fillMaxSize，横屏根据盒子状态调整
                        if (isPortrait) {
                            Modifier.fillMaxSize()
                        } else {
                            if (isEffectivelyHidden) Modifier.fillMaxSize()
                            else Modifier.fillMaxHeight().fillMaxWidth(0.75f)
                        }
                    )
                    // 修复横屏平移：只在横屏且盒子隐藏时添加偏移
                    .offset { 
                        if (isLandscape && isEffectivelyHidden) {
                            IntOffset(timeOffsetX.value.roundToInt(), 0)
                        } else {
                            IntOffset(0, 0)
                        }
                    }
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
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)),
                        style = TextStyle(
                            fontSize = fontSizes.clockHourMinute,
                            fontWeight = FontWeight.Thin,
                            color = Color.White
                        )
                    )
                    Text(
                        SimpleDateFormat(":ss", Locale.getDefault()).format(Date(time)),
                        style = TextStyle(
                            fontSize = fontSizes.clockSecond,
                            fontWeight = FontWeight.ExtraLight,
                            color = Color.White.copy(0.8f)
                        ),
                        modifier = Modifier.padding(bottom = fontSizes.clockSecondOffset, start = 12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date(time)),
                    style = TextStyle(
                        fontSize = fontSizes.dateText,
                        fontWeight = FontWeight.Light,
                        color = Color.White.copy(0.8f)
                    )
                )
            }
        }

        /* 待办区 (覆盖层，无外层Padding，确保全屏) */
        // 修复时间消失：保持原逻辑，确保动画期间TodoBox仍然渲染
        if (boxState != TodoBoxState.HIDDEN || boxOffsetX.value < screenWidthPx * 0.99f) {
            TodoBoxContent(
                boxState = boxState,
                boxOffsetX = boxOffsetX.value,
                expandProgress = expandProgress.value,
                boxWidthFraction = boxWidthFraction,
                todos = todos,
                hazeState = hazeState,
                viewModel = viewModel,
                onStateChange = { newState, currentIsPortrait ->
                    scope.launch {
                        // 状态切换回调
                        when (newState) {
                            TodoBoxState.EXPANDED -> {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { expandProgress.animateTo(1f, tween(500)) }
                                boxState = TodoBoxState.EXPANDED
                            }
                            TodoBoxState.NORMAL -> {
                                if (boxState == TodoBoxState.EXPANDED) {
                                    // 从展开收回
                                    boxState = TodoBoxState.TRANSITIONING
                                    launch { expandProgress.animateTo(0f, tween(350)) }
                                    // 竖屏直接隐藏，横屏回NORMAL
                                    if (currentIsPortrait) {
                                        // 竖屏收回逻辑
                                        launch { boxOffsetX.animateTo(screenWidthPx, tween(350)) }
                                        delay(350)
                                        boxState = TodoBoxState.HIDDEN
                                    } else {
                                        // 横屏：回到Normal位置
                                        launch { boxOffsetX.animateTo(0f, tween(400)) }
                                        // 横屏从展开收回到Normal时，时间回到左侧原位
                                        launch { timeOffsetX.animateTo(0f, tween(400)) }
                                        boxState = TodoBoxState.NORMAL
                                    }
                                } else {
                                    boxState = TodoBoxState.NORMAL
                                }
                            }
                            TodoBoxState.HIDDEN -> {
                                // 核心修复：这里只负责更新状态，动画已经在子组件完成了
                                boxState = TodoBoxState.HIDDEN
                                // 立即将父组件偏移量设置为屏幕外，防止闪烁
                                boxOffsetX.snapTo(screenWidthPx) 
                                if (isLandscape) {
                                    // 横屏收回到HIDDEN时，时间平移到屏幕中央
                                    timeOffsetX.animateTo(screenWidthPx * 0.125f, tween(400))
                                    viewModel.setBoxManuallyHidden(true)
                                }
                            }
                            else -> {}
                        }
                    }
                },
                isLandscape = isLandscape,
                screenWidthPx = screenWidthPx,
                fontSizes = fontSizes,
                isPortrait = isPortrait
            )
        }

        /* Snackbar */
        if (viewModel.snackbarVisible) {
            Card(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "已完成！",
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    color = Color.White
                )
            }
        }

        /* BottomSheet */
        OriginalAddTodoSheet(
            visible = viewModel.showSheet,
            text = viewModel.inputText,
            onTextChange = { viewModel.inputText = it },
            onDismiss = { viewModel.showSheet = false },
            onSave = { viewModel.saveTodo() }
        )
    }
}

@Composable
fun TodoBoxContent(
    boxState: TodoBoxState,
    boxOffsetX: Float,
    expandProgress: Float,
    boxWidthFraction: Float,
    todos: List<TodoItem>,
    hazeState: HazeState,
    viewModel: MainViewModel,
    onStateChange: (TodoBoxState, Boolean) -> Unit,
    isLandscape: Boolean,
    screenWidthPx: Float,
    fontSizes: ResponsiveFontSizes,
    isPortrait: Boolean
) {
    val scope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    var dragType by remember { mutableStateOf<String?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    // 核心修复：即使HIDDEN也不重置为0，直到动画完全结束
    // 这里如果BoxState变为了HIDDEN，我们希望它保持在屏幕外(screenWidthPx)，而不是跳回0
    // 但是，因为我们改为"子组件动画结束后才切换状态"，所以这里的逻辑可以简化
    val actualOffsetX = dragOffsetX.value + boxOffsetX

    // 监听状态重置内部偏移
    LaunchedEffect(boxState) {
        if (boxState == TodoBoxState.HIDDEN) {
            dragOffsetX.snapTo(0f)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(actualOffsetX.roundToInt(), 0) }
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(boxWidthFraction)
                .align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(32.dp * (1f - expandProgress)))
                .hazeChild(
                    state = hazeState,
                    shape = RoundedCornerShape(32.dp * (1f - expandProgress)),
                    tint = Color.White.copy(0.1f),
                    blurRadius = 25.dp
                )
                .then(
                    if (expandProgress < 1f) {
                        Modifier.border(
                            1.dp,
                            Color.White.copy(0.15f),
                            RoundedCornerShape(32.dp * (1f - expandProgress))
                        )
                    } else Modifier
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding() // 确保内容不被刘海遮挡，但不影响背景铺满
                    .padding(24.dp) // 内容内边距
                    .pointerInput(boxState, isPortrait, expandProgress) {
                        if (boxState == TodoBoxState.NORMAL || boxState == TodoBoxState.EXPANDED) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragType = null
                                    isDragging = true
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        if (dragType == null && abs(dragAmount) > 5f) {
                                            dragType = if (dragAmount < 0) "expand" else "hide"
                                        }

                                        when (boxState) {
                                            TodoBoxState.NORMAL -> {
                                                if (dragType == "hide" && isLandscape) {
                                                    val newOffset = (dragOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)
                                                    dragOffsetX.snapTo(newOffset)
                                                }
                                            }
                                            TodoBoxState.EXPANDED -> {
                                                // 允许双向滑动，解决卡顿
                                                val newOffset = (dragOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)
                                                dragOffsetX.snapTo(newOffset)
                                            }
                                            else -> {}
                                        }
                                    }
                                },
                                onDragEnd = {
                                    isDragging = false
                                    scope.launch {
                                        when (boxState) {
                                            TodoBoxState.NORMAL -> {
                                                if (dragType == "hide") {
                                                    // 核心动画修复：
                                                    // 1. 如果超过阈值，先在子组件内播放"滑出"动画
                                                    if (dragOffsetX.value > screenWidthPx * 0.07f && isLandscape) {
                                                        dragOffsetX.animateTo(screenWidthPx, tween(300))
                                                        // 2. 动画播完后，再通知父组件切换状态
                                                        onStateChange(TodoBoxState.HIDDEN, isPortrait)
                                                    } else {
                                                        dragOffsetX.animateTo(0f, tween(300))
                                                    }
                                                } else if (dragType == "expand") {
                                                    if (isPortrait) {
                                                        onStateChange(TodoBoxState.EXPANDED, isPortrait)
                                                    } else {
                                                        dragOffsetX.animateTo(0f, tween(300))
                                                    }
                                                } else {
                                                    dragOffsetX.animateTo(0f, tween(300))
                                                }
                                            }
                                            TodoBoxState.EXPANDED -> {
                                                if (dragOffsetX.value > screenWidthPx * 0.07f) {
                                                    // BUG修复：区分横竖屏处理
                                                    if (isPortrait) {
                                                        // 竖屏模式：直接从此位置动画到隐藏
                                                        dragOffsetX.animateTo(screenWidthPx, tween(300))
                                                        onStateChange(TodoBoxState.HIDDEN, isPortrait)
                                                    } else {
                                                        // 横屏模式：保持原始逻辑，动画回弹后通知父组件收缩
                                                        dragOffsetX.animateTo(0f, tween(200))
                                                        onStateChange(TodoBoxState.NORMAL, isPortrait)
                                                    }
                                                } else {
                                                    // 如果拖动距离不够，弹回原位
                                                    dragOffsetX.animateTo(0f, tween(300))
                                                }
                                            }
                                            else -> {}
                                        }
                                        dragType = null
                                    }
                                }
                            )
                        }
                    }
                    .pointerInput(boxState) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (boxState == TodoBoxState.EXPANDED) {
                                    onStateChange(TodoBoxState.NORMAL, isPortrait)
                                }
                            }
                        )
                    }
            ) {
                Text(
                    "待办事项",
                    style = TextStyle(
                        fontSize = fontSizes.todoTitle,
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
                                    fontSize = fontSizes.emptyText
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
                                    onComplete = { viewModel.complete(item.id) },
                                    onStar = { viewModel.toggleStar(item.id, item.isStarred) },
                                    fontSizes = fontSizes
                                )
                            }
                        }
                    }
                }

                Box(
                    Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    if (expandProgress > 0.5f) {
                        Button(
                            onClick = { viewModel.showSheet = true },
                            modifier = Modifier.fillMaxWidth(0.7f).align(Alignment.Center).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("添加", fontWeight = FontWeight.Bold, color = Color.White, fontSize = fontSizes.buttonText)
                        }
                    } else {
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
    onStar: () -> Unit,
    fontSizes: ResponsiveFontSizes
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val iconScale by remember {
        derivedStateOf { (abs(offsetX.value) / 150f).coerceIn(0f, 1.2f) }
    }

    Box(Modifier.fillMaxWidth().wrapContentHeight()) {
        if (offsetX.value < 0) {
            Box(
                modifier = Modifier.matchParentSize().padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFB800),
                    modifier = Modifier.size(28.dp).graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = iconScale.coerceIn(0f, 1f)
                    }
                )
            }
        }
        
        if (offsetX.value > 0) {
            Box(
                modifier = Modifier.matchParentSize().padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier.size(28.dp).graphicsLayer {
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
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value > 150f -> onComplete()
                                    offsetX.value < -150f -> onStar()
                                }
                                offsetX.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                            }
                        }
                    )
                },
            color = if (todo.isStarred) Color(0xFFFFB800).copy(0.15f) else Color.White.copy(0.12f),
            shape = RoundedCornerShape(14.dp),
            border = if (todo.isStarred) BorderStroke(1.dp, Color(0xFFFFB800).copy(0.4f)) else null
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (todo.isStarred) {
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    todo.content,
                    color = Color.White,
                    fontSize = fontSizes.todoItem,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(visible) {
        if (visible) sheetState.show() else sheetState.hide()
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
            Column(Modifier.padding(24.dp).padding(bottom = 32.dp)) {
                Text(
                    "新建待办事项",
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                    placeholder = { Text("想做点什么？", color = Color.White.copy(0.4f)) }
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color.White.copy(0.6f)) }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onSave,
                        enabled = text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))
                    ) { Text("添加", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
