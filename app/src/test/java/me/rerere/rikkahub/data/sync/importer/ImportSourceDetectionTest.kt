package me.rerere.rikkahub.data.sync.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSourceDetectionTest {
    @Test fun detectsChatGptByMappingTree() {
        val probe = """[{"title":"t","mapping":{"a":{"id":"a"}},"current_node":"a"}]"""
        assertEquals(ImportSource.CHATGPT, ImportSource.detectByContent(probe))
    }

    @Test fun detectsClaudeByChatMessages() {
        val probe = """[{"uuid":"1","name":"t","chat_messages":[]}]"""
        assertEquals(ImportSource.CLAUDE, ImportSource.detectByContent(probe))
    }

    @Test fun detectsLobeChatByExportType() {
        val probe = """{"exportType":"sessions","state":{"sessions":[]},"version":9}"""
        assertEquals(ImportSource.LOBE_CHAT, ImportSource.detectByContent(probe))
    }

    @Test fun detectsNextChatByMasks() {
        val probe = """{"sessions":[{"id":"s"}],"messages":[],"masks":{},"historyMessageCount":4}"""
        assertEquals(ImportSource.NEXTCHAT, ImportSource.detectByContent(probe))
    }

    @Test fun detectsJanByThreadsAndAssistants() {
        val probe = """{"threads":[{"id":"t"}],"assistants":[{"id":"a"}]}"""
        assertEquals(ImportSource.JAN, ImportSource.detectByContent(probe))
    }

    @Test fun unknownContentReturnsNullInsteadOfGuessing() {
        assertEquals(null, ImportSource.detectByContent("""{"foo":"bar"}"""))
    }

    /** 文件名完全认不出时，靠内容也要能识别（很多工具导出的都叫 conversations.json）。 */
    @Test fun contentWinsOverAnonymousFileName() {
        val probe = """[{"uuid":"1","chat_messages":[{"sender":"human","text":"hi"}]}]"""
        assertEquals(ImportSource.CLAUDE, ImportSource.detect("conversations.json", probe))
    }
}
