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
import androidx.compose.ui.text.style.TextOverflow
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------- Data ---------- */

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

    @Insert suspend fun insert(todo: TodoItem)
    @Query("UPDATE todos SET isCompleted = 1 WHERE id = :id") suspend fun markAsCompleted(id: Long)
    @Query("UPDATE todos SET isStarred = :starred WHERE id = :id") suspend fun setStarred(id: Long, starred: Boolean)
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

    private val dao = (application as ClockApplication).database.todoDao()
    private val BG_KEY = stringPreferencesKey("bg_uri")

    val currentTime = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        System.currentTimeMillis()
    )

    val todos = dao.getAllActiveTodos()
        .scan(emptyList<TodoItem>()) { old, new -> if (new.isEmpty()) old else new }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _bgUri = MutableStateFlow<Uri?>(null)
    val bgUri = _bgUri.asStateFlow()

    var showSheet by mutableStateOf(false)
    var showDiscardDialog by mutableStateOf(false)
    var inputText by mutableStateOf("")
    var snackbarVisible by mutableStateOf(false)

    private var snackbarJob: Job? = null

    init {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data
                .map { it[BG_KEY] }
                .firstOrNull()
                ?.let {
                    val uri = Uri.parse(it)
                    if (canRead(uri)) _bgUri.value = uri else clearBg()
                }
        }
    }

    private fun canRead(uri: Uri): Boolean =
        try {
            getApplication<Application>().contentResolver.openInputStream(uri)?.close()
            true
        } catch (_: Exception) { false }

    private fun clearBg() {
        viewModelScope.launch {
            _bgUri.value = null
            getApplication<Application>().dataStore.edit { it.remove(BG_KEY) }
        }
    }

    fun setBackground(uri: Uri) {
        _bgUri.value = uri
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getApplication<Application>().dataStore.edit { it[BG_KEY] = uri.toString() }
            } catch (_: Exception) { clearBg() }
        }
    }

    fun saveTodo() {
        if (inputText.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insert(TodoItem(content = inputText.trim()))
        }
        inputText = ""
        showSheet = false
    }

    fun complete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { dao.markAsCompleted(id) }
        snackbarJob?.cancel()
        snackbarJob = viewModelScope.launch {
            snackbarVisible = true
            delay(1000)
            snackbarVisible = false
        }
    }

    fun toggleStar(id: Long, current: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { dao.setStarred(id, !current) }
    }
}

/* ---------- UI ---------- */

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockTodoApp() {
    val vm: MainViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as Application
        )
    )

    val bgUri by vm.bgUri.collectAsState()
    val time by vm.currentTime.collectAsState()
    val todos by vm.todos.collectAsState()

    val haze = remember { HazeState() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { it?.let(vm::setBackground) }

    Box(Modifier.fillMaxSize()) {

        Box(Modifier.fillMaxSize().haze(haze)) {
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

        IconButton(
            onClick = { launcher.launch(arrayOf("image/*")) },
            modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
        ) {
            Icon(Icons.Default.Image, null, tint = Color.White.copy(0.5f))
        }

        Row(Modifier.fillMaxSize().padding(32.dp)) {

            Column(
                Modifier.weight(0.75f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = TextStyle(
                            fontSize = 180.sp,
                            fontWeight = FontWeight.Thin,
                            color = Color.White
                        )
                    )
                    Text(
                        SimpleDateFormat(":ss", Locale.getDefault()).format(Date(time)),
                        style = TextStyle(
                            fontSize = 50.sp,
                            fontWeight = FontWeight.ExtraLight,
                            color = Color.White.copy(0.8f)
                        ),
                        modifier = Modifier.padding(bottom = 32.dp, start = 12.dp)
                    )
                }
            }

            Column(Modifier.weight(0.25f).padding(start = 24.dp)) {
                Box(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(32.dp))
                        .hazeChild(
                            haze,
                            RoundedCornerShape(32.dp),
                            Color.White.copy(0.1f),
                            25.dp
                        )
                        .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(32.dp))
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text("待办事项", color = Color.White)
                        Spacer(Modifier.height(16.dp))

                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(todos, key = { it.id }) {
                                OriginalSwipeItem(
                                    it,
                                    { vm.complete(it.id) },
                                    { vm.toggleStar(it.id, it.isStarred) }
                                )
                            }
                        }

                        FloatingActionButton(
                            onClick = { vm.showSheet = true },
                            containerColor = Color(0xFFFFB800),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(Icons.Default.Add, null)
                        }
                    }
                }
            }
        }

        if (vm.snackbarVisible) {
            Card(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("已完成！", Modifier.padding(16.dp), color = Color.White)
            }
        }

        if (vm.showSheet) {
            OriginalAddTodoSheet(
                vm.inputText,
                { vm.inputText = it },
                {
                    if (vm.inputText.isNotBlank()) vm.showDiscardDialog = true
                    else vm.showSheet = false
                },
                { vm.saveTodo() }
            )
        }

        if (vm.showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { vm.showDiscardDialog = false },
                title = { Text("继续编辑吗？") },
                text = { Text("内容还没保存。") },
                confirmButton = {
                    TextButton(onClick = { vm.showDiscardDialog = false }) {
                        Text("继续编辑")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.inputText = ""
                        vm.showDiscardDialog = false
                        vm.showSheet = false
                    }) { Text("放弃") }
                }
            )
        }
    }
}

/* ---------- Swipe Item ---------- */

@Composable
fun OriginalSwipeItem(todo: TodoItem, onComplete: () -> Unit, onStar: () -> Unit) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scale = (abs(offsetX.value) / 150f).coerceIn(0f, 1.2f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .pointerInput(todo.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, drag ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo(
                                (offsetX.value + drag).coerceIn(-200f, 200f)
                            )
                        }
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
        color = if (todo.isStarred)
            Color(0xFFFFB800).copy(0.15f)
        else Color.White.copy(0.12f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (todo.isStarred) {
                Icon(
                    Icons.Default.Star,
                    null,
                    tint = Color(0xFFFFB800),
                    modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(todo.content, color = Color.White, maxLines = 1)
        }
    }
}

/* ---------- Bottom Sheet ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalAddTodoSheet(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color(0xFF1C1C1E)
    ) {
        Column(Modifier.padding(24.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("想做点什么？") }
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSave, enabled = text.isNotBlank()) {
                Text("添加")
            }
        }
    }
}
