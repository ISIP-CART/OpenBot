package org.openbot.cropcollector;

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
import org.openbot.databinding.FragmentPersonCropCollectorBinding;
import org.openbot.env.ImageUtils;
import org.openbot.tflite.Detector;
import org.openbot.tflite.Model;
import org.openbot.tflite.Network;
import org.openbot.utils.CameraUtils;
import org.openbot.utils.Enums;
import timber.log.Timber;

public class PersonCropCollectorFragment extends CameraFragment {

  private FragmentPersonCropCollectorBinding binding;
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

  private final PersonCropCaptureConfig config = new PersonCropCaptureConfig();
  private PersonCropSession currentSession = null;
  private PersonCropSaver saver = null;
  private boolean captureEnabled = false;
  private long lastSaveTimeMs = 0;

  private final List<RectF> drawBoxes = new ArrayList<>();
  private int drawFrameWidth = 0;
  private int drawFrameHeight = 0;
  private int drawSensorOrientation = 0;

  private final Paint personBoxPaint = new Paint();
  private final Paint boxTextPaint = new Paint();

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    personBoxPaint.setColor(Color.GREEN);
    personBoxPaint.setStyle(Paint.Style.STROKE);
    personBoxPaint.setStrokeWidth(6.0f);
    boxTextPaint.setColor(Color.WHITE);
    boxTextPaint.setTextSize(36.0f);
  }

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentPersonCropCollectorBinding.inflate(inflater, container, false);
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

    binding.statusText.setText(getString(R.string.person_crop_idle));
    binding.btnStart.setEnabled(true);
    binding.btnStop.setEnabled(false);

    binding.intervalValue.setText(config.intervalMs + "ms");
    binding.minusInterval.setOnClickListener(
        v -> {
          if (config.intervalMs <= 100) return;
          config.intervalMs -= 100;
          binding.intervalValue.setText(config.intervalMs + "ms");
        });
    binding.plusInterval.setOnClickListener(
        v -> {
          if (config.intervalMs >= 2000) return;
          config.intervalMs += 100;
          binding.intervalValue.setText(config.intervalMs + "ms");
        });
    binding.singlePersonSwitch.setOnCheckedChangeListener(
        (buttonView, isChecked) -> config.singlePersonOnly = isChecked);

    binding.btnStart.setOnClickListener(v -> startCapture());
    binding.btnStop.setOnClickListener(v -> stopCapture());
  }

  private void startCapture() {
    String personId = binding.personIdInput.getText().toString().trim();
    if (personId.isEmpty()) {
      Toast.makeText(requireContext(), "请输入 Person ID", Toast.LENGTH_SHORT).show();
      return;
    }

    config.personId = personId;
    config.minConfidence = minConfidence;

    currentSession = new PersonCropSession(requireContext(), personId);
    currentSession.initMetadataCsv();
    currentSession.writeSessionInfo(config);

    if (saver != null) {
      saver.shutdown();
    }
    saver = new PersonCropSaver();
    captureEnabled = true;
    lastSaveTimeMs = 0;

    binding.btnStart.setEnabled(false);
    binding.btnStop.setEnabled(true);
    binding.personIdInput.setEnabled(false);
    binding.modelSpinner.setEnabled(false);
    binding.minusInterval.setEnabled(false);
    binding.plusInterval.setEnabled(false);
    binding.singlePersonSwitch.setEnabled(false);
    binding.statusText.setText("采集中: " + currentSession.sessionId);
  }

  private void stopCapture() {
    captureEnabled = false;
    if (saver != null) {
      saver.shutdown();
      saver = null;
    }
    int saved = currentSession != null ? currentSession.savedCount : 0;
    int skipped = currentSession != null ? currentSession.skippedCount : 0;
    String path = currentSession != null ? currentSession.sessionDir.getAbsolutePath() : "";

    binding.btnStart.setEnabled(true);
    binding.btnStop.setEnabled(false);
    binding.personIdInput.setEnabled(true);
    binding.modelSpinner.setEnabled(true);
    binding.minusInterval.setEnabled(true);
    binding.plusInterval.setEnabled(true);
    binding.singlePersonSwitch.setEnabled(true);
    binding.statusText.setText(
        String.format(Locale.US, "已停止: saved=%d skipped=%d\n%s", saved, skipped, path));
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
              ? "该模型未下载，请先在主菜单 Model Management 中下载: " + model.name
              : "模型加载失败: " + e.getMessage();
      requireActivity()
          .runOnUiThread(
              () ->
                  Toast.makeText(requireContext().getApplicationContext(), msg, Toast.LENGTH_LONG)
                      .show());
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
                          "模型配置失败: " + e.getMessage(),
                          Toast.LENGTH_LONG)
                      .show());
    }
  }

  @Override
  public synchronized void onResume() {
    croppedBitmap = null;
    handlerThread = new HandlerThread("inference");
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

            handleCropCapture(workingFrame, mappedRecognitions);

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

  private void handleCropCapture(Bitmap workingFrame, List<Detector.Recognition> persons) {
    if (!captureEnabled || currentSession == null || saver == null) return;

    long now = System.currentTimeMillis();
    if (now - lastSaveTimeMs < config.intervalMs) return;

    if (currentSession.savedCount >= config.maxCrops) {
      requireActivity().runOnUiThread(() -> stopCapture());
      return;
    }

    if (persons.isEmpty()) {
      currentSession.skippedCount++;
      return;
    }

    if (config.singlePersonOnly && persons.size() != 1) {
      currentSession.skippedCount++;
      return;
    }

    int numPersons = persons.size();
    if (config.singlePersonOnly) {
      int cropId = currentSession.savedCount + 1;
      currentSession.savedCount = cropId;
      saver.saveCropAsync(
          workingFrame, persons.get(0), 0, numPersons, currentSession, config, frameNum, cropId, sensorOrientation);
    } else {
      for (int i = 0; i < numPersons; i++) {
        int cropId = currentSession.savedCount + 1;
        currentSession.savedCount = cropId;
        saver.saveCropAsync(
            workingFrame, persons.get(i), i, numPersons, currentSession, config, frameNum, cropId, sensorOrientation);
      }
    }
    lastSaveTimeMs = now;
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
    for (RectF box : snapshot) {
      RectF rect = new RectF(box);
      matrix.mapRect(rect);
      float cornerSize = Math.min(rect.width(), rect.height()) / 8.0f;
      canvas.drawRoundRect(rect, cornerSize, cornerSize, personBoxPaint);
    }
  }

  private void updateDebugInfo(int persons, float fps) {
    if (binding == null) return;
    int saved = currentSession != null ? currentSession.savedCount : 0;
    int skipped = currentSession != null ? currentSession.skippedCount : 0;
    String info =
        String.format(
            Locale.US,
            "persons=%d\nsaved=%d\nskipped=%d\nfps=%.1f",
            persons,
            saved,
            skipped,
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
