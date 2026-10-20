package org.example

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Condition(val label: String) {
    CONTROL("Control Group (Static Mode)"),
    EXPERIMENTAL("Experimental Group (Auto Mode)")
}

data class SurveyMetadata(
    val participantId: String = "P-01",
    val condition: Condition = Condition.EXPERIMENTAL,
    val dateTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
    val phaseOrder: String = "Phase 1: Amphitheatre -> Phase 2: Lake Memorial -> Phase 3 & 4: Tosun Terzioğlu Garden"
)

data class NasaTlxDimension(
    val id: String,
    val title: String,
    val prompt: String,
    val lowLabel: String,
    val highLabel: String,
    val defaultScore: Int = 50
)

data class QuizOption(
    val key: String, // "A", "B", "C", "D", "E"
    val text: String
)

data class RetentionQuestion(
    val id: Int,
    val poi: String,
    val phase: String,
    val tier: String, // "Core", "Detailed", "Attention Check"
    val question: String,
    val options: List<QuizOption>,
    val correctKey: String,
    val correctSummary: String,
    val isAttentionCheck: Boolean = false
)

data class QuestionTiming(
    var firstAnswerTimeMs: Long = 0L,
    var lastAnswerTimeMs: Long = 0L,
    var timeSpentMs: Long = 0L
)

object SurveyDefinitions {
    val nasaTlxDimensions = listOf(
        NasaTlxDimension(
            id = "mental_demand",
            title = "1. Mental Demand",
            prompt = "How much mental and perceptual activity was required (e.g., thinking, deciding, calculating, remembering, looking, searching)?",
            lowLabel = "Low (0)",
            highLabel = "High (100)"
        ),
        NasaTlxDimension(
            id = "physical_demand",
            title = "2. Physical Demand",
            prompt = "How much physical activity was required (e.g., walking, climbing stairs, coordinating movement)?",
            lowLabel = "Low (0)",
            highLabel = "High (100)"
        ),
        NasaTlxDimension(
            id = "temporal_demand",
            title = "3. Temporal Demand",
            prompt = "How much time pressure did you feel due to the pace or rate at which the tasks or information occurred?",
            lowLabel = "Low (0)",
            highLabel = "High (100)"
        ),
        NasaTlxDimension(
            id = "performance",
            title = "4. Performance",
            prompt = "How successful do you think you were in accomplishing the goals of the tour (listening, understanding, navigating)?",
            lowLabel = "Good (0)",
            highLabel = "Poor (100)"
        ),
        NasaTlxDimension(
            id = "effort",
            title = "5. Effort",
            prompt = "How hard did you have to work (mentally and physically) to accomplish your level of performance?",
            lowLabel = "Low (0)",
            highLabel = "High (100)"
        ),
        NasaTlxDimension(
            id = "frustration",
            title = "6. Frustration",
            prompt = "How irritated, stressed, annoyed, or overwhelmed versus content, relaxed, and complacent did you feel during the tour?",
            lowLabel = "Low (0)",
            highLabel = "High (100)"
        )
    )

    val retentionQuestions = listOf(
        // Part A: Point of Interest 1 – Amphitheatre (Phase 1: Low Cognitive Load)
        RetentionQuestion(
            id = 1,
            poi = "Amphitheatre",
            phase = "Phase 1: Low Cognitive Load",
            tier = "Core",
            question = "In what year was the Outdoor Amphitheatre constructed?",
            options = listOf(
                QuizOption("A", "1997"),
                QuizOption("B", "2000"),
                QuizOption("C", "2003"),
                QuizOption("D", "2008"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "C",
            correctSummary = "Built in 2003"
        ),
        RetentionQuestion(
            id = 2,
            poi = "Amphitheatre",
            phase = "Phase 1: Low Cognitive Load",
            tier = "Core",
            question = "Approximately how large is the total project area of the Amphitheatre?",
            options = listOf(
                QuizOption("A", "3,200 square meters"),
                QuizOption("B", "5,300 square meters"),
                QuizOption("C", "7,800 square meters"),
                QuizOption("D", "10,500 square meters"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "5,300 square meters"
        ),
        RetentionQuestion(
            id = 3,
            poi = "Amphitheatre",
            phase = "Phase 1: Low Cognitive Load",
            tier = "Core",
            question = "What is the spectator seating capacity of the Amphitheatre?",
            options = listOf(
                QuizOption("A", "2,500 seated spectators"),
                QuizOption("B", "4,000 seated spectators"),
                QuizOption("C", "6,500 seated spectators"),
                QuizOption("D", "8,000 seated spectators"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "4,000 seated spectators"
        ),
        RetentionQuestion(
            id = 4,
            poi = "Amphitheatre",
            phase = "Phase 1: Low Cognitive Load",
            tier = "Core",
            question = "What university milestone tradition takes place at the Amphitheatre every summer?",
            options = listOf(
                QuizOption("A", "All graduating students celebrate commencement together in a unified institutional ceremony"),
                QuizOption("B", "Departmental valedictorians compete in an annual academic debate"),
                QuizOption("C", "Incoming first-year students sign a physical matriculation registry"),
                QuizOption("D", "Each faculty holds an independent graduation ceremony on separate days"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "A",
            correctSummary = "All graduating students celebrate commencement together in a unified ceremony"
        ),
        RetentionQuestion(
            id = 5,
            poi = "Amphitheatre",
            phase = "Phase 1: Low Cognitive Load",
            tier = "Detailed",
            question = "What historical architectural tradition deeply inspired the design of the Amphitheatre?",
            options = listOf(
                QuizOption("A", "Ancient open-air theatrical traditions of Anatolia"),
                QuizOption("B", "Classical Roman gladiatorial amphitheaters"),
                QuizOption("C", "Traditional Ottoman courtyard architecture"),
                QuizOption("D", "Modernist Scandinavian amphitheater principles"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "A",
            correctSummary = "Ancient open-air theatrical traditions of Anatolia"
        ),
        RetentionQuestion(
            id = 6,
            poi = "Amphitheatre",
            phase = "Phase 1: Low Cognitive Load",
            tier = "Detailed",
            question = "Where is the Amphitheatre located within the campus master plan?",
            options = listOf(
                QuizOption("A", "On the eastern side of the campus, next to the Performing Arts Center (SGM) and University Center"),
                QuizOption("B", "In the southwest corner, right next to the football stadium and sports center"),
                QuizOption("C", "Directly facing the campus lake and boat dock"),
                QuizOption("D", "In the central green quadrangle beside the Rectorate"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "A",
            correctSummary = "Eastern side of campus, next to SGM & University Center"
        ),

        // Part B: Point of Interest 2 – Lake Memorial (Phase 2: Medium Cognitive Load)
        RetentionQuestion(
            id = 7,
            poi = "Lake Memorial",
            phase = "Phase 2: Medium Cognitive Load",
            tier = "Core",
            question = "What was the site of the campus lake prior to the university's construction in 1997?",
            options = listOf(
                QuizOption("A", "A natural marshland fed by mountain streams"),
                QuizOption("B", "An empty and degraded industrial gravel pit"),
                QuizOption("C", "An agricultural olive and hazelnut orchard"),
                QuizOption("D", "A disused municipal water reservoir"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Degraded industrial gravel pit prior to 1997"
        ),
        RetentionQuestion(
            id = 8,
            poi = "Lake Memorial",
            phase = "Phase 2: Medium Cognitive Load",
            tier = "Core",
            question = "How is the artificial lake systematically filled and kept sustainable?",
            options = listOf(
                QuizOption("A", "Deep groundwater artesian extraction wells"),
                QuizOption("B", "Rainwater collected from building roofs and treated wastewater"),
                QuizOption("C", "Direct municipal water pipeline connection from Istanbul"),
                QuizOption("D", "Surface runoff diverted from neighboring residential ponds"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Rainwater from building roofs & treated wastewater"
        ),
        RetentionQuestion(
            id = 9,
            poi = "Lake Memorial",
            phase = "Phase 2: Medium Cognitive Load",
            tier = "Core",
            question = "How did the lake's fish population first originate?",
            options = listOf(
                QuizOption("A", "They were artificially stocked by the university's biology department"),
                QuizOption("B", "Fish eggs were spontaneously transported into the basin by local seagulls"),
                QuizOption("C", "They migrated naturally via underground culvert pipelines"),
                QuizOption("D", "They were introduced by student club aquarium conservation drives"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Seagulls transporting fish eggs into the basin"
        ),
        RetentionQuestion(
            id = 10,
            poi = "Lake Memorial",
            phase = "Phase 2: Medium Cognitive Load",
            tier = "Core",
            question = "What commemorative tradition takes place at the Graduation Memorial during commencement?",
            options = listOf(
                QuizOption("A", "A commemorative Class Brick engraved with the graduation year is embedded into the memorial pathway"),
                QuizOption("B", "Every individual student tosses a bronze commemorative medallion into the lake"),
                QuizOption("C", "Each graduate's name is individually engraved onto a glass wall"),
                QuizOption("D", "Graduating classes plant a commemorative cypress along the shoreline"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "A",
            correctSummary = "Commemorative Class Brick embedded into memorial pathway"
        ),
        RetentionQuestion(
            id = 11,
            poi = "Lake Memorial",
            phase = "Phase 2: Medium Cognitive Load",
            tier = "Attention Check (Trap 1)",
            question = "Water quality in the artificial lake is regulated through several ecological mechanisms. Because it is important to verify that all statements are read attentively, please select water filtration pumps as your answer for this item, which assists researchers in recording which infrastructure elements were observed during the walking route.",
            options = listOf(
                QuizOption("A", "Soil moisture variance"),
                QuizOption("B", "Solar radiation levels"),
                QuizOption("C", "Water filtration pumps"),
                QuizOption("D", "Aquatic vegetation index"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "C",
            correctSummary = "Instructed choice: Water filtration pumps",
            isAttentionCheck = true
        ),
        RetentionQuestion(
            id = 12,
            poi = "Lake Memorial",
            phase = "Phase 2: Medium Cognitive Load",
            tier = "Detailed",
            question = "What geological/engineering lining was applied to make the lake basin watertight?",
            options = listOf(
                QuizOption("A", "Impermeable clay layered with gravel"),
                QuizOption("B", "Poured reinforced waterproof concrete"),
                QuizOption("C", "Multi-layer polyethylene geomembrane sheets"),
                QuizOption("D", "Sprayed bituminous asphalt emulsion"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "A",
            correctSummary = "Impermeable clay layered with gravel"
        ),
        RetentionQuestion(
            id = 13,
            poi = "Lake Memorial",
            phase = "Phase 2: Medium Cognitive Load",
            tier = "Detailed",
            question = "Symbolically, what do the flowing lake water and the solid memorial bricks represent?",
            options = listOf(
                QuizOption("A", "Water represents the fluid passage of time/new students, while bricks represent permanent individual achievement"),
                QuizOption("B", "Water represents scientific inquiry, while bricks represent established cultural heritage"),
                QuizOption("C", "Water represents academic freedom, while bricks represent institutional discipline"),
                QuizOption("D", "Water represents financial sustainability, while bricks represent infrastructure expansion"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "A",
            correctSummary = "Water = passage of time/students; Bricks = permanent achievement"
        ),

        // Part C: Point of Interest 3 – Tosun Terzioğlu Garden (Phases 3 & 4: High Cognitive Load / Overload)
        RetentionQuestion(
            id = 14,
            poi = "Tosun Terzioğlu Garden",
            phase = "Phases 3 & 4: High Cognitive Load / Overload",
            tier = "Core",
            question = "Approximately how many individual plants does the Sabancı University campus host?",
            options = listOf(
                QuizOption("A", "Around 25,000 plants"),
                QuizOption("B", "Around 75,000 plants"),
                QuizOption("C", "Over 170,000 plants"),
                QuizOption("D", "Over 500,000 plants"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "C",
            correctSummary = "Over 170,000 plants"
        ),
        RetentionQuestion(
            id = 15,
            poi = "Tosun Terzioğlu Garden",
            phase = "Phases 3 & 4: High Cognitive Load / Overload",
            tier = "Core",
            question = "Who is the botanical garden dedicated to, and what was his personal connection to nature?",
            options = listOf(
                QuizOption("A", "Sakıp Sabancı, who financed the original botanical curation"),
                QuizOption("B", "Founding rector Tosun Terzioğlu, who memorized the Latin names of campus trees"),
                QuizOption("C", "Haluk Pamir, the campus landscape architect"),
                QuizOption("D", "Toshitaka Hagiwara, a renowned Japanese master botanist"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Founding rector Tosun Terzioğlu (memorized Latin tree names)"
        ),
        RetentionQuestion(
            id = 16,
            poi = "Tosun Terzioğlu Garden",
            phase = "Phases 3 & 4: High Cognitive Load / Overload",
            tier = "Core",
            question = "Which organization gifted the Japanese Sakura (Cherry Blossom) saplings between 2007 and 2009?",
            options = listOf(
                QuizOption("A", "Toyota Corporation"),
                QuizOption("B", "Komatsu"),
                QuizOption("C", "Mitsubishi Heavy Industries"),
                QuizOption("D", "The Embassy of Japan Cultural Fund"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Komatsu (donated saplings 2007–2009)"
        ),
        RetentionQuestion(
            id = 17,
            poi = "Tosun Terzioğlu Garden",
            phase = "Phases 3 & 4: High Cognitive Load / Overload",
            tier = "Core",
            question = "Approximately how many Sakura trees and distinct species are present across the campus?",
            options = listOf(
                QuizOption("A", "Nearly 500 trees across 3 species"),
                QuizOption("B", "Nearly 2,000 trees across 7 species"),
                QuizOption("C", "Over 5,000 trees across 12 species"),
                QuizOption("D", "Exactly 10,000 trees of a single species"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Nearly 2,000 trees across 7 species"
        ),
        RetentionQuestion(
            id = 18,
            poi = "Tosun Terzioğlu Garden",
            phase = "Phases 3 & 4: High Cognitive Load / Overload",
            tier = "Attention Check (Trap 2)",
            question = "The botanical curation around the garden includes various evergreen and deciduous plantings across the university grounds. To ensure that participants are reading each survey question in full, please select black sea rhododendrons as your response to this item before continuing to review the remaining historical and botanical landmarks along the path.",
            options = listOf(
                QuizOption("A", "Mediterranean olive trees"),
                QuizOption("B", "Black Sea rhododendrons"),
                QuizOption("C", "Japanese Sakura blossoms"),
                QuizOption("D", "Oriental plane trees"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Instructed choice: Black Sea rhododendrons",
            isAttentionCheck = true
        ),
        RetentionQuestion(
            id = 19,
            poi = "Tosun Terzioğlu Garden",
            phase = "Phases 3 & 4: High Cognitive Load / Overload",
            tier = "Detailed",
            question = "Where was the 3.3-ton, 10,000-year-old Sakura Stone Monument sourced and imported from?",
            options = listOf(
                QuizOption("A", "The Gujyo city area in central Japan"),
                QuizOption("B", "The Mount Fuji volcanic foothills"),
                QuizOption("C", "The Yoshino mountain range in Nara"),
                QuizOption("D", "The coastal riverbeds of Hokkaido"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "A",
            correctSummary = "Gujyo city area in central Japan"
        ),
        RetentionQuestion(
            id = 20,
            poi = "Tosun Terzioğlu Garden",
            phase = "Phases 3 & 4: High Cognitive Load / Overload",
            tier = "Detailed",
            question = "Whose calligraphy was masterfully engraved onto the stone monument displaying the kanji for \"Sakura\"?",
            options = listOf(
                QuizOption("A", "Akira Kurosawa"),
                QuizOption("B", "Toshitaka Hagiwara"),
                QuizOption("C", "Kenzo Tange"),
                QuizOption("D", "Tosun Terzioğlu"),
                QuizOption("E", "I don't remember / Not sure")
            ),
            correctKey = "B",
            correctSummary = "Toshitaka Hagiwara calligraphy"
        )
    )
}

data class SurveyScoringResult(
    val amphCoreScore: Int, // /4 (Q1-Q4)
    val lakeCoreScore: Int, // /4 (Q7-Q10)
    val gardenCoreScore: Int, // /4 (Q14-Q17)
    val totalCoreScore: Int, // /12

    val amphDetailedScore: Int, // /2 (Q5-Q6)
    val lakeDetailedScore: Int, // /2 (Q12-Q13)
    val gardenDetailedScore: Int, // /2 (Q19-Q20)
    val totalDetailedScore: Int, // /6

    val overallRetentionScore: Int, // /18
    val omissionCount: Int,
    val omissionRatePercent: Double,
    val errorCount: Int,
    val errorRatePercent: Double,

    val attentionScore: Int, // /2
    val attentionStatus: String, // "Pass (2/2)", "Borderline (1/2)", "Fail (0/2)"

    val nasaTlxScores: Map<String, Int>,
    val meanNasaTlxScore: Double,

    // Timings (logged only, NEVER shown in UI)
    val surveyStartTime: Long,
    val surveyEndTime: Long,
    val totalDurationMs: Long,
    val totalDurationSeconds: Double,
    val questionTimings: Map<Int, QuestionTiming>
) {
    fun toJsonObject(metadata: SurveyMetadata, answers: Map<Int, String>): JSONObject {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        val root = JSONObject()

        // Metadata
        val metaObj = JSONObject().apply {
            put("participant_id", metadata.participantId)
            put("condition", metadata.condition.label)
            put("date_time", metadata.dateTime)
            put("phase_order", metadata.phaseOrder)
        }
        root.put("metadata", metaObj)

        // Timing metrics (logged in JSON, hidden from UI)
        val timingObj = JSONObject().apply {
            put("survey_start_timestamp", dateFormat.format(Date(surveyStartTime)))
            put("survey_end_timestamp", dateFormat.format(Date(surveyEndTime)))
            put("total_duration_ms", totalDurationMs)
            put("total_duration_seconds", totalDurationSeconds)

            val qTimingsArr = JSONArray()
            questionTimings.forEach { (qId, timing) ->
                val qT = JSONObject().apply {
                    put("question_id", qId)
                    put("first_answered_ms_after_start", if (timing.firstAnswerTimeMs > 0) timing.firstAnswerTimeMs - surveyStartTime else 0)
                    put("last_answered_ms_after_start", if (timing.lastAnswerTimeMs > 0) timing.lastAnswerTimeMs - surveyStartTime else 0)
                    put("time_spent_ms", timing.timeSpentMs)
                }
                qTimingsArr.put(qT)
            }
            put("per_question_timings", qTimingsArr)
        }
        root.put("timings", timingObj)

        // NASA-TLX
        val tlxObj = JSONObject().apply {
            nasaTlxScores.forEach { (k, v) -> put(k, v) }
            put("mean_workload_score", meanNasaTlxScore)
        }
        root.put("nasa_tlx", tlxObj)

        // Scores
        val scoresObj = JSONObject().apply {
            put("overall_retention_score", overallRetentionScore)
            put("max_retention_score", 18)
            put("core_retention_score", totalCoreScore)
            put("max_core_score", 12)
            put("detailed_retention_score", totalDetailedScore)
            put("max_detailed_score", 6)

            put("amphitheatre_core", amphCoreScore)
            put("lake_memorial_core", lakeCoreScore)
            put("tosun_terzioglu_garden_core", gardenCoreScore)

            put("amphitheatre_detailed", amphDetailedScore)
            put("lake_memorial_detailed", lakeDetailedScore)
            put("tosun_terzioglu_garden_detailed", gardenDetailedScore)

            put("omission_count", omissionCount)
            put("omission_rate_percent", omissionRatePercent)
            put("error_count", errorCount)
            put("error_rate_percent", errorRatePercent)

            put("attention_check_score", attentionScore)
            put("max_attention_score", 2)
            put("attention_status", attentionStatus)
        }
        root.put("scores", scoresObj)

        // Individual responses
        val responsesArr = JSONArray()
        SurveyDefinitions.retentionQuestions.forEach { q ->
            val userAns = answers[q.id] ?: ""
            val isCorrect = userAns.equals(q.correctKey, ignoreCase = true)
            val isOmission = userAns.equals("E", ignoreCase = true)
            val isError = !isCorrect && !isOmission && userAns.isNotBlank()

            val qObj = JSONObject().apply {
                put("question_id", q.id)
                put("poi", q.poi)
                put("phase", q.phase)
                put("tier", q.tier)
                put("question", q.question)
                put("user_answer", userAns)
                put("correct_answer", q.correctKey)
                put("correct_summary", q.correctSummary)
                put("is_correct", isCorrect)
                put("is_omission", isOmission)
                put("is_incorrect_guess", isError)
                put("is_attention_check", q.isAttentionCheck)
            }
            responsesArr.put(qObj)
        }
        root.put("quiz_responses", responsesArr)

        return root
    }
}

object SurveyScorer {
    fun calculateScores(
        answers: Map<Int, String>,
        nasaTlxScores: Map<String, Int>,
        startTime: Long,
        endTime: Long,
        questionTimings: Map<Int, QuestionTiming>
    ): SurveyScoringResult {
        // Core items:
        // Amphitheatre: Q1, Q2, Q3, Q4
        var amphCore = 0
        for (qId in 1..4) {
            val q = SurveyDefinitions.retentionQuestions.first { it.id == qId }
            if (answers[qId].equals(q.correctKey, ignoreCase = true)) amphCore++
        }

        // Lake Memorial Core: Q7, Q8, Q9, Q10
        var lakeCore = 0
        for (qId in 7..10) {
            val q = SurveyDefinitions.retentionQuestions.first { it.id == qId }
            if (answers[qId].equals(q.correctKey, ignoreCase = true)) lakeCore++
        }

        // Tosun Terzioglu Garden Core: Q14, Q15, Q16, Q17
        var gardenCore = 0
        for (qId in 14..17) {
            val q = SurveyDefinitions.retentionQuestions.first { it.id == qId }
            if (answers[qId].equals(q.correctKey, ignoreCase = true)) gardenCore++
        }
        val totalCore = amphCore + lakeCore + gardenCore

        // Detailed items:
        // Amphitheatre Detailed: Q5, Q6
        var amphDetailed = 0
        for (qId in 5..6) {
            val q = SurveyDefinitions.retentionQuestions.first { it.id == qId }
            if (answers[qId].equals(q.correctKey, ignoreCase = true)) amphDetailed++
        }

        // Lake Memorial Detailed: Q12, Q13
        var lakeDetailed = 0
        for (qId in 12..13) {
            val q = SurveyDefinitions.retentionQuestions.first { it.id == qId }
            if (answers[qId].equals(q.correctKey, ignoreCase = true)) lakeDetailed++
        }

        // Garden Detailed: Q19, Q20
        var gardenDetailed = 0
        for (qId in 19..20) {
            val q = SurveyDefinitions.retentionQuestions.first { it.id == qId }
            if (answers[qId].equals(q.correctKey, ignoreCase = true)) gardenDetailed++
        }
        val totalDetailed = amphDetailed + lakeDetailed + gardenDetailed

        val overallRetention = totalCore + totalDetailed

        // Omission & Error across the 18 retention items (excluding Q11 and Q18)
        val retentionItemIds = (1..20).filter { it != 11 && it != 18 }
        var omissionCount = 0
        var errorCount = 0
        for (qId in retentionItemIds) {
            val q = SurveyDefinitions.retentionQuestions.first { it.id == qId }
            val ans = answers[qId] ?: ""
            if (ans.equals("E", ignoreCase = true)) {
                omissionCount++
            } else if (!ans.equals(q.correctKey, ignoreCase = true)) {
                errorCount++
            }
        }
        val omissionRate = (omissionCount.toDouble() / 18.0) * 100.0
        val errorRate = (errorCount.toDouble() / 18.0) * 100.0

        // Attention Check: Q11 (Option C) & Q18 (Option B)
        var attentionScore = 0
        if (answers[11].equals("C", ignoreCase = true)) attentionScore++
        if (answers[18].equals("B", ignoreCase = true)) attentionScore++

        val attentionStatus = when (attentionScore) {
            2 -> "Pass (2/2) - High attention and data fidelity"
            1 -> "Borderline (1/2) - Retained for sensitivity analysis"
            else -> "Fail (0/2) - Systematic inattention / prompt neglect"
        }

        // Mean NASA-TLX score
        val tlxValues = nasaTlxScores.values
        val meanTlx = if (tlxValues.isNotEmpty()) tlxValues.average() else 0.0

        val durationMs = (endTime - startTime).coerceAtLeast(0L)
        val durationSeconds = durationMs / 1000.0

        return SurveyScoringResult(
            amphCoreScore = amphCore,
            lakeCoreScore = lakeCore,
            gardenCoreScore = gardenCore,
            totalCoreScore = totalCore,
            amphDetailedScore = amphDetailed,
            lakeDetailedScore = lakeDetailed,
            gardenDetailedScore = gardenDetailed,
            totalDetailedScore = totalDetailed,
            overallRetentionScore = overallRetention,
            omissionCount = omissionCount,
            omissionRatePercent = omissionRate,
            errorCount = errorCount,
            errorRatePercent = errorRate,
            attentionScore = attentionScore,
            attentionStatus = attentionStatus,
            nasaTlxScores = nasaTlxScores,
            meanNasaTlxScore = meanTlx,
            surveyStartTime = startTime,
            surveyEndTime = endTime,
            totalDurationMs = durationMs,
            totalDurationSeconds = durationSeconds,
            questionTimings = questionTimings
        )
    }
}
