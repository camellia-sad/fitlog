package dev.lzk.fitlog.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal fun Modifier.semanticsLabel(label: String) = semantics { contentDescription = label }
