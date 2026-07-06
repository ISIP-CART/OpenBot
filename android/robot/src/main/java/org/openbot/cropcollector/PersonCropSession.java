package org.openbot.cropcollector;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

public class PersonCropSession {

  private static final String TAG = "PersonCropSession";

  private static final String CSV_HEADER =
      "session_id,person_id,frame_id,crop_id,timestamp_ms,crop_path,num_persons,person_index,"
          + "confidence,bbox_left,bbox_top,bbox_right,bbox_bottom,bbox_width,bbox_height,"
          + "image_width,image_height,edge_touch,save_reason";

  public final String sessionId;
  public final String personId;
  public final File sessionDir;
  public final File cropsDir;
  public final File metadataCsv;
  public int savedCount = 0;
  public int skippedCount = 0;

  public PersonCropSession(Context context, String personId) {
    this.personId = personId;
    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    this.sessionId = personId + "_" + timestamp;

    File baseDir =
        new File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "cartfollow_crops");
    this.sessionDir = new File(baseDir, sessionId);
    this.cropsDir = new File(sessionDir, "crops");
    this.cropsDir.mkdirs();
    this.metadataCsv = new File(sessionDir, "metadata.csv");
  }

  public void initMetadataCsv() {
    try (FileWriter writer = new FileWriter(metadataCsv, false)) {
      writer.append(CSV_HEADER).append('\n');
    } catch (IOException e) {
      Log.e(TAG, "Failed to init metadata.csv", e);
    }
  }

  public void writeSessionInfo(PersonCropCaptureConfig config) {
    JSONObject json = new JSONObject();
    try {
      json.put("session_id", sessionId);
      json.put("person_id", personId);
      json.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
      json.put("capture_interval_ms", config.intervalMs);
      json.put("min_confidence", config.minConfidence);
      json.put("single_person_only", config.singlePersonOnly);
      json.put("bbox_padding_ratio", config.paddingRatio);
      json.put("max_crops", config.maxCrops);
      json.put("jpeg_quality", config.jpegQuality);
      json.put("app_mode", "PersonCropCollector");
    } catch (JSONException e) {
      Log.e(TAG, "Failed to build session_info.json", e);
      return;
    }

    File infoFile = new File(sessionDir, "session_info.json");
    try (FileWriter writer = new FileWriter(infoFile, false)) {
      writer.write(json.toString(2));
    } catch (IOException | JSONException e) {
      Log.e(TAG, "Failed to write session_info.json", e);
    }
  }
}
