package com.example.my

import android.Manifest
import android.media.AudioAttributes
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.my.ui.theme.AuraBackground
import com.example.my.ui.theme.AuraCyan
import com.example.my.ui.theme.AuraMint
import com.example.my.ui.theme.AuraSurface
import com.example.my.ui.theme.AuraSurfaceHigh
import com.example.my.ui.theme.AuraText
import com.example.my.ui.theme.AuraTextMuted
import com.example.my.ui.theme.MyTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var state by mutableStateOf(AssistantState.IDLE)
    private var transcript by mutableStateOf("")
    private var answer by mutableStateOf("")
    private var statusMessage by mutableStateOf("Готова слушать")
    private var inputLevel by mutableStateOf(0f)
    private var isVoiceSessionActive by mutableStateOf(false)
    private var conversationId = ""
    private var accumulatedSpeech = ""
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeech: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val submitSpeechRunnable = Runnable { submitAccumulatedSpeech() }
    private val restartListeningRunnable = Runnable {
        if (isVoiceSessionActive && state == AssistantState.LISTENING) startListening()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceSession() else showError("Нужен доступ к микрофону")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        textToSpeech = TextToSpeech(this, this)
        setContent {
            MyTheme {
                VoiceAssistantScreen(
                    state = state,
                    transcript = transcript,
                    answer = answer,
                    statusMessage = statusMessage,
                    inputLevel = inputLevel,
                    isVoiceSessionActive = isVoiceSessionActive,
                    isConfigured = BuildConfig.DIFY_API_KEY.isNotBlank(),
                    onVoiceTap = ::handleVoiceTap,
                    onSuggestion = ::sendQuestion,
                    onStopSpeaking = ::stopSpeaking,
                    onReplayAnswer = { if (answer.isNotBlank()) speak(answer) }
                )
            }
        }
    }

    private fun handleVoiceTap() {
        if (isVoiceSessionActive) {
            stopVoiceSession()
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceSession()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceSession() {
        isVoiceSessionActive = true
        accumulatedSpeech = ""
        transcript = ""
        answer = ""
        startListening()
    }

    private fun stopVoiceSession() {
        isVoiceSessionActive = false
        mainHandler.removeCallbacks(submitSpeechRunnable)
        mainHandler.removeCallbacks(restartListeningRunnable)
        accumulatedSpeech = ""
        inputLevel = 0f
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        state = AssistantState.IDLE
        statusMessage = "Голосовой режим остановлен"
    }

    private fun startListening() {
        if (!isVoiceSessionActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            isVoiceSessionActive = false
            showError("Распознавание речи недоступно на устройстве")
            return
        }
        textToSpeech?.stop()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    state = AssistantState.LISTENING
                    statusMessage = if (accumulatedSpeech.isBlank()) {
                        "Слушаю вас…"
                    } else {
                        "Можете продолжить…"
                    }
                    inputLevel = 0f
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    inputLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    inputLevel = 0f
                    if (isVoiceSessionActive) {
                        state = AssistantState.LISTENING
                        statusMessage = "Жду продолжение…"
                    }
                }

                override fun onError(error: Int) {
                    inputLevel = 0f
                    if (!isVoiceSessionActive) return
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            if (accumulatedSpeech.isNotBlank()) scheduleQuestionSubmission()
                            else {
                                statusMessage = "Жду ваш вопрос…"
                                scheduleListeningRestart(350L)
                            }
                        }
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                            statusMessage = "Ошибка распознавания. Повторяю…"
                            scheduleListeningRestart(1_000L)
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            scheduleListeningRestart(500L)
                        else -> scheduleListeningRestart(500L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) {
                        accumulatedSpeech = listOf(accumulatedSpeech, text)
                            .filter(String::isNotBlank)
                            .joinToString(" ")
                            .trim()
                        transcript = accumulatedSpeech
                        scheduleQuestionSubmission()
                    }
                    scheduleListeningRestart(250L)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    transcript = listOf(accumulatedSpeech, partial)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        1_000L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        900L
                    )
                }
            )
        }
    }

    private fun scheduleQuestionSubmission() {
        mainHandler.removeCallbacks(submitSpeechRunnable)
        mainHandler.postDelayed(submitSpeechRunnable, 1_400L)
    }

    private fun scheduleListeningRestart(delay: Long) {
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed(restartListeningRunnable, delay)
    }

    private fun submitAccumulatedSpeech() {
        val question = accumulatedSpeech.trim()
        if (!isVoiceSessionActive || question.isBlank()) return
        mainHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        accumulatedSpeech = ""
        sendQuestion(question)
    }

    private fun sendQuestion(question: String) {
        transcript = question
        answer = ""
        state = AssistantState.THINKING
        statusMessage = "Думаю над ответом…"

        if (BuildConfig.DIFY_API_KEY.isBlank()) {
            mainHandler.postDelayed({
                val demoAnswer = "Подключение работает в демо-режиме. Добавьте DIFY_API_KEY " +
                    "в local.properties, и я начну отвечать через вашего ассистента Dify."
                answer = demoAnswer
                speak(demoAnswer)
            }, 900)
            return
        }

        thread(name = "dify-request") {
            runCatching { DifyClient.ask(question, conversationId) }
                .onSuccess { result ->
                    mainHandler.post {
                        conversationId = result.conversationId
                        answer = result.answer
                        speak(result.answer)
                    }
                }
                .onFailure { error ->
                    mainHandler.post {
                        showError(error.message ?: "Не удалось получить ответ от Dify")
                    }
                }
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady) {
            pendingSpeech = text
            state = AssistantState.SPEAKING
            statusMessage = "Подготавливаю голос…"
            return
        }
        state = AssistantState.SPEAKING
        statusMessage = "Отвечаю…"
        val spokenText = text
            .replace(Regex("""https?://\S+"""), "Ссылка указана на экране.")
            .replace(Regex("""[*_#`]"""), "")
            .replace("•", "")
        val speechParams = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        val result = textToSpeech?.speak(
            spokenText,
            TextToSpeech.QUEUE_FLUSH,
            speechParams,
            "dify-answer"
        )
        if (result == TextToSpeech.ERROR) {
            state = AssistantState.IDLE
            statusMessage = "Не удалось включить озвучивание"
        }
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
        if (isVoiceSessionActive) {
            state = AssistantState.LISTENING
            statusMessage = "Слушаю следующий вопрос…"
            mainHandler.postDelayed({ startListening() }, 250L)
        } else {
            state = AssistantState.IDLE
            statusMessage = "Озвучивание остановлено"
        }
    }

    private fun showError(message: String) {
        state = AssistantState.ERROR
        statusMessage = message
        mainHandler.postDelayed({
            if (state == AssistantState.ERROR) {
                state = AssistantState.IDLE
                statusMessage = "Нажмите, чтобы повторить"
            }
        }, 3_000)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = textToSpeech ?: return
            val languageResult = engine.setLanguage(Locale.forLanguageTag("ru-RU"))
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.language = Locale.forLanguageTag("ru")
            }
            engine.voices
                ?.filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
                ?.sortedByDescending { voice ->
                    val name = voice.name.lowercase()
                    when {
                        "ruf" in name || "female" in name -> 3
                        "local" in name || "embedded" in name -> 2
                        else -> 1
                    }
                }
                ?.firstOrNull()
                ?.let { engine.voice = it }
            engine.setSpeechRate(0.98f)
            engine.setPitch(1f)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        state = AssistantState.IDLE
                        statusMessage = "Ответ готов"
                    }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (state == AssistantState.SPEAKING) {
                            if (isVoiceSessionActive) {
                                state = AssistantState.LISTENING
                                statusMessage = "Слушаю следующий вопрос…"
                                mainHandler.postDelayed({ startListening() }, 350L)
                            } else {
                                state = AssistantState.IDLE
                                statusMessage = "Можно спросить ещё"
                            }
                        }
                    }
                }
            })
            isTtsReady = true
            pendingSpeech?.let { queuedText ->
                pendingSpeech = null
                mainHandler.post { speak(queuedText) }
            }
        } else {
            isTtsReady = false
            mainHandler.post {
                state = AssistantState.ERROR
                statusMessage = "На устройстве не работает синтез речи"
            }
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}

private object DifyClient {
    data class Result(val answer: String, val conversationId: String)

    fun ask(question: String, conversationId: String): Result {
        val baseUrl = BuildConfig.DIFY_BASE_URL.trimEnd('/').removeSuffix("/v1")
        val isWorkflow = BuildConfig.DIFY_API_MODE.equals("workflow", ignoreCase = true)
        val endpoint = if (isWorkflow) {
            "$baseUrl/v1/workflows/run"
        } else {
            "$baseUrl/v1/chat-messages"
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer ${BuildConfig.DIFY_API_KEY}")
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().apply {
            put(
                "inputs",
                if (isWorkflow) JSONObject().put("query", question) else JSONObject()
            )
            if (!isWorkflow) put("query", question)
            put("response_mode", "blocking")
            put("user", "aura-android-user")
            if (!isWorkflow && conversationId.isNotBlank()) {
                put("conversation_id", conversationId)
            }
        }.toString()

        connection.outputStream.bufferedWriter().use { it.write(body) }
        val responseCode = connection.responseCode
        val responseText = (if (responseCode in 200..299) connection.inputStream
        else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (responseCode !in 200..299) {
            val apiMessage = runCatching { JSONObject(responseText).optString("message") }.getOrNull()
            throw IllegalStateException(apiMessage?.takeIf { it.isNotBlank() }
                ?: "Dify вернул ошибку $responseCode")
        }
        val json = JSONObject(responseText)
        val rawAnswer = if (isWorkflow) {
            extractWorkflowOutput(json)
        } else {
            json.optString("answer")
        }
        return Result(
            answer = extractAnswerText(rawAnswer),
            conversationId = if (isWorkflow) "" else json.optString("conversation_id")
        )
    }

    private fun extractWorkflowOutput(response: JSONObject): String {
        val data = response.optJSONObject("data")
            ?: return response.optString("answer")
        val outputs = data.optJSONObject("outputs")
            ?: return data.optString("output")

        return listOf("text", "answer", "reply", "output", "result")
            .asSequence()
            .mapNotNull { key ->
                if (outputs.isNull(key)) null else outputs.opt(key)?.toString()
            }
            .firstOrNull { value -> value.isUsefulAnswer() }
            ?: outputs.toString()
    }

    private fun extractAnswerText(rawAnswer: String): String {
        val cleaned = rawAnswer
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (!cleaned.isUsefulAnswer()) return NO_ANSWER_MESSAGE

        return runCatching {
            val nested = JSONObject(cleaned)
            val directAnswer = listOf("reply", "answer", "text", "output", "result", "message")
                .asSequence()
                .mapNotNull { key ->
                    if (nested.isNull(key)) null else nested.optString(key)
                }
                .firstOrNull { value -> value.isUsefulAnswer() }

            directAnswer ?: when (nested.optString("reason")) {
                "out_of_scope" -> OUT_OF_SCOPE_MESSAGE
                else -> NO_ANSWER_MESSAGE
            }
        }.getOrDefault(cleaned)
    }

    private fun String.isUsefulAnswer(): Boolean =
        isNotBlank() && !equals("null", ignoreCase = true) &&
            !equals("undefined", ignoreCase = true)

    private const val NO_ANSWER_MESSAGE =
        "Не удалось получить текст ответа. Уточните нотариальный вопрос и попробуйте ещё раз."

    private const val OUT_OF_SCOPE_MESSAGE =
        "Сейчас я отвечаю только на вопросы о нотариальных услугах Кыргызской Республики. " +
            "Уточните, какая нотариальная услуга вам нужна."
}

@Composable
private fun VoiceAssistantScreen(
    state: AssistantState,
    transcript: String,
    answer: String,
    statusMessage: String,
    inputLevel: Float,
    isVoiceSessionActive: Boolean,
    isConfigured: Boolean,
    onVoiceTap: () -> Unit,
    onSuggestion: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onReplayAnswer: () -> Unit
) {
    val background = Brush.radialGradient(
        colors = listOf(Color(0xFF12342F), AuraBackground),
        center = Offset(400f, 480f),
        radius = 950f
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        AmbientGlow(state)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(isConfigured)
            Spacer(Modifier.weight(0.8f))
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> "Я слушаю"
                    AssistantState.THINKING -> "Секунду…"
                    AssistantState.SPEAKING -> "Вот что я нашла"
                    AssistantState.ERROR -> "Что-то пошло не так"
                    AssistantState.IDLE -> if (answer.isBlank()) "О чём поговорим?" else "Спросите ещё"
                },
                style = MaterialTheme.typography.displaySmall,
                color = AuraText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            val visibleStatus = if (
                state == AssistantState.LISTENING && inputLevel > 0.08f
            ) {
                "Слышу голос…"
            } else {
                statusMessage
            }
            AnimatedContent(targetState = visibleStatus, label = "status") { message ->
                Text(
                    text = message,
                    color = if (state == AssistantState.ERROR)
                        MaterialTheme.colorScheme.error else AuraTextMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(34.dp))
            VoiceOrb(state = state, inputLevel = inputLevel, onClick = onVoiceTap)
            Spacer(Modifier.height(34.dp))
            ConversationCard(transcript, answer, state, onStopSpeaking, onReplayAnswer)
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = transcript.isBlank() && answer.isBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Suggestions(onSuggestion)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = when {
                    isVoiceSessionActive ->
                        "Голосовой режим включён · нажмите сферу, чтобы остановить"
                    else -> "Нажмите на сферу, чтобы включить постоянный диалог"
                },
                color = AuraTextMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Header(isConfigured: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Brush.linearGradient(listOf(AuraMint, AuraCyan)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = AuraBackground, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("NurAI", fontWeight = FontWeight.SemiBold, color = AuraText)
            Text(
                "Единое окно нотариальных услуг КР",
                style = MaterialTheme.typography.bodyMedium,
                color = AuraTextMuted,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(AuraSurface)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (isConfigured) AuraMint else Color(0xFFFFC66D), CircleShape)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (isConfigured) "Online" else "Демо",
                style = MaterialTheme.typography.labelLarge,
                color = AuraText,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BoxScope.AmbientGlow(state: AssistantState) {
    val transition = rememberInfiniteTransition(label = "ambient")
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "ambientPulse"
    )
    val color by animateColorAsState(
        when (state) {
            AssistantState.ERROR -> Color(0xFFFF776D)
            AssistantState.THINKING -> AuraCyan
            else -> AuraMint
        },
        label = "ambientColor"
    )
    Box(
        Modifier
            .size(320.dp)
            .scale(if (state == AssistantState.IDLE) 0.85f else pulse)
            .alpha(0.12f)
            .blur(70.dp)
            .background(color, CircleShape)
            .align(Alignment.Center)
    )
}

@Composable
private fun VoiceOrb(state: AssistantState, inputLevel: Float, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voiceOrb")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == AssistantState.LISTENING || state == AssistantState.SPEAKING) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(tween(720), RepeatMode.Reverse),
        label = "orbPulse"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600)),
        label = "orbSpin"
    )
    val accent = when (state) {
        AssistantState.ERROR -> Color(0xFFFF8B82)
        AssistantState.THINKING -> AuraCyan
        else -> AuraMint
    }
    Box(
        modifier = Modifier
            .size(174.dp)
            .scale(if (state == AssistantState.LISTENING) pulse + inputLevel * 0.10f else pulse)
            .semantics { contentDescription = "Кнопка голосового ассистента" }
            .clickable(
                interactionSource = MutableInteractionSource(),
                indication = null,
                enabled = state != AssistantState.THINKING,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.08f), accent, AuraCyan, accent.copy(alpha = 0.08f)),
                    center = center
                ),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            )
            if (state == AssistantState.THINKING) {
                val angle = Math.toRadians(spin.toDouble())
                val radius = size.minDimension * 0.47f
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(
                        center.x + cos(angle).toFloat() * radius,
                        center.y + sin(angle).toFloat() * radius
                    )
                )
            }
        }
        Box(
            Modifier
                .size(142.dp)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.34f), AuraSurfaceHigh, AuraSurface)
                    ),
                    CircleShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(state, label = "orbIcon") {
                if (it == AssistantState.THINKING) ThinkingIcon(accent)
                else MicrophoneIcon(accent, it == AssistantState.LISTENING)
            }
        }
    }
}

@Composable
private fun MicrophoneIcon(color: Color, listening: Boolean) {
    Canvas(Modifier.size(58.dp)) {
        val stroke = 4.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.34f, size.height * 0.10f),
            size = Size(size.width * 0.32f, size.height * 0.48f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f),
            style = Stroke(stroke)
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.20f, size.height * 0.28f),
            size = Size(size.width * 0.60f, size.height * 0.48f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color,
            Offset(size.width / 2, size.height * 0.76f),
            Offset(size.width / 2, size.height * 0.90f),
            stroke,
            StrokeCap.Round
        )
        drawLine(
            color,
            Offset(size.width * 0.37f, size.height * 0.90f),
            Offset(size.width * 0.63f, size.height * 0.90f),
            stroke,
            StrokeCap.Round
        )
        if (listening) {
            drawArc(
                color.copy(alpha = 0.35f),
                -55f,
                110f,
                false,
                Offset(-4f, size.height * 0.10f),
                Size(size.width + 8f, size.height * 0.72f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun ThinkingIcon(color: Color) {
    Canvas(Modifier.size(58.dp)) {
        repeat(3) { index ->
            drawCircle(
                color = color.copy(alpha = 0.45f + index * 0.25f),
                radius = (5 + index).dp.toPx(),
                center = Offset(size.width * (0.25f + index * 0.25f), size.height / 2)
            )
        }
    }
}

@Composable
private fun ConversationCard(
    transcript: String,
    answer: String,
    state: AssistantState,
    onStopSpeaking: () -> Unit,
    onReplayAnswer: () -> Unit
) {
    AnimatedVisibility(
        visible = transcript.isNotBlank() || answer.isNotBlank(),
        enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.96f),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.98f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 230.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AuraSurface.copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            if (transcript.isNotBlank()) {
                Text("ВЫ", style = MaterialTheme.typography.labelLarge, color = AuraMint)
                Spacer(Modifier.height(6.dp))
                Text(transcript, style = MaterialTheme.typography.bodyLarge, color = AuraText)
            }
            if (answer.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("NurAI", style = MaterialTheme.typography.labelLarge, color = AuraCyan)
                Spacer(Modifier.height(6.dp))
                Text(answer, style = MaterialTheme.typography.bodyLarge, color = AuraText)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (state == AssistantState.SPEAKING) {
                        "Остановить озвучивание"
                    } else {
                        "Озвучить ответ"
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            onClick = if (state == AssistantState.SPEAKING) {
                                onStopSpeaking
                            } else {
                                onReplayAnswer
                            }
                        )
                        .padding(vertical = 8.dp),
                    color = AuraMint,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun Suggestions(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "Документы для доверенности",
        "Как оформить наследство?",
        "Как найти нотариуса?"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        suggestions.forEach { suggestion ->
            Surface(
                modifier = Modifier
                    .height(46.dp)
                    .clickable { onSuggestion(suggestion) },
                shape = RoundedCornerShape(16.dp),
                color = AuraSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = 0.10f)
                )
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(suggestion, style = MaterialTheme.typography.labelLarge, color = AuraText)
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun VoiceAssistantPreview() {
    MyTheme {
        VoiceAssistantScreen(
            state = AssistantState.IDLE,
            transcript = "",
            answer = "",
            statusMessage = "Готова слушать",
            inputLevel = 0f,
            isVoiceSessionActive = false,
            isConfigured = false,
            onVoiceTap = {},
            onSuggestion = {},
            onStopSpeaking = {},
            onReplayAnswer = {}
        )
    }
}
