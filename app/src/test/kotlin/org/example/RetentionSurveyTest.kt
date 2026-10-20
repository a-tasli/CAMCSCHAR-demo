package org.example

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RetentionSurveyTest {

    @Test
    fun testAll20QuestionsConfiguredCorrectly() {
        assertEquals(20, SurveyDefinitions.retentionQuestions.size)

        // Verify all 20 questions have 5 options A, B, C, D, E
        SurveyDefinitions.retentionQuestions.forEach { q ->
            assertEquals(5, q.options.size, "Question ${q.id} should have 5 options")
            val keys = q.options.map { it.key }
            assertEquals(listOf("A", "B", "C", "D", "E"), keys)
            assertTrue(q.correctKey in listOf("A", "B", "C", "D"), "Question ${q.id} correct key must be A, B, C, or D")
        }

        // Verify attention checks
        val q11 = SurveyDefinitions.retentionQuestions.first { it.id == 11 }
        assertTrue(q11.isAttentionCheck)
        assertEquals("C", q11.correctKey)

        val q18 = SurveyDefinitions.retentionQuestions.first { it.id == 18 }
        assertTrue(q18.isAttentionCheck)
        assertEquals("B", q18.correctKey)

        // Verify equal distribution of correct answers (5 of each across 20 questions)
        val keyCounts = SurveyDefinitions.retentionQuestions.groupingBy { it.correctKey }.eachCount()
        assertEquals(5, keyCounts["A"])
        assertEquals(5, keyCounts["B"])
        assertEquals(5, keyCounts["C"])
        assertEquals(5, keyCounts["D"])

        // Verify trick question lengths are similar to normal questions
        val normalLengths = SurveyDefinitions.retentionQuestions.filter { !it.isAttentionCheck }.map { it.question.length }
        val avgNormalLength = normalLengths.average()
        val trickLengths = SurveyDefinitions.retentionQuestions.filter { it.isAttentionCheck }.map { it.question.length }
        trickLengths.forEach { trickLen ->
            assertTrue(kotlin.math.abs(trickLen - avgNormalLength) < 30.0, "Trick question length ($trickLen) should be close to normal average ($avgNormalLength)")
        }
    }

    @Test
    fun testNASAWorkloadDimensions() {
        assertEquals(6, SurveyDefinitions.nasaTlxDimensions.size)
        val ids = SurveyDefinitions.nasaTlxDimensions.map { it.id }
        assertTrue(ids.containsAll(listOf("mental_demand", "physical_demand", "temporal_demand", "performance", "effort", "frustration")))
    }

    @Test
    fun testPerfectScoreScoring() {
        val answers = mutableMapOf<Int, String>()
        SurveyDefinitions.retentionQuestions.forEach { q ->
            answers[q.id] = q.correctKey
        }

        val tlxScores = mapOf(
            "mental_demand" to 30,
            "physical_demand" to 40,
            "temporal_demand" to 20,
            "performance" to 10,
            "effort" to 25,
            "frustration" to 15
        )

        val startTime = 1000L
        val endTime = 61000L // 60 seconds
        val timings = mutableMapOf<Int, QuestionTiming>()

        val result = SurveyScorer.calculateScores(
            answers = answers,
            nasaTlxScores = tlxScores,
            startTime = startTime,
            endTime = endTime,
            questionTimings = timings
        )

        // Core score should be 12/12
        assertEquals(4, result.amphCoreScore)
        assertEquals(4, result.lakeCoreScore)
        assertEquals(4, result.gardenCoreScore)
        assertEquals(12, result.totalCoreScore)

        // Detailed score should be 6/6
        assertEquals(2, result.amphDetailedScore)
        assertEquals(2, result.lakeDetailedScore)
        assertEquals(2, result.gardenDetailedScore)
        assertEquals(6, result.totalDetailedScore)

        // Overall retention score: 18/18
        assertEquals(18, result.overallRetentionScore)
        assertEquals(0, result.omissionCount)
        assertEquals(0.0, result.omissionRatePercent)
        assertEquals(0, result.errorCount)
        assertEquals(0.0, result.errorRatePercent)

        // Attention check: 2/2 Pass
        assertEquals(2, result.attentionScore)
        assertTrue(result.attentionStatus.startsWith("Pass"))

        // Mean NASA TLX
        assertEquals((30 + 40 + 20 + 10 + 25 + 15) / 6.0, result.meanNasaTlxScore)

        // Timing
        assertEquals(60000L, result.totalDurationMs)
        assertEquals(60.0, result.totalDurationSeconds)

        // JSON serialization
        val metadata = SurveyMetadata(participantId = "P-99", condition = Condition.EXPERIMENTAL)
        val json = result.toJsonObject(metadata, answers)

        assertEquals("P-99", json.getJSONObject("metadata").getString("participant_id"))
        assertEquals(18, json.getJSONObject("scores").getInt("overall_retention_score"))
        assertEquals(60000L, json.getJSONObject("timings").getLong("total_duration_ms"))
    }

    @Test
    fun testOmissionAndErrorRates() {
        val answers = mutableMapOf<Int, String>()
        // All retention questions answered with 'E' (I don't remember)
        // Attention questions answered wrong
        (1..20).forEach { id ->
            answers[id] = "E"
        }

        val result = SurveyScorer.calculateScores(
            answers = answers,
            nasaTlxScores = emptyMap(),
            startTime = 0L,
            endTime = 10000L,
            questionTimings = emptyMap()
        )

        assertEquals(0, result.overallRetentionScore)
        assertEquals(18, result.omissionCount)
        assertEquals(100.0, result.omissionRatePercent)
        assertEquals(0, result.errorCount)
        assertEquals(0.0, result.errorRatePercent)
        assertEquals(0, result.attentionScore)
        assertTrue(result.attentionStatus.startsWith("Fail"))
    }

    @Test
    fun testAttentionCheckBorderline() {
        val answers = mutableMapOf<Int, String>()
        // Q11 correct ('C'), Q18 wrong ('A')
        answers[11] = "C"
        answers[18] = "A"

        val result = SurveyScorer.calculateScores(
            answers = answers,
            nasaTlxScores = emptyMap(),
            startTime = 0L,
            endTime = 5000L,
            questionTimings = emptyMap()
        )

        assertEquals(1, result.attentionScore)
        assertTrue(result.attentionStatus.startsWith("Borderline"))
    }

    @Test
    fun testNasaTlxUnansweredByDefaultAndValidation() {
        // Initial state should be unanswered (null for all dimensions)
        val initialScores = mutableMapOf<String, Int?>()
        SurveyDefinitions.nasaTlxDimensions.forEach { dim ->
            initialScores[dim.id] = null
        }

        // Verify all 6 start as null
        assertEquals(6, initialScores.size)
        val unansweredCountInitial = SurveyDefinitions.nasaTlxDimensions.count { initialScores[it.id] == null }
        assertEquals(6, unansweredCountInitial)

        // Partially answer 3 of 6
        initialScores["mental_demand"] = 45
        initialScores["physical_demand"] = 60
        initialScores["temporal_demand"] = 25

        val unansweredCountPartial = SurveyDefinitions.nasaTlxDimensions.count { initialScores[it.id] == null }
        assertEquals(3, unansweredCountPartial)

        // Fully answer remaining 3
        initialScores["performance"] = 15
        initialScores["effort"] = 50
        initialScores["frustration"] = 30

        val unansweredCountComplete = SurveyDefinitions.nasaTlxDimensions.count { initialScores[it.id] == null }
        assertEquals(0, unansweredCountComplete)

        // Map non-null values for scoring
        val completeScores = initialScores.mapValues { it.value ?: 0 }
        assertEquals(6, completeScores.size)
        assertEquals(45, completeScores["mental_demand"])

        val result = SurveyScorer.calculateScores(
            answers = emptyMap(),
            nasaTlxScores = completeScores,
            startTime = 0L,
            endTime = 1000L,
            questionTimings = emptyMap()
        )

        val expectedMean = (45 + 60 + 25 + 15 + 50 + 30) / 6.0
        assertEquals(expectedMean, result.meanNasaTlxScore)
    }

    @Test
    fun testUnifiedJsonStructure() {
        val answers = mutableMapOf<Int, String>()
        (1..20).forEach { answers[it] = "A" }

        val tlxScores = mapOf(
            "mental_demand" to 30,
            "physical_demand" to 40,
            "temporal_demand" to 20,
            "performance" to 10,
            "effort" to 25,
            "frustration" to 15
        )

        val result = SurveyScorer.calculateScores(
            answers = answers,
            nasaTlxScores = tlxScores,
            startTime = 1000L,
            endTime = 10000L,
            questionTimings = emptyMap()
        )

        val metadata = SurveyMetadata(
            participantId = "P-Test",
            condition = Condition.EXPERIMENTAL
        )

        val surveyJson = result.toJsonObject(metadata, answers)

        val fakeExperimentData = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("timestamp", "2026-10-20T19:40:00.000+0300")
                put("hr", 85)
                put("hrv_rmssd", 40.0)
                put("step_variance", 15.0)
                put("poi", "tosun_terzioglu_garden")
            })
        }

        val unifiedJson = org.json.JSONObject().apply {
            put("participant_id", metadata.participantId)
            put("condition", metadata.condition.label)
            put("date_time", metadata.dateTime)
            put("survey", surveyJson)
            put("experiment_data", fakeExperimentData)
        }

        assertEquals("P-Test", unifiedJson.getString("participant_id"))
        assertTrue(unifiedJson.has("survey"))
        assertTrue(unifiedJson.has("experiment_data"))
        assertEquals(1, unifiedJson.getJSONArray("experiment_data").length())
        assertEquals(85, unifiedJson.getJSONArray("experiment_data").getJSONObject(0).getInt("hr"))
        assertEquals(18, unifiedJson.getJSONObject("survey").getJSONObject("scores").getInt("max_retention_score"))
    }
}
