package com.techno.kurdtube

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.techno.kurdtube.backend.theme.ThemeManager
import com.techno.kurdtube.backend.utils.LOG
import com.techno.kurdtube.frontend.components.MainComponent
import com.techno.kurdtube.frontend.components.currentBrowser
import com.techno.kurdtube.frontend.theme.MainTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    //applies the selected theme and language to all resources of this activity
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        //must run before super.onCreate(), otherwise the splash screen is not installed
        val splashScreen = installSplashScreen()
        val splashStart = SystemClock.uptimeMillis()
        //keep the splash visible briefly so it is not only a single frame
        splashScreen.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() - splashStart < 600L
        }
        super.onCreate(savedInstanceState)
        LOG("onCreate MainActivity")

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.analyticsManager.logAppStart()

        setContent {

            MainTheme(darkTheme = viewModel.getTheme()) {
                MainComponent(
                    viewModel = viewModel,
                    onLanguageChange = { languageCode ->
                        viewModel.setLanguage(languageCode)
                        //recreate so the new resources and layout direction are applied
                        recreate()
                    }
                )
            }

            //lifecycle listener
            LifecycleListener(onChange = { event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> {

                        if (viewModel.hasUpdatesEnabled()) {
                            viewModel.updateManager.checkForUpdates()
                        }
                        viewModel.loadSharedYoutubeUrlInBrowser(intent)
                        if (intent?.action == Intent.ACTION_SEND) {
                            // mark it handled so a recreation does not repeat the share
                            intent.action = Intent.ACTION_MAIN
                        }

                    }
                    Lifecycle.Event.ON_START -> viewModel.playerManager.load(applicationContext)
                    //opens the installer of an update that finished downloading in the background
                    Lifecycle.Event.ON_RESUME -> viewModel.updateManager.resumePendingInstall()
                    Lifecycle.Event.ON_STOP -> viewModel.playerManager.unload()
                    Lifecycle.Event.ON_DESTROY -> currentBrowser = null
                    else -> {}
                }
            })

            //load after all permissions are granted
            val hasMissingPermission = viewModel.permissionManager.getMissingPermission() != null
            LaunchedEffect(hasMissingPermission) {
                if (hasMissingPermission) return@LaunchedEffect

                LOG("all permissions are granted")
                viewModel.libraryManager.loadLibrary()
                viewModel.libraryManager.loadPlaylists()
                viewModel.continuePendingDownload()
            }

            //finish listener
            val finish = viewModel.hasRequestedFinish
            LaunchedEffect(finish) {
                if (!finish) return@LaunchedEffect
                viewModel.hasRequestedFinish = false

                LOG("finish listener")
                finish()
            }

            //back handler
            var lastBackClick = remember { 0L }
            BackHandler {
                val time = System.currentTimeMillis()
                if (time - lastBackClick < 1000L) {
                    finish()
                } else {
                    lastBackClick = time
                    viewModel.showToast(R.string.toast_back_button_close)
                }
            }

        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.loadSharedYoutubeUrlInBrowser(intent)
        if (intent.action == Intent.ACTION_SEND) {
            // mark it handled so a recreation does not repeat the share
            intent.action = Intent.ACTION_MAIN
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        viewModel.screenManager.updateOrientation(newConfig.orientation)
    }

    @Composable
    private fun LifecycleListener(onChange: (Lifecycle.Event) -> Unit) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {

            val observer = LifecycleEventObserver { _, event -> onChange(event) }
            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }

        }
    }

}