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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.my.ui.theme.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

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
    private val userHistory = mutableMapOf<Int, String>()
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
        if (shouldKeepListening() && (state == AssistantState.IDLE || state == AssistantState.LISTENING)) startListening()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoiceSession() else showError("Разрешите доступ к микрофону")
        }

    @SuppressLint("UnsafeOptInUsageError")
    @androidx.camera.core.ExperimentalGetImage
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) faceScanner?.bind(null, this, this)
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

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceSession()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleVoiceTap() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
        isFaceVisible = trackingId != null
        if (trackingId == null) {
            currentTrackingId = null
            wasFaceVisible = false
            return
        }
        if (trackingId == currentTrackingId && wasFaceVisible) return
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

    private fun stopCurrentListening() {
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(submitSpeechRunnable)
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun stopVoiceSession() {
        stopCurrentListening()
        accumulatedSpeech = ""
        inputLevel = 0f
        runCatching { textToSpeech?.stop() }
        startVoiceSession()
    }

    private fun startListening() {
        if (!shouldKeepListening()) return
        if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) return
        
        stopCurrentListening()
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    state = AssistantState.LISTENING
                    statusMessage = if (accumulatedSpeech.isBlank()) "Слушаю вас (RU/KG)…" else "Можете продолжить…"
                    inputLevel = 0f
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    inputLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    inputLevel = 0f
                    if (shouldKeepListening()) {
                        state = AssistantState.LISTENING
                    }
                }
                override fun onError(error: Int) {
                    inputLevel = 0f
                    if (!shouldKeepListening()) return
                    scheduleListeningRestart(200L)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) {
                        if (isWaitingForName) {
                            currentTrackingId?.let { id -> userNames[id] = text }
                            isWaitingForName = false
                            speak("Очень приятно, $text! Чем я могу вам помочь?")
                            transcript = text
                            return
                        }
                        accumulatedSpeech = listOf(accumulatedSpeech, text).filter { it.isNotBlank() }.joinToString(" ")
                        transcript = accumulatedSpeech
                        scheduleQuestionSubmission()
                    } else {
                        scheduleListeningRestart(300L)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    transcript = listOf(accumulatedSpeech, partial).filter { it.isNotBlank() }.joinToString(" ")
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("ru-RU", "ky-KG"))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            })
        }
    }

    private fun scheduleQuestionSubmission() {
        mainHandler.removeCallbacks(submitSpeechRunnable)
        mainHandler.postDelayed(submitSpeechRunnable, 1400L)
    }

    private fun scheduleListeningRestart(delay: Long) {
        if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) return
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed(restartListeningRunnable, delay)
    }

    private fun submitAccumulatedSpeech() {
        val question = accumulatedSpeech.trim()
        if (question.isBlank()) return
        accumulatedSpeech = ""
        sendQuestion(question)
    }

    private fun sendQuestion(question: String) {
        stopCurrentListening()
        transcript = question
        answer = ""
        state = AssistantState.THINKING
        statusMessage = "Думаю…"

        thread(name = "dify-request") {
            runCatching { DifyClient.ask(question, conversationId) }
                .onSuccess { result ->
                    mainHandler.post {
                        conversationId = result.conversationId
                        answer = result.answer
                        currentTrackingId?.let { id -> userHistory[id] = result.answer.take(50) }
                        speak(result.answer)
                    }
                }
                .onFailure { error ->
                    mainHandler.post { showError(error.message ?: "Ошибка Dify") }
                }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        stopCurrentListening()
        
        val isKyrgyz = text.any { it in 'Ө'..'ө' || it in 'Ң'..'ң' || it in 'Ү'..'ү' }
        textToSpeech?.language = if (isKyrgyz) Locale("ky", "KG") else Locale("ru", "RU")

        state = AssistantState.SPEAKING
        statusMessage = "Отвечаю…"
        
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "answer")
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        
        if (isTtsReady) {
            runCatching { textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "answer") }
                .onFailure { 
                    state = AssistantState.IDLE
                    startListening() 
                }
        } else {
            pendingSpeech = text
            initTts()
        }
    }

    private fun initTts() {
        textToSpeech?.shutdown()
        textToSpeech = TextToSpeech(this, this)
    }

    private fun stopSpeaking() {
        runCatching { textToSpeech?.stop() }
        state = AssistantState.IDLE
        startListening()
    }

    private fun showError(message: String) {
        state = AssistantState.ERROR
        statusMessage = message
        mainHandler.postDelayed({ if (state == AssistantState.ERROR) state = AssistantState.IDLE }, 3000)
    }

    private fun shouldKeepListening(): Boolean = isActivityVisible

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceSession()
    }

    override fun onStop() {
        isActivityVisible = false
        stopCurrentListening()
        super.onStop()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = textToSpeech ?: return
            engine.language = Locale("ru", "RU")
            val femaleVoice = engine.voices?.find { it.name.lowercase().contains("female") || it.name.lowercase().contains("-f-") }
            if (femaleVoice != null) engine.voice = femaleVoice
            engine.setPitch(1.3f)
            engine.setSpeechRate(1.0f)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) { mainHandler.post { state = AssistantState.IDLE; startListening() } }
                override fun onDone(utteranceId: String?) { mainHandler.post { state = AssistantState.IDLE; startListening() } }
            })
            isTtsReady = true
            pendingSpeech?.let { speak(it); pendingSpeech = null }
        } else {
            isTtsReady = false
        }
    }

    override fun onDestroy() {
        stopCurrentListening()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}

private object DifyClient {
    data class Result(val answer: String, val conversationId: String)
    fun ask(question: String, conversationId: String): Result {
        val baseUrl = BuildConfig.DIFY_BASE_URL.trimEnd('/').removeSuffix("/v1")
        val endpoint = if (BuildConfig.DIFY_API_MODE == "workflow") "$baseUrl/v1/workflows/run" else "$baseUrl/v1/chat-messages"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.DIFY_API_KEY}")
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().apply {
            put("inputs", if (BuildConfig.DIFY_API_MODE == "workflow") JSONObject().put("query", question) else JSONObject())
            if (BuildConfig.DIFY_API_MODE != "workflow") put("query", question)
            put("response_mode", "blocking")
            put("user", "user")
            if (conversationId.isNotBlank()) put("conversation_id", conversationId)
        }.toString()
        connection.outputStream.bufferedWriter().use { it.write(body) }
        val response = if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else connection.errorStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val answer = if (BuildConfig.DIFY_API_MODE == "workflow") json.optJSONObject("data")?.optJSONObject("outputs")?.optString("text") ?: "" else json.optString("answer")
        return Result(answer, json.optString("conversation_id"))
    }
}

@Composable
private fun VoiceAssistantScreen(
    state: AssistantState, transcript: String, answer: String, statusMessage: String,
    inputLevel: Float, isVoiceSessionActive: Boolean, isConfigured: Boolean,
    onVoiceTap: () -> Unit, onSuggestion: (String) -> Unit, onStopSpeaking: () -> Unit, onReplayAnswer: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF12342F), AuraBackground))).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(isConfigured)
            Spacer(Modifier.weight(1f))
            Text(text = when(state) {
                AssistantState.LISTENING -> "Я слушаю"
                AssistantState.THINKING -> "Секунду…"
                AssistantState.SPEAKING -> "Отвечаю"
                else -> "Нурай"
            }, style = MaterialTheme.typography.displaySmall, color = AuraText)
            Text(text = statusMessage, color = AuraTextMuted)
            Spacer(Modifier.height(32.dp))
            NurAiAvatar(state, inputLevel, onVoiceTap)
            Spacer(Modifier.height(32.dp))
            ConversationCard(transcript, answer, state, onStopSpeaking, onReplayAnswer)
            Spacer(Modifier.weight(1f))
            if (transcript.isEmpty()) Suggestions(onSuggestion)
        }
    }
}

@Composable
private fun NurAiAvatar(state: AssistantState, inputLevel: Float, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition()
    val animatedLevel by animateFloatAsState(inputLevel, tween(100))
    val breathing by transition.animateFloat(0f, 10f, infiniteRepeatable(tween(2000), RepeatMode.Reverse))
    
    Box(modifier = Modifier.size(280.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AmbientGlowEffect(state, animatedLevel)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = center
            // Лицо
            drawOval(color = AuraSurfaceHigh, topLeft = Offset(center.x - 90.dp.toPx(), center.y - 110.dp.toPx() + breathing), size = Size(180.dp.toPx(), 220.dp.toPx()))
            drawOval(color = AuraMint.copy(alpha = 0.3f), topLeft = Offset(center.x - 90.dp.toPx(), center.y - 110.dp.toPx() + breathing), size = Size(180.dp.toPx(), 220.dp.toPx()), style = Stroke(2.dp.toPx()))
            
            // Глаза
            val eyeY = center.y - 30.dp.toPx() + breathing
            val blink = if (state == AssistantState.IDLE && (System.currentTimeMillis() % 4000 > 3800)) 0.1f else 1f
            drawCircle(AuraCyan, 8.dp.toPx(), Offset(center.x - 40.dp.toPx(), eyeY), alpha = blink)
            drawCircle(AuraCyan, 8.dp.toPx(), Offset(center.x + 40.dp.toPx(), eyeY), alpha = blink)
            
            // Рот
            val mouthY = center.y + 40.dp.toPx() + breathing
            val mHeight = if (state == AssistantState.SPEAKING) 5.dp.toPx() + animatedLevel * 30.dp.toPx() else 2.dp.toPx()
            drawOval(AuraCyan, topLeft = Offset(center.x - 20.dp.toPx(), mouthY - mHeight/2), size = Size(40.dp.toPx(), mHeight))
        }
    }
}

@Composable
private fun AmbientGlowEffect(state: AssistantState, intensity: Float) {
    val transition = rememberInfiniteTransition()
    val scale by transition.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(2000), RepeatMode.Reverse))
    val color = if (state == AssistantState.THINKING) AuraCyan else AuraMint
    Box(Modifier.size(300.dp).scale(scale + intensity).alpha(0.1f).blur(50.dp).background(color, CircleShape))
}

@Composable
private fun ConversationCard(transcript: String, answer: String, state: AssistantState, onStop: () -> Unit, onReplay: () -> Unit) {
    if (transcript.isEmpty() && answer.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(24.dp)).background(AuraSurface.copy(alpha = 0.8f)).verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (transcript.isNotEmpty()) Text("ВЫ: $transcript", color = AuraText)
        if (answer.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("NurAI: $answer", color = AuraCyan)
        }
    }
}

@Composable
private fun Suggestions(onSuggestion: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Документы?", "Наследство", "Нотариус").forEach {
            Surface(modifier = Modifier.clickable { onSuggestion(it) }, shape = RoundedCornerShape(12.dp), color = AuraSurface) {
                Text(it, modifier = Modifier.padding(12.dp, 8.dp), color = AuraText)
            }
        }
    }
}

@Composable
private fun Header(isConfigured: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp).background(AuraMint, CircleShape), contentAlignment = Alignment.Center) { Text("N") }
        Spacer(Modifier.width(12.dp))
        Text("NurAI", fontWeight = FontWeight.Bold, color = AuraText)
        Spacer(Modifier.weight(1f))
        Text(if (isConfigured) "Online" else "Demo", color = AuraMint)
    }
}
