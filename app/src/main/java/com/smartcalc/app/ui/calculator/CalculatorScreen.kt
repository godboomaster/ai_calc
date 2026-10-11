package com.smartcalc.app.ui.calculator

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.DocumentReader
import com.smartcalc.app.ui.math.MathMarkup
import com.smartcalc.app.ui.math.MathText
import kotlinx.coroutines.delay

/** Шрифт уменьшается с ростом выражения, а не уезжает за край экрана. */
internal fun displayFontSize(length: Int): TextUnit = when {
    length <= 10 -> 52.sp
    length <= 14 -> 42.sp
    length <= 20 -> 34.sp
    else -> 28.sp
}

/** Символы обычного выражения. Всё остальное (буквы, «=», «^») значит, что это задача для ИИ. */
private const val ARITHMETIC = "0123456789.,+-−×÷*/()%E∞"

private fun isArithmetic(text: String) = text.all { it in ARITHMETIC || it.isWhitespace() }

/** Действие ИИ на месте цифры. */
private class AiAction(
    val label: String,
    val icon: ImageVector,
    val highlighted: Boolean = false,
    val onClick: () -> Unit,
)

/** Шаг решения, который открывается удержанием цифры: 1–9 — шаги с первого по девятый, 0 — десятый. */
private fun stepIndex(digit: Char) = if (digit == '0') 9 else digit - '1'

/** Задачи длиннее выражений: после шкалы калькулятора шрифт мельчает дальше. */
private fun inputFontSize(length: Int): TextUnit = when {
    length <= 20 -> displayFontSize(length)
    length <= 60 -> 26.sp
    else -> 22.sp
}

/**
 * Калькулятор, он же вход в ИИ. Значок в углу превращает цифры 1–8 в действия ИИ (написать,
 * камера, галерея, документ, история, настройки, шаблоны, формат), а нажатие на строку ввода
 * открывает системную клавиатуру — задачу можно написать прямо на дисплее. «=» считает выражение,
 * а всё, что выражением не является, отправляет ИИ; ход решения и ответ появляются на этом же дисплее.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CalculatorScreen(
    vm: SolutionViewModel,
    onOpenSolution: () -> Unit,
    onOpenStep: (Int) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFormat: () -> Unit,
    onOpenTemplates: () -> Unit,
) {
    // rememberSaveable: выражение и режим не теряются, пока открыты камера, история или настройки.
    var expr by rememberSaveable { mutableStateOf("") }
    var evaluated by rememberSaveable { mutableStateOf(false) }
    var aiKeys by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // Строка ввода дописывает вопрос к ответу на дисплее, а не новую задачу.
    var followUp by rememberSaveable { mutableStateOf(false) }
    // Недописанное дополнение не пропадает, если клавиатуру закрыли.
    var followUpDraft by rememberSaveable { mutableStateOf("") }
    var taskDraft by rememberSaveable { mutableStateOf("") }
    var returnToAssistant by rememberSaveable { mutableStateOf(false) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val keyboard = LocalSoftwareKeyboardController.current

    val display by vm.display.collectAsState()
    val swipeToRestore by vm.swipeToRestore.collectAsState()
    val onDisplay = display.visible
    val solving by vm.solving.collectAsState()
    val error by vm.error.collectAsState()
    val pending by vm.pending.collectAsState()
    val solution by vm.current.collectAsState()
    var draftSolutionId by rememberSaveable { mutableStateOf(solution?.id) }
    val format by vm.format.collectAsState()
    val template by vm.armedTemplate.collectAsState()
    val templates by vm.templates.collectAsState()
    val steps = solution?.latestSteps.orEmpty()
    val answerVisible = onDisplay && !solving && error == null && solution != null
    val canSwipeToRestore = swipeToRestore && !onDisplay && !editing && !aiKeys &&
        (solving || solution != null || error != null)
    val answerScroll = rememberScrollState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()

    // Меню, редактор и экраны поверх калькулятора не означают, что результат прочитан.
    LaunchedEffect(display, solving, editing, aiKeys, lifecycle) {
        if (onDisplay && !solving && !editing && !aiKeys && lifecycle == Lifecycle.State.RESUMED) {
            vm.markViewed()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::solveGalleryImage)
    }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::solveDocument)
    }

    val preview = remember(expr) {
        if (expr.isBlank() || expr.last() in "+−×÷(" || !isArithmetic(expr)) null
        else try {
            ExpressionParser.format(ExpressionParser.evaluate(expr))
        } catch (e: Exception) {
            null
        }
    }

    /** Клавиша нажата, пока на дисплее ИИ: калькулятор возвращается к обычному счёту. */
    fun leaveAi() {
        aiKeys = false
        vm.hideFromDisplay()
    }

    fun startEditing(text: String, asFollowUp: Boolean = false) {
        returnToAssistant = onDisplay
        vm.hideFromDisplay()
        field = TextFieldValue(text, TextRange(text.length))
        followUp = asFollowUp
        editing = true
    }

    /** Клавиатура убрана без отправки: недописанное дополнение откладывается, ответ возвращается на дисплей. */
    fun stopEditing(saveDraft: Boolean = true) {
        keyboard?.hide()
        if (!editing) return
        editing = false
        if (saveDraft) {
            if (followUp) {
                followUpDraft = field.text
            } else if (!returnToAssistant && (field.text.isBlank() || isArithmetic(field.text))) {
                expr = field.text
                evaluated = false
                taskDraft = ""
            } else {
                taskDraft = field.text
            }
            if (returnToAssistant) vm.showOnDisplay()
        }
        followUp = false
    }

    fun restoreAssistant() {
        stopEditing()
        aiKeys = false
        vm.showOnDisplay()
    }

    /**
     * Нажатие на строку ввода: продолжить выражение, поправить задачу после ошибки или, если на дисплее
     * ответ ИИ, задать к нему дополнительный вопрос. Новая задача вместо дополнения — после «C».
     */
    fun tapDisplay() {
        when {
            !onDisplay -> startEditing(taskDraft.ifEmpty { if (evaluated && !isArithmetic(expr)) "" else expr })
            solving -> Unit
            error != null -> startEditing(vm.retryText.orEmpty(), asFollowUp = vm.retryIsFollowUp && solution != null)
            else -> startEditing(followUpDraft, asFollowUp = true)
        }
    }

    fun input(token: String) {
        leaveAi()
        if (evaluated) {
            expr = if (token.first().isDigit() || token == "(") "" else expr
            evaluated = false
        }
        expr += token
    }

    fun clear() {
        leaveAi()
        if (onDisplay) return
        expr = ""
        evaluated = false
    }

    fun equals() {
        val text = (if (editing) field.text else expr).trim()
        if (!editing) leaveAi()
        if (text.isEmpty()) return
        if (editing && followUp) {
            // Дополнение уходит ИИ целиком, даже если похоже на выражение: «а если 2+3?».
            followUp = false
            followUpDraft = ""
            stopEditing(saveDraft = false)
            vm.askFollowUp(text)
            return
        }
        if (isArithmetic(text)) {
            stopEditing(saveDraft = false)
            expr = try {
                ExpressionParser.format(ExpressionParser.evaluate(text))
            } catch (e: Exception) {
                "Ошибка"
            }
            evaluated = true
        } else if (editing || !evaluated) {
            // «Ошибка» и «Не число» — результаты, а не задачи: их ИИ не отправляем.
            stopEditing(saveDraft = false)
            vm.solveText(text)
            taskDraft = ""
        }
    }

    // Пошло решение или открыта запись из истории — снова цифры: на них шаги решения. Счётчик, а не
    // onDisplay: дисплей мог показывать ответ и до этого, а возврат из настроек клавиши ИИ не сбрасывает.
    val shown by vm.shown.collectAsState()
    var seenShown by rememberSaveable { mutableIntStateOf(shown) }
    LaunchedEffect(shown) {
        if (shown != seenShown) {
            seenShown = shown
            aiKeys = false
            editing = false
            followUp = false
        }
    }
    // Возврат с экрана шага заново создаёт композицию, но не меняет задачу и её черновик.
    LaunchedEffect(solution?.id) {
        if (draftSolutionId != solution?.id) {
            followUpDraft = ""
            draftSolutionId = solution?.id
        }
    }
    // «Изменить условие» и «Дополнить» на экране решения: строка ввода открывается с условием или пустой.
    val editRequest = vm.editRequest
    LaunchedEffect(editRequest) {
        if (editRequest != null) {
            vm.editRequest = null
            aiKeys = false
            startEditing(editRequest.text, asFollowUp = editRequest.followUp && solution != null)
        }
    }
    // Клавиатуру убрали жестом или «назад» — строка снова обычная, на месте снова клавиши.
    val imeVisible = WindowInsets.isImeVisible
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, editing) {
        when {
            !editing -> imeShown = false
            imeVisible -> imeShown = true
            imeShown -> stopEditing()
        }
    }
    BackHandler(enabled = editing) { stopEditing() }
    BackHandler(enabled = aiKeys && !editing) { aiKeys = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.End,
        ) {
            // Помощник скрыт: на экране только калькулятор и неприметный значок в углу.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = "Вернуться к ответу",
                    onLongClick = ::restoreAssistant,
                    onClick = {
                        stopEditing()
                        aiKeys = !aiKeys
                    },
                ),
            ) {
                Icon(
                    if (aiKeys) Icons.Outlined.Calculate else Icons.Outlined.AutoAwesome,
                    contentDescription = if (aiKeys) "К калькулятору" else "ИИ-помощник",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                if (display.unread) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
                            .size(5.dp).background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape,
                            ),
                    )
                }
            }
            // Нажатие в любом месте дисплея — без ряби: калькулятор не должен выдавать, что строка «живая».
            // Пока строка уже с клавиатурой, нажатия мимо неё ничего не сбрасывают.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .swipeUpToRestore(enabled = canSwipeToRestore, onRestore = ::restoreAssistant)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !editing && !(onDisplay && solving),
                        onClick = ::tapDisplay,
                    ),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End,
            ) {
                when {
                    editing -> {
                        val s = solution
                        if (followUp && s != null) {
                            // Дополнение пишется с прежним ответом перед глазами; ✕ — это новая задача.
                            // С ним уйдёт выбранный шаблон или шаблон, по которому решалась задача.
                            val withTemplate = template ?: s.templateId?.let { id -> templates.firstOrNull { it.id == id } }
                            ContextCaption(
                                "Дополнение к ответу" + (withTemplate?.let { " · шаблон «${it.name}»" } ?: ""),
                                s.latestAnswer,
                                onDrop = { followUp = false },
                            )
                        } else if (template != null) {
                            // Задача уйдёт вместе с шаблоном; ✕ — решать без него.
                            ContextCaption("Шаблон", template!!.name, onDrop = vm::disarmTemplate)
                        } else if (format.isSet) {
                            DisplayCaption("Формат: ${format.summary}", maxLines = 1)
                        }
                        InputField(
                            field,
                            placeholder = if (followUp) "Уточните или спросите ещё" else "Напишите задачу",
                        ) {
                            field = it
                        }
                    }

                    !onDisplay -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        horizontalAlignment = Alignment.End,
                    ) {
                        val shown = expr.ifEmpty { "0" }
                        Text(
                            text = shown,
                            fontSize = inputFontSize(shown.length),
                            lineHeight = inputFontSize(shown.length) * 1.15f,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                        )
                        Text(
                            text = when {
                                preview != null -> "= $preview"
                                aiKeys && template != null -> "Шаблон «${template!!.name}»: прикрепите задачу"
                                aiKeys -> "Нажмите на строку, чтобы написать задачу"
                                else -> " "
                            },
                            fontSize = if (preview != null) 26.sp else 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 8.dp),
                        )
                    }

                    solving -> {
                        DisplayCaption(pending ?: "Задача с фото")
                        SolvingDots()
                    }

                    error != null -> Column(horizontalAlignment = Alignment.End) {
                        DisplayCaption(error.orEmpty(), maxLines = 4)
                        Text(
                            "Ошибка",
                            fontSize = 52.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                    }

                    solution != null -> Column(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalAlignment = Alignment.End,
                    ) {
                        val s = solution!!
                        // У ответа на дополнение подпись — сам вопрос со стрелкой продолжения.
                        DisplayCaption(if (s.followUps.isEmpty()) s.problem else "↳ ${s.latestQuestion}")
                        // Длинный ответ не выталкивает клавиатуру, а прокручивается внутри дисплея.
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(answerScroll),
                        ) {
                            val size = answerFontSize(MathMarkup.plain(s.latestAnswer).length)
                            MathText(
                                s.latestAnswer,
                                fontSize = size,
                                textAlign = TextAlign.End,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        val numberColor = MaterialTheme.colorScheme.surfaceVariant
        val operatorColor = MaterialTheme.colorScheme.primaryContainer
        val accentColor = MaterialTheme.colorScheme.primary

        // В режиме ИИ клавиатура та же, только цифры 1–8 становятся действиями: 1–4 — откуда взять
        // задачу, 5–6 — история и настройки, 7–8 — шаблон и формат. 9, 0 и операции считают как обычно.
        val aiActions: Map<Char, AiAction> = mapOf(
            '1' to AiAction("Написать", Icons.Outlined.Edit, onClick = ::tapDisplay),
            '2' to AiAction("Камера", Icons.Outlined.PhotoCamera, onClick = onOpenCamera),
            '3' to AiAction("Галерея", Icons.Outlined.PhotoLibrary) {
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            '4' to AiAction("Документ", Icons.Outlined.Description) {
                documentLauncher.launch(DocumentReader.mimeTypes)
            },
            '5' to AiAction("История", Icons.Outlined.History, onClick = onOpenHistory),
            '6' to AiAction("Настройки", Icons.Outlined.Settings, onClick = onOpenSettings),
            // Выбранный шаблон и заданный формат подсвечены: следующая задача пойдёт с ними.
            '7' to AiAction("Шаблоны", Icons.Outlined.ContentCopy, highlighted = template != null, onClick = onOpenTemplates),
            '8' to AiAction("Формат", Icons.Outlined.Tune, highlighted = format.isSet, onClick = onOpenFormat),
        )

        /**
         * Цифра; удержание открывает шаг решения с этим номером, точка в углу подсказывает, что он есть.
         * В режиме ИИ на месте цифры — действие.
         */
        @Composable
        fun RowScope.DigitKey(digit: Char) {
            val action = aiActions[digit]?.takeIf { aiKeys }
            if (action != null) {
                CalcKey(if (action.highlighted) operatorColor else numberColor, onClick = action.onClick) {
                    // Иконка и подпись не выше цифры — клавиши не меняют размер при переключении.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(action.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            action.label,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                return
            }
            val step = stepIndex(digit).takeIf { answerVisible && it < steps.size }
            CalcKey(
                digit.toString(),
                numberColor,
                marked = step != null,
                onLongClick = step?.let { { onOpenStep(it) } },
            ) { input(digit.toString()) }
        }

        when {
            // Над системной клавиатурой — только «назад» и «=»: цифры и так на ней.
            editing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalcKey(numberColor, onClick = { stopEditing() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Убрать клавиатуру")
                }
                CalcKey(accentColor, weight = 3f, enabled = field.text.isNotBlank(), onClick = ::equals) {
                    Text("=", fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalcKey("C", numberColor, MaterialTheme.colorScheme.error) { clear() }
                    CalcKey("(", numberColor) { input("(") }
                    CalcKey(")", numberColor) { input(")") }
                    CalcKey("÷", operatorColor) { input("÷") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitKey('7')
                    DigitKey('8')
                    DigitKey('9')
                    CalcKey("×", operatorColor) { input("×") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitKey('4')
                    DigitKey('5')
                    DigitKey('6')
                    CalcKey("−", operatorColor) { input("−") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitKey('1')
                    DigitKey('2')
                    DigitKey('3')
                    CalcKey("+", operatorColor) { input("+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalcKey("%", numberColor) { input("%") }
                    DigitKey('0')
                    CalcKey(".", numberColor) { input(".") }
                    CalcKey("⌫", numberColor, onLongClick = { clear() }) {
                        leaveAi()
                        if (expr == "Ошибка") expr = ""
                        if (expr.isNotEmpty()) expr = expr.dropLast(1)
                        evaluated = false
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Удержание «=» — всё решение целиком.
                    CalcKey(
                        "=",
                        accentColor,
                        MaterialTheme.colorScheme.onPrimary,
                        onLongClick = if (answerVisible) onOpenSolution else null,
                    ) { equals() }
                }
            }
        }
    }
}

/** Строка ввода с системной клавиатурой: текст справа и того же размера, что выражение на дисплее. */
/**
 * Над строкой ввода — с чем уйдёт задача: прежний ответ (дополнение) или шаблон. ✕ — отправить
 * без этого, просто новой задачей.
 */
@Composable
private fun ContextCaption(title: String, text: String, onDrop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.End) {
            Text(
                title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            DisplayCaption(text, maxLines = 2)
        }
        IconButton(onClick = onDrop) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Отправить без этого — новой задачей",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InputField(value: TextFieldValue, placeholder: String, onValueChange: (TextFieldValue) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val size = inputFontSize(value.text.length)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            fontSize = size,
            lineHeight = size * 1.15f,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterEnd) {
                if (value.text.isEmpty()) {
                    Text(
                        placeholder,
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .focusRequester(focus),
    )
}

@Composable
private fun DisplayCaption(text: String, maxLines: Int = 3) {
    MathText(
        text,
        fontSize = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = maxLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Точки бегут, как на ожидающем дисплее; недостающие прозрачные — текст не прыгает. */
@Composable
private fun SolvingDots() {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dots = dots % 3 + 1
        }
    }
    val text = buildAnnotatedString {
        append("•".repeat(dots))
        withStyle(SpanStyle(color = Color.Transparent)) { append("•".repeat(3 - dots)) }
    }
    Text(text, fontSize = 52.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
}

/** Ответы ИИ длиннее чисел, поэтому к шкале калькулятора добавлены меньшие шаги. */
internal fun answerFontSize(length: Int): TextUnit = when {
    length > 80 -> 20.sp
    length > 40 -> 24.sp
    else -> displayFontSize(length)
}

@Composable
internal fun RowScope.CalcKey(
    label: String,
    container: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    weight: Float = 1f,
    marked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    CalcKey(container, weight = weight, marked = marked, onLongClick = onLongClick, onClick = onClick) {
        Text(label, fontSize = 26.sp, color = contentColor)
    }
}

/** Клавиша калькулятора с произвольным содержимым — ей же оформлены кнопки на экранах ИИ. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowScope.CalcKey(
    container: Color,
    modifier: Modifier = Modifier,
    weight: Float = 1f,
    enabled: Boolean = true,
    marked: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .weight(weight)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            content()
        }
        // Неприметная метка: у клавиши есть действие на удержание.
        if (marked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 14.dp)
                    .size(5.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape),
            )
        }
    }
}
