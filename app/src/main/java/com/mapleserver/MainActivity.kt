package com.mapleserver

import MainCompose
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mapleserver.ui.theme.MapleServerTheme
import com.mapleserver.ui.theme.ServerConfigScreen
import java.io.File
import java.io.FileOutputStream


class MainActivity : ComponentActivity() {
    private val mainViewModel by lazy { MainViewModel(this@MainActivity) }
    private lateinit var navController: NavHostController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationBugOnXiaomiDevices()
        val preferences = getSharedPreferences("MyPrefs", MODE_PRIVATE)
        val isFirstRun = preferences.getBoolean("isFirstRun", true)
        if (isFirstRun) {
            copyAssetFileApplication("config.yaml")
            preferences.edit().putBoolean("isFirstRun", false).apply()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent()
    }

    override fun onStart() {
        super.onStart()
        if (!mainViewModel.isServiceBound) {
            Intent(this, ServerService::class.java).also { intent ->
                bindService(intent, mainViewModel.connection, Context.BIND_AUTO_CREATE)
                mainViewModel.isServiceBound = true
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (mainViewModel.isServiceBound) {
            unbindService(mainViewModel.connection)
            mainViewModel.isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mainViewModel.isServiceBound) {
            SharedUtil.stopMapleServer(this, mainViewModel.serviceIntent, mainViewModel.connection)
            mainViewModel.isServiceBound = false
        }
    }

    private fun copyAssetFileApplication(assetFileName: String) {
        try {
            val appDir: File = applicationContext.dataDir
            val yamlConfig = File(appDir, "config.yaml")
            val inputStream = assets.open(assetFileName)
            val outputStream = FileOutputStream(yamlConfig)

            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            inputStream.close()
            outputStream.close()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setContent() {
        setContent {
            MapleServerTheme {
                navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "main_screen"
                ) {
                    composable("main_screen") {
                        MainCompose(this@MainActivity, navController, mainViewModel)
                    }
                    composable("config_editor_screen") {
                        ServerConfigScreen(this@MainActivity, navController, mainViewModel)
                    }
                    composable("export_db_screen") {
                        ExportDBCompose(this@MainActivity, navController)
                    }
                    composable("import_db_screen") {
                        ImportDBCompose(this@MainActivity, navController)
                    }
                }
            }
        }
    }

    fun handleNavigationBugOnXiaomiDevices() {
        window.decorView.post {
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
}