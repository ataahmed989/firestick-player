package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;

/**
 * FFmpeg's audio decoder, used on the side by FirePlayer's subtitle auto sync to hear the dialogue in sound that goes
 * to the TV undecoded (surround passthrough). Media3 keeps the decoder to its own package, so this small door sits in
 * that package. Output is 16-bit samples.
 */
@UnstableApi
public final class SideAudioDecoder {

  /** Receives decoded samples: [pcm] holds 16-bit interleaved samples from its position to its limit. */
  public interface Output {
    void onSamples(ByteBuffer pcm, int channels, int sampleRate, long timeUs);
  }

  private final FfmpegAudioDecoder decoder;

  /** Whether FFmpeg (as bundled) can decode sound of this type. */
  public static boolean canDecode(@Nullable String mimeType) {
    return mimeType != null && FfmpegLibrary.supportsFormat(mimeType);
  }

  public SideAudioDecoder(Format format) throws FfmpegDecoderException {
    decoder = new FfmpegAudioDecoder(format, 4, 4, 8192, /* outputFloat= */ false);
  }

  /** Hands one compressed piece over; false when the decoder is full (take its output first). */
  public boolean queue(byte[] data, int size, long timeUs) throws FfmpegDecoderException {
    DecoderInputBuffer input = decoder.dequeueInputBuffer();
    if (input == null) return false;
    input.ensureSpaceForWrite(size);
    input.data.put(data, 0, size);
    input.timeUs = timeUs;
    input.flip();
    decoder.queueInputBuffer(input);
    return true;
  }

  /** Passes on whatever has been decoded so far. */
  public void drain(Output output) throws FfmpegDecoderException {
    SimpleDecoderOutputBuffer buffer;
    while ((buffer = decoder.dequeueOutputBuffer()) != null) {
      try {
        if (!buffer.shouldBeSkipped && !buffer.isEndOfStream() && buffer.data != null) {
          output.onSamples(buffer.data, decoder.getChannelCount(), decoder.getSampleRate(), buffer.timeUs);
        }
      } finally {
        buffer.release();
      }
    }
  }

  public void flush() {
    decoder.flush();
  }

  public void release() {
    decoder.release();
  }
}
