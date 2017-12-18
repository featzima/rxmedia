package com.featzima.rxmedia_sample

import android.media.MediaFormat.*
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.featzima.rxmedia.common.MimeTrackSelector
import com.featzima.rxmedia.common.RxMediaExtractor
import com.featzima.rxmedia.i.CodecEvent
import com.featzima.rxmedia.i.DataCodecEvent
import com.featzima.rxmedia.i.FormatCodecEvent
import io.reactivex.Flowable
import io.reactivex.subscribers.TestSubscriber

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.nio.ByteBuffer

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getTargetContext()
        assertEquals("com.featzima.rxmedia", appContext.packageName)
    }
}

@RunWith(AndroidJUnit4::class)
class RxMediaExtractorTest {
    @Test
    fun testMediaFormat() {
        val appContext = InstrumentationRegistry.getTargetContext()
        val assetFd = appContext.assets.openFd("big_buck_bunny.mp4")
        val mediaExtractor = RxMediaExtractor(assetFd, MimeTrackSelector("video/"))
        val subscriber = TestSubscriber<FormatCodecEvent<ByteBuffer>>()

        Flowable.fromPublisher(mediaExtractor.output)
                .takeUntil { it is FormatCodecEvent<ByteBuffer> }
                .map { it as FormatCodecEvent<ByteBuffer> }
                .subscribe(subscriber)
        subscriber.request(1)

        subscriber.assertValue { it.mediaFormat.getInteger(KEY_WIDTH) == 640 }
        subscriber.assertValue { it.mediaFormat.getInteger(KEY_HEIGHT) == 360 }
        subscriber.assertValue { it.mediaFormat.getLong(KEY_DURATION) == 60095000L }
        subscriber.assertValue { it.mediaFormat.getInteger(KEY_FRAME_RATE) == 24 }
        subscriber.assertValue { it.mediaFormat.getString(KEY_MIME) == "video/avc" }
    }

    @Test
    fun testNumberOfFrames() {
        val appContext = InstrumentationRegistry.getTargetContext()
        val assetFd = appContext.assets.openFd("big_buck_bunny.mp4")
        val mediaExtractor = RxMediaExtractor(assetFd, MimeTrackSelector("video/"))
        val subscriber = TestSubscriber<DataCodecEvent<ByteBuffer>>()

        Flowable.fromPublisher(mediaExtractor.output)
                .filter { it is DataCodecEvent<ByteBuffer> }
                .map { it as DataCodecEvent<ByteBuffer> }
                .subscribe(subscriber)
        subscriber.request(Long.MAX_VALUE)

        subscriber.assertComplete()
    }
}
