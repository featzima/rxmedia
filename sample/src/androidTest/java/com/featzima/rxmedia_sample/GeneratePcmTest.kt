package com.featzima.rxmedia_sample

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.featzima.rxmedia.audio.RxCutter
import io.reactivex.Flowable
import io.reactivex.subscribers.TestSubscriber
import junit.framework.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.reactivestreams.Publisher
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.experimental.and


@RunWith(AndroidJUnit4::class)
class GeneratePcmTest {

    @Test
    fun testSineSignal() {
        val sampleRate = 44100
        val testSubscriber = TestSubscriber<ByteBuffer>(1)
        val sineWave = Wave(2000, Short.MAX_VALUE)
        val sineGenerator = MonoWaveGenerator(sineWave, sampleRate)

        sineGenerator.output.subscribe(testSubscriber)
        testSubscriber.awaitCount(1)

        val pcmBuffer = testSubscriber.values().first()
        val waveAnalyzer = WaveAnalyzer(sineWave, sampleRate, 0.1)
        Assert.assertTrue(waveAnalyzer.isSinCorrect(pcmBuffer))
        Assert.assertFalse(waveAnalyzer.isCosineCorrect(pcmBuffer))
    }

    @Test
    fun testRxCutter() {
        val sampleRate = 44100
        val testSubscriber = TestSubscriber<ByteBuffer>(1)
        val sineWave = Wave(1, Short.MAX_VALUE)
        val sineGenerator = MonoWaveGenerator(sineWave, sampleRate)

        val rxCutter = RxCutter(500_000, 1_000_000)
        sineGenerator.output.subscribe(rxCutter.input)
        rxCutter.output.subscribe(testSubscriber)
        testSubscriber.awaitCount(1)

        val pcmBuffer = testSubscriber.values().first()
        val waveAnalyzer = WaveAnalyzer(sineWave, sampleRate, 0.1)
        Assert.assertFalse(waveAnalyzer.isSinCorrect(pcmBuffer))
        Assert.assertTrue(waveAnalyzer.isCosineCorrect(pcmBuffer))
    }

    private fun properWAV(pcmBuffer: ByteBuffer) {
        try {
            val mySubChunk1Size: Long = 16
            val myBitsPerSample = 16
            val myFormat = 1
            val myChannels: Long = 1
            val mySampleRate: Long = 22100
            val myByteRate = mySampleRate * myChannels * myBitsPerSample.toLong() / 8
            val myBlockAlign = (myChannels * myBitsPerSample / 8).toInt()

            val myDataSize = pcmBuffer.limit().toLong()
            val myChunk2Size = myDataSize * myChannels * myBitsPerSample.toLong() / 8
            val myChunkSize = 36 + myChunk2Size

            val os: OutputStream
            os = FileOutputStream(File(InstrumentationRegistry.getTargetContext().filesDir, "proper.wav"))
            val bos = BufferedOutputStream(os)
            val outFile = DataOutputStream(bos)

            outFile.writeBytes("RIFF")                                          // 00 - RIFF
            outFile.write(intToByteArray(myChunkSize.toInt()), 0, 4)            // 04 - how big is the rest of this file?
            outFile.writeBytes("WAVE")                                          // 08 - WAVE
            outFile.writeBytes("fmt ")                                          // 12 - fmt
            outFile.write(intToByteArray(mySubChunk1Size.toInt()), 0, 4)        // 16 - size of this chunk
            outFile.write(shortToByteArray(myFormat.toShort()), 0, 2)           // 20 - what is the audio format? 1 for PCM = Pulse Code Modulation
            outFile.write(shortToByteArray(myChannels.toShort()), 0, 2)         // 22 - mono or stereo? 1 or 2?  (or 5 or ???)
            outFile.write(intToByteArray(mySampleRate.toInt()), 0, 4)           // 24 - samples per second (numbers per second)
            outFile.write(intToByteArray(myByteRate.toInt()), 0, 4)             // 28 - bytes per second
            outFile.write(shortToByteArray(myBlockAlign.toShort()), 0, 2)       // 32 - # of bytes in one sample, for all channels
            outFile.write(shortToByteArray(myBitsPerSample.toShort()), 0, 2)    // 34 - how many bits in a sample(number)?  usually 16 or 24
            outFile.writeBytes("data")                                          // 36 - data
            outFile.write(intToByteArray(myDataSize.toInt()), 0, 4)             // 40 - how big is this data chunk
            outFile.write(pcmBuffer.array())                                    // 44 - the actual data itself - just a long string of numbers

            outFile.flush()
            outFile.close()

        } catch (e: IOException) {
            e.printStackTrace()
        }

    }


    private fun intToByteArray(i: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (i and 0x00FF).toByte()
        b[1] = (i shr 8 and 0x000000FF).toByte()
        b[2] = (i shr 16 and 0x000000FF).toByte()
        b[3] = (i shr 24 and 0x000000FF).toByte()
        return b
    }

    // convert a short to a byte array
    fun shortToByteArray(data: Short): ByteArray {
        /*
         * NB have also tried:
         * return new byte[]{(byte)(data & 0xff),(byte)((data >> 8) & 0xff)};
         *
         */

        return byteArrayOf((data and 0xff).toByte(), (data.toInt().ushr(8) and 0xff).toByte())
    }

}

data class Wave(
        val frequency: Int,
        val amplitude: Short = 1) {

    fun sinAt(time: Long, timeUnit: TimeUnit): Short {
        val value = Math.sin(timeUnit.toMicros(time) / 500000.0 * Math.PI) * amplitude
        return value.toShort()
    }

    fun cosAt(time: Long, timeUnit: TimeUnit): Short {
        val value = Math.cos(timeUnit.toMicros(time) / 500000.0 * Math.PI) * amplitude
        return value.toShort()
    }

}

class MonoWaveGenerator(
        val wave: Wave,
        val sampleRate: Int) {

    val output: Publisher<ByteBuffer> = Flowable.generate { emitter ->
        val pcmBuffer = ByteBuffer.allocate(sampleRate * 2).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            (0 until sampleRate).forEach { sampleIndex ->
                val sinValue = wave.sinAt(sampleIndex * 1000000L / sampleRate, TimeUnit.MICROSECONDS)
                putShort(sampleIndex * 2, sinValue)
            }
        }
        emitter.onNext(pcmBuffer)
    }
}

class WaveAnalyzer(
        val wave: Wave,
        val sampleRate: Int,
        val allowableError: Double) {

    fun isSinCorrect(byteBuffer: ByteBuffer): Boolean {
        return (0 until byteBuffer.limit() / 2).all { sampleIndex ->
            val pcmSample = byteBuffer.getShort(sampleIndex * 2)
            val time = 1_000_000L / sampleRate * sampleIndex
            val sinValue = wave.sinAt(time, TimeUnit.MICROSECONDS)
            (sinValue.toDouble() - pcmSample) / wave.amplitude < allowableError
        }
    }

    fun isCosineCorrect(byteBuffer: ByteBuffer): Boolean {
        return (0 until byteBuffer.limit() / 2).all { sampleIndex ->
            val pcmSample = byteBuffer.getShort(sampleIndex * 2)
            val time = 1_000_000L / sampleRate * sampleIndex
            val sinValue = wave.cosAt(time, TimeUnit.MICROSECONDS)
            (sinValue.toDouble() - pcmSample) / wave.amplitude < allowableError
        }
    }

}
