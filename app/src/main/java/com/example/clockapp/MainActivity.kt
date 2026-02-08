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

/* ---------- 数据层 ---------- */

val Context.dataStore by preferencesDataStore(name = "settings")

@Entity(tableName = "todos")
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val isStarred: Boolean = false,
    val orderIndex: Int = 0 // 新增：用于手动排序
)

@Dao
interface TodoDao {
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

/* ---------- ViewModel ---------- */

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val todoDao = (application as ClockApplication).database.todoDao()
    private val BACKGROUND_KEY = stringPreferencesKey("bg_uri")
    private val TODO_BOX_HIDDEN_KEY = booleanPreferencesKey("todo_box_manually_hidden")

    val currentTime = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    val todos = todoDao.getAllActiveTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _bgUri = MutableStateFlow<Uri?>(null)
    val bgUri = _bgUri.asStateFlow()

    private val _isBoxManuallyHidden = MutableStateFlow(false)
    val isBoxManuallyHidden = _isBoxManuallyHidden.asStateFlow()

    var showSheet by mutableStateOf(false)
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

/* ---------- Activity & UI ---------- */

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
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(LocalContext.current.applicationContext as Application)
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

    var boxState by remember { mutableStateOf(TodoBoxState.HIDDEN) }
    val boxOffsetX = remember { Animatable(0f) }
    val expandProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    LaunchedEffect(Unit) {
        boxState = when {
            isPortrait -> TodoBoxState.HIDDEN
            isBoxManuallyHidden -> TodoBoxState.HIDDEN
            else -> TodoBoxState.NORMAL
        }
        boxOffsetX.snapTo(if (boxState == TodoBoxState.HIDDEN) screenWidthPx else 0f)
        expandProgress.snapTo(if (boxState == TodoBoxState.EXPANDED) 1f else 0f)
    }

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
                    boxState = TodoBoxState.HIDDEN
                }
                TodoBoxState.NORMAL -> {
                    launch { boxOffsetX.animateTo(0f, tween(400)) }
                    launch { expandProgress.animateTo(0f, tween(400)) }
                    boxState = TodoBoxState.NORMAL
                }
                else -> {}
            }
        }
    }

    val timeAlpha = 1f - expandProgress.value
    val timeScale = 1f - expandProgress.value * 0.3f
    val boxWidthFraction = 0.25f + 0.75f * expandProgress.value
    val isEffectivelyHidden = boxState == TodoBoxState.HIDDEN && boxOffsetX.value > screenWidthPx * 0.9f

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::setBackground) }

    // 边缘滑动逻辑修复版
    val edgeGestureModifier = Modifier.pointerInput(boxState, isPortrait) {
        if (boxState != TodoBoxState.EXPANDED && boxState != TodoBoxState.TRANSITIONING) {
            detectDragGestures(
                onDragStart = { offset ->
                    val edgeThreshold = with(density) { 50.dp.toPx() }
                    if (offset.x >= screenWidthPx - edgeThreshold) {
                        if (boxOffsetX.value < screenWidthPx) scope.launch { boxOffsetX.snapTo(screenWidthPx) }
                    }
                },
                onDrag = { change, dragAmount ->
                    val (dx, dy) = dragAmount
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
                            if (isPortrait) {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { boxOffsetX.animateTo(0f, tween(400)) }
                                launch { expandProgress.animateTo(1f, tween(500)) }
                                boxState = TodoBoxState.EXPANDED
                            } else {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { boxOffsetX.animateTo(0f, tween(400)) }
                                boxState = TodoBoxState.NORMAL
                                viewModel.setBoxManuallyHidden(false)
                            }
                        } else {
                            boxState = TodoBoxState.TRANSITIONING
                            launch { boxOffsetX.animateTo(screenWidthPx, tween(400)) }
                            if (isLandscape) viewModel.setBoxManuallyHidden(true)
                            boxState = TodoBoxState.HIDDEN
                        }
                    }
                }
            )
        }
    }

    Box(
        Modifier.fillMaxSize().then(edgeGestureModifier)
            .pointerInput(boxState) {
                detectTapGestures(onDoubleTap = { offset ->
                    val boxVisible = boxState != TodoBoxState.HIDDEN && boxState != TodoBoxState.TRANSITIONING
                    val inBoxArea = when (boxState) {
                        TodoBoxState.EXPANDED -> true
                        TodoBoxState.NORMAL -> offset.x >= screenWidthPx * 0.75f
                        else -> false
                    }
                    if (!boxVisible || !inBoxArea) launcher.launch(arrayOf("image/*"))
                })
            }
    ) {
        /* 背景层 */
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            if (bgUri != null) {
                Image(rememberAsyncImagePainter(bgUri), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.15f)))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43)))))
            }
        }

        /* 时间显示区 */
        Box(Modifier.fillMaxSize().padding(32.dp).statusBarsPadding()) {
            Column(
                Modifier.then(if (isEffectivelyHidden) Modifier.fillMaxSize() else Modifier.fillMaxHeight().fillMaxWidth(0.75f))
                    .graphicsLayer { scaleX = timeScale; scaleY = timeScale; alpha = timeAlpha },
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)), style = TextStyle(fontSize = fontSizes.clockHourMinute, fontWeight = FontWeight.Thin, color = Color.White))
                    Text(SimpleDateFormat(":ss", Locale.getDefault()).format(Date(time)), style = TextStyle(fontSize = fontSizes.clockSecond, fontWeight = FontWeight.ExtraLight, color = Color.White.copy(0.8f)), modifier = Modifier.padding(bottom = fontSizes.clockSecondOffset, start = 12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date(time)), style = TextStyle(fontSize = fontSizes.dateText, fontWeight = FontWeight.Light, color = Color.White.copy(0.8f)))
            }
        }

        /* 待办区 */
        if (boxState != TodoBoxState.HIDDEN || boxOffsetX.value < screenWidthPx * 0.99f) {
            TodoBoxContent(
                boxState = boxState, boxOffsetX = boxOffsetX.value, expandProgress = expandProgress.value,
                boxWidthFraction = boxWidthFraction, todos = todos, hazeState = hazeState,
                viewModel = viewModel, onStateChange = { newState, currentIsPortrait ->
                    scope.launch {
                        when (newState) {
                            TodoBoxState.EXPANDED -> {
                                boxState = TodoBoxState.TRANSITIONING
                                launch { expandProgress.animateTo(1f, tween(500)) }
                                boxState = TodoBoxState.EXPANDED
                            }
                            TodoBoxState.NORMAL -> {
                                if (boxState == TodoBoxState.EXPANDED) {
                                    boxState = TodoBoxState.TRANSITIONING
                                    launch { expandProgress.animateTo(0f, tween(350)) }
                                    if (currentIsPortrait) {
                                        launch { boxOffsetX.animateTo(screenWidthPx, tween(350)) }
                                        delay(350)
                                        boxState = TodoBoxState.HIDDEN
                                    } else {
                                        launch { boxOffsetX.animateTo(0f, tween(400)) }
                                        boxState = TodoBoxState.NORMAL
                                    }
                                } else boxState = TodoBoxState.NORMAL
                            }
                            TodoBoxState.HIDDEN -> {
                                launch { expandProgress.animateTo(0f, tween(400)) } // 核心修复：确保淡入
                                boxState = TodoBoxState.HIDDEN
                                boxOffsetX.snapTo(screenWidthPx)
                                if (isLandscape) viewModel.setBoxManuallyHidden(true)
                            }
                            else -> {}
                        }
                    }
                },
                isLandscape = isLandscape, screenWidthPx = screenWidthPx, fontSizes = fontSizes, isPortrait = isPortrait
            )
        }

        if (viewModel.snackbarVisible) {
            Card(Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(12.dp)) {
                Text("已完成！", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Color.White)
            }
        }

        OriginalAddTodoSheet(
            visible = viewModel.showSheet, text = viewModel.inputText,
            onTextChange = { viewModel.inputText = it }, onDismiss = { viewModel.showSheet = false },
            onSave = { viewModel.saveTodo() }
        )
    }
}

@Composable
fun TodoBoxContent(
    boxState: TodoBoxState, boxOffsetX: Float, expandProgress: Float, boxWidthFraction: Float,
    todos: List<TodoItem>, hazeState: HazeState, viewModel: MainViewModel,
    onStateChange: (TodoBoxState, Boolean) -> Unit, isLandscape: Boolean,
    screenWidthPx: Float, fontSizes: ResponsiveFontSizes, isPortrait: Boolean
) {
    val scope = rememberCoroutineScope()
    val dragOffsetX = remember { Animatable(0f) }
    var dragType by remember { mutableStateOf<String?>(null) }
    
    // 排序专用状态
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragDeltaY by remember { mutableStateOf(0f) }
    val lazyListState = rememberLazyListState()
    val mutableTodos = remember(todos) { todos.toMutableStateList() }

    val actualOffsetX = dragOffsetX.value + boxOffsetX

    LaunchedEffect(boxState) { if (boxState == TodoBoxState.HIDDEN) dragOffsetX.snapTo(0f) }

    Box(Modifier.fillMaxSize().offset { IntOffset(actualOffsetX.roundToInt(), 0) }) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(boxWidthFraction).align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(32.dp * (1f - expandProgress)))
                .hazeChild(state = hazeState, shape = RoundedCornerShape(32.dp * (1f - expandProgress)), tint = Color.White.copy(0.1f), blurRadius = 25.dp)
                .then(if (expandProgress < 1f) Modifier.border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(32.dp * (1f - expandProgress))) else Modifier)
        ) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)
                    .pointerInput(boxState, isPortrait) {
                        if (boxState == TodoBoxState.NORMAL || boxState == TodoBoxState.EXPANDED) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragType = null },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        if (dragType == null && abs(dragAmount) > 5f) dragType = if (dragAmount < 0) "expand" else "hide"
                                        if ((boxState == TodoBoxState.NORMAL && dragType == "hide" && isLandscape) || boxState == TodoBoxState.EXPANDED) {
                                            val newOffset = (dragOffsetX.value + dragAmount).coerceIn(0f, screenWidthPx)
                                            dragOffsetX.snapTo(newOffset)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    scope.launch {
                                        if (dragOffsetX.value > screenWidthPx * 0.07f) {
                                            if (isPortrait && boxState == TodoBoxState.EXPANDED) {
                                                onStateChange(TodoBoxState.HIDDEN, true)
                                                dragOffsetX.animateTo(screenWidthPx, tween(300))
                                            } else {
                                                dragOffsetX.animateTo(0f, tween(200))
                                                onStateChange(if (boxState == TodoBoxState.EXPANDED) TodoBoxState.NORMAL else TodoBoxState.HIDDEN, isPortrait)
                                            }
                                        } else dragOffsetX.animateTo(0f, tween(300))
                                        dragType = null
                                    }
                                }
                            )
                        }
                    }
                    .pointerInput(boxState) {
                        detectTapGestures(onDoubleTap = { if (boxState == TodoBoxState.EXPANDED) onStateChange(TodoBoxState.NORMAL, isPortrait) })
                    }
            ) {
                Text("待办事项", style = TextStyle(fontSize = fontSizes.todoTitle, fontWeight = FontWeight.Bold, color = Color.White))
                Spacer(Modifier.height(20.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (todos.isEmpty()) {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("悠闲的一天，去喝杯茶吧~", style = TextStyle(color = Color.White.copy(0.25f), fontSize = fontSizes.emptyText))
                        }
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.pointerInput(mutableTodos) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val item = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) }
                                        item?.let { draggingItemIndex = it.index }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragDeltaY += dragAmount.y
                                        draggingItemIndex?.let { currentIndex ->
                                            val targetIndex = if (dragDeltaY > 0) currentIndex + 1 else currentIndex - 1
                                            if (targetIndex in mutableTodos.indices) {
                                                val itemHeight = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }?.size ?: 0
                                                if (abs(dragDeltaY) > itemHeight * 0.6f) {
                                                    mutableTodos.add(targetIndex, mutableTodos.removeAt(currentIndex))
                                                    draggingItemIndex = targetIndex
                                                    dragDeltaY = 0f
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingItemIndex = null
                                        dragDeltaY = 0f
                                        viewModel.updateTodoOrder(mutableTodos.toList())
                                    },
                                    onDragCancel = { draggingItemIndex = null; dragDeltaY = 0f }
                                )
                            }
                        ) {
                            itemsIndexed(mutableTodos, key = { _, item -> item.id }) { index, item ->
                                val isDragging = draggingItemIndex == index
                                Box(Modifier.zIndex(if (isDragging) 1f else 0f)) {
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

                Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    if (expandProgress > 0.5f) {
                        Button(onClick = { viewModel.showSheet = true }, modifier = Modifier.fillMaxWidth(0.7f).align(Alignment.Center).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800)), shape = RoundedCornerShape(24.dp)) {
                            Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("添加", fontWeight = FontWeight.Bold, color = Color.White, fontSize = fontSizes.buttonText)
                        }
                    } else {
                        FloatingActionButton(onClick = { viewModel.showSheet = true }, containerColor = Color(0xFFFFB800), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) {
                            Icon(Icons.Default.Add, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OriginalSwipeItem(
    todo: TodoItem,
    isDragging: Boolean,
    onComplete: () -> Unit,
    onStar: () -> Unit,
    fontSizes: ResponsiveFontSizes
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val iconScale by remember { derivedStateOf { (abs(offsetX.value) / 150f).coerceIn(0f, 1.2f) } }

    Box(Modifier.fillMaxWidth().wrapContentHeight()) {
        if (offsetX.value < 0) {
            Box(Modifier.matchParentSize().padding(end = 16.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Default.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.size(28.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale; alpha = iconScale.coerceIn(0f, 1f) })
            }
        }
        if (offsetX.value > 0) {
            Box(Modifier.matchParentSize().padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.size(28.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale; alpha = iconScale.coerceIn(0f, 1f) }.background(Color(0xFF4CAF50), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }

        Surface(
            modifier = Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }.fillMaxWidth().wrapContentHeight()
                .pointerInput(todo) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount -> change.consume(); scope.launch { offsetX.snapTo(offsetX.value + dragAmount) } },
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
            // 样式修复：拖拽时固定白色描边，星标时常驻黄色描边
            border = when {
                isDragging -> BorderStroke(2.dp, Color.White)
                todo.isStarred -> BorderStroke(1.dp, Color(0xFFFFB800).copy(0.4f))
                else -> null
            }
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (todo.isStarred) {
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFB800), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(todo.content, color = Color.White, fontSize = fontSizes.todoItem, style = TextStyle(lineHeight = 24.sp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalAddTodoSheet(
    visible: Boolean, text: String, onTextChange: (String) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(visible) { if (visible) sheetState.show() else sheetState.hide() }
    if (sheetState.isVisible) {
        BackHandler(enabled = true) { onDismiss() }
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF1C1C1E)) {
            Column(Modifier.padding(24.dp).padding(bottom = 32.dp)) {
                Text("新建待办事项", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White))
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = text, onValueChange = onTextChange, modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFFFB800), unfocusedBorderColor = Color.White.copy(0.3f), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    placeholder = { Text("想做点什么？", color = Color.White.copy(0.4f)) }
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color.White.copy(0.6f)) }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onSave, enabled = text.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB800))) {
                        Text("添加", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
