package org.openbot.sequencecollector;

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

public class PersonSequenceSession {

  private static final String TAG = "PersonSequenceSession";

  private static final String FRAME_LOG_HEADER =
      "session_id,frame_id,timestamp_ms,elapsed_ms,image_width,image_height,num_persons,"
          + "raw_frame_path,overlay_path,event_tag,note";
  private static final String DETECTIONS_HEADER =
      "session_id,frame_id,det_id,timestamp_ms,confidence,bbox_left,bbox_top,bbox_right,"
          + "bbox_bottom,bbox_width,bbox_height,bbox_area_ratio,center_x,center_y,edge_touch,"
          + "crop_path";
  private static final String EVENTS_HEADER = "session_id,timestamp_ms,frame_id,event_type,note";

  public final String sessionId;
  public final String personId;
  public final File sessionDir;
  public final File cropsDir;
  public final File overlaysDir;
  public final File frameLogCsv;
  public final File detectionsCsv;
  public final File eventsCsv;
  public final long startedAtMs;

  public int frameRows = 0;
  public int detectionRows = 0;
  public int cropCount = 0;
  public int eventRows = 0;

  public PersonSequenceSession(Context context, String personId) {
    this.personId = personId;
    this.startedAtMs = System.currentTimeMillis();
    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    this.sessionId = personId + "_seq_" + timestamp;

    File baseDir =
        new File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "cartfollow_sequences");
    this.sessionDir = new File(baseDir, sessionId);
    this.cropsDir = new File(sessionDir, "crops");
    this.overlaysDir = new File(sessionDir, "overlays");
    this.cropsDir.mkdirs();
    this.overlaysDir.mkdirs();
    this.frameLogCsv = new File(sessionDir, "frame_log.csv");
    this.detectionsCsv = new File(sessionDir, "detections.csv");
    this.eventsCsv = new File(sessionDir, "events.csv");
  }

  public void initCsvFiles() {
    writeHeader(frameLogCsv, FRAME_LOG_HEADER);
    writeHeader(detectionsCsv, DETECTIONS_HEADER);
    writeHeader(eventsCsv, EVENTS_HEADER);
  }

  private void writeHeader(File file, String header) {
    try (FileWriter writer = new FileWriter(file, false)) {
      writer.append(header).append('\n');
    } catch (IOException e) {
      Log.e(TAG, "Failed to init csv: " + file.getName(), e);
    }
  }

  public void writeSessionInfo(PersonSequenceCaptureConfig config, String detectorModelName) {
    JSONObject json = new JSONObject();
    try {
      json.put("session_id", sessionId);
      json.put("person_id", personId);
      json.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
      json.put("collector", "PersonSequenceCollector");
      json.put("mode", "SEQUENCE");
      json.put("frame_log_interval_ms", config.frameLogIntervalMs);
      json.put("crop_interval_ms", config.cropIntervalMs);
      json.put("overlay_interval_ms", config.overlayIntervalMs);
      json.put("min_confidence", config.minConfidence);
      json.put("save_crops", config.saveCrops);
      json.put("save_overlays", config.saveOverlays);
      json.put("bbox_padding_ratio", config.paddingRatio);
      json.put("jpeg_quality", config.jpegQuality);
      json.put("detector", detectorModelName == null ? "" : detectorModelName);
      json.put("app_mode", "PersonSequenceCollector");
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
