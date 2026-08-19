package com.picoxr.mrspacetowerdefense.utils

import android.util.Log

object GlobalExceptionHandler {
    private const val TAG = "GlobalExceptionHandler"

    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler is LoggingExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(LoggingExceptionHandler(previousHandler))
    }

    private class LoggingExceptionHandler(
        private val delegate: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            delegate?.uncaughtException(thread, throwable)
        }
    }
}
