package com.gist.app.llm.sse

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class SseEvent(
    val event: String? = null,
    val data: String
)

/**
 * Parses a Server-Sent Events (SSE) stream from a Ktor [ByteReadChannel] or sequence of lines.
 * Handles chunk boundaries, `event:` headers, multi-line `data:` payloads, comments (`:`), and `[DONE]`.
 */
object SseParser {

    fun parseChannel(channel: ByteReadChannel): Flow<SseEvent> = flow {
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()

        while (!channel.isClosedForRead) {
            val rawLine = channel.readUTF8Line() ?: break
            val line = rawLine.trimEnd('\r')

            if (line.isEmpty()) {
                if (dataLines.isNotEmpty()) {
                    val payload = dataLines.joinToString("\n")
                    dataLines.clear()
                    val ev = currentEvent
                    currentEvent = null
                    if (payload.trim() == "[DONE]") {
                        break
                    }
                    emit(SseEvent(event = ev, data = payload))
                } else {
                    currentEvent = null
                }
                continue
            }

            if (line.startsWith(":")) {
                // SSE comment / keep-alive
                continue
            }

            when {
                line.startsWith("event:") -> {
                    currentEvent = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    val content = line.removePrefix("data:")
                    dataLines.add(if (content.startsWith(" ")) content.substring(1) else content)
                }
            }
        }

        if (dataLines.isNotEmpty()) {
            val payload = dataLines.joinToString("\n")
            if (payload.trim() != "[DONE]") {
                emit(SseEvent(event = currentEvent, data = payload))
            }
        }
    }

    /**
     * Pure helper for parsing an arbitrary sequence of text chunks (used in unit tests for chunk-boundary verification).
     */
    fun parseChunks(chunks: Iterable<String>): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        val buffer = StringBuilder()
        var currentEvent: String? = null
        val dataLines = mutableListOf<String>()

        fun processLine(rawLine: String) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty()) {
                if (dataLines.isNotEmpty()) {
                    val payload = dataLines.joinToString("\n")
                    dataLines.clear()
                    val ev = currentEvent
                    currentEvent = null
                    if (payload.trim() != "[DONE]") {
                        events.add(SseEvent(event = ev, data = payload))
                    }
                } else {
                    currentEvent = null
                }
                return
            }
            if (line.startsWith(":")) return
            when {
                line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    val content = line.removePrefix("data:")
                    dataLines.add(if (content.startsWith(" ")) content.substring(1) else content)
                }
            }
        }

        for (chunk in chunks) {
            buffer.append(chunk)
            var newlineIdx = buffer.indexOf("\n")
            while (newlineIdx != -1) {
                val line = buffer.substring(0, newlineIdx)
                buffer.delete(0, newlineIdx + 1)
                processLine(line)
                newlineIdx = buffer.indexOf("\n")
            }
        }
        if (buffer.isNotEmpty()) {
            processLine(buffer.toString())
        }
        if (dataLines.isNotEmpty()) {
            val payload = dataLines.joinToString("\n")
            if (payload.trim() != "[DONE]") {
                events.add(SseEvent(event = currentEvent, data = payload))
            }
        }
        return events
    }
}
