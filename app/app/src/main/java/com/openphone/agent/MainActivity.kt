package com.openphone.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openphone.agent.ui.AgentScreen
import com.openphone.agent.ui.theme.OpenPhoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenPhoneTheme {
                val viewModel: AgentViewModel = viewModel()
                AgentScreen(viewModel)
            }
        }
    }
}
