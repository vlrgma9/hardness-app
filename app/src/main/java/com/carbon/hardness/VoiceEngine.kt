package com.carbon.hardness

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 연속 음성 인식 (수동 on/off).
 * - 가능하면 온디바이스 전용 인식기 사용: 효과음("띠링")이 아예 없고 오프라인·연속 인식에 적합
 * - 구형 폰은 일반 인식기 + 효과음 스트림 뮤트로 폴백
 */
class VoiceEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStateChange: (Boolean) -> Unit,
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var listening = false      // 개별 세션이 돌고 있는지
    private var wantListening = false  // 사용자가 켜둔 상태인지
    private var muted = false
    var usingOnDevice = false; private set

    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        recognizer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                usingOnDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (_: Exception) {
            usingOnDevice = false
            try { SpeechRecognizer.createSpeechRecognizer(context) } catch (_: Exception) { null }
        }
        recognizer?.setRecognitionListener(this)
    }

    val isOn get() = wantListening

    fun start() {
        if (recognizer == null) return
        wantListening = true
        if (!usingOnDevice) muteBeeps()   // 온디바이스는 효과음이 없어 뮤트 불필요
        onStateChange(true)
        startListeningInternal()
    }

    fun stop() {
        wantListening = false
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        unmuteBeeps()
        onStateChange(false)
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

    private fun startListeningInternal() {
        if (listening || !wantListening) return
        try {
            recognizer?.startListening(buildIntent())
            listening = true
        } catch (_: Exception) {
            scheduleRestart()
        }
    }

    private fun scheduleRestart(delay: Long = 350) {
        listening = false
        if (!wantListening) return
        handler.postDelayed({ startListeningInternal() }, delay)
    }

    /** 인식 시작/종료 시스템 효과음 뮤트 (폴백 인식기용) — 스트림별 개별 시도 */
    private val beepStreams = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
    )

    private fun muteBeeps() {
        if (muted) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (s in beepStreams) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) {}
        }
        muted = true
    }

    private fun unmuteBeeps() {
        if (!muted) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (s in beepStreams) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        muted = false
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
        val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800L else 350L
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
        unmuteBeeps()
        try { recognizer?.destroy() } catch (_: Exception) {}
    }
}
