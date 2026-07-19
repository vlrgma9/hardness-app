package com.carbon.hardness

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 연속 음성 인식 (수동 on/off).
 * - 온디바이스 인식기(효과음 없음) 우선, 한국어 모델이 없으면 자동으로 일반 인식기로 폴백
 * - 폴백 시 인식 효과음("띠링")은 스트림 뮤트로 억제
 */
class VoiceEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStateChange: (Boolean) -> Unit,
    private val onNotice: (String) -> Unit = {},
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var listening = false      // 개별 세션이 돌고 있는지
    private var wantListening = false  // 사용자가 켜둔 상태인지
    private var muted = false
    private var consecutiveErrors = 0
    var usingOnDevice = false; private set

    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onNotice("이 폰은 음성인식을 지원하지 않아요")
            return
        }
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
        if (usingOnDevice) checkKoreanModel()
    }

    /** 온디바이스 한국어 모델이 있는지 확인. 없으면 다운로드 요청 + 일반 인식으로 전환 */
    private fun checkKoreanModel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val rec = recognizer ?: return
        try {
            rec.checkRecognitionSupport(
                buildIntent(), context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val installed = recognitionSupport.installedOnDeviceLanguages
                            .any { it.startsWith("ko") }
                        if (installed) return
                        val supported = recognitionSupport.supportedOnDeviceLanguages
                            .any { it.startsWith("ko") }
                        if (supported) {
                            try { rec.triggerModelDownload(buildIntent()) } catch (_: Exception) {}
                            onNotice("한국어 음성팩 받는 중 · 당분간 일반 인식 사용")
                        }
                        switchToNetwork(silent = true)
                    }
                    override fun onError(error: Int) { /* 확인 실패 시 그대로 두고 런타임 폴백에 맡김 */ }
                }
            )
        } catch (_: Exception) {}
    }

    /** 온디바이스가 안 되면 일반(네트워크) 인식기로 전환 */
    private fun switchToNetwork(silent: Boolean = false) {
        if (!usingOnDevice) return
        usingOnDevice = false
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceEngine)
            }
        } catch (_: Exception) { null }
        if (!silent) onNotice("일반 인식으로 전환했어요")
        if (wantListening) {
            muteBeeps()
            listening = false
            scheduleRestart(200)
        }
    }

    val isOn get() = wantListening

    fun start() {
        if (recognizer == null) return
        wantListening = true
        consecutiveErrors = 0
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
        consecutiveErrors = 0
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (text.isNotBlank()) onFinal(text)
        scheduleRestart(250)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (text.isNotBlank()) { consecutiveErrors = 0; onPartial(text) }
    }

    override fun onError(error: Int) {
        // NO_MATCH/타임아웃은 말이 없었을 뿐 → 오류로 세지 않음
        val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (!benign) consecutiveErrors++

        // 온디바이스 언어팩 문제 → 즉시 일반 인식으로
        val langProblem = error == 12 /* LANGUAGE_NOT_SUPPORTED */ ||
            error == 13 /* LANGUAGE_UNAVAILABLE */
        if (usingOnDevice && (langProblem || consecutiveErrors >= 4)) {
            consecutiveErrors = 0
            switchToNetwork()
            return
        }
        if (consecutiveErrors >= 6) {
            onNotice("음성인식이 계속 실패해요 (오류 $error) · 마이크를 껐다 켜보세요")
            consecutiveErrors = 0
        }
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
