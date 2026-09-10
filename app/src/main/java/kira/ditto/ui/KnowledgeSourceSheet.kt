package kira.ditto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kira.ditto.R
import kira.ditto.data.KnowledgeCitation
import kira.ditto.data.markdownSourceHost
import kira.ditto.ui.theme.AetherOnSurface
import kira.ditto.ui.theme.AetherOnSurfaceVariant
import kira.ditto.ui.theme.AetherPrimary
import kira.ditto.ui.theme.AetherSurface
import kira.ditto.ui.theme.AetherSurfaceHigh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeSourceSheet(
    citation: KnowledgeCitation,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AetherSurface,
        contentColor = AetherOnSurface,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 36.dp, end = 24.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_knowledge_source),
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherOnSurfaceVariant,
                )
                if (citation.sourceName.isNotBlank()) {
                    Text(
                        text = citation.sourceName,
                        style = MaterialTheme.typography.titleSmall,
                        color = AetherOnSurface,
                    )
                }
                val excerpt = citation.text.trim()
                if (excerpt.isNotBlank() &&
                    excerpt != citation.sourceName &&
                    excerpt != citation.url
                ) {
                    SelectionContainer {
                        Text(
                            text = excerpt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(AetherSurfaceHigh)
                                .padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AetherOnSurfaceVariant,
                        )
                    }
                } else if (excerpt.isBlank() && citation.url.isBlank()) {
                    Text(
                        text = stringResource(R.string.chat_knowledge_source_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(AetherSurfaceHigh)
                            .padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant,
                    )
                }
                if (citation.url.isNotBlank()) {
                    val host = markdownSourceHost(citation.url).ifBlank { citation.url }
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AetherSurfaceHigh)
                            .clickable {
                                onOpenLink(citation.url)
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            AetherSheetDragHandleScrim()
        }
    }
}
