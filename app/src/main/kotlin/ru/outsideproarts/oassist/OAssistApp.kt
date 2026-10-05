package ru.outsideproarts.oassist

import android.app.Application
import ru.outsideproarts.oassist.core.model.OAssistLog
import ru.outsideproarts.oassist.di.AppGraph

class OAssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        OAssistLog.info(TAG, "OAssist запущен, AppGraph инициализирован")
    }

    companion object {
        private const val TAG = "OAssistApp"
    }
}
