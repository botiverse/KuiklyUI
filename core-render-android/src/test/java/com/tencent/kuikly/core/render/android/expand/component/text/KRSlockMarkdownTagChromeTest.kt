package com.tencent.kuikly.core.render.android.expand.component.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KRSlockMarkdownTagChromeTest {

    @Test
    fun ordinaryMentionUsesTextUnderlineInsteadOfAtomicChipChrome() {
        assertFalse(SLOCK_MARKDOWN_TAG_KIND_ORDINARY_MENTION.isSlockMarkdownTagChipChrome())
        assertFalse((null as String?).isSlockMarkdownTagChipChrome())
    }

    @Test
    fun actualChipKindsKeepAtomicLayoutAndPaintChrome() {
        listOf("channel", "thread", "task", "selfMention", "active").forEach { kind ->
            assertTrue(kind, kind.isSlockMarkdownTagChipChrome())
        }
    }
}
