package ru.outsideproarts.oassist.feature.share

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.SpannedString
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.model.ModelUserInput
import ru.outsideproarts.oassist.core.provider.ImageCodec
import java.io.ByteArrayOutputStream
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class SharePayloadContentProviderTest {
    @Test fun readsPlainAndStyledText() {
        for (text in listOf<CharSequence>("shared text", SpannedString("shared text"))) {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            assertEquals("shared text", SharePayload.from(intent) { null }.text)
        }
    }

    @Test fun classifiesEachStreamAndRejectsPdfAndUnknown() {
        val image = Uri.parse("content://share-test/image")
        val pdf = Uri.parse("content://share-test/pdf")
        val unknown = Uri.parse("content://share-test/unknown")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(image, pdf, unknown))
        val payload = SharePayload.from(intent) { uri ->
            when (uri) { image -> "image/png"; pdf -> "application/pdf"; else -> null }
        }
        assertEquals(listOf(image), payload.imageUris)
        assertEquals(listOf(pdf, unknown), payload.otherUris)
        assertTrue(payload.unsupported)
        val overflow = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(List(5) { image }))
        assertTrue(SharePayload.from(overflow) { "image/png" }.unsupported)
        val spoofedPdf = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, pdf)
        assertTrue(SharePayload.from(spoofedPdf) { "application/pdf" }.unsupported)
        assertEquals(listOf(pdf), SharePayload.from(spoofedPdf) { null }.imageUris)
    }

    @Test fun sendWaitsForActualProviderReadAndDecodeAcrossSameVmLoadRetry() = runBlocking {
        val png = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) } finally { bitmap.recycle() }
            output.toByteArray()
        }
        val provider = DelayedProvider()
        val resolver = ContentResolver.wrap(provider)
        val uri = Uri.parse("content://share-test/image")
        val finished = CompletableDeferred<Unit>()
        val owner = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ShareSessionViewModel(null).also { owner.put("share", it) }
        }
        try {
            withContext(Dispatchers.Main) {
                vm.newChat()
                vm.loadPayload {
                    loadShareImages(resolver, listOf(uri)).also { finished.complete(Unit) }
                }
            }
            withTimeout(10_000) { provider.opened.await() }
            withContext(Dispatchers.Main) {
                assertFalse(vm.payloadReady)
                assertFalse(vm.canSend)
                vm.ask(ShareAskRequest("question", "question", ModelUserInput("question"), "image"))
                assertFalse(vm.isStreaming)
                // Repeated loader registration on the same VM, without recreating an Activity.
                vm.loadPayload { error("Payload must not load twice") }
            }
            ParcelFileDescriptor.AutoCloseOutputStream(provider.pipe[1]).use { it.write(png) }
            withTimeout(10_000) { finished.await() }
            withContext(Dispatchers.Main) {
                assertTrue(vm.payloadReady)
                assertTrue(vm.canSend)
                assertEquals(listOf(ImageCodec.fromBytes(png)), vm.images)
                assertEquals(1, provider.opens)
            }
        } finally {
            provider.pipe.forEach { runCatching { it.close() } }
            withContext(Dispatchers.Main) { owner.clear() }
        }
    }

    @Test fun unreadableImageDoesNotEnableSending() = runBlocking {
        val provider = DelayedProvider()
        val owner = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ShareSessionViewModel(null).also { owner.put("share", it) }
        }
        try {
            withContext(Dispatchers.Main) {
                vm.loadPayload {
                    loadShareImages(ContentResolver.wrap(provider), listOf(Uri.parse("content://share-test/image")), requireAll = true)
                }
            }
            withTimeout(10_000) { provider.opened.await() }
            ParcelFileDescriptor.AutoCloseOutputStream(provider.pipe[1]).use { it.write("not an image".toByteArray()) }
            withTimeout(10_000) {
                while (!withContext(Dispatchers.Main) { vm.payloadLoadFailed }) kotlinx.coroutines.yield()
            }
            withContext(Dispatchers.Main) {
                assertFalse(vm.payloadReady)
                assertFalse(vm.canSend)
            }
        } finally {
            provider.pipe.forEach { runCatching { it.close() } }
            withContext(Dispatchers.Main) { owner.clear() }
        }
    }

    @Test fun clearingOwnerReleasesBlockedReadBeforeProducerClosesPipe() = runBlocking {
        val provider = DelayedProvider()
        val resolver = ContentResolver.wrap(provider)
        val owner = ViewModelStore()
        val readFinished = CompletableDeferred<Unit>()
        val vm = withContext(Dispatchers.Main) {
            ShareSessionViewModel(null).also { owner.put("share", it) }
        }
        try {
            withContext(Dispatchers.Main) {
                vm.loadPayload {
                    try {
                        loadShareImages(resolver, listOf(Uri.parse("content://share-test/image")))
                    } finally {
                        readFinished.complete(Unit)
                    }
                }
            }
            assertTrue(
                "ContentProvider stream did not enter read",
                withTimeoutOrNull(10_000) { provider.readStarted.await(); true } ?: false,
            )
            withContext(Dispatchers.Main) { owner.clear() }
            // The producer remains open and sends neither bytes nor EOF throughout these assertions.
            assertTrue(
                "Owner was cleared but the read did not finish before producer close",
                withTimeoutOrNull(10_000) { readFinished.await(); true } ?: false,
            )
            assertTrue(provider.pipe[1].fileDescriptor.valid())
            assertFalse(provider.pipe[0].fileDescriptor.valid())
            assertFalse(vm.payloadReady)
        } finally {
            provider.pipe.forEach { runCatching { it.close() } }
            withContext(Dispatchers.Main) { owner.clear() }
        }
    }

    private class DelayedProvider : ContentProvider() {
        val opened = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<Unit>()
        val pipe = ParcelFileDescriptor.createPipe()
        var opens = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            opened.complete(Unit)
            return pipe[0]
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
            object : AssetFileDescriptor(openFile(uri, mode), 0, UNKNOWN_LENGTH) {
                override fun createInputStream(): FileInputStream =
                    object : AutoCloseInputStream(this) {
                        override fun read(buffer: ByteArray): Int {
                            readStarted.complete(Unit)
                            return super.read(buffer)
                        }
                        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                            readStarted.complete(Unit)
                            return super.read(buffer, offset, count)
                        }
                    }
            }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
