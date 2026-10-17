package com.gist.app.overlay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Top-right `⋮` menu (`DropdownMenu`) in the ChatSheet header (§3.3):
 * - **Share** — icon: forked arrow (`Icons.Outlined.Share`)
 * - **Settings** — icon: gear (`Icons.Outlined.Settings`)
 * - *(also)* Copy last answer · Open in ChatGPT/Claude/Gemini app · New chat
 */
@Composable
fun HeaderMenu(
    onShareToGoogleDoc: () -> Unit,
    onOpenSettings: () -> Unit,
    onCopyLastAnswer: () -> Unit,
    onOpenInExternalApp: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .testTag("header_more_menu_button")
                .semantics { contentDescription = "More options" }
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = Color(0xFF44474E)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share to Google Docs"
                    )
                },
                onClick = {
                    expanded = false
                    onShareToGoogleDoc()
                },
                modifier = Modifier.testTag("menu_item_share")
            )

            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Open Settings"
                    )
                },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
                modifier = Modifier.testTag("menu_item_settings")
            )

            HorizontalDivider()

            DropdownMenuItem(
                text = { Text("Copy last answer") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy last answer"
                    )
                },
                onClick = {
                    expanded = false
                    onCopyLastAnswer()
                },
                modifier = Modifier.testTag("menu_item_copy_last")
            )

            DropdownMenuItem(
                text = { Text("Open in ChatGPT/Claude/Gemini app") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = "Open in external AI app"
                    )
                },
                onClick = {
                    expanded = false
                    onOpenInExternalApp()
                },
                modifier = Modifier.testTag("menu_item_handoff")
            )

            DropdownMenuItem(
                text = { Text("New chat") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.AddComment,
                        contentDescription = "Start new chat"
                    )
                },
                onClick = {
                    expanded = false
                    onNewChat()
                },
                modifier = Modifier.testTag("menu_item_new_chat")
            )
        }
    }
}
