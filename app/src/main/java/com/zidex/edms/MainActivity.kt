package com.zidex.edms

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zidex.edms.ui.ChangePasswordForm
import com.zidex.edms.ui.EdmsTheme
import com.zidex.edms.ui.HomeShell
import com.zidex.edms.ui.LoginScreen
import com.zidex.edms.ui.Session
import com.zidex.edms.ui.aboveKeyboard
import kotlinx.coroutines.delay

/**
 * Single-activity host. Login screen → (local) archive shell, or → desktop
 * link (RemoteActivity). `edms://pair?...` deep links land on the login
 * screen's desktop tab with a confirmation dialog.
 */
class MainActivity : ComponentActivity() {
    private var pendingPair by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // screens pad for system bars themselves
        pendingPair = intent?.data?.toString()
        setContent {
            EdmsTheme {
                // aboveKeyboard() on the root is the single fix that covers every
                // input in the app: the window draws edge to edge, so without it a
                // field near the bottom sat behind the soft keyboard. Scrollable
                // screens add imeNestedScroll on top so a focused field further up
                // the page is scrolled into view.
                Surface(
                    Modifier.fillMaxSize().aboveKeyboard(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val user = Session.user
                    when {
                        user == null -> LoginScreen(pendingPair) { pendingPair = null }
                        user.mustChangePassword -> AlertDialog(
                            onDismissRequest = {},
                            title = { Text("تغيير كلمة المرور") },
                            text = { ChangePasswordForm(forced = true) {} },
                            confirmButton = {},
                        )
                        else -> HomeShell()
                    }
                    Session.message?.let { msg ->
                        LaunchedEffect(msg) { delay(3500); Session.message = null }
                        Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
                            Snackbar { Text(msg) }
                        }
                    }
                }
            }
        }
        Updater.check(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Shown on the login screen's desktop tab (immediately, or after sign-out).
        intent.data?.toString()?.let { pendingPair = it }
    }
}
