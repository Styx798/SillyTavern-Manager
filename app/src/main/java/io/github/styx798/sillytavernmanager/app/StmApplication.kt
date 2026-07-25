package io.github.styx798.sillytavernmanager.app

import android.app.Application

class StmApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.NONE) {
        DefaultAppContainer(applicationContext)
    }
}
