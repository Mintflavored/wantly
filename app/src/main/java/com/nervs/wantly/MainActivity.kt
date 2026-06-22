package com.nervs.wantly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nervs.wantly.navigation.WantlyNavHost
import com.nervs.wantly.ui.theme.WantlyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WantlyTheme {
                WantlyNavHost()
            }
        }
    }
}
