package ru.outsideproarts.oassist.share

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.text.SpannedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.outsideproarts.oassist.core.agent.*
import ru.outsideproarts.oassist.core.data.chat.*
import ru.outsideproarts.oassist.core.data.db.OAssistDatabase
import ru.outsideproarts.oassist.core.data.secret.SecretStore
import ru.outsideproarts.oassist.core.model.*
import ru.outsideproarts.oassist.core.model.diagnostics.Diagnostics
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.provider.ModelClient
import ru.outsideproarts.oassist.feature.share.ShareActivity
import ru.outsideproarts.oassist.feature.share.ShareSessionViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ShareIncomingPayloadUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rejectedAndSupportedAttachmentsReachRealScreenAndRequest() {
        val oldFactory = AgentFactory.get()
        val oldRepository = checkNotNull(ChatHandle.get())
        val oldNetwork = checkNotNull(NetworkHandle.get())
        val db = Room.inMemoryDatabaseBuilder(context, OAssistDatabase::class.java).build()
        val mediaRoot = File(context.cacheDir, "share-ui-fixture-${System.nanoTime()}")
        val repository = ChatRepository(db, ChatMediaStore(mediaRoot))
        val requests = CopyOnWriteArrayList<JsonObject>()
        val engine = SseEngine(MockEngineConfig().apply { requestHandlers.add { request ->
            requests += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("event: message_start\ndata: {\"type\":\"message_start\"}\n\nevent: content_block_start\ndata: {\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"done\"}}\n\nevent: content_block_stop\ndata: {\"index\":0}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        } })
        val http = HttpClient(engine) {
            install(SSE)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }) }
        }
        val profile = ProviderProfile("share-test", "share-test", ProviderPreset.CUSTOM, "https://api.anthropic.com/v1", protocol = ModelProtocol.ANTHROPIC_MESSAGES)
        val client = kotlinx.coroutines.runBlocking { ModelClient.create(Diagnostics, profile, EmptySecrets, http) }
        val factory = object : AgentFactory by oldFactory {
            override suspend fun modeConfig(mode: AssistMode) = ModeConfig(mode, modelId = "test", soundEnabled = false, autoSaveChats = false)
            override suspend fun providerForMode(mode: AssistMode) = profile
            override suspend fun modelClientFor(profile: ProviderProfile): ModelClient = error("Title generation disabled in fixture")
            override suspend fun agentFor(profile: ProviderProfile, mode: AssistMode) = AgentLoop(client, ToolRegistry(emptyList(), diagnostics = Diagnostics), approvalPolicy = InMemoryApprovalPolicy(), sessionScope = mode.name, diagnostics = Diagnostics)
            override suspend fun systemPromptFor(mode: AssistMode, screenContext: String?, sharedContent: String?) = RuntimePrompt("system")
            override fun usageTotals() = MutableStateFlow(UsageTotalsState())
            override fun clearSessionApprovals(mode: AssistMode) = Unit
            override fun resetUsageTotals() = Unit
            override suspend fun recordModelUsage(usage: ProviderUsage) = Unit
        }
        val uris = mutableListOf<Uri>()
        fun item(name: String, mime: String, bytes: ByteArray): Uri {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime) }
            val uri = checkNotNull(context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values))
            uris += uri
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return uri
        }
        val png = java.io.ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } finally { bitmap.recycle() }
            out.toByteArray()
        }
        try {
            AgentFactory.register(factory)
            ChatHandle.register(repository)
            NetworkHandle.register(object : NetworkHandle { override val isOnline = MutableStateFlow(true) })
            val image = item("fixture.png", "image/png", png)
            val pdf = item("fixture.pdf", "application/pdf", "%PDF".toByteArray())
            val unknown = item("fixture.bin", "application/octet-stream", byteArrayOf(1, 2))
            val bad = item("broken.png", "image/png", byteArrayOf(1, 2))
            val cases = listOf(listOf(pdf), listOf(unknown), listOf(image, pdf), List(5) { image }, listOf(bad))
            fun launch(streams: List<Uri>): ActivityScenario<ShareActivity> {
                val intent = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
                    .putExtra(Intent.EXTRA_TEXT, SpannedString("styled fixture text"))
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))
                return ActivityScenario.launch(intent)
            }
            for (streams in cases) {
                launch(streams).use { scenario ->
                    val label = context.getString(ru.outsideproarts.oassist.feature.share.R.string.share_unsupported_file)
                    compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText(label).assertIsDisplayed()
                    compose.onNodeWithText(context.getString(ru.outsideproarts.oassist.core.ui.R.string.chats_new)).performClick()
                    val question = compose.onNodeWithText(context.getString(ru.outsideproarts.oassist.feature.share.R.string.share_ask_label))
                    question.performTextInput("editable question")
                    compose.onNodeWithText("editable question").assertExists()
                    compose.onNodeWithContentDescription(context.getString(ru.outsideproarts.oassist.feature.share.R.string.share_send)).assertIsNotEnabled()
                    compose.onNodeWithText(label).assertIsDisplayed()
                    scenario.onActivity { assertTrue(ViewModelProvider(it)[ShareSessionViewModel::class.java].turns.isEmpty()) }
                    assertTrue(requests.isEmpty())
                }
            }
            launch(listOf(image)).use {
                compose.onNodeWithText(context.getString(ru.outsideproarts.oassist.core.ui.R.string.chats_new)).performClick()
                compose.onNodeWithText(context.getString(ru.outsideproarts.oassist.feature.share.R.string.share_ask_label)).performTextInput("fixture question")
                val send = compose.onNodeWithContentDescription(context.getString(ru.outsideproarts.oassist.feature.share.R.string.share_send))
                compose.waitUntil(10_000) { runCatching { send.assertIsEnabled(); true }.getOrDefault(false) }
                send.performClick()
                compose.waitUntil(20_000) { requests.isNotEmpty() }
                val body = requests.single().toString()
                assertTrue(body.contains("styled fixture text"))
                assertTrue(body.contains("fixture question"))
                val encoded = checkNotNull(ru.outsideproarts.oassist.core.provider.ImageCodec.fromBytes(png))
                assertTrue(body.contains(encoded.mimeType))
                assertTrue(body.contains(encoded.imageBase64))
            }
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
            AgentFactory.register(oldFactory); ChatHandle.register(oldRepository); NetworkHandle.register(oldNetwork)
            http.close(); db.close(); mediaRoot.deleteRecursively()
        }
    }

    private class SseEngine(config: MockEngineConfig) : MockEngine(config) {
        override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> = super.supportedCapabilities + SSECapability
        @OptIn(InternalAPI::class)
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val response = super.execute(data)
            val body = response.body as? ByteReadChannel ?: return response
            val adapted = data.attributes.getOrNull(ResponseAdapterAttributeKey)?.adapt(data, response.statusCode, response.headers, body, data.body, response.callContext) ?: return response
            return HttpResponseData(response.statusCode, response.requestTime, response.headers, response.version, adapted, response.callContext)
        }
    }
    private object EmptySecrets : SecretStore {
        override suspend fun put(alias: String, plaintext: CharArray) = Unit
        override suspend fun get(alias: String): CharArray? = null
        override suspend fun putPair(firstAlias: String, first: CharArray?, secondAlias: String, second: CharArray?) = Unit
        override suspend fun getPair(firstAlias: String?, secondAlias: String?) = null to null
        override suspend fun contains(alias: String) = false
        override suspend fun delete(alias: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun aliases() = emptyList<String>()
    }
}
