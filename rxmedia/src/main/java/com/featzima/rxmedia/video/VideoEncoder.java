package com.featzima.rxmedia.video;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder {

    private static final String TAG = "RxVideoCodec";

    private static final String OUTPUT_VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String OUTPUT_AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int TIMEOUT_USEC = 10000;
    private static final int frameRate = 30;
    private static final int bitrate = 700000;
    private static final int keyFrameInternal = 1;
    private static final int kNumInputBytes = 256 * 1024;


    MediaMuxer muxer = null;
    MediaCodec codec = null;
    boolean muxerStarted = false;
    boolean codecStarted = false;
    int videoTrackIndex = -1;
    private Surface surface;
    private int width;
    private int height;
    private ByteBuffer[] outputBuffers;
    private MediaCodec.BufferInfo info;
    private int nbEncoded;
    private static long lastQueuedPresentationTimeStampUs = 0;
    private static long lastDequeuedPresentationTimeStampUs = 0;

    public void prepareEncoder(final int lastFrameNumber, final int width, final int height, @NotNull final String videoFilePath) {
        int nbFrames = lastFrameNumber + 1;
        this.width = width;
        this.height = height;

        try {
            MediaFormat mediaFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, width, height);

            muxer = new MediaMuxer(videoFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            codec = MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME);

            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameInternal);

            try {
                codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception ce) {
                Log.e(TAG, ce.getMessage());
            }

            surface = codec.createInputSurface();
            codec.start();
            codecStarted = true;

            outputBuffers = codec.getOutputBuffers();
            int outputBufferIndex;
            info = new MediaCodec.BufferInfo();

            nbEncoded = 0;
        } catch (Exception e) {
            Log.e(TAG, "Encoding exception: " + e.toString());
        }
    }

    public void renderAudio(ByteBuffer byteBuffer) throws IOException {
        int trackIndex = -1;
        MediaFormat format = MediaFormat.createAudioFormat(OUTPUT_AUDIO_MIME, 48000, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 190000);

        MediaCodec codec = MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME);

        try {
            codec.configure(
                    format,
                    null /* surface */,
                    null /* crypto */,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IllegalStateException e) {
            Log.e(TAG, "codec '" + OUTPUT_AUDIO_MIME + "' failed configuration.");

        }

        codec.start();

        ByteBuffer[] codecInputBuffers = codec.getInputBuffers();
        ByteBuffer[] codecOutputBuffers = codec.getOutputBuffers();

        int numBytesSubmitted = 0;
        boolean doneSubmittingInput = false;
        int numBytesDequeued = 0;

        while (true) {
            int index;

            if (!doneSubmittingInput) {
                index = codec.dequeueInputBuffer(TIMEOUT_USEC);

                if (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (numBytesSubmitted >= kNumInputBytes) {
                        lastQueuedPresentationTimeStampUs = getNextQueuedPresentationTimeStampUs();
                        Log.i(TAG, "queueInputBuffer EOS pts: " + lastQueuedPresentationTimeStampUs);
                        codec.queueInputBuffer(
                                index,
                                0 /* offset */,
                                0 /* size */,
                                lastQueuedPresentationTimeStampUs /* timeUs */,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                        {
                            Log.d(TAG, "queued input EOS.");
                        }

                        doneSubmittingInput = true;
                    } else if (!doneSubmittingInput) {
                        int size = queueInputBuffer(
                                codec, codecInputBuffers, index);

                        numBytesSubmitted += size;
                    }
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            index = codec.dequeueOutputBuffer(info, TIMEOUT_USEC /* timeoutUs */);
            // Log.d(TAG, "dequeueOutputBuffer BufferInfo. size: " + info.size + " pts: " + info.presentationTimeUs + " offset: " + info.offset + " flags: " + info.flags + " index: " + index);
            Log.d(TAG, "dequeueOutputBuffer BufferInfo. pts: " + info.presentationTimeUs + " flags: " + info.flags);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = codec.getOutputFormat();
                trackIndex = muxer.addTrack(newFormat);
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                codecOutputBuffers = codec.getOutputBuffers();
            } else if (index >= 0) {
                // Write to muxer
                //dequeueOutputBuffer(codec, codecOutputBuffers, index, info);

                ByteBuffer encodedData = codecOutputBuffers[index];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + index +
                            " was null");
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    info.size = 0;
                }

                if (info.size != 0) {
                    if (!muxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);


                    Log.d(TAG, "sending " + info.size + " audio bytes to muxer with pts " + info.presentationTimeUs + " offset: " + info.offset + " flags: " + info.flags);
                    info.presentationTimeUs = getNextDeQueuedPresentationTimeStampUs();
                    muxer.writeSampleData(trackIndex, encodedData, info);
                    lastDequeuedPresentationTimeStampUs = info.presentationTimeUs;
                }

                codec.releaseOutputBuffer(index, false);

                // End write to muxer
                numBytesDequeued += info.size;

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    {
                        Log.d(TAG, "dequeued output EOS.");
                    }
                    break;
                }

                {
                    Log.d(TAG, "dequeued " + info.size + " bytes of output data.");
                }
            }
        }

        {
            Log.d(TAG, "queued a total of " + numBytesSubmitted + "bytes, "
                    + "dequeued " + numBytesDequeued + " bytes.");
        }

        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int inBitrate = sampleRate * channelCount * 16;  // bit/sec
        int outBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE);

        float desiredRatio = (float) outBitrate / (float) inBitrate;
        float actualRatio = (float) numBytesDequeued / (float) numBytesSubmitted;

        if (actualRatio < 0.9 * desiredRatio || actualRatio > 1.1 * desiredRatio) {
            Log.w(TAG, "desiredRatio = " + desiredRatio
                    + ", actualRatio = " + actualRatio);
        }


        codec.release();
        codec = null;
    }

    private static int queueInputBuffer(
            MediaCodec codec, ByteBuffer[] inputBuffers, int index) {
        ByteBuffer buffer = inputBuffers[index];
        buffer.clear();

        int size = buffer.limit();

        byte[] zeroes = new byte[size];
        buffer.put(zeroes);

        lastQueuedPresentationTimeStampUs = getNextQueuedPresentationTimeStampUs();

        Log.i(TAG, "queueInputBuffer " + size + " bytes of input with pts: " + lastQueuedPresentationTimeStampUs);
        codec.queueInputBuffer(index, 0 /* offset */, size, lastQueuedPresentationTimeStampUs /* timeUs */, 0);

        return size;
    }

    public void renderFrame(Bitmap bitmap, int frameNumber) {
        Log.e("!!!", "renderFrame(" + frameNumber + ")");
        try {
            Bitmap frame = bitmap;
            Canvas canvas = surface.lockCanvas(new Rect(0, 0, width, height));

            canvas.drawBitmap(frame, 0, 0, new Paint());
            surface.unlockCanvasAndPost(canvas);

            boolean outputDone = false;
            while (!outputDone) {
//                canvas = surface.lockCanvas(new Rect(0, 0, 0, 0));
//                surface.unlockCanvasAndPost(canvas);

                int outputBufferIndex = codec.dequeueOutputBuffer(info, TIMEOUT_USEC);
                Log.i(TAG, "outputBufferIndex = " + String.valueOf(outputBufferIndex));

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    Log.d(TAG, "no output from encoder available");
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    outputBuffers = codec.getOutputBuffers();
                    Log.d(TAG, "encoder output buffers changed");
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted)
                        throwException("format changed twice");
                    MediaFormat newFormat = codec.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (outputBufferIndex < 0) {
                    throwException("unexpected result from encoder.dequeueOutputBuffer: " + outputBufferIndex);
                } else { // encoderStatus >= 0
                    ByteBuffer encodedData = outputBuffers[outputBufferIndex];
                    if (encodedData == null) {
                        throwException("encoderOutputBuffer " + outputBufferIndex + " was null");
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out and fed to the muxer when we got
                        // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                        Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        info.size = 0;
                    }

                    if (info.size != 0) {
                        if (!muxerStarted)
                            throwException("muxer hasn't started");

                        info.presentationTimeUs = computePresentationTime(nbEncoded, frameRate);

                        if (videoTrackIndex == -1)
                            throwException("video track not set yet");

                        muxer.writeSampleData(videoTrackIndex, encodedData, info);

                        nbEncoded++;

                        outputDone = true;
                    }

                    // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Encoding exception: " + e.toString());
        }
    }

    private static long getNextDeQueuedPresentationTimeStampUs(){
        Log.i(TAG, "nextDequeuedPresentationTimeStampUs: " + (lastDequeuedPresentationTimeStampUs + 1));
        lastDequeuedPresentationTimeStampUs ++;
        return lastDequeuedPresentationTimeStampUs;
    }

    private static long getNextQueuedPresentationTimeStampUs(){
        long nextQueuedPresentationTimeStampUs = (lastQueuedPresentationTimeStampUs > lastDequeuedPresentationTimeStampUs) ? (lastQueuedPresentationTimeStampUs + 1) : (lastDequeuedPresentationTimeStampUs + 1);
        Log.i(TAG, "nextQueuedPresentationTimeStampUs: " + nextQueuedPresentationTimeStampUs);
        return nextQueuedPresentationTimeStampUs;
    }

    public void finishEncoding() {
        if (codec != null) {
            if (codecStarted) {
                codec.stop();
            }
            codec.release();
        }
        if (muxer != null) {
            if (muxerStarted) {
                muxer.stop();
            }
            muxer.release();
        }
    }

    private static long computePresentationTime(int frameIndex, int frameRate) {
        return frameIndex * 1000000 / frameRate;
    }

    private static Bitmap getBitmapFromImage(String inputFilePath, int width, int height) {
        Bitmap bitmap = decodeSampledBitmapFromFile(inputFilePath, width, height);

        if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, false);
            bitmap.recycle();
            return scaled;
        } else {
            return bitmap;
        }
    }

    private static Bitmap decodeSampledBitmapFromFile(String filePath,
                                                      int reqWidth,
                                                      int reqHeight) {

        final BitmapFactory.Options options = new BitmapFactory.Options();
        // Calculate inSampleSize
        // First decode with inJustDecodeBounds=true to check dimensions
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, options);
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        return BitmapFactory.decodeFile(filePath, options);
    }

    private static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private static void throwException(String exp) {
        throw new RuntimeException(exp);
    }

}