package com.example.engine

import android.app.Application
import android.os.Process
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class BrowserLifecycleObserver(
    private val app: Application,
    private val onPurgeRequested: () -> Unit
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var reaperJob: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d("BrowserLifecycleObserver", "App in foreground, cancelling reaper.")
        reaperJob?.cancel()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d("BrowserLifecycleObserver", "App in background, starting 2-minute reaper countdown.")
        reaperJob = scope.launch {
            delay(120_000L) // 2 minutes
            Log.d("BrowserLifecycleObserver", "Reaper triggered. Purging...")
            
            onPurgeRequested()
            
            deleteRecursively(app.cacheDir)
            deleteRecursively(app.codeCacheDir)
            
            Log.d("BrowserLifecycleObserver", "Caches wiped. Committing suicide...")
            Process.killProcess(Process.myPid())
        }
    }

    private fun deleteRecursively(fileOrDirectory: File?) {
        if (fileOrDirectory != null && fileOrDirectory.exists()) {
            if (fileOrDirectory.isDirectory) {
                fileOrDirectory.listFiles()?.forEach { child ->
                    deleteRecursively(child)
                }
            }
            fileOrDirectory.delete()
        }
    }
}
