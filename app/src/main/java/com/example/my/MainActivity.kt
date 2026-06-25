package com.example.my

import android.annotation.SuppressLint
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
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.remember
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
private enum class ListeningMode { OFF, CONVERSATION }

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var state by mutableStateOf(AssistantState.IDLE)
    private var transcript by mutableStateOf("")
    private var answer by mutableStateOf("")
    private var statusMessage by mutableStateOf("Готова слушать")
    private var inputLevel by mutableStateOf(0f)
    private var isVoiceSessionActive by mutableStateOf(false)
    private var isFaceVisible by mutableStateOf(false)
    private val userNames = mutableMapOf<Int, String>()
    private val userHistory = mutableMapOf<Int, String>() // История тем для каждого пользователя
    private var currentTrackingId: Int? = null
    private var isWaitingForName by mutableStateOf(false)
    private var conversationId = ""
    private var accumulatedSpeech = ""
    private var listeningMode = ListeningMode.OFF
    private var isActivityVisible = false
    private var wasFaceVisible = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var faceScanner: CameraFaceScanner? = null
    private var isTtsReady = false
    private var pendingSpeech: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val submitSpeechRunnable = Runnable { submitAccumulatedSpeech() }
    private val restartListeningRunnable = Runnable {
        if (shouldKeepListening() && state == AssistantState.LISTENING) startListening()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceSession() else showError(
                "Разрешите доступ к микрофону, чтобы я могла вас слышать"
            )
        }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.camera.core.ExperimentalGetImage
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                faceScanner?.bind(null, this, this)
            }
        }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.camera.core.ExperimentalGetImage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        textToSpeech = TextToSpeech(this, this)
        faceScanner = CameraFaceScanner(this) { handleFacePresenceChanged(it) }

        setContent {
            MyTheme {
                VoiceAssistantScreen(
                    state = state,
                    transcript = transcript,
                    answer = answer,
                    statusMessage = statusMessage,
                    isFaceVisible = isFaceVisible,
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

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            faceScanner?.bind(null, this, this)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleVoiceTap() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (listeningMode == ListeningMode.OFF) {
            startVoiceSession()
        } else {
            stopVoiceSession()
        }
    }

    private fun startVoiceSession() {
        listeningMode = ListeningMode.CONVERSATION
        isVoiceSessionActive = true
        accumulatedSpeech = ""
        transcript = ""
        answer = ""
        startListening()
    }

    private fun handleFacePresenceChanged(trackingId: Int?) {
        android.util.Log.d("NurAI", "Face detected: $trackingId")
        isFaceVisible = trackingId != null
        
        if (trackingId == null) {
            currentTrackingId = null
            wasFaceVisible = false
            return
        }
        
        // Если это тот же самый человек, которого мы уже видим, ничего не делаем
        if (trackingId == currentTrackingId && wasFaceVisible) return
        
        // ВАЖНО: Если мы сейчас ГОВОРИМ или ДУМАЕМ, не перебиваем приветствием
        if (state == AssistantState.SPEAKING || state == AssistantState.THINKING) return
        
        currentTrackingId = trackingId
        wasFaceVisible = true

        mainHandler.post {
            val name = userNames[trackingId]
            if (name == null) {
                isWaitingForName = true
                speak("Здравствуйте! Я вас вижу. Как мне к вам обращаться?")
            } else {
                speak("Здравствуйте, $name! Рада вас снова видеть. Чем могу помочь?")
            }
        }
    }

    private fun stopVoiceSession() {
        mainHandler.removeCallbacks(submitSpeechRunnable)
        mainHandler.removeCallbacks(restartListeningRunnable)
        accumulatedSpeech = ""
        inputLevel = 0f
        stopCurrentListening()
        runCatching { textToSpeech?.stop() }
        startVoiceSession()
    }

    private fun startListening() {
        if (!shouldKeepListening()) return
        
        // СТРОГАЯ ПРОВЕРКА: Если робот думает или говорит, микрофон НЕ включается
        if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
            android.util.Log.d("NurAI", "Пропуск startListening: робот занят (state=$state)")
            return
        }
        
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listeningMode = ListeningMode.OFF
            isVoiceSessionActive = false
            showError("Распознавание речи недоступно на устройстве")
            return
        }
        textToSpeech?.stop()
        speechRecognizer?.destroy()
        val recognitionMode = listeningMode
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (recognitionMode != listeningMode) return
                    state = AssistantState.LISTENING
                    statusMessage = if (accumulatedSpeech.isBlank()) "Слушаю вас (RU/KG)…" else "Можете продолжить…"
                    inputLevel = 0f
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    if (recognitionMode != listeningMode) return
                    inputLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (recognitionMode != listeningMode) return
                    inputLevel = 0f
                    if (shouldKeepListening()) {
                        state = AssistantState.LISTENING
                        statusMessage = "Слушаю вас…"
                    }
                }

                override fun onError(error: Int) {
                    if (recognitionMode != listeningMode) return
                    inputLevel = 0f
                    if (!shouldKeepListening()) return
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            statusMessage = "Слушаю..."
                            scheduleListeningRestart(100L)
                        }
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                            statusMessage = "Ошибка сети. Повторяю…"
                            scheduleListeningRestart(500L)
                        }
                        else -> scheduleListeningRestart(200L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (recognitionMode != listeningMode) return
                    val candidates = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                    
                    val text = candidates.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        if (isWaitingForName) {
                            currentTrackingId?.let { id -> userNames[id] = text }
                            isWaitingForName = false
                            speak("Очень приятно, $text! Чем я могу вам помочь?")
                            transcript = text
                            return
                        }
                        
                        accumulatedSpeech = listOf(accumulatedSpeech, text)
                            .filter(String::isNotBlank)
                            .joinToString(" ")
                            .trim()
                        transcript = accumulatedSpeech
                        
                        // Если получили текст, планируем отправку и НЕ перезапускаем прослушивание сразу
                        scheduleQuestionSubmission()
                    } else {
                        // Если текста нет, можно попробовать слушать дальше
                        scheduleListeningRestart(300L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (recognitionMode != listeningMode) return
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
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("ru-RU", "ky-KG"))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    
                    // УЛЬТРА-НАСТРОЙКИ ЧУВСТВИТЕЛЬНОСТИ
                    // Уменьшаем время ожидания, чтобы реакция была мгновенной
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                    
                    // Повышаем точность за счет подавления эха (на системном уровне)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
                }
            )
        }
    }

    private fun scheduleQuestionSubmission() {
        mainHandler.removeCallbacks(submitSpeechRunnable)
        mainHandler.postDelayed(submitSpeechRunnable, 1_400L)
    }

    private fun scheduleListeningRestart(delay: Long) {
        if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) return
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed(restartListeningRunnable, delay)
    }

    private fun submitAccumulatedSpeech() {
        val question = accumulatedSpeech.trim()
        if (listeningMode != ListeningMode.CONVERSATION || question.isBlank()) return
        mainHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        accumulatedSpeech = ""
        sendQuestion(question)
    }

    private fun sendQuestion(question: String) {
        if (listeningMode != ListeningMode.CONVERSATION) {
            listeningMode = ListeningMode.CONVERSATION
            isVoiceSessionActive = true
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
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
                        
                        // Сохраняем краткий контекст в память пользователя
                        currentTrackingId?.let { id ->
                            userHistory[id] = result.answer.take(50) + "..."
                        }

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
        if (text.isBlank()) return
        
        stopCurrentListening()
        
        // Определяем язык ответа для синтезатора
        val isKyrgyz = text.any { it in 'Ө'..'ө' || it in 'Ң'..'ң' || it in 'Ү'..'ү' }
        if (isKyrgyz) {
            textToSpeech?.language = Locale("ky", "KG")
        } else {
            textToSpeech?.language = Locale("ru", "RU")
        }

        state = AssistantState.SPEAKING
        statusMessage = "Отвечаю…"
        val spokenText = text
            .replace(Regex("""https?://\S+"""), "Ссылка указана на экране.")
            .replace(Regex("""[*_#`]"""), "")
            .replace("•", "")
            
        val speechParams = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "dify-answer")
            // Принудительно направляем в динамик
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        
        if (isTtsReady) {
            runCatching {
                textToSpeech?.speak(
                    spokenText,
                    TextToSpeech.QUEUE_FLUSH,
                    speechParams,
                    "dify-answer"
                )
            }.onFailure { e ->
                android.util.Log.e("NurAI", "TTS Speak error", e)
                state = AssistantState.IDLE
                statusMessage = "Сбой синтеза речи"
                startListening()
            }
        } else {
            pendingSpeech = text
            statusMessage = "Инициализация голоса…"
            // Если TTS не готов, попробуем пересоздать его
            initTts()
        }
    }

    private fun initTts() {
        textToSpeech?.shutdown()
        textToSpeech = TextToSpeech(this, this)
    }

    private fun stopCurrentListening() {
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(submitSpeechRunnable)
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun stopSpeaking() {
        runCatching { textToSpeech?.stop() }
        if (isVoiceSessionActive) {
            state = AssistantState.IDLE
            statusMessage = "Слушаю вас…"
            startListening()
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

    private fun shouldKeepListening(): Boolean = isActivityVisible

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (listeningMode == ListeningMode.OFF) {
                startVoiceSession()
            } else {
                state = AssistantState.LISTENING
                scheduleListeningRestart(150L)
            }
        }
    }

    override fun onStop() {
        isActivityVisible = false
        mainHandler.removeCallbacks(submitSpeechRunnable)
        mainHandler.removeCallbacks(restartListeningRunnable)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        inputLevel = 0f
        super.onStop()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = textToSpeech ?: return
            
            // ПРИНУДИТЕЛЬНО РУССКИЙ
            engine.setLanguage(Locale("ru", "RU"))

            // Пытаемся найти ЛЮБОЙ женский голос
            val voices = engine.voices
            val femaleVoice = voices?.find { it.name.lowercase().contains("female") || it.name.lowercase().contains("-f-") } 
                ?: voices?.firstOrNull { it.locale.language == "ru" }

            if (femaleVoice != null) {
                engine.voice = femaleVoice
            }

            engine.setPitch(1.3f) 
            engine.setSpeechRate(1.0f) 
            
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                @Suppress("DeprecatedCallableAddReplaceWith")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        state = AssistantState.IDLE
                        statusMessage = "Ответ готов"
                    }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (state == AssistantState.SPEAKING) {
                            state = AssistantState.IDLE
                            statusMessage = "Я закончила, слушаю вас…"
                            // Включаем микрофон СРАЗУ ПОСЛЕ того, как робот замолчал
                            startListening()
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
        listeningMode = ListeningMode.OFF
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
@SuppressLint("UnsafeOptInUsageError")
private fun VoiceAssistantScreen(
    state: AssistantState,
    transcript: String,
    answer: String,
    statusMessage: String,
    isFaceVisible: Boolean,
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
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.weight(0.8f))
            Text(
                text = when (state) {
                    AssistantState.LISTENING -> "Я слушаю"
                    AssistantState.THINKING -> "Секунду…"
                    AssistantState.SPEAKING ->
                        if (transcript.isBlank()) "Здравствуйте" else "Вот что я нашла"
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
                text = if (isVoiceSessionActive) {
                    "Голосовой режим включён · нажмите сферу, чтобы перезагрузить"
                } else {
                    "Нажмите на сферу, чтобы включить голосовой режим"
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
    val transition = rememberInfiniteTransition(label = "orb")
    val interactionSource = remember { MutableInteractionSource() }
    
    // Плавная анимация уровня звука
    val animatedLevel by animateFloatAsState(
        targetValue = inputLevel,
        animationSpec = tween<Float>(150),
        label = "level"
    )

    val orbColor by animateColorAsState(
        when (state) {
            AssistantState.ERROR -> Color(0xFFFF8B82)
            AssistantState.THINKING -> AuraCyan
            AssistantState.SPEAKING -> AuraMint
            else -> if (inputLevel > 0.1f) AuraCyan else AuraMint
        },
        label = "color"
    )

    Box(
        modifier = Modifier
            .size(width = 240.dp, height = 240.dp)
            .scale(1f + animatedLevel * 0.2f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Фоновое свечение (VFX)
        repeat(3) { index ->
            val pulseScale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.2f + (index * 0.2f),
                animationSpec = infiniteRepeatable(
                    animation = tween(2000 + index * 500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .scale(pulseScale + animatedLevel * 0.5f)
                    .alpha(0.1f / (index + 1))
                    .background(orbColor, CircleShape)
                    .blur(40.dp)
            )
        }

        // Основная "живая" сфера
        Box(
            Modifier
                .size(160.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(orbColor, AuraSurfaceHigh, AuraBackground)
                    ),
                    CircleShape
                )
                .border(
                    (2.dp + (animatedLevel * 4).dp),
                    Brush.linearGradient(
                        listOf(orbColor, AuraCyan.copy(alpha = 0.5f))
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Эффект "энергии" внутри
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2
                
                for (i in 0 until 360 step 15) {
                    val angle = Math.toRadians(i.toDouble())
                    val lineLen = radius * (0.1f + animatedLevel * 0.4f)
                    val start = Offset(
                        (center.x + (radius - lineLen) * Math.cos(angle)).toFloat(),
                        (center.y + (radius - lineLen) * Math.sin(angle)).toFloat()
                    )
                    val end = Offset(
                        (center.x + radius * Math.cos(angle)).toFloat(),
                        (center.y + radius * Math.sin(angle)).toFloat()
                    )
                    drawLine(
                        color = orbColor.copy(alpha = 0.6f),
                        start = start,
                        end = end,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            
            // Глаза робота (уменьшенные внутри сферы)
            RobotEyes(state, orbColor, inputLevel)
        }
    }
}

@Composable
private fun RobotEyes(state: AssistantState, color: Color, inputLevel: Float) {
    val transition = rememberInfiniteTransition(label = "eyes")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(150),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(2000)
        ),
        label = "blink"
    )

    Canvas(Modifier.size(80.dp, 40.dp)) {
        val eyeWidth = 12.dp.toPx()
        val eyeHeight = 12.dp.toPx()
        val spacing = 30.dp.toPx()
        
        // Левый глаз
        drawOval(
            color = color,
            topLeft = Offset(center.x - spacing / 2 - eyeWidth / 2, center.y - (eyeHeight * blink) / 2),
            size = Size(eyeWidth, eyeHeight * if (state == AssistantState.IDLE) blink else 1f)
        )
        // Правый глаз
        drawOval(
            color = color,
            topLeft = Offset(center.x + spacing / 2 - eyeWidth / 2, center.y - (eyeHeight * blink) / 2),
            size = Size(eyeWidth, eyeHeight * if (state == AssistantState.IDLE) blink else 1f)
        )
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
            isFaceVisible = false,
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
