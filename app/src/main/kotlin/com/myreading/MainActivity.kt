package com.myreading

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.myreading.ui.ReaderApp

class MainActivity : ComponentActivity() {
    private val externalEpubUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalEpubUri.value = intent.epubUri()
        setContent {
            ReaderApp(
                externalEpubUri = externalEpubUri.value,
                onExternalEpubConsumed = { externalEpubUri.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalEpubUri.value = intent.epubUri()
    }

    private fun Intent.epubUri(): Uri? {
        return data.takeIf { action == Intent.ACTION_VIEW }
    }
}
