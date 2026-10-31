package dev.komichi.agent

import dev.komichi.agent.agent.ResponseAccumulator
import dev.komichi.agent.agent.SseReader
import dev.komichi.agent.agent.ToolCall
import dev.komichi.agent.agent.ToolCatalog
import dev.komichi.agent.data.obj
import dev.komichi.agent.security.Privacy
import dev.komichi.agent.auth.officialRequest
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class AgentSecurityTest {
    private fun call(name: String, args: org.json.JSONObject = obj(), namespace: String = "android") = ToolCall("call_one", namespace, name, args)
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun onlyOfficialHttpsEndpointsAreAccepted() {
        rejects { officialRequest("https://chatgpt.com/backend-api/codex/responses") }
        rejects { officialRequest("https://api.openai.com.evil.example/v1/responses") }
        rejects { officialRequest("http://api.openai.com/v1/responses") }
        rejects { officialRequest("https://api.openai.com:444/v1/responses") }
        assertEquals("api.openai.com", officialRequest("https://api.openai.com/v1/responses").build().url.host)
    }
    @Test fun rejectsShellInjectionUnknownToolsAndExtraArguments() {
        rejects { ToolCatalog.validate(call("shell",obj("command" to "id"))) }
        rejects { ToolCatalog.validate(call("open_app", obj("package" to "com.example.app; reboot"))) }
        rejects { ToolCatalog.validate(call("open_app", obj("package" to "com.example.app", "auto_approve" to "true"))) }
        rejects { ToolCatalog.validate(call("open_app", obj("package" to 123))) }
        rejects { ToolCatalog.validate(call("open_app", obj("package" to "com.example.app"), "system")) }
        ToolCatalog.validate(call("open_app", obj("package" to "com.example.app")))
    }
    @Test fun rejectsSensitiveInputAndOutOfRangeEnums() {
        val snapshot = "d2d58f8b-a6a6-4a4e-9dbf-ea4451011020"
        rejects { ToolCatalog.validate(call("input_text",obj("snapshot_id" to snapshot,"element_id" to "n0","text" to "OTP 123456"))) }
        rejects { ToolCatalog.validate(call("swipe_screen",obj("snapshot_id" to snapshot,"direction" to "diagonal"))) }
        rejects { ToolCatalog.validate(call("tap_element",obj("snapshot_id" to "stale","element_id" to "n0"))) }
        ToolCatalog.validate(call("input_text",obj("snapshot_id" to snapshot,"element_id" to "n0","text" to "hello")))
    }
    @Test fun masksSecretsAndKeepsOrdinaryText() {
        val masked = Privacy.mask("mail alex@example.com\nOTP 123456\ncard 4111 1111 1111 1111")
        assertFalse(masked.contains("alex@example.com")); assertFalse(masked.contains("123456")); assertFalse(masked.contains("4111"))
        assertEquals("hello world", Privacy.mask("hello world"))
    }
    @Test fun neverExecutesToolsFromPartialOrFailedStreams() {
        val accumulator = ResponseAccumulator()
        accumulator.accept(obj("type" to "response.output_item.done", "item" to obj("type" to "function_call", "name" to "open_app")))
        rejects { accumulator.finish() }
        rejects { accumulator.accept(obj("type" to "response.failed", "response" to obj("error" to obj("code" to "subscription_sharing_usage_limit_exceeded")))) }
        rejects { accumulator.finish() }
    }
    @Test fun completedResponseValidatesEveryToolBeforeReturningAny() {
        val good = obj("type" to "function_call", "namespace" to "android", "call_id" to "one", "name" to "open_app", "arguments" to obj("package" to "com.example.app").toString())
        val bad = obj("type" to "function_call", "namespace" to "android", "call_id" to "two", "name" to "shell", "arguments" to "{}")
        val accumulator = ResponseAccumulator()
        accumulator.accept(obj("type" to "response.completed", "response" to obj("status" to "completed", "output" to JSONArray().put(good).put(bad))))
        rejects { accumulator.finish() }
    }
    @Test fun consumesMultilineSseAndRequiresCompletedNotDone() = runTest {
        val partial = ResponseAccumulator()
        SseReader.read(StringReader("data: {\"type\":\"response.output_text.delta\",\n" + "data: \"delta\":\"hello\"}\n\ndata: [DONE]\n\n")) { partial.accept(it) }
        rejects { partial.finish() }
        val complete = ResponseAccumulator()
        val output = JSONArray().put(obj("type" to "message", "content" to JSONArray().put(obj("type" to "output_text", "text" to "done"))))
        val event = obj("type" to "response.completed", "response" to obj("status" to "completed", "output" to output))
        SseReader.read(StringReader("data: $event\r\n\r\ndata: [DONE]\r\n\r\n")) { complete.accept(it) }
        assertEquals("done", complete.finish().text); assertTrue(complete.finish().calls.isEmpty())
    }
}
