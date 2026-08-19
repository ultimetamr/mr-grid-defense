package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

abstract class BaseManager {
    private var appContext: Context? = null
    private var rootJob: Job? = null
    private var rootScope: CoroutineScope? = null

    protected val applicationContext: Context
        get() = checkNotNull(appContext) { "${this::class.simpleName} is not initialized" }

    protected val managerScope: CoroutineScope
        get() = checkNotNull(rootScope) { "${this::class.simpleName} is not initialized" }

    val isInitialized: Boolean
        get() = appContext != null

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        val application = context.applicationContext
        val job = SupervisorJob()
        appContext = application
        rootJob = job
        rootScope = CoroutineScope(job + Dispatchers.Main.immediate)

        runCatching { onInitialize(application) }
            .onFailure {
                rootScope?.cancel()
                rootScope = null
                rootJob = null
                appContext = null
                throw it
            }
    }

    /**
     * Creates a child scope whose Job is automatically cancelled when [owner] is destroyed.
     * Manager-wide work remains alive until [destroy] is called.
     */
    fun createPageScope(
        owner: LifecycleOwner,
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ): CoroutineScope {
        val parentJob = checkNotNull(rootJob) { "${this::class.simpleName} is not initialized" }
        val pageJob = SupervisorJob(parentJob)
        val pageScope = CoroutineScope(pageJob + dispatcher)
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    pageJob.cancel()
                }
            }
        owner.lifecycle.addObserver(observer)
        pageJob.invokeOnCompletion { owner.lifecycle.removeObserver(observer) }
        return pageScope
    }

    @Synchronized
    fun destroy() {
        if (!isInitialized) return
        try {
            onDestroy()
        } finally {
            rootScope?.cancel()
            rootScope = null
            rootJob = null
            appContext = null
        }
    }

    protected open fun onInitialize(applicationContext: Context) = Unit

    protected open fun onDestroy() = Unit
}
