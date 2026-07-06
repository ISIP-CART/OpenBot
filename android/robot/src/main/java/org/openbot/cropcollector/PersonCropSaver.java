package org.openbot.cropcollector;

import android.graphics.Bitmap;
import android.graphics.RectF;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.openbot.tflite.Detector.Recognition;
import timber.log.Timber;

public class PersonCropSaver {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public void saveCropAsync(
      Bitmap frame,
      Recognition person,
      int personIndex,
      int numPersons,
      PersonCropSession session,
      PersonCropCaptureConfig config,
      long frameNum,
      int cropId) {
    RectF bbox = person.getLocation();
    if (bbox == null || frame == null) {
      session.skippedCount++;
      return;
    }

    float padX = bbox.width() * config.paddingRatio;
    float padY = bbox.height() * config.paddingRatio;
    int left = clamp((int) (bbox.left - padX), 0, frame.getWidth() - 1);
    int top = clamp((int) (bbox.top - padY), 0, frame.getHeight() - 1);
    int right = clamp((int) (bbox.right + padX), left + 1, frame.getWidth());
    int bottom = clamp((int) (bbox.bottom + padY), top + 1, frame.getHeight());
    int w = right - left;
    int h = bottom - top;
    if (w <= 0 || h <= 0) {
      session.skippedCount++;
      return;
    }

    final Bitmap crop;
    try {
      crop = Bitmap.createBitmap(frame, left, top, w, h);
    } catch (Exception e) {
      session.skippedCount++;
      return;
    }

    final int imgW = frame.getWidth();
    final int imgH = frame.getHeight();
    final boolean edgeTouch = left == 0 || top == 0 || right == imgW || bottom == imgH;
    final RectF bboxCopy = new RectF(bbox);
    final float confidence = person.getConfidence() == null ? 0f : person.getConfidence();

    executor.execute(
        () -> {
          String filename =
              String.format(Locale.US, "%06d_person%d_conf%.2f.jpg", cropId, personIndex, confidence);
          File cropFile = new File(session.cropsDir, filename);

          try (FileOutputStream fos = new FileOutputStream(cropFile)) {
            crop.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, fos);
          } catch (IOException e) {
            Timber.e(e, "Failed to save crop");
          } finally {
            crop.recycle();
          }

          appendMetadataCsv(
              session,
              config,
              personIndex,
              numPersons,
              cropId,
              frameNum,
              filename,
              bboxCopy,
              imgW,
              imgH,
              confidence,
              edgeTouch);
        });
  }

  private void appendMetadataCsv(
      PersonCropSession session,
      PersonCropCaptureConfig config,
      int personIndex,
      int numPersons,
      int cropId,
      long frameNum,
      String filename,
      RectF bbox,
      int imgW,
      int imgH,
      float confidence,
      boolean edgeTouch) {
    String cropPath = "crops/" + filename;
    String row =
        String.format(
            Locale.US,
            "%s,%s,%d,%d,%d,%s,%d,%d,%.4f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d,%s,%s\n",
            session.sessionId,
            config.personId,
            frameNum,
            cropId,
            System.currentTimeMillis(),
            cropPath,
            numPersons,
            personIndex,
            confidence,
            bbox.left,
            bbox.top,
            bbox.right,
            bbox.bottom,
            bbox.width(),
            bbox.height(),
            imgW,
            imgH,
            edgeTouch ? "1" : "0",
            "single_person_valid");
    try (FileWriter writer = new FileWriter(session.metadataCsv, true)) {
      writer.append(row);
    } catch (IOException e) {
      Timber.e(e, "Failed to append metadata.csv");
    }
  }

  public void shutdown() {
    executor.shutdown();
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
