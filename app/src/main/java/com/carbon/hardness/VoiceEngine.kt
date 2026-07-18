package com.carbon.hardness

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 연속 음성 인식 + TTS 되읽기.
 * - 항상 듣고 있다가 결과가 나오면 재시작(핸즈프리)
 * - TTS 로 말하는 동안에는 인식을 멈춰 자기 목소리를 되먹지 않게 함
 */
class VoiceEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    private var listening = false
    private var wantListening = false
    private var speaking = false
    private var ttsReady = false

    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(this@VoiceEngine)
        }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { resumeAfterSpeech() }
            @Deprecated("deprecated") override fun onError(utteranceId: String?) { resumeAfterSpeech() }
        })
    }

    private fun resumeAfterSpeech() {
        handler.post {
            speaking = false
            if (wantListening) startListeningInternal()
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    fun start() {
        wantListening = true
        if (!speaking) startListeningInternal()
    }

    fun stop() {
        wantListening = false
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    /** 탭-투-토크: 지금 바로 새로 듣기 시작 */
    fun nudge() {
        wantListening = true
        if (speaking) return
        try { recognizer?.cancel() } catch (_: Exception) {}
        listening = false
        handler.postDelayed({ startListeningInternal() }, 120)
    }

    private fun startListeningInternal() {
        if (listening || speaking || !wantListening) return
        try {
            recognizer?.startListening(buildIntent())
            listening = true
        } catch (_: Exception) {
            scheduleRestart()
        }
    }

    private fun scheduleRestart(delay: Long = 400) {
        listening = false
        if (!wantListening || speaking) return
        handler.postDelayed({ startListeningInternal() }, delay)
    }

    fun speak(text: String) {
        if (!ttsReady) return
        speaking = true
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
        // 안전장치: onDone 이 안 오면 강제 재개
        handler.postDelayed({ if (speaking) resumeAfterSpeech() }, 6000)
    }

    // ---- RecognitionListener ----
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (text.isNotBlank()) onFinal(text)
        scheduleRestart(250)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (text.isNotBlank()) onPartial(text)
    }

    override fun onError(error: Int) {
        val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 400L
        scheduleRestart(delay)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    fun destroy() {
        wantListening = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        tts?.shutdown()
    }
}
