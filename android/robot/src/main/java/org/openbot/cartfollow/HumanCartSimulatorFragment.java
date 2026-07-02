package org.openbot.cartfollow;

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
import org.openbot.databinding.FragmentHumanCartSimulatorBinding;
import org.openbot.env.ImageUtils;
import org.openbot.tflite.Detector;
import org.openbot.tflite.Model;
import org.openbot.tflite.Network;
import org.openbot.utils.CameraUtils;
import org.openbot.utils.Enums;
import org.openbot.vehicle.Control;
import timber.log.Timber;

public class HumanCartSimulatorFragment extends CameraFragment {

  private FragmentHumanCartSimulatorBinding binding;
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

  private final ControlGenerator controlGenerator = new ControlGenerator();
  private final HumanCommandInterpreter interpreter = new HumanCommandInterpreter();

  private final List<DrawBox> drawBoxes = new ArrayList<>();
  private int drawFrameWidth = 0;
  private int drawFrameHeight = 0;
  private int drawSensorOrientation = 0;

  private final Paint targetBoxPaint = new Paint();
  private final Paint personBoxPaint = new Paint();
  private final Paint boxTextPaint = new Paint();

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    targetBoxPaint.setColor(Color.GREEN);
    targetBoxPaint.setStyle(Paint.Style.STROKE);
    targetBoxPaint.setStrokeWidth(8.0f);
    personBoxPaint.setColor(Color.WHITE);
    personBoxPaint.setStyle(Paint.Style.STROKE);
    personBoxPaint.setStrokeWidth(6.0f);
    boxTextPaint.setColor(Color.WHITE);
    boxTextPaint.setTextSize(40.0f);
  }

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentHumanCartSimulatorBinding.inflate(inflater, container, false);
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
          controlGenerator.MIN_CONFIDENCE = minConfidence;
        });
    binding.minusConfidence.setOnClickListener(
        v -> {
          int confValue = (int) (minConfidence * 100);
          if (confValue <= 5) return;
          confValue -= 5;
          minConfidence = confValue / 100f;
          binding.confidenceValue.setText(confValue + "%");
          controlGenerator.MIN_CONFIDENCE = minConfidence;
        });

    List<String> models = getModelNames(f -> f.type.equals(Model.TYPE.DETECTOR));
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getObjectNavModel());

    setAnalyserResolution(Enums.Preview.HD.getValue());

    binding.trackingOverlay.addCallback(canvas -> drawOverlay(canvas));

    binding.startSwitch.setChecked(false);
    binding.startSwitch.setOnClickListener(
        v -> {
          if (binding.startSwitch.isChecked()) {
            binding.modelSpinner.setEnabled(false);
          } else {
            binding.modelSpinner.setEnabled(true);
            updateCommandText(getString(R.string.cart_sim_idle));
            updateDebugInfo(FollowState.IDLE, new Control(0f, 0f), 0, 0f);
          }
        });
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
    if (binding == null || !binding.startSwitch.isChecked()) return;
    if (computingNetwork) return;

    computingNetwork = true;
    runInBackground(
        () -> {
          final Canvas canvas = new Canvas(croppedBitmap);
          if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            canvas.drawBitmap(
                CameraUtils.flipBitmapHorizontal(bitmap), frameToCropTransform, null);
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
            ControlGenerator.Result gen =
                controlGenerator.generate(mappedRecognitions, frameW, frameH, sensorOrientation);

            FollowState state;
            if (gen.target == null) state = FollowState.LOST;
            else if (gen.tooClose) state = FollowState.STOP;
            else state = FollowState.FOLLOW;

            String command = interpreter.interpret(gen.control, state, gen.tooClose);

            updateDrawState(mappedRecognitions, gen.target, frameW, frameH, sensorOrientation);
            updateCommandText(command);
            float fps = lastProcessingTimeMs > 0 ? 1000f / lastProcessingTimeMs : 0f;
            updateDebugInfo(state, gen.control, gen.persons.size(), fps);
            binding.trackingOverlay.postInvalidate();
          }
          computingNetwork = false;
        });
  }

  private void updateCropImageInfo() {
    sensorOrientation = 90 - ImageUtils.getScreenOrientation(requireActivity());
    recreateNetwork(getModel(), getDevice(), getNumThreads());
  }

  private synchronized void updateDrawState(
      List<Detector.Recognition> recognitions,
      Detector.Recognition target,
      int frameW,
      int frameH,
      int sensorOrientation) {
    drawBoxes.clear();
    for (Detector.Recognition r : recognitions) {
      if (r.getLocation() == null) continue;
      drawBoxes.add(new DrawBox(new RectF(r.getLocation()), r == target));
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

    List<DrawBox> snapshot;
    synchronized (this) {
      snapshot = new ArrayList<>(drawBoxes);
    }
    for (DrawBox box : snapshot) {
      RectF rect = new RectF(box.location);
      matrix.mapRect(rect);
      Paint paint = box.isTarget ? targetBoxPaint : personBoxPaint;
      float cornerSize = Math.min(rect.width(), rect.height()) / 8.0f;
      canvas.drawRoundRect(rect, cornerSize, cornerSize, paint);
      if (box.isTarget) {
        canvas.drawText("目标", rect.left + cornerSize, rect.top, boxTextPaint);
      }
    }
  }

  private void updateCommandText(String text) {
    if (binding == null) return;
    requireActivity().runOnUiThread(() -> binding.commandText.setText(text));
  }

  private void updateDebugInfo(FollowState state, Control control, int persons, float fps) {
    if (binding == null) return;
    float forward = (control.getLeft() + control.getRight()) / 2f;
    float turn = (control.getRight() - control.getLeft()) / 2f;
    String info =
        String.format(
            Locale.US,
            "state=%s\nforward=%.2f\nturn=%.2f\nleft=%.2f\nright=%.2f\npersons=%d\nfps=%.1f",
            state.name(),
            forward,
            turn,
            control.getLeft(),
            control.getRight(),
            persons,
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

  private static class DrawBox {
    final RectF location;
    final boolean isTarget;

    DrawBox(RectF location, boolean isTarget) {
      this.location = location;
      this.isTarget = isTarget;
    }
  }
}
