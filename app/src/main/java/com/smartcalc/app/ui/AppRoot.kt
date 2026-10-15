package com.smartcalc.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import android.net.Uri
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import com.smartcalc.app.ui.components.LocalReadingHeight
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.session.ReadingTarget
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.ui.calculator.CalculatorScreen
import com.smartcalc.app.ui.camera.CameraScreen
import com.smartcalc.app.ui.format.FormatScreen
import com.smartcalc.app.ui.search.LibraryScreen
import com.smartcalc.app.ui.library.*
import com.smartcalc.app.ui.search.SearchScreen
import com.smartcalc.app.ui.settings.ConnectionScreen
import com.smartcalc.app.ui.settings.ConnectionEditorViewModel
import com.smartcalc.app.ui.settings.SettingsScreen
import com.smartcalc.app.ui.solution.HistoryScreen
import com.smartcalc.app.ui.solution.SolutionScreen
import com.smartcalc.app.ui.solution.StepScreen
import com.smartcalc.app.ui.template.TemplateEditScreen
import com.smartcalc.app.ui.template.TemplatesScreen

object Routes {
    const val CALCULATOR = "calculator"
    const val CAMERA = "camera"
    const val HISTORY = "history"
    const val SOLUTION = "solution"
    const val STEP = "step/{index}"
    const val SETTINGS = "settings?section={section}"
    const val CONNECTION = "connection?id={id}"
    const val FORMAT = "format"
    const val TEMPLATES = "templates"
    const val TEMPLATE_EDIT = "template?id={id}"
    const val SEARCH = "search?q={q}"
    const val LIBRARY = "library"
    const val LIBRARY_EDIT = "library/section?id={id}&example={example}"
    const val LIBRARY_FILES = "library/files"
    const val LIBRARY_ADD = "library/add"
    const val LIBRARY_IMPORT = "library/import"
    const val LIBRARY_REVIEW = "library/review/{jobId}"
    const val LIBRARY_SOURCE = "library/source/{sourceId}?ordinal={ordinal}"
    const val LIBRARY_CAMERA = "library/camera"

    fun templateEdit(id: String?) = if (id == null) "template" else "template?id=$id"
    fun connection(id: String?) = if (id == null) "connection" else "connection?id=${Uri.encode(id)}"
    fun search(query: String) = if (query.isBlank()) "search" else "search?q=${Uri.encode(query)}"
    fun libraryEdit(id: String?, exampleIndex: Int? = null) = if (id == null) "library/section"
        else "library/section?id=${Uri.encode(id)}" + (exampleIndex?.let { "&example=$it" } ?: "")
    fun step(index: Int) = "step/$index"
    fun libraryReview(id: String) = "library/review/$id"
    fun librarySource(id: String, ordinal: Int) = "library/source/$id?ordinal=$ordinal"
}

@Composable
fun AppRoot(vm: SolutionViewModel = viewModel()) {
    val uiState = rememberSaveableStateHolder()
    var savedInstance by rememberSaveable { mutableStateOf(vm.sessionInstanceId) }
    if (savedInstance != vm.sessionInstanceId) {
        // После гибели процесса убираем также сохранённую навигацию и открытый редактор.
        uiState.removeState(savedInstance)
        savedInstance = vm.sessionInstanceId
    }
    uiState.SaveableStateProvider(savedInstance) { AppSession(vm) }
}

@Composable
private fun AppSession(vm: SolutionViewModel) {
    val nav = rememberNavController()
    val libraryVm: LibraryImportViewModel = viewModel()
    fun libraryTab(route: String) {
        nav.navigate(route) {
            popUpTo(Routes.CALCULATOR) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val solution by vm.current.collectAsState()
    val shown by vm.shown.collectAsState()
    val solving by vm.solving.collectAsState()
    val error by vm.error.collectAsState()
    // Новая задача, дополнение или выбор из истории начинают новый сеанс чтения.
    val readingKey = "${solution?.id}:${solution?.followUps?.size}:$shown"
    var readingReturn by rememberSaveable {
        val saved = vm.session.value.reading
        mutableStateOf(when (saved.target) {
            ReadingTarget.STEP -> Routes.step(saved.selectedStep)
            ReadingTarget.SOLUTION -> Routes.SOLUTION
            ReadingTarget.BRIEF -> null
        })
    }
    var savedReadingKey by rememberSaveable { mutableStateOf(readingKey) }
    var selectedStep by rememberSaveable(readingKey) { mutableIntStateOf(vm.session.value.reading.selectedStep) }

    fun forgetReadingReturn() {
        readingReturn?.let { nav.clearBackStack(it) }
        readingReturn = null
    }

    LaunchedEffect(readingKey) {
        if (savedReadingKey != readingKey) {
            forgetReadingReturn()
            savedReadingKey = readingKey
        }
    }

    // Задача отправлена или выбрана из истории — ход решения и ответ показывает дисплей калькулятора.
    fun backToCalculator() {
        nav.popBackStack(Routes.CALCULATOR, inclusive = false)
    }

    fun closeReading() {
        vm.setReadingTarget(ReadingTarget.BRIEF)
        nav.popBackStack()
    }

    fun hideReading(route: String) {
        readingReturn = route
        savedReadingKey = readingKey
        vm.hideFromDisplay()
        // Сохраняем саму запись навигации: её ключи rememberSaveable остаются прежними при возврате.
        nav.popBackStack(Routes.CALCULATOR, inclusive = false, saveState = true)
    }

    fun restoreAssistant() {
        vm.showOnDisplay()
        val route = readingReturn
        readingReturn = null
        if (route != null && savedReadingKey == readingKey && solution != null && !solving && error == null) {
            nav.navigate(route) {
                restoreState = true
                launchSingleTop = true
            }
        } else if (route != null) {
            nav.clearBackStack(route)
        }
    }

    Scaffold { padding ->
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding)) {
        CompositionLocalProvider(LocalReadingHeight provides maxHeight) {
        NavHost(
            navController = nav,
            startDestination = Routes.CALCULATOR,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            // consumeWindowInsets: калькулятор поднимает «=» над системной клавиатурой без лишнего отступа.
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.CALCULATOR) {
                CalculatorScreen(
                    vm = vm,
                    onOpenSolution = {
                        forgetReadingReturn()
                        vm.setReadingTarget(ReadingTarget.SOLUTION)
                        nav.navigate(Routes.SOLUTION) { launchSingleTop = true }
                    },
                    onOpenStep = {
                        forgetReadingReturn()
                        selectedStep = it
                        vm.setReadingTarget(ReadingTarget.STEP, it)
                        nav.navigate(Routes.step(it)) { launchSingleTop = true }
                    },
                    onRestoreAssistant = ::restoreAssistant,
                    onOpenCamera = { nav.navigate(Routes.CAMERA) },
                    onOpenHistory = { nav.navigate(Routes.HISTORY) },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenConnections = { nav.navigate("settings?section=connections") },
                    onOpenFormat = { nav.navigate(Routes.FORMAT) },
                    onOpenTemplates = { nav.navigate(Routes.TEMPLATES) },
                    onOpenSearch = { query -> nav.navigate(Routes.search(query)) },
                )
            }
            composable(
                Routes.SEARCH,
                arguments = listOf(
                    navArgument("q") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SearchScreen(
                    vm = vm,
                    initialQuery = entry.arguments?.getString("q").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenLibrary = { libraryTab(Routes.LIBRARY) },
                    onOpenFiles = { libraryTab(Routes.LIBRARY_FILES) },
                    onAddMaterial = { nav.navigate(Routes.LIBRARY_ADD) },
                    onOpenSolution = ::backToCalculator,
                    onSource = { ref -> nav.navigate(Routes.librarySource(ref.sourceId, ref.ordinal)) },
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onEdit = { id, index -> nav.navigate(Routes.libraryEdit(id, index)) },
                    onFiles = { libraryTab(Routes.LIBRARY_FILES) },
                    onSearch = { libraryTab(Routes.search("")) },
                    onAddMaterial = { nav.navigate(Routes.LIBRARY_ADD) },
                    onUse = ::backToCalculator,
                    onSource = { ref -> nav.navigate(Routes.librarySource(ref.sourceId, ref.ordinal)) },
                )
            }
            composable(Routes.LIBRARY_FILES) {
                LibraryFilesScreen(libraryVm, onBack = { nav.popBackStack() },
                    onSelection = { nav.navigate(Routes.LIBRARY_IMPORT) },
                    onReview = { nav.navigate(Routes.libraryReview(it)) },
                    onSource = { id, ordinal -> nav.navigate(Routes.librarySource(id, ordinal)) },
                    onSearch = { libraryTab(Routes.search("")) },
                    onTasks = { libraryTab(Routes.LIBRARY) },
                    onAddMaterial = { nav.navigate(Routes.LIBRARY_ADD) })
            }
            composable(Routes.LIBRARY_ADD) {
                AddLibraryMaterialScreen(vm, libraryVm,
                    onBack = { nav.popBackStack() },
                    onSelection = { nav.navigate(Routes.LIBRARY_IMPORT) },
                    onCamera = { nav.navigate(Routes.LIBRARY_CAMERA) },
                    onManual = { nav.navigate(Routes.libraryEdit(null)) },
                    onCollectionImported = {
                        nav.popBackStack()
                        libraryTab(Routes.LIBRARY)
                    })
            }
            composable(Routes.LIBRARY_IMPORT) {
                ImportSelectionScreen(libraryVm, onBack = { nav.popBackStack() }, onReview = {
                    nav.navigate(Routes.libraryReview(it)) { popUpTo(Routes.LIBRARY_IMPORT) { inclusive = true } }
                })
            }
            composable(Routes.LIBRARY_REVIEW) { entry ->
                ImportReviewScreen(libraryVm, entry.arguments?.getString("jobId").orEmpty(),
                    onBack = { nav.popBackStack() }, onSource = { id, ordinal -> nav.navigate(Routes.librarySource(id, ordinal)) })
            }
            composable(Routes.LIBRARY_SOURCE, arguments = listOf(navArgument("ordinal") { type = NavType.IntType; defaultValue = 1 })) { entry ->
                SourceViewerScreen(libraryVm, entry.arguments?.getString("sourceId").orEmpty(),
                    entry.arguments?.getInt("ordinal") ?: 1, onBack = { nav.popBackStack() })
            }
            composable(Routes.LIBRARY_CAMERA) {
                CameraScreen(vm, onBack = { nav.popBackStack() }, onOpenFormat = {}, onSolved = {},
                    onImportInput = { libraryVm.copyInputs(listOf(it)) }, onImported = {
                        nav.navigate(Routes.LIBRARY_IMPORT) { popUpTo(Routes.LIBRARY_CAMERA) { inclusive = true } }
                    })
            }
            composable(
                Routes.LIBRARY_EDIT,
                arguments = listOf(
                    navArgument("example") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                TemplateEditScreen(
                    vm = vm,
                    templateId = entry.arguments?.getString("id"),
                    onBack = { nav.popBackStack() },
                    kind = TemplateKind.LIBRARY,
                    exampleIndex = entry.arguments?.getInt("example")?.takeIf { it >= 0 },
                    onOpenSource = { ref -> nav.navigate(Routes.librarySource(ref.sourceId, ref.ordinal)) },
                )
            }
            composable(Routes.TEMPLATES) {
                TemplatesScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onUse = ::backToCalculator,
                    onEdit = { id -> nav.navigate(Routes.templateEdit(id)) },
                )
            }
            composable(
                Routes.TEMPLATE_EDIT,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                TemplateEditScreen(vm = vm, templateId = entry.arguments?.getString("id"), onBack = { nav.popBackStack() })
            }
            composable(Routes.CAMERA) {
                CameraScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenFormat = { nav.navigate(Routes.FORMAT) },
                    onSolved = ::backToCalculator,
                )
            }
            composable(Routes.FORMAT) {
                FormatScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable(Routes.HISTORY) {
                HistoryScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenSolution = ::backToCalculator)
            }
            composable(Routes.SOLUTION) {
                AcknowledgeReading(vm)
                SolutionScreen(
                    vm = vm,
                    onBack = ::closeReading,
                    onOpenCalculator = {
                        forgetReadingReturn()
                        vm.setReadingTarget(ReadingTarget.BRIEF)
                        backToCalculator()
                    },
                    onHideToCalculator = { hideReading(Routes.SOLUTION) },
                )
            }
            composable(
                Routes.STEP,
                arguments = listOf(navArgument("index") { type = NavType.IntType }),
            ) { entry ->
                val s = solution
                if (s != null) {
                    AcknowledgeReading(vm)
                    val initialScroll = remember(s.id, s.followUps.size, selectedStep) {
                        vm.session.value.reading.stepScroll[selectedStep] ?: 0
                    }
                    StepScreen(
                        solution = s,
                        index = selectedStep.coerceIn(0, s.latestSteps.size),
                        onIndexChange = {
                            selectedStep = it
                            vm.setReadingTarget(ReadingTarget.STEP, it)
                        },
                        onBack = ::closeReading,
                        initialScroll = initialScroll,
                        onScrollChange = { index, offset -> vm.saveStepScroll(s.id, s.followUps.size, index, offset) },
                        // Восстанавливаем исходную запись маршрута; выбранный стрелками шаг хранится отдельно.
                        onHideToCalculator = { hideReading(Routes.step(entry.arguments?.getInt("index") ?: 0)) },
                    )
                } else {
                    // После восстановления процесса решение может ещё отсутствовать в памяти.
                    LaunchedEffect(Unit) { backToCalculator() }
                }
            }
            composable(Routes.SETTINGS, arguments = listOf(navArgument("section") { type = NavType.StringType; defaultValue = "" })) { entry ->
                SettingsScreen(vm = vm, onBack = { nav.popBackStack() },
                    openConnections = entry.arguments?.getString("section") == "connections",
                    onOpenConnection = { nav.navigate(Routes.connection(it)) })
            }
            composable(
                Routes.CONNECTION,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("id")
                val editor: ConnectionEditorViewModel = viewModel(
                    viewModelStoreOwner = entry,
                    factory = ConnectionEditorViewModel.factory(connectionId),
                )
                ConnectionScreen(
                    vm = vm,
                    editor = editor,
                    onBack = { nav.popBackStack() },
                    // Копия открывается вместо исходного подключения: «назад» ведёт к списку.
                    onOpenConnection = { id ->
                        nav.navigate(Routes.connection(id)) { popUpTo(Routes.CONNECTION) { inclusive = true } }
                    },
                )
            }
        }
        }
        }
    }
}

/** Открытый шаг тоже считается просмотром ответа; переход мимо него не гасит метку. */
@Composable
private fun AcknowledgeReading(vm: SolutionViewModel) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val display by vm.display.collectAsState()
    val solving by vm.solving.collectAsState()
    LaunchedEffect(lifecycle, display, solving) {
        if (lifecycle == Lifecycle.State.RESUMED && !solving) vm.markViewed()
    }
}
