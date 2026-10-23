package ru.outsideproarts.oassist

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ru.outsideproarts.oassist.core.agent.AppVisibility
import ru.outsideproarts.oassist.core.model.OAssistLog
import ru.outsideproarts.oassist.di.AppGraph

class OAssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = AppVisibility.onActivityResumed()
            override fun onActivityPaused(activity: Activity) = AppVisibility.onActivityPaused()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        OAssistLog.info(TAG, "OAssist запущен, AppGraph инициализирован")
    }

    companion object {
        private const val TAG = "OAssistApp"
    }
}
