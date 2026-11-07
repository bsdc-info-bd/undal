package com.undal.syl.app.core.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.undal.syl.app.core.theme.UndalDimens

/** A titled content card shared by policy, customer and vendor surfaces. */
@Composable
fun UndalSection(
    title: String,
    subtitle: String = "",
    content: @Composable () -> Unit,
) {
    UndalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(UndalDimens.sm)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(UndalDimens.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(UndalDimens.xs))
            content()
        }
    }
}
