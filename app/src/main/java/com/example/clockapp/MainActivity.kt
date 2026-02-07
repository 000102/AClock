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
    HIDDEN,
    NORMAL,
    EXPANDED,
    ANIMATING
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

    var boxState by remember { mutableStateOf(TodoBoxState.NORMAL) }
    
    LaunchedEffect(isLandscape, isBoxManuallyHidden) {
        if (boxState != TodoBoxState.ANIMATING && boxState != TodoBoxState.EXPANDED) {
            boxState = when {
                isPortrait -> TodoBoxState.HIDDEN
                isBoxManuallyHidden -> TodoBoxState.HIDDEN
                else -> TodoBoxState.NORMAL
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { it?.let(viewModel::setBackground) }

    val boxOffsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val expandProgress = remember { Animatable(0f) }
    val visualExpandProgress = remember { Animatable(0f) }

    // 修复1：时间完全透明 - 直接使用 visualExpandProgress
    val timeScale = 1f - visualExpandProgress.value * 0.3f
    val timeAlpha = 1f - visualExpandProgress.value

    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    LaunchedEffect(boxState) {
        when (boxState) {
            TodoBoxState.HIDDEN -> {
                if (boxOffsetX.value != screenWidthPx) {
                    boxOffsetX.animateTo(screenWidthPx, tween(300))
                }
                visualExpandProgress.animateTo(0f, tween(300))
                expandProgress.snapTo(0f)
            }
            TodoBoxState.NORMAL -> {
                if (boxOffsetX.value != 0f) {
                    boxOffsetX.animateTo(0f, tween(400))
                }
                visualExpandProgress.animateTo(0f, tween(400))
                expandProgress.animateTo(0f, tween(400))
            }
            TodoBoxState.EXPANDED -> {
                if (boxOffsetX.value != 0f) {
                    boxOffsetX.animateTo(0f, tween(300))
                }
                visualExpandProgress.animateTo(1f, tween(500))
                expandProgress.animateTo(1f, tween(500))
            }
            else -> {}
        }
    }

    val isEffectivelyExpanded = visualExpandProgress.value > 0.1f
    val isEffectivelyHidden = boxState == TodoBoxState.HIDDEN && boxOffsetX.value > screenWidthPx * 0.95f

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(boxState) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val boxVisible = boxState != TodoBoxState.HIDDEN && boxState != TodoBoxState.ANIMATING
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
            .pointerInput(boxState, screenWidthPx, isPortrait) {
                // 修复4：竖屏时直接展开全屏
                if (boxState == TodoBoxState.HIDDEN || boxState == TodoBoxState.NORMAL) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val edgeThreshold = with(density) { 50.dp.toPx() }
                            if (offset.x >= screenWidthPx - edgeThreshold) {
                                scope.launch {
                                    if (boxOffsetX.value == 0f) {
                                        boxOffsetX.snapTo(screenWidthPx)
                                    }
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (boxOffsetX.value > 0 || boxOffsetX.value == screenWidthPx) {
                                change.consume()
                                scope.launch {
                                    val newValue = (boxOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)
                                    boxOffsetX.snapTo(newValue)
                                    
                                    // 根据滑动距离计算视觉进度（用于时间区域动画）
                                    if (isPortrait && boxState == TodoBoxState.HIDDEN) {
                                        // 竖屏：直接计算全屏进度
                                        val slideProgress = 1f - (boxOffsetX.value / screenWidthPx).coerceIn(0f, 1f)
                                        visualExpandProgress.snapTo(slideProgress)
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                // 修复2：阈值改为 7% (0.93f)
                                if (boxOffsetX.value < screenWidthPx * 0.93f) {
                                    if (isPortrait && boxState == TodoBoxState.HIDDEN) {
                                        // 竖屏直接展开全屏
                                        boxState = TodoBoxState.EXPANDED
                                        boxOffsetX.animateTo(0f, tween(400))
                                        visualExpandProgress.animateTo(1f, tween(500))
                                        expandProgress.animateTo(1f, tween(500))
                                    } else {
                                        boxOffsetX.animateTo(0f, tween(400))
                                        boxState = TodoBoxState.NORMAL
                                        viewModel.setBoxManuallyHidden(false)
                                    }
                                } else {
                                    boxOffsetX.animateTo(screenWidthPx, tween(400))
                                    if (isLandscape) {
                                        boxState = TodoBoxState.HIDDEN
                                        viewModel.setBoxManuallyHidden(true)
                                    }
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
            /* 时间区 */
            Column(
                Modifier
                    .then(
                        if (isEffectivelyHidden) {
                            Modifier.fillMaxSize()
                        } else {
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
                            fontSize = fontSizes.clockHourMinute,
                            fontWeight = FontWeight.Thin,
                            color = Color.White
                        )
                    )
                    Text(
                        SimpleDateFormat(":ss", Locale.getDefault())
                            .format(Date(time)),
                        style = TextStyle(
                            fontSize = fontSizes.clockSecond,
                            fontWeight = FontWeight.ExtraLight,
                            color = Color.White.copy(0.8f)
                        ),
                        modifier = Modifier.padding(
                            bottom = fontSizes.clockSecondOffset,
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
                        fontSize = fontSizes.dateText,
                        fontWeight = FontWeight.Light,
                        color = Color.White.copy(0.8f)
                    )
                )
            }

            /* 待办区 */
            TodoBoxContent(
                boxState = boxState,
                expandProgress = expandProgress,
                visualExpandProgress = visualExpandProgress,
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
                                visualExpandProgress.animateTo(1f, tween(500))
                            }
                            TodoBoxState.NORMAL -> {
                                if (boxState == TodoBoxState.EXPANDED) {
                                    // 修复3：优化动画，避免抽搐
                                    boxState = TodoBoxState.ANIMATING
                                    
                                    // 同时执行滑出和收缩动画
                                    launch { boxOffsetX.animateTo(screenWidthPx, tween(350)) }
                                    launch { visualExpandProgress.animateTo(0f, tween(350)) }
                                    launch { expandProgress.animateTo(0f, tween(350)) }
                                    
                                    // 等待滑出完成后瞬间重置并滑入
                                    delay(350)
                                    boxState = TodoBoxState.NORMAL
                                    
                                    // 从右侧滑入
                                    boxOffsetX.snapTo(screenWidthPx)
                                    boxOffsetX.animateTo(0f, tween(400))
                                } else {
                                    boxState = TodoBoxState.NORMAL
                                    visualExpandProgress.animateTo(0f, tween(400))
                                }
                            }
                            TodoBoxState.HIDDEN -> {
                                if (isLandscape) {
                                    viewModel.setBoxManuallyHidden(true)
                                }
                                boxState = TodoBoxState.ANIMATING
                                launch { visualExpandProgress.animateTo(0f, tween(300)) }
                                launch { expandProgress.animateTo(0f, tween(300)) }
                                boxOffsetX.animateTo(screenWidthPx, tween(400))
                                boxState = TodoBoxState.HIDDEN
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

        /* BottomSheet */
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
    visualExpandProgress: Animatable<Float, *>,
    boxOffsetX: Float,
    todos: List<TodoItem>,
    hazeState: HazeState,
    viewModel: MainViewModel,
    onStateChange: (TodoBoxState) -> Unit,
    isLandscape: Boolean,
    screenWidthPx: Float,
    fontSizes: ResponsiveFontSizes,
    isPortrait: Boolean
) {
    if (boxOffsetX >= screenWidthPx * 0.99f && boxState != TodoBoxState.ANIMATING) {
        return
    }

    val scope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    var dragType by remember { mutableStateOf<String?>(null) }
    
    val actualOffsetX = when (boxState) {
        TodoBoxState.HIDDEN -> boxOffsetX
        TodoBoxState.ANIMATING -> boxOffsetX
        else -> dragOffsetX.value + boxOffsetX
    }

    val boxWidthFraction = 0.25f + 0.75f * visualExpandProgress.value

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
                .clip(RoundedCornerShape(32.dp * (1f - visualExpandProgress.value)))
                .hazeChild(
                    state = hazeState,
                    shape = RoundedCornerShape(32.dp * (1f - visualExpandProgress.value)),
                    tint = Color.White.copy(0.1f),
                    blurRadius = 25.dp
                )
                .then(
                    if (visualExpandProgress.value < 1f) {
                        Modifier.border(
                            1.dp,
                            Color.White.copy(0.15f),
                            RoundedCornerShape(32.dp * (1f - visualExpandProgress.value))
                        )
                    } else Modifier
                )
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .fillMaxSize()
                    .pointerInput(boxState, isPortrait) {
                        if (boxState == TodoBoxState.NORMAL || boxState == TodoBoxState.EXPANDED) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragType = null
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    
                                    scope.launch {
                                        if (dragType == null && abs(dragAmount) > 5f) {
                                            dragType = if (dragAmount < 0) "expand" else "hide"
                                        }
                                        
                                        when (boxState) {
                                            TodoBoxState.NORMAL -> {
                                                when (dragType) {
                                                    "expand" -> {
                                                        // 竖屏直接展开全屏，不经过中间状态
                                                        if (isPortrait) {
                                                            val delta = -dragAmount / (screenWidthPx * 0.75f)
                                                            val newProgress = (visualExpandProgress.value + delta).coerceIn(0f, 1f)
                                                            visualExpandProgress.snapTo(newProgress)
                                                            expandProgress.snapTo(newProgress)
                                                        } else {
                                                            val delta = -dragAmount / (screenWidthPx * 0.75f)
                                                            val newProgress = (expandProgress.value + delta).coerceIn(0f, 1f)
                                                            expandProgress.snapTo(newProgress)
                                                            visualExpandProgress.snapTo(newProgress)
                                                        }
                                                    }
                                                    "hide" -> {
                                                        if (isLandscape) {
                                                            val newOffset = (dragOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)
                                                            dragOffsetX.snapTo(newOffset)
                                                        }
                                                    }
                                                }
                                            }
                                            TodoBoxState.EXPANDED -> {
                                                if (dragAmount > 0) {
                                                    val newOffset = (dragOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)
                                                    dragOffsetX.snapTo(newOffset)
                                                    val slideProgress = 1f - (dragOffsetX.value / screenWidthPx).coerceIn(0f, 1f)
                                                    visualExpandProgress.snapTo(slideProgress)
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
                                                        // 竖屏直接展开全屏
                                                        if (isPortrait) {
                                                            if (visualExpandProgress.value > 0.15f) {
                                                                visualExpandProgress.animateTo(1f, tween(300))
                                                                expandProgress.animateTo(1f, tween(300))
                                                                onStateChange(TodoBoxState.EXPANDED)
                                                            } else {
                                                                visualExpandProgress.animateTo(0f, tween(300))
                                                                expandProgress.animateTo(0f, tween(300))
                                                            }
                                                        } else {
                                                            if (expandProgress.value > 0.2f) {
                                                                expandProgress.animateTo(1f, tween(300))
                                                                visualExpandProgress.animateTo(1f, tween(300))
                                                                onStateChange(TodoBoxState.EXPANDED)
                                                            } else {
                                                                expandProgress.animateTo(0f, tween(300))
                                                                visualExpandProgress.animateTo(0f, tween(300))
                                                            }
                                                        }
                                                    }
                                                    "hide" -> {
                                                        // 修复2：阈值改为 7%
                                                        if (dragOffsetX.value > screenWidthPx * 0.07f && isLandscape) {
                                                            dragOffsetX.animateTo(screenWidthPx, tween(300))
                                                            onStateChange(TodoBoxState.HIDDEN)
                                                        } else {
                                                            dragOffsetX.animateTo(0f, tween(300))
                                                        }
                                                    }
                                                    else -> {
                                                        expandProgress.animateTo(0f, tween(300))
                                                        visualExpandProgress.animateTo(0f, tween(300))
                                                        dragOffsetX.animateTo(0f, tween(300))
                                                    }
                                                }
                                            }
                                            TodoBoxState.EXPANDED -> {
                                                // 修复2：阈值改为 7%
                                                if (dragOffsetX.value > screenWidthPx * 0.07f) {
                                                    onStateChange(TodoBoxState.NORMAL)
                                                } else {
                                                    dragOffsetX.animateTo(0f, tween(300))
                                                    visualExpandProgress.animateTo(1f, tween(300))
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
                                    onStateChange(TodoBoxState.NORMAL)
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
                                    onComplete = {
                                        viewModel.complete(item.id)
                                    },
                                    onStar = {
                                        viewModel.toggleStar(
                                            item.id,
                                            item.isStarred
                                        )
                                    },
                                    fontSizes = fontSizes
                                )
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    if (visualExpandProgress.value > 0.5f) {
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
                            Text(
                                "添加", 
                                fontWeight = FontWeight.Bold, 
                                color = Color.White,
                                fontSize = fontSizes.buttonText
                            )
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
