package com.picoxr.mrspacetowerdefense.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.manager.GameStateManager
import com.picoxr.mrspacetowerdefense.manager.GameManager
import com.picoxr.mrspacetowerdefense.manager.GridManager
import com.picoxr.mrspacetowerdefense.manager.GoldManager
import com.picoxr.mrspacetowerdefense.manager.MonsterManager
import com.picoxr.mrspacetowerdefense.manager.SceneLayoutManager
import com.picoxr.mrspacetowerdefense.manager.SpatialManager
import com.picoxr.mrspacetowerdefense.manager.TowerManager
import com.picoxr.mrspacetowerdefense.manager.WaveManager
import com.picoxr.mrspacetowerdefense.manager.WallManager
import com.picoxr.mrspacetowerdefense.manager.UIManager
import com.picoxr.mrspacetowerdefense.manager.SaveManager
import com.picoxr.mrspacetowerdefense.mainApp
import com.picoxr.mrspacetowerdefense.utils.GlobalExceptionHandler

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalExceptionHandler.install()
        EventBus.initialize(applicationContext)
        SaveManager.initialize(applicationContext)
        GameStateManager.initialize(applicationContext)
        GoldManager.initialize(applicationContext)
        SpatialManager.initialize(applicationContext)
        SceneLayoutManager.initialize(applicationContext)
        WallManager.initialize(applicationContext)
        TowerManager.initialize(applicationContext)
        MonsterManager.initialize(applicationContext)
        WaveManager.initialize(applicationContext)
        GridManager.initialize(applicationContext)
        GameManager.initialize(applicationContext)
        UIManager.initialize(applicationContext)
        launch(::mainApp)
    }

    override fun onTerminate() {
        UIManager.destroy()
        GameManager.destroy()
        GridManager.destroy()
        WaveManager.destroy()
        MonsterManager.destroy()
        TowerManager.destroy()
        WallManager.destroy()
        SceneLayoutManager.destroy()
        SpatialManager.destroy()
        GoldManager.destroy()
        GameStateManager.destroy()
        SaveManager.destroy()
        EventBus.destroy()
        super.onTerminate()
    }
}
