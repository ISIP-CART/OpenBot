package org.openbot.cartfollow;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

public class TfliteReIDFeatureExtractor implements ReIDFeatureExtractor {
  public static final String DEFAULT_ASSET_PATH = "networks/reid/osnet_x0_25_market1501.tflite";

  private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
  private static final float[] STD = {0.229f, 0.224f, 0.225f};

  private final Interpreter interpreter;
  private final int inputWidth;
  private final int inputHeight;
  private final boolean nchw;
  private final int embeddingSize;
  private final int[] pixels;
  private final ByteBuffer inputBuffer;

  public TfliteReIDFeatureExtractor(Activity activity, String assetPath, int numThreads)
      throws IOException {
    Interpreter.Options options = new Interpreter.Options();
    options.setNumThreads(numThreads > 0 ? numThreads : 2);
    interpreter = new Interpreter(loadModelFile(activity, assetPath), options);

    Tensor inputTensor = interpreter.getInputTensor(0);
    int[] inShape = inputTensor.shape();
    if (inShape.length != 4) {
      throw new IOException("Unsupported ReID input shape length: " + inShape.length);
    }
    nchw = inShape[1] == 3;
    inputHeight = nchw ? inShape[2] : inShape[1];
    inputWidth = nchw ? inShape[3] : inShape[2];
    if (inputWidth <= 0 || inputHeight <= 0) {
      throw new IOException("Invalid ReID input shape.");
    }

    int[] outShape = interpreter.getOutputTensor(0).shape();
    int size = 1;
    for (int i = 1; i < outShape.length; i++) {
      size *= Math.max(1, outShape[i]);
    }
    embeddingSize = Math.max(1, size);
    pixels = new int[inputWidth * inputHeight];
    inputBuffer = ByteBuffer.allocateDirect(4 * inputWidth * inputHeight * 3);
    inputBuffer.order(ByteOrder.nativeOrder());
  }

  @Override
  public synchronized float[] extract(Bitmap personCrop) {
    if (personCrop == null) return null;
    Bitmap resized = Bitmap.createScaledBitmap(personCrop, inputWidth, inputHeight, true);
    resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
    inputBuffer.rewind();
    if (nchw) {
      for (int c = 0; c < 3; c++) {
        for (int y = 0; y < inputHeight; y++) {
          for (int x = 0; x < inputWidth; x++) {
            inputBuffer.putFloat(normalizedChannel(pixels[y * inputWidth + x], c));
          }
        }
      }
    } else {
      for (int y = 0; y < inputHeight; y++) {
        for (int x = 0; x < inputWidth; x++) {
          int px = pixels[y * inputWidth + x];
          inputBuffer.putFloat(normalizedChannel(px, 0));
          inputBuffer.putFloat(normalizedChannel(px, 1));
          inputBuffer.putFloat(normalizedChannel(px, 2));
        }
      }
    }
    float[][] output = new float[1][embeddingSize];
    inputBuffer.rewind();
    interpreter.run(inputBuffer, output);
    return l2Normalize(output[0]);
  }

  public void close() {
    interpreter.close();
  }

  private static float normalizedChannel(int px, int channel) {
    int v;
    if (channel == 0) v = (px >> 16) & 0xFF;
    else if (channel == 1) v = (px >> 8) & 0xFF;
    else v = px & 0xFF;
    return (v / 255f - MEAN[channel]) / STD[channel];
  }

  private static float[] l2Normalize(float[] values) {
    float sum = 0f;
    for (float v : values) sum += v * v;
    float norm = (float) Math.sqrt(Math.max(sum, 1e-12f));
    for (int i = 0; i < values.length; i++) values[i] /= norm;
    return values;
  }

  private static MappedByteBuffer loadModelFile(Activity activity, String assetPath)
      throws IOException {
    AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(assetPath);
    FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
    FileChannel fileChannel = inputStream.getChannel();
    return fileChannel.map(
        FileChannel.MapMode.READ_ONLY,
        fileDescriptor.getStartOffset(),
        fileDescriptor.getDeclaredLength());
  }
}
