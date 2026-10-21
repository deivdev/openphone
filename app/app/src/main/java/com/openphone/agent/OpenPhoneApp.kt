package com.openphone.agent

import android.app.Application
import com.openphone.agent.llm.ModelDownloader
import com.openphone.agent.llm.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the single model, shared by the app screen and the overlay. */
class OpenPhoneApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var modelManager: ModelManager
        private set

    lateinit var modelDownloader: ModelDownloader
        private set

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(this, appScope)
        modelDownloader = ModelDownloader(this, modelManager)
    }
}
