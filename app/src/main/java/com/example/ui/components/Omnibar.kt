package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun Omnibar(
    currentUrl: String,
    isDesktopMode: Boolean,
    isDataSaver: Boolean,
    onUrlSubmit: (String) -> Unit,
    onToggleDesktop: () -> Unit,
    onToggleDataSaver: () -> Unit,
    onGeminiHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(currentUrl) { mutableStateOf(currentUrl) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search or type URL") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onUrlSubmit(text) }),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(onClick = onToggleDesktop) {
            Icon(
                Icons.Default.Computer,
                contentDescription = "Desktop Mode",
                tint = if (isDesktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onToggleDataSaver) {
            Icon(
                Icons.Default.DataUsage,
                contentDescription = "Data Saver",
                tint = if (isDataSaver) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onGeminiHelp) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = "Get help with Gemini",
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}
