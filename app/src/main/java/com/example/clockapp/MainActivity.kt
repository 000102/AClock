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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.zIndex
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

// ==========================================
// 1. 数据持久化层 (Room & DataStore)
// ==========================================

val Context.dataStore by preferencesDataStore(name = "settings")

@Entity(tableName = "todos")
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val isStarred: Boolean = false,
    val orderIndex: Int = 0 // 用于记录用户手动拖拽的顺序
)

@Dao
interface TodoDao {
    // 关键修改：默认排序仅根据 orderIndex，不再根据 isStarred 强制置顶
    @Query("SELECT * FROM todos WHERE isCompleted = 0 ORDER BY orderIndex ASC")
    fun getAllActiveTodos(): Flow<List<TodoItem>>

    @Insert
    suspend fun insert(todo: TodoItem)

    @Update
    suspend fun updateAll(todos: List<TodoItem>)

    @Query("SELECT MAX(orderIndex) FROM todos")
    suspend fun getMaxOrderIndex(): Int?

    @Query("UPDATE todos SET isCompleted = 1 WHERE id = :id")
    suspend fun markAsCompleted(id: Long)

    @Query("UPDATE todos SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)
}

@Database(entities = [TodoItem::class], version = 3, exportSchema = false)
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

// ==========================================
// 2. ViewModel 逻辑控制层
// ==========================================

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val todoDao = (application as ClockApplication).database.todoDao()
    private val BACKGROUND_KEY = stringPreferencesKey("bg_uri")
    private val TODO_BOX_HIDDEN_KEY = booleanPreferencesKey("todo_box_manually_hidden")

    // 时钟流
    val currentTime = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    // 待办事项流
    val todos = todoDao.getAllActiveTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 背景图与盒子显隐状态
    private val _bgUri = MutableStateFlow<Uri?>(null)
    val bgUri = _bgUri.asStateFlow()

    private val _isBoxManuallyHidden = MutableStateFlow(false)
    val isBoxManuallyHidden = _isBoxManuallyHidden.asStateFlow()

    var showSheet by mutableStateOf(false)
    var inputText by mutableStateOf("")
    var snackbarVisible by mutableStateOf(false)

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data.firstOrNull()?.let { prefs ->
                prefs[BACKGROUND_KEY]?.let { _bgUri.value = Uri.parse(it) }
                _isBoxManuallyHidden.value = prefs[TODO_BOX_HIDDEN_KEY] ?: false
            }
        }
    }

    fun setBackground(uri: Uri) {
        _bgUri.value = uri
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {}
            getApplication<Application>().dataStore.edit { it[BACKGROUND_KEY] = uri.toString() }
        }
    }

    fun setBoxManuallyHidden(hidden: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[TODO_BOX_HIDDEN_KEY] = hidden }
        }
    }

    fun saveTodo() {
        val text = inputText.trim()
        if (text.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val maxIndex = todoDao.getMaxOrderIndex() ?: 0
                // 新增事项排在末尾
                todoDao.insert(TodoItem(content = text, orderIndex = maxIndex + 1))
            }
            inputText = ""
            showSheet = false
        }
    }

    fun updateTodoOrder(newOrderList: List<TodoItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = newOrderList.mapIndexed { index, item ->
                item.copy(orderIndex = index)
            }
            todoDao.updateAll(updatedList)
        }
    }

    fun complete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { todoDao.markAsCompleted(id) }
        viewModelScope.launch {
            snackbarVisible = true
            delay(1000)
            snackbarVisible = false
        }
    }

    fun toggleStar(id: Long, current: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { todoDao.setStarred(id, !current) }
    }
}

// ==========================================
// 3. UI 界面层
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { ClockTodoApp() }
        }
    }
}

enum class TodoBoxState { HIDDEN, NORMAL, EXPANDED, TRANSITIONING }

@Composable
fun ClockTodoApp() {
    val viewModel: MainViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(LocalContext.current.applicationContext as Application)
    )
    val bgUri by viewModel.bgUri.collectAsState()
    val time by viewModel.currentTime.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val isBoxManuallyHidden by viewModel.isBoxManuallyHidden.collectAsState()
    
    val hazeState = remember { HazeState() }
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    // 状态控制
    var boxState by remember { mutableStateOf(TodoBoxState.HIDDEN) }
    val boxOffsetX = remember { Animatable(0f) }
    val expandProgress = remember { Animatable(0f) }

    // 初始化状态逻辑
    LaunchedEffect(isPortrait, isBoxManuallyHidden) {
        val targetState = when {
            isPortrait -> TodoBoxState.HIDDEN
            isBoxManuallyHidden -> TodoBoxState.HIDDEN
            else -> TodoBoxState.NORMAL
        }
        if (boxState != TodoBoxState.EXPANDED) {
            boxState = TodoBoxState.TRANSITIONING
            launch { boxOffsetX.animateTo(if (targetState == TodoBoxState.HIDDEN) screenWidthPx else 0f, tween(450)) }
            launch { expandProgress.animateTo(0f, tween(450)) }
            boxState = targetState
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::setBackground) }

    Box(Modifier.fillMaxSize()) {
        // --- 背景层 ---
        Box(Modifier.fillMaxSize().haze(hazeState).pointerInput(boxState) {
            detectTapGestures(onDoubleTap = { offset ->
                // 仅在点击非盒子区域时触发换背景
                val inBox = boxState != TodoBoxState.HIDDEN && offset.x > screenWidthPx * 0.7f
                if (!inBox) launcher.launch(arrayOf("image/*"))
            })
        }) {
            if (bgUri != null) {
                Image(rememberAsyncImagePainter(bgUri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.15f)))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43)))))
            }
        }

        // --- 时钟层 ---
        ClockDisplay(
            time = time,
            alpha = 1f - expandProgress.value,
            scale = 1f - expandProgress.value * 0.2f,
            isHidden = boxState == TodoBoxState.HIDDEN && boxOffsetX.value > screenWidthPx * 0.8f
        )

        // --- 待办事项盒子 ---
        if (boxState != TodoBoxState.HIDDEN || boxOffsetX.value < screenWidthPx) {
            TodoBoxContainer(
                boxState = boxState,
                boxOffsetX = boxOffsetX.value,
                expandProgress = expandProgress.value,
                todos = todos,
                hazeState = hazeState,
                viewModel = viewModel,
                screenWidthPx = screenWidthPx,
                isPortrait = isPortrait,
                onStateChange = { newState ->
                    scope.launch {
                        when (newState) {
                            TodoBoxState.EXPANDED -> {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { expandProgress.animateTo(1f, tween(500)) }
                                boxState = TodoBoxState.EXPANDED
                            }
                            TodoBoxState.NORMAL -> {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { expandProgress.animateTo(0f, tween(400)) }
                                launch { boxOffsetX.animateTo(0f, tween(400)) }
                                boxState = TodoBoxState.NORMAL
                            }
                            TodoBoxState.HIDDEN -> {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { expandProgress.animateTo(0f, tween(400)) }
                                launch { boxOffsetX.animateTo(screenWidthPx, tween(400)) }
                                boxState = TodoBoxState.HIDDEN
                                if (!isPortrait) viewModel.setBoxManuallyHidden(true)
                            }
                            else -> {}
                        }
                    }
                }
            )
        }

        // --- 完成提示 ---
        if (viewModel.snackbarVisible) {
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
                color = Color(0xFF4CAF50), shape = RoundedCornerShape(50), shadowElevation = 8.dp
            ) {
                Text("任务已完成！", Modifier.padding(horizontal = 24.dp, vertical = 10.dp), color = Color.White)
            }
        }

        // --- 输入弹窗 ---
        OriginalAddTodoSheet(
            visible = viewModel.showSheet, text = viewModel.inputText,
            onTextChange = { viewModel.inputText = it }, onDismiss = { viewModel.showSheet = false },
            onSave = { viewModel.saveTodo() }
        )
    }
}

@Composable
fun ClockDisplay(time: Long, alpha: Float, scale: Float, isHidden: Boolean) {
    val fontSizes = rememberResponsiveFontSizes()
    Column(
        Modifier.fillMaxSize().padding(48.dp).statusBarsPadding()
            .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)), fontSize = fontSizes.clockHourMinute, fontWeight = FontWeight.Thin, color = Color.White)
            Text(SimpleDateFormat(":ss", Locale.getDefault()).format(Date(time)), fontSize = fontSizes.clockSecond, fontWeight = FontWeight.ExtraLight, color = Color.White.copy(0.7f), modifier = Modifier.padding(bottom = fontSizes.clockSecondOffset, start = 8.dp))
        }
        Text(SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date(time)), fontSize = fontSizes.dateText, fontWeight = FontWeight.Light, color = Color.White.copy(0.6f))
    }
}

@Composable
fun TodoBoxContainer(
    boxState: TodoBoxState, boxOffsetX: Float, expandProgress: Float,
    todos: List<TodoItem>, hazeState: HazeState, viewModel: MainViewModel,
    screenWidthPx: Float, isPortrait: Boolean, onStateChange: (TodoBoxState) -> Unit
) {
    val scope = rememberCoroutineScope()
    val fontSizes = rememberResponsiveFontSizes()
    val dragOffsetX = remember { Animatable(0f) }
    
    // 排序专用：即时响应的内存列表
    val mutableTodos = remember(todos) { todos.toMutableStateList() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragYOffset by remember { mutableStateOf(0f) }
    val lazyListState = rememberLazyListState()

    val totalOffsetX = boxOffsetX + dragOffsetX.value
    val widthFraction = 0.28f + (0.72f * expandProgress)

    Box(Modifier.fillMaxSize().offset { IntOffset(totalOffsetX.roundToInt(), 0) }) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(widthFraction).align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp))
                .hazeChild(hazeState, shape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp), tint = Color.White.copy(0.08f), blurRadius = 30.dp)
                .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp))
                .padding(24.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Text("待办事项", fontSize = fontSizes.todoTitle, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(20.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (todos.isEmpty()) {
                        Text("暂无任务", Modifier.align(Alignment.Center), color = Color.White.copy(0.3f))
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.pointerInput(mutableTodos) {
                                // 长按拖动排序逻辑
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        lazyListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) }
                                            ?.let { draggingIndex = it.index }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragYOffset += dragAmount.y
                                        draggingIndex?.let { current ->
                                            val target = if (dragYOffset > 0) current + 1 else current - 1
                                            if (target in mutableTodos.indices) {
                                                val itemSize = lazyListState.layoutInfo.visibleItemsInfo.find { it.index == current }?.size ?: 100
                                                if (abs(dragYOffset) > itemSize * 0.6f) {
                                                    mutableTodos.add(target, mutableTodos.removeAt(current))
                                                    draggingIndex = target
                                                    dragYOffset = 0f
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = null
                                        dragYOffset = 0f
                                        viewModel.updateTodoOrder(mutableTodos.toList())
                                    }
                                )
                            }
                        ) {
                            itemsIndexed(mutableTodos, key = { _, item -> item.id }) { index, item ->
                                val isDragging = draggingIndex == index
                                Box(Modifier.zIndex(if (isDragging) 10f else 1f)) {
                                    OriginalSwipeItem(
                                        todo = item,
                                        isDragging = isDragging,
                                        onComplete = { viewModel.complete(item.id) },
                                        onStar = { viewModel.toggleStar(item.id, item.isStarred) },
                                        fontSizes = fontSizes
                                    )
                                }
                            }
                        }
                    }
                }

                // 添加按钮
                Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                    if (expandProgress > 0.6f) {
                        Button(
                            onClick = { viewModel.showSheet = true },
                            modifier = Modifier.fillMaxWidth(0.8f).height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("新建任务", fontWeight = FontWeight.Bold, fontSize = fontSizes.buttonText)
                        }
                    } else {
                        FloatingActionButton(onClick = { viewModel.showSheet = true }, containerColor = Color(0xFFFFB800), shape = CircleShape) {
                            Icon(Icons.Default.Add, null, tint = Color.White)
                        }
                    }
                }
            }
            
            // 边缘滑动遮罩（用于检测盒子的缩放/隐藏手势）
            Box(Modifier.fillMaxSize().pointerInput(boxState) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { dragOffsetX.snapTo((dragOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)) }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (dragOffsetX.value > 100f) {
                                if (boxState == TodoBoxState.EXPANDED) onStateChange(TodoBoxState.NORMAL)
                                else onStateChange(TodoBoxState.HIDDEN)
                            } else if (dragOffsetX.value < -50f && boxState == TodoBoxState.NORMAL) {
                                onStateChange(TodoBoxState.EXPANDED)
                            }
                            dragOffsetX.animateTo(0f)
                        }
                    }
                )
            })
        }
    }
}

@Composable
fun OriginalSwipeItem(todo: TodoItem, isDragging: Boolean, onComplete: () -> Unit, onStar: () -> Unit, fontSizes: ResponsiveFontSizes) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxWidth()) {
        // 背景操作图标
        if (offsetX.value > 50) {
            Icon(Icons.Default.Check, null, tint = Color.Green, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        } else if (offsetX.value < -50) {
            Icon(Icons.Default.Star, null, tint = Color.Yellow, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amt -> change.consume(); scope.launch { offsetX.snapTo(offsetX.value + amt) } },
                    onDragEnd = {
                        scope.launch {
                            if (offsetX.value > 150) onComplete()
                            else if (offsetX.value < -150) onStar()
                            offsetX.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                        }
                    }
                )
            }.offset { IntOffset(offsetX.value.roundToInt(), 0) },
            color = Color.White.copy(if (todo.isStarred) 0.15f else 0.08f),
            shape = RoundedCornerShape(16.dp),
            // 样式核心逻辑：拖动时强制白色描边，平时星标黄色描边
            border = when {
                isDragging -> BorderStroke(2.dp, Color.White)
                todo.isStarred -> BorderStroke(1.5.dp, Color(0xFFFFB800))
                else -> null
            }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (todo.isStarred) Icon(Icons.Default.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.size(18.dp).padding(end = 8.dp))
                Text(todo.content, color = Color.White, fontSize = fontSizes.todoItem)
            }
        }
    }
}

// ==========================================
// 4. 辅助函数与弹窗
// ==========================================

@Composable
fun rememberResponsiveFontSizes(): ResponsiveFontSizes {
    val config = LocalConfiguration.current
    val factor = (config.screenWidthDp / 1080f).coerceIn(0.7f, 1.4f)
    return remember(config.screenWidthDp) {
        ResponsiveFontSizes(
            clockHourMinute = (160 * factor).sp, clockSecond = (45 * factor).sp,
            clockSecondOffset = (28 * factor).dp, dateText = (24 * factor).sp,
            todoTitle = (22 * factor).sp, todoItem = (18 * factor).sp,
            buttonText = (16 * factor).sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalAddTodoSheet(visible: Boolean, text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(visible) { if (visible) state.show() else state.hide() }
    if (state.isVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Color(0xFF1A1A1A)) {
            Column(Modifier.padding(24.dp).padding(bottom = 40.dp)) {
                Text("添加新任务", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text, onValueChange = onTextChange, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800), unfocusedTextColor = Color.White, focusedTextColor = Color.White)
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))) {
                    Text("保存", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

data class ResponsiveFontSizes(
    val clockHourMinute: androidx.compose.ui.unit.TextUnit,
    val clockSecond: androidx.compose.ui.unit.TextUnit,
    val clockSecondOffset: androidx.compose.ui.unit.Dp,
    val dateText: androidx.compose.ui.unit.TextUnit,
    val todoTitle: androidx.compose.ui.unit.TextUnit,
    val todoItem: androidx.compose.ui.unit.TextUnit,
    val buttonText: androidx.compose.ui.unit.TextUnit
)
