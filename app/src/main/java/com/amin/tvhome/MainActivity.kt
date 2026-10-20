package com.amin.tvhome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repo: AppRepository
    private lateinit var homeSetup: HomeSetup
    private var setupStatus by mutableStateOf<String?>(null)
    private var setupRunning = false

    // Fired when Home is pressed while we're already in front: reset to the top of Apps, like tvOS.
    private val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(applicationContext)
        homeSetup = HomeSetup(applicationContext)
        setContent {
            val apps by repo.apps.collectAsState()
            val settings by repo.settings.collectAsState()
            LauncherTheme {
                LauncherScreen(
                    apps = apps,
                    settings = settings,
                    homePresses = homePresses,
                    cachedArt = repo::cachedArt,
                    loadArt = repo::loadArt,
                    onLaunch = { start(repo.launchIntent(it), it.label) },
                    onAppInfo = { start(repo.appInfoIntent(it), it.label) },
                    onSetting = { start(it.intent, it.label) },
                    setupStatus = setupStatus,
                    onMakeHome = { runSetup(homeSetup::makeHome) },
                    onRestoreHome = { runSetup(homeSetup::restoreStock) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up installs/uninstalls that happened while another app was in front.
        lifecycleScope.launch { repo.refresh() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses.tryEmit(Unit)
    }

    private fun runSetup(action: suspend ((String) -> Unit) -> String) {
        if (setupRunning) return
        setupRunning = true
        lifecycleScope.launch {
            setupStatus = action { setupStatus = it }
            setupRunning = false
        }
    }

    private fun start(intent: Intent, label: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            fail(label, e)
        } catch (e: SecurityException) {
            fail(label, e)
        }
    }

    private fun fail(label: String, e: Exception) {
        Log.e(TAG, "Couldn't open $label", e)
        Toast.makeText(this, "Couldn't open $label", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "TvHome"
    }
}
