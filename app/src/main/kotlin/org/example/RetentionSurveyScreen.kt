package org.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetentionSurveyScreen(
    initialIsAutoMode: Boolean,
    serverUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val surveyManager = remember { RetentionSurveyManager(context) }

    // Start timing (logged silently, NEVER displayed in UI)
    val surveyStartTime = remember { System.currentTimeMillis() }
    val questionTimings = remember { mutableStateMapOf<Int, QuestionTiming>() }

    // Metadata state
    var participantId by remember { mutableStateOf("P-01") }
    var selectedCondition by remember {
        mutableStateOf(if (initialIsAutoMode) Condition.EXPERIMENTAL else Condition.CONTROL)
    }
    val dateTimeStr = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    }

    // NASA-TLX state (0-100 in steps of 5, unanswered by default)
    val nasaTlxScores = remember {
        mutableStateMapOf<String, Int?>().apply {
            SurveyDefinitions.nasaTlxDimensions.forEach { dim ->
                put(dim.id, null)
            }
        }
    }

    // Retention Quiz answers: map of Question ID -> selected key ("A", "B", "C", "D", "E")
    val quizAnswers = remember { mutableStateMapOf<Int, String>() }

    // Submission states
    var isSubmitting by remember { mutableStateOf(false) }
    var scoringResult by remember { mutableStateOf<SurveyScoringResult?>(null) }
    var showResultsDialog by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun recordQuestionAnswer(qId: Int, key: String) {
        val now = System.currentTimeMillis()
        val timing = questionTimings.getOrPut(qId) { QuestionTiming() }
        if (timing.firstAnswerTimeMs == 0L) {
            timing.firstAnswerTimeMs = now
        }
        timing.lastAnswerTimeMs = now
        timing.timeSpentMs += (now - timing.lastAnswerTimeMs).coerceAtLeast(0L)
        quizAnswers[qId] = key
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Post-Tour Retention Survey",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "CAMCS Multi-Modal AR Experiment",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss Survey")
                    }
                },
                actions = {
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss", fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Participant & Experiment Metadata
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "1. Participant & Experiment Metadata",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = participantId,
                        onValueChange = { participantId = it },
                        label = { Text("Participant ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text(
                        text = "Experimental Condition:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedCondition == Condition.CONTROL,
                            onClick = { selectedCondition = Condition.CONTROL }
                        )
                        Text(
                            text = Condition.CONTROL.label,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { selectedCondition = Condition.CONTROL }
                                .padding(end = 12.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedCondition == Condition.EXPERIMENTAL,
                            onClick = { selectedCondition = Condition.EXPERIMENTAL }
                        )
                        Text(
                            text = Condition.EXPERIMENTAL.label,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { selectedCondition = Condition.EXPERIMENTAL }
                        )
                    }

                    Text(
                        text = "Date & Time: $dateTimeStr",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )
                    Text(
                        text = "Order of Phases: Amphitheatre -> Lake Memorial -> Tosun Terzioğlu Garden",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            // Section 2: NASA-TLX
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "2. Subjective Cognitive Workload Assessment (NASA-TLX)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4A148C)
                    )
                    Text(
                        text = "Please rate your overall experience during the campus tour across each of the following 6 dimensions on a 21-point scale (0 to 100, in steps of 5).",
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.DarkGray
                    )

                    SurveyDefinitions.nasaTlxDimensions.forEach { dimension ->
                        NasaTlxDimensionCard(
                            dimension = dimension,
                            currentScore = nasaTlxScores[dimension.id],
                            onScoreChange = { newScore ->
                                nasaTlxScores[dimension.id] = newScore
                            }
                        )
                    }
                }
            }

            // Section 3: Information Retention Quiz (20 Questions)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "3. Information Retention Quiz (20 Questions)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20)
                    )
                    Text(
                        text = "Instructions: Please answer each question based on the audio commentary and screen content presented during your campus tour. If you genuinely do not recall the answer, please select 'E) I don't remember / Not sure' rather than guessing.",
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.DarkGray
                    )

                    // Group by POI
                    val groupedQuestions = SurveyDefinitions.retentionQuestions.groupBy { it.poi }

                    groupedQuestions.forEach { (poiName, questions) ->
                        val phaseTitle = questions.firstOrNull()?.phase ?: ""
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFC8E6C9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "$poiName ($phaseTitle)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF1B5E20)
                                )
                            }
                        }

                        questions.forEach { q ->
                            RetentionQuestionCard(
                                question = q,
                                selectedKey = quizAnswers[q.id],
                                onOptionSelected = { key ->
                                    recordQuestionAnswer(q.id, key)
                                }
                            )
                        }
                    }
                }
            }

            // Validation error banner if any
            if (validationError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = validationError!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dismiss")
                }

                Button(
                    onClick = {
                        val unansweredTlx = SurveyDefinitions.nasaTlxDimensions.count { nasaTlxScores[it.id] == null }
                        if (unansweredTlx > 0) {
                            validationError = "Please rate all 6 NASA-TLX dimensions before submitting ($unansweredTlx remaining)."
                            return@Button
                        }

                        val unansweredCount = SurveyDefinitions.retentionQuestions.count { !quizAnswers.containsKey(it.id) }
                        if (unansweredCount > 0) {
                            validationError = "Please answer all 20 questions before submitting ($unansweredCount remaining). Select 'E' if you do not recall."
                            return@Button
                        }
                        validationError = null
                        isSubmitting = true

                        val endTime = System.currentTimeMillis()
                        val metadata = SurveyMetadata(
                            participantId = participantId.ifBlank { "P-Unknown" },
                            condition = selectedCondition,
                            dateTime = dateTimeStr
                        )

                        val completeTlxScores = nasaTlxScores.mapValues { it.value ?: 0 }

                        val result = SurveyScorer.calculateScores(
                            answers = quizAnswers,
                            nasaTlxScores = completeTlxScores,
                            startTime = surveyStartTime,
                            endTime = endTime,
                            questionTimings = questionTimings
                        )

                        coroutineScope.launch {
                            surveyManager.saveSurveyResult(
                                result = result,
                                metadata = metadata,
                                answers = quizAnswers,
                                serverUrl = serverUrl
                            )
                            scoringResult = result
                            isSubmitting = false
                            showResultsDialog = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Submit Survey")
                }
            }
        }
    }

    // Results / Confirmation Dialog (NOTE: NO TIME DISPLAY, adhering to requirements)
    if (showResultsDialog && scoringResult != null) {
        val r = scoringResult!!
        AlertDialog(
            onDismissRequest = { /* Force explicit close */ },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Survey Submitted", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "The survey response and calculated metrics have been successfully saved to device storage and Downloads.",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text("Participant: ${participantId.ifBlank { "P-01" }} (${selectedCondition.label})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Attention Check: ${r.attentionStatus}", fontWeight = FontWeight.Bold, color = if (r.attentionScore == 2) Color(0xFF2E7D32) else Color(0xFFC62828), fontSize = 13.sp)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text("Retention Scores Summary:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("• Overall Retention: ${r.overallRetentionScore} / 18", fontSize = 13.sp)
                    Text("• Core Retention: ${r.totalCoreScore} / 12 (Amphitheatre: ${r.amphCoreScore}/4, Lake: ${r.lakeCoreScore}/4, Garden: ${r.gardenCoreScore}/4)", fontSize = 12.sp)
                    Text("• Detailed Retention: ${r.totalDetailedScore} / 6 (Amphitheatre: ${r.amphDetailedScore}/2, Lake: ${r.lakeDetailedScore}/2, Garden: ${r.gardenDetailedScore}/2)", fontSize = 12.sp)
                    Text(String.format(Locale.US, "• Omission Rate (Opt E): %.1f%% (%d/18)", r.omissionRatePercent, r.omissionCount), fontSize = 12.sp)
                    Text(String.format(Locale.US, "• Error Rate (Guesses): %.1f%% (%d/18)", r.errorRatePercent, r.errorCount), fontSize = 12.sp)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(String.format(Locale.US, "Subjective Workload (NASA-TLX Mean): %.1f / 100", r.meanNasaTlxScore), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Mental: ${r.nasaTlxScores["mental_demand"]} | Physical: ${r.nasaTlxScores["physical_demand"]} | Temporal: ${r.nasaTlxScores["temporal_demand"]}", fontSize = 11.sp, color = Color.DarkGray)
                    Text("Performance: ${r.nasaTlxScores["performance"]} | Effort: ${r.nasaTlxScores["effort"]} | Frustration: ${r.nasaTlxScores["frustration"]}", fontSize = 11.sp, color = Color.DarkGray)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResultsDialog = false
                        onDismiss()
                    }
                ) {
                    Text("Close Survey")
                }
            }
        )
    }
}

@Composable
fun NasaTlxDimensionCard(
    dimension: NasaTlxDimension,
    currentScore: Int?,
    onScoreChange: (Int?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dimension.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (currentScore == null) {
                    Surface(
                        color = Color(0xFFF5F5F5),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0))
                    ) {
                        Text(
                            text = "Unanswered",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF757575),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Score: $currentScore",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "Clear",
                            fontSize = 11.sp,
                            color = Color(0xFFC62828),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { onScoreChange(null) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = dimension.prompt,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                color = Color.DarkGray
            )

            if (currentScore == null) {
                // Interactive scale without any pre-selected thumb
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF0F0F0))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                val snapped = (fraction * 100f / 5f).roundToInt() * 5
                                onScoreChange(snapped.coerceIn(0, 100))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val trackY = size.height / 2f
                        val startX = 16.dp.toPx()
                        val availableW = size.width - 32.dp.toPx()
                        // Track baseline
                        drawLine(
                            color = Color(0xFFBDBDBD),
                            start = Offset(startX, trackY),
                            end = Offset(startX + availableW, trackY),
                            strokeWidth = 3.dp.toPx()
                        )
                        // Ticks across 21 increments
                        for (i in 0..20) {
                            val x = startX + (i / 20f) * availableW
                            val isMajor = (i % 5 == 0) // 0, 25, 50, 75, 100
                            val tickH = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
                            val tickColor = if (isMajor) Color(0xFF616161) else Color(0xFF9E9E9E)
                            drawLine(
                                color = tickColor,
                                start = Offset(x, trackY - tickH / 2),
                                end = Offset(x, trackY + tickH / 2),
                                strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
                            )
                        }
                    }

                    Text(
                        text = "Tap along scale to rate (0 to 100)",
                        fontSize = 11.sp,
                        color = Color(0xFF616161),
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Slider(
                    value = currentScore.toFloat(),
                    onValueChange = { floatVal ->
                        val snapped = (floatVal / 5f).roundToInt() * 5
                        onScoreChange(snapped.coerceIn(0, 100))
                    },
                    valueRange = 0f..100f,
                    steps = 19, // 21 distinct values: 0, 5, 10, ... 100
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(dimension.lowLabel, fontSize = 11.sp, color = Color.Gray)
                Text(dimension.highLabel, fontSize = 11.sp, color = Color.Gray)
            }

            // Quick preset chips for rapid, clear marking
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf(0 to "0", 25 to "25", 50 to "50", 75 to "75", 100 to "100").forEach { (scoreVal, label) ->
                    val isSelected = currentScore == scoreVal
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF5F5F5),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFE0E0E0)
                        ),
                        modifier = Modifier.clickable { onScoreChange(scoreVal) }
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.DarkGray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RetentionQuestionCard(
    question: RetentionQuestion,
    selectedKey: String?,
    onOptionSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Q${question.id}: ${question.poi}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = if (selectedKey != null) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (selectedKey != null) "Answered: $selectedKey" else "Unanswered",
                        fontSize = 10.sp,
                        color = if (selectedKey != null) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = question.question,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Black
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                question.options.forEach { option ->
                    val isSelected = selectedKey == option.key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                            .selectable(
                                selected = isSelected,
                                onClick = { onOptionSelected(option.key) }
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onOptionSelected(option.key) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${option.key}) ${option.text}",
                            fontSize = 12.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else Color.DarkGray,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
