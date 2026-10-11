package com.smartcalc.app.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.ui.calculator.CalculatorScreen
import com.smartcalc.app.ui.camera.CameraScreen
import com.smartcalc.app.ui.format.FormatScreen
import com.smartcalc.app.ui.settings.SettingsScreen
import com.smartcalc.app.ui.solution.HistoryScreen
import com.smartcalc.app.ui.solution.SolutionScreen
import com.smartcalc.app.ui.template.TemplateEditScreen
import com.smartcalc.app.ui.template.TemplatesScreen

object Routes {
    const val CALCULATOR = "calculator"
    const val CAMERA = "camera"
    const val HISTORY = "history"
    const val SOLUTION = "solution"
    const val SETTINGS = "settings"
    const val FORMAT = "format"
    const val TEMPLATES = "templates"
    const val TEMPLATE_EDIT = "template?id={id}"

    fun templateEdit(id: String?) = if (id == null) "template" else "template?id=$id"
}

@Composable
fun AppRoot(vm: SolutionViewModel = viewModel()) {
    val nav = rememberNavController()

    // Задача отправлена или выбрана из истории — ход решения и ответ показывает дисплей калькулятора.
    fun backToCalculator() {
        nav.popBackStack(Routes.CALCULATOR, inclusive = false)
    }

    Scaffold { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.CALCULATOR,
            // consumeWindowInsets: калькулятор поднимает «=» над системной клавиатурой без лишнего отступа.
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(Routes.CALCULATOR) {
                CalculatorScreen(
                    vm = vm,
                    onOpenSolution = { nav.navigate(Routes.SOLUTION) },
                    onOpenCamera = { nav.navigate(Routes.CAMERA) },
                    onOpenHistory = { nav.navigate(Routes.HISTORY) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenFormat = { nav.navigate(Routes.FORMAT) },
                    onOpenTemplates = { nav.navigate(Routes.TEMPLATES) },
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
                SolutionScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenCalculator = ::backToCalculator)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
