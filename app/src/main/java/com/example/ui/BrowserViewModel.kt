package com.example.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrowserViewModel : ViewModel() {

    private val _currentUrl = MutableStateFlow("https://www.google.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _isDesktopMode = MutableStateFlow(false)
    val isDesktopMode: StateFlow<Boolean> = _isDesktopMode.asStateFlow()

    private val _isDataSaver = MutableStateFlow(false)
    val isDataSaver: StateFlow<Boolean> = _isDataSaver.asStateFlow()

    private val _purgeTrigger = MutableStateFlow(0)
    val purgeTrigger: StateFlow<Int> = _purgeTrigger.asStateFlow()

    fun loadUrl(url: String) {
        var finalUrl = url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            finalUrl = "https://$url"
        }
        _currentUrl.value = finalUrl
    }
    
    fun setUrlFromEngine(url: String) {
        _currentUrl.value = url
    }

    fun updateProgress(progress: Int) {
        _progress.value = progress
    }

    fun toggleDesktopMode() {
        _isDesktopMode.value = !_isDesktopMode.value
    }

    fun toggleDataSaver() {
        _isDataSaver.value = !_isDataSaver.value
    }

    fun triggerPurgeAndLoad(url: String) {
        _currentUrl.value = url
        _purgeTrigger.value += 1
    }
}
