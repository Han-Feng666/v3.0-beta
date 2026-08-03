package moe.shizuku.manager

import android.app.Application

lateinit var application: Application
    private set

fun init(app: Application) {
    if (!::application.isInitialized) {
        application = app
    }
}
