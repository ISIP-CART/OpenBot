package org.openbot.sequencecollector;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.openbot.R;
import org.openbot.common.CameraFragment;
import org.openbot.databinding.FragmentPersonSequenceCollectorBinding;
import org.openbot.env.ImageUtils;
import org.openbot.tflite.Detector;
import org.openbot.tflite.Model;
import org.openbot.tflite.Network;
import org.openbot.utils.CameraUtils;
import org.openbot.utils.Enums;
import timber.log.Timber;

public class PersonSequenceCollectorFragment extends CameraFragment {

  private FragmentPersonSequenceCollectorBinding binding;
  private Handler handler;
  private HandlerThread handlerThread;

  private boolean computingNetwork = false;
  private float minConfidence = 0.5f;

  private Detector detector;
  private Matrix frameToCropTransform;
  private Bitmap croppedBitmap;
  private int sensorOrientation;
  private Matrix cropToFrameTransform;

  private Model model;
  private Network.Device device = Network.Device.CPU;
  private int numThreads = -1;
  private final String classType = "person";

  private long lastProcessingTimeMs = -1;
  private long frameNum = 0;

  private final PersonSequenceCaptureConfig config = new PersonSequenceCaptureConfig();
  private PersonSequenceSession currentSession = null;
  private PersonSequenceSaver saver = null;
  private boolean captureEnabled = false;
  private long lastFrameLogTimeMs = 0;
  private long lastCropTimeMs = 0;
  private String pendingEventTag = "";
  private String pendingEventNote = "";

  private final List<RectF> drawBoxes = new ArrayList<>();
  private int drawFrameWidth = 0;
  private int drawFrameHeight = 0;
  private int drawSensorOrientation = 0;

  private final Paint personBoxPaint = new Paint();
  private final Paint boxTextPaint = new Paint();

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    personBoxPaint.setColor(Color.CYAN);
    personBoxPaint.setStyle(Paint.Style.STROKE);
    personBoxPaint.setStrokeWidth(6.0f);
    boxTextPaint.setColor(Color.WHITE);
    boxTextPaint.setTextSize(36.0f);
  }

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentPersonSequenceCollectorBinding.inflate(inflater, container, false);
    return inflateFragment(binding, inflater, container);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    binding.confidenceValue.setText((int) (minConfidence * 100) + "%");
    binding.plusConfidence.setOnClickListener(
        v -> {
          int confValue = (int) (minConfidence * 100);
          if (confValue >= 95) return;
          confValue += 5;
          minConfidence = confValue / 100f;
          binding.confidenceValue.setText(confValue + "%");
        });
    binding.minusConfidence.setOnClickListener(
        v -> {
          int confValue = (int) (minConfidence * 100);
          if (confValue <= 5) return;
          confValue -= 5;
          minConfidence = confValue / 100f;
          binding.confidenceValue.setText(confValue + "%");
        });

    List<String> models = getModelNames(f -> f.type.equals(Model.TYPE.DETECTOR));
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getObjectNavModel());
    setAnalyserResolution(Enums.Preview.HD.getValue());

    binding.trackingOverlay.addCallback(canvas -> drawOverlay(canvas));
    binding.statusText.setText(getString(R.string.person_sequence_idle));
    binding.btnStart.setEnabled(true);
    binding.btnStop.setEnabled(false);

    binding.frameIntervalValue.setText(config.frameLogIntervalMs + "ms");
    binding.minusFrameInterval.setOnClickListener(v -> adjustFrameInterval(-100));
    binding.plusFrameInterval.setOnClickListener(v -> adjustFrameInterval(100));

    binding.cropIntervalValue.setText(config.cropIntervalMs + "ms");
    binding.minusCropInterval.setOnClickListener(v -> adjustCropInterval(-100));
    binding.plusCropInterval.setOnClickListener(v -> adjustCropInterval(100));

    binding.saveCropsSwitch.setChecked(config.saveCrops);
    binding.saveCropsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> config.saveCrops = isChecked);

    binding.btnStart.setOnClickListener(v -> startCapture());
    binding.btnStop.setOnClickListener(v -> stopCapture());
    binding.btnTargetLeft.setOnClickListener(v -> recordManualEvent("target_left"));
    binding.btnTargetReturn.setOnClickListener(v -> recordManualEvent("target_return"));
    binding.btnOcclusionStart.setOnClickListener(v -> recordManualEvent("occlusion_start"));
    binding.btnOcclusionEnd.setOnClickListener(v -> recordManualEvent("occlusion_end"));
    binding.btnDistractorEnter.setOnClickListener(v -> recordManualEvent("distractor_enter"));
    binding.btnDistractorLeave.setOnClickListener(v -> recordManualEvent("distractor_leave"));
    binding.btnManualNote.setOnClickListener(v -> recordManualEvent("manual_note"));
  }

  private void adjustFrameInterval(long deltaMs) {
    long next = config.frameLogIntervalMs + deltaMs;
    if (next < 100 || next > 2000) return;
    config.frameLogIntervalMs = next;
    binding.frameIntervalValue.setText(config.frameLogIntervalMs + "ms");
  }

  private void adjustCropInterval(long deltaMs) {
    long next = config.cropIntervalMs + deltaMs;
    if (next < 200 || next > 3000) return;
    config.cropIntervalMs = next;
    binding.cropIntervalValue.setText(config.cropIntervalMs + "ms");
  }

  private void startCapture() {
    String personId = binding.personIdInput.getText().toString().trim();
    if (personId.isEmpty()) {
      Toast.makeText(requireContext(), "Please input Person ID", Toast.LENGTH_SHORT).show();
      return;
    }

    config.personId = personId;
    config.minConfidence = minConfidence;

    currentSession = new PersonSequenceSession(requireContext(), personId);
    currentSession.initCsvFiles();
    currentSession.writeSessionInfo(config, model == null ? "" : model.name);

    if (saver != null) {
      saver.shutdown();
    }
    saver = new PersonSequenceSaver();
    captureEnabled = true;
    lastFrameLogTimeMs = 0;
    lastCropTimeMs = 0;
    pendingEventTag = "";
    pendingEventNote = "";

    setCaptureControlsEnabled(false);
    binding.statusText.setText("Collecting: " + currentSession.sessionId);
  }

  private void stopCapture() {
    captureEnabled = false;
    if (saver != null) {
      saver.shutdown();
      saver = null;
    }
    String path = currentSession != null ? currentSession.sessionDir.getAbsolutePath() : "";
    int frames = currentSession != null ? currentSession.frameRows : 0;
    int detections = currentSession != null ? currentSession.detectionRows : 0;
    int crops = currentSession != null ? currentSession.cropCount : 0;
    int events = currentSession != null ? currentSession.eventRows : 0;

    setCaptureControlsEnabled(true);
    binding.statusText.setText(
        String.format(
            Locale.US,
            "Stopped frames=%d det=%d crops=%d events=%d\n%s",
            frames,
            detections,
            crops,
            events,
            path));
  }

  private void setCaptureControlsEnabled(boolean enabled) {
    binding.btnStart.setEnabled(enabled);
    binding.btnStop.setEnabled(!enabled);
    binding.personIdInput.setEnabled(enabled);
    binding.modelSpinner.setEnabled(enabled);
    binding.minusFrameInterval.setEnabled(enabled);
    binding.plusFrameInterval.setEnabled(enabled);
    binding.minusCropInterval.setEnabled(enabled);
    binding.plusCropInterval.setEnabled(enabled);
    binding.saveCropsSwitch.setEnabled(enabled);
  }

  private void recordManualEvent(String eventType) {
    String note = binding.noteInput.getText().toString().trim();
    if (captureEnabled && currentSession != null && saver != null) {
      pendingEventTag = eventType;
      pendingEventNote = note;
      saver.saveEventAsync(currentSession, frameNum, eventType, note);
      binding.statusText.setText("Event: " + eventType);
    } else {
      Toast.makeText(requireContext(), "Start a sequence session first", Toast.LENGTH_SHORT).show();
    }
  }

  protected void onInferenceConfigurationChanged() {
    computingNetwork = false;
    if (croppedBitmap == null) return;
    final Network.Device device = getDevice();
    final Model model = getModel();
    final int numThreads = getNumThreads();
    runInBackground(() -> recreateNetwork(model, device, numThreads));
  }

  private void recreateNetwork(Model model, Network.Device device, int numThreads) {
    if (model == null) return;
    Detector newDetector = null;
    try {
      newDetector = Detector.create(requireActivity(), model, device, numThreads);
    } catch (IllegalArgumentException | IOException e) {
      Timber.e(e, "Failed to create network.");
      String msg =
          model.pathType == Model.PATH_TYPE.URL
              ? "Model not downloaded. Download it in Model Management first: " + model.name
              : "Failed to load model: " + e.getMessage();
      requireActivity()
          .runOnUiThread(
              () -> Toast.makeText(requireContext().getApplicationContext(), msg, Toast.LENGTH_LONG).show());
      return;
    }

    if (detector != null) {
      detector.close();
    }
    detector = newDetector;
    try {
      croppedBitmap =
          Bitmap.createBitmap(
              detector.getImageSizeX(), detector.getImageSizeY(), Bitmap.Config.ARGB_8888);
      frameToCropTransform =
          ImageUtils.getTransformationMatrix(
              getMaxAnalyseImageSize().getWidth(),
              getMaxAnalyseImageSize().getHeight(),
              croppedBitmap.getWidth(),
              croppedBitmap.getHeight(),
              sensorOrientation,
              detector.getCropRect(),
              detector.getMaintainAspect());
      cropToFrameTransform = new Matrix();
      frameToCropTransform.invert(cropToFrameTransform);
    } catch (Exception e) {
      Timber.e(e, "Failed to configure detector.");
      requireActivity()
          .runOnUiThread(
              () ->
                  Toast.makeText(
                          requireContext().getApplicationContext(),
                          "Failed to configure model: " + e.getMessage(),
                          Toast.LENGTH_LONG)
                      .show());
    }
  }

  @Override
  public synchronized void onResume() {
    croppedBitmap = null;
    handlerThread = new HandlerThread("sequence-inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    super.onResume();
  }

  @Override
  public synchronized void onPause() {
    if (captureEnabled) {
      stopCapture();
    }
    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
    super.onPause();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) handler.post(r);
  }

  @Override
  protected void processUSBData(String data) {}

  @Override
  protected void processControllerKeyData(String commandType) {}

  @Override
  protected void processFrame(Bitmap bitmap, ImageProxy image) {
    if (detector == null) {
      updateCropImageInfo();
      if (detector == null) return;
    }

    ++frameNum;
    if (binding == null) return;
    if (computingNetwork) return;

    computingNetwork = true;
    runInBackground(
        () -> {
          final Canvas canvas = new Canvas(croppedBitmap);
          Bitmap workingFrame = bitmap;
          if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            Bitmap flipped = CameraUtils.flipBitmapHorizontal(bitmap);
            canvas.drawBitmap(flipped, frameToCropTransform, null);
            workingFrame = flipped;
          } else {
            canvas.drawBitmap(bitmap, frameToCropTransform, null);
          }

          if (detector != null) {
            final long startTime = SystemClock.elapsedRealtime();
            final List<Detector.Recognition> results =
                detector.recognizeImage(croppedBitmap, classType);
            lastProcessingTimeMs = SystemClock.elapsedRealtime() - startTime;

            final List<Detector.Recognition> mappedRecognitions = new ArrayList<>();
            for (final Detector.Recognition result : results) {
              final RectF location = result.getLocation();
              if (location != null && result.getConfidence() >= minConfidence) {
                cropToFrameTransform.mapRect(location);
                result.setLocation(location);
                mappedRecognitions.add(result);
              }
            }

            int frameW = getMaxAnalyseImageSize().getWidth();
            int frameH = getMaxAnalyseImageSize().getHeight();
            updateDrawState(mappedRecognitions, frameW, frameH, sensorOrientation);
            float fps = lastProcessingTimeMs > 0 ? 1000f / lastProcessingTimeMs : 0f;

            handleSequenceCapture(workingFrame, mappedRecognitions, frameW, frameH);
            updateDebugInfo(mappedRecognitions.size(), fps);
            binding.trackingOverlay.postInvalidate();
          }
          computingNetwork = false;
        });
  }

  private void updateCropImageInfo() {
    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());
    recreateNetwork(getModel(), getDevice(), getNumThreads());
  }

  private void handleSequenceCapture(
      Bitmap workingFrame, List<Detector.Recognition> persons, int frameW, int frameH) {
    if (!captureEnabled || currentSession == null || saver == null) return;

    long now = System.currentTimeMillis();
    if (now - lastFrameLogTimeMs < config.frameLogIntervalMs) return;

    boolean saveCropsForThisFrame = config.saveCrops && now - lastCropTimeMs >= config.cropIntervalMs;
    String eventTag = pendingEventTag;
    String eventNote = pendingEventNote;
    pendingEventTag = "";
    pendingEventNote = "";

    saver.saveFrameAsync(
        workingFrame,
        persons,
        currentSession,
        config,
        frameNum,
        frameW,
        frameH,
        sensorOrientation,
        saveCropsForThisFrame,
        eventTag,
        eventNote);

    lastFrameLogTimeMs = now;
    if (saveCropsForThisFrame) {
      lastCropTimeMs = now;
    }
  }

  private synchronized void updateDrawState(
      List<Detector.Recognition> persons, int frameW, int frameH, int sensorOrientation) {
    drawBoxes.clear();
    for (Detector.Recognition r : persons) {
      if (r == null || r.getLocation() == null) continue;
      drawBoxes.add(new RectF(r.getLocation()));
    }
    drawFrameWidth = frameW;
    drawFrameHeight = frameH;
    drawSensorOrientation = sensorOrientation;
  }

  private void drawOverlay(Canvas canvas) {
    if (drawFrameWidth <= 0 || drawFrameHeight <= 0) return;
    final boolean rotated = drawSensorOrientation % 180 == 90;
    final float multiplier =
        Math.min(
            canvas.getHeight() / (float) (rotated ? drawFrameWidth : drawFrameHeight),
            canvas.getWidth() / (float) (rotated ? drawFrameHeight : drawFrameWidth));
    Matrix matrix =
        ImageUtils.getTransformationMatrix(
            drawFrameWidth,
            drawFrameHeight,
            (int) (multiplier * (rotated ? drawFrameHeight : drawFrameWidth)),
            (int) (multiplier * (rotated ? drawFrameWidth : drawFrameHeight)),
            drawSensorOrientation,
            new RectF(0, 0, 0, 0),
            false);

    List<RectF> snapshot;
    synchronized (this) {
      snapshot = new ArrayList<>(drawBoxes);
    }
    int idx = 0;
    for (RectF box : snapshot) {
      RectF rect = new RectF(box);
      matrix.mapRect(rect);
      float cornerSize = Math.min(rect.width(), rect.height()) / 8.0f;
      canvas.drawRoundRect(rect, cornerSize, cornerSize, personBoxPaint);
      canvas.drawText("person " + idx, rect.left, Math.max(0, rect.top - 8), boxTextPaint);
      idx++;
    }
  }

  private void updateDebugInfo(int persons, float fps) {
    if (binding == null) return;
    int frames = currentSession != null ? currentSession.frameRows : 0;
    int detections = currentSession != null ? currentSession.detectionRows : 0;
    int crops = currentSession != null ? currentSession.cropCount : 0;
    int events = currentSession != null ? currentSession.eventRows : 0;
    String info =
        String.format(
            Locale.US,
            "persons=%d\nframes=%d\ndet=%d\ncrops=%d\nevents=%d\nfps=%.1f",
            persons,
            frames,
            detections,
            crops,
            events,
            fps);
    requireActivity().runOnUiThread(() -> binding.debugInfo.setText(info));
  }

  protected Model getModel() {
    return model;
  }

  @Override
  protected void setModel(Model model) {
    if (this.model != model) {
      this.model = model;
      preferencesManager.setObjectNavModel(model.name);
      onInferenceConfigurationChanged();
    }
  }

  protected Network.Device getDevice() {
    return device;
  }

  protected int getNumThreads() {
    return numThreads;
  }
}
