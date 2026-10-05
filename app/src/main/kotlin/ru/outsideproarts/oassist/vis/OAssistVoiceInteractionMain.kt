package ru.outsideproarts.oassist.vis

import android.service.voice.VoiceInteractionService

/**
 * VoiceInteractionService: точка входа для assist-жеста.
 * Регистрируется в манифесте с `@xml/voice_interaction_service`.
 */
class OAssistVoiceInteractionService : VoiceInteractionService()
