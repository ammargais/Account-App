package com.ammargais.accountapp

import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ammargais.accountapp.io.BackgroundServices

fun MainActivity.authenticate(onSuccess: () -> Unit, onError: (String) -> Unit) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        onError("فعّل بصمة أو رمز قفل للشاشة من إعدادات الهاتف أولًا."); return
    }
    val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError("لم يُفتح القفل. يمكنك المحاولة مجددًا.") }
    })
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("فتح دفتر الحسابات").setSubtitle("استخدم البصمة أو رمز قفل هاتفك").setAllowedAuthenticators(allowed).build())
}
@Composable fun LockScreen(activity: MainActivity) {
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("دفتر مقفل", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { activity.authenticate({ activity.unlock() }, { error = it }) }) { Text("فتح بالبصمة أو رمز الهاتف") }
        if (error.isNotBlank()) Text(error, Modifier.padding(top = 16.dp))
    }
}
