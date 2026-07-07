package org.openbot.sequencecollector;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.openbot.tflite.Detector.Recognition;
import timber.log.Timber;

public class PersonSequenceSaver {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public void saveFrameAsync(
      Bitmap frame,
      List<Recognition> persons,
      PersonSequenceSession session,
      PersonSequenceCaptureConfig config,
      long frameNum,
      int imageWidth,
      int imageHeight,
      int sensorOrientation,
      boolean saveCropsForThisFrame,
      String eventTag,
      String note) {
    final long timestampMs = System.currentTimeMillis();
    final long elapsedMs = timestampMs - session.startedAtMs;
    final List<RecognitionSnapshot> snapshots = snapshotPersons(persons);
    Bitmap.Config bitmapConfig = frame == null || frame.getConfig() == null ? Bitmap.Config.ARGB_8888 : frame.getConfig();
    final Bitmap frameCopy =
        (frame != null && saveCropsForThisFrame && config.saveCrops) ? frame.copy(bitmapConfig, false) : null;
    final String safeEventTag = eventTag == null ? "" : eventTag;
    final String safeNote = note == null ? "" : note;

    executor.execute(
        () -> {
          appendFrameLog(
              session,
              frameNum,
              timestampMs,
              elapsedMs,
              imageWidth,
              imageHeight,
              snapshots.size(),
              "",
              "",
              safeEventTag,
              safeNote);

          for (int i = 0; i < snapshots.size(); i++) {
            RecognitionSnapshot snapshot = snapshots.get(i);
            String cropPath = "";
            if (frameCopy != null) {
              cropPath =
                  saveCrop(
                      frameCopy,
                      snapshot,
                      session,
                      config,
                      frameNum,
                      i,
                      sensorOrientation);
            }
            appendDetection(session, frameNum, timestampMs, i, snapshot, imageWidth, imageHeight, cropPath);
          }

          if (frameCopy != null) {
            frameCopy.recycle();
          }
        });
  }

  public void saveEventAsync(PersonSequenceSession session, long frameNum, String eventType, String note) {
    final long timestampMs = System.currentTimeMillis();
    final String safeType = eventType == null ? "" : eventType;
    final String safeNote = note == null ? "" : note;
    executor.execute(() -> appendEvent(session, timestampMs, frameNum, safeType, safeNote));
  }

  private List<RecognitionSnapshot> snapshotPersons(List<Recognition> persons) {
    List<RecognitionSnapshot> snapshots = new ArrayList<>();
    if (persons == null) return snapshots;
    for (Recognition person : persons) {
      if (person == null || person.getLocation() == null) continue;
      float confidence = person.getConfidence() == null ? 0f : person.getConfidence();
      snapshots.add(new RecognitionSnapshot(new RectF(person.getLocation()), confidence));
    }
    return snapshots;
  }

  private void appendFrameLog(
      PersonSequenceSession session,
      long frameNum,
      long timestampMs,
      long elapsedMs,
      int imageWidth,
      int imageHeight,
      int numPersons,
      String rawFramePath,
      String overlayPath,
      String eventTag,
      String note) {
    String row =
        String.format(
            Locale.US,
            "%s,%d,%d,%d,%d,%d,%d,%s,%s,%s,%s\n",
            csv(session.sessionId),
            frameNum,
            timestampMs,
            elapsedMs,
            imageWidth,
            imageHeight,
            numPersons,
            csv(rawFramePath),
            csv(overlayPath),
            csv(eventTag),
            csv(note));
    append(session.frameLogCsv, row, "frame_log.csv");
    session.frameRows++;
  }

  private void appendDetection(
      PersonSequenceSession session,
      long frameNum,
      long timestampMs,
      int detId,
      RecognitionSnapshot snapshot,
      int imageWidth,
      int imageHeight,
      String cropPath) {
    RectF bbox = snapshot.bbox;
    float areaRatio = imageWidth > 0 && imageHeight > 0 ? bbox.width() * bbox.height() / (imageWidth * imageHeight) : 0f;
    boolean edgeTouch =
        bbox.left <= 0 || bbox.top <= 0 || bbox.right >= imageWidth || bbox.bottom >= imageHeight;
    String row =
        String.format(
            Locale.US,
            "%s,%d,%d,%d,%.4f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.6f,%.1f,%.1f,%s,%s\n",
            csv(session.sessionId),
            frameNum,
            detId,
            timestampMs,
            snapshot.confidence,
            bbox.left,
            bbox.top,
            bbox.right,
            bbox.bottom,
            bbox.width(),
            bbox.height(),
            areaRatio,
            bbox.centerX(),
            bbox.centerY(),
            edgeTouch ? "1" : "0",
            csv(cropPath));
    append(session.detectionsCsv, row, "detections.csv");
    session.detectionRows++;
  }

  private void appendEvent(
      PersonSequenceSession session, long timestampMs, long frameNum, String eventType, String note) {
    String row =
        String.format(
            Locale.US,
            "%s,%d,%d,%s,%s\n",
            csv(session.sessionId), timestampMs, frameNum, csv(eventType), csv(note));
    append(session.eventsCsv, row, "events.csv");
    session.eventRows++;
  }

  private String saveCrop(
      Bitmap frame,
      RecognitionSnapshot snapshot,
      PersonSequenceSession session,
      PersonSequenceCaptureConfig config,
      long frameNum,
      int detId,
      int sensorOrientation) {
    RectF bbox = snapshot.bbox;
    float padX = bbox.width() * config.paddingRatio;
    float padY = bbox.height() * config.paddingRatio;
    int left = clamp((int) (bbox.left - padX), 0, frame.getWidth() - 1);
    int top = clamp((int) (bbox.top - padY), 0, frame.getHeight() - 1);
    int right = clamp((int) (bbox.right + padX), left + 1, frame.getWidth());
    int bottom = clamp((int) (bbox.bottom + padY), top + 1, frame.getHeight());
    int w = right - left;
    int h = bottom - top;
    if (w <= 0 || h <= 0) return "";

    Bitmap rawCrop;
    try {
      rawCrop = Bitmap.createBitmap(frame, left, top, w, h);
    } catch (Exception e) {
      return "";
    }

    Bitmap uprightCrop;
    if (sensorOrientation % 360 != 0) {
      Matrix matrix = new Matrix();
      matrix.postRotate(sensorOrientation);
      uprightCrop = Bitmap.createBitmap(rawCrop, 0, 0, rawCrop.getWidth(), rawCrop.getHeight(), matrix, true);
      rawCrop.recycle();
    } else {
      uprightCrop = rawCrop;
    }

    String filename =
        String.format(Locale.US, "%06d_person%d_conf%.2f.jpg", frameNum, detId, snapshot.confidence);
    File cropFile = new File(session.cropsDir, filename);
    try (FileOutputStream fos = new FileOutputStream(cropFile)) {
      uprightCrop.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, fos);
      session.cropCount++;
      return "crops/" + filename;
    } catch (IOException e) {
      Timber.e(e, "Failed to save sequence crop");
      return "";
    } finally {
      uprightCrop.recycle();
    }
  }

  private void append(File file, String row, String label) {
    try (FileWriter writer = new FileWriter(file, true)) {
      writer.append(row);
    } catch (IOException e) {
      Timber.e(e, "Failed to append %s", label);
    }
  }

  public void shutdown() {
    executor.shutdown();
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  private static String csv(String value) {
    if (value == null) return "";
    String escaped = value.replace("\"", "\"\"");
    return "\"" + escaped + "\"";
  }

  private static class RecognitionSnapshot {
    final RectF bbox;
    final float confidence;

    RecognitionSnapshot(RectF bbox, float confidence) {
      this.bbox = bbox;
      this.confidence = confidence;
    }
  }
}
