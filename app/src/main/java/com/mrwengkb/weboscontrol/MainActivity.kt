package com.mrwengkb.weboscontrol

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrwengkb.weboscontrol.core.WebOsClient
import com.mrwengkb.weboscontrol.ui.DevicesScreen
import com.mrwengkb.weboscontrol.ui.RemoteScreen
import com.mrwengkb.weboscontrol.ui.RemoteTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteTheme {
                val state by viewModel.ui.collectAsStateWithLifecycle()
                when (state.screen) {
                    Screen.Devices -> {
                        BackHandler(enabled = state.current != null) { viewModel.showRemote() }
                        DevicesScreen(state, viewModel)
                    }
                    Screen.Remote -> RemoteScreen(state, viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onBackground()
    }

    /** The phone's volume keys control the TV while the remote is connected. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val state = viewModel.ui.value
        if (state.screen == Screen.Remote && state.connection == WebOsClient.State.Connected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { viewModel.volumeUp(); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { viewModel.volumeDown(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
