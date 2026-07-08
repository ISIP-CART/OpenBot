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

  private static final int COLOR_TARGET = 0;
  private static final int COLOR_CANDIDATE = 1;
  private static final int COLOR_NORMAL = 2;
  private static final int COLOR_FAIL = 3;

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
  private final TargetMatcher matcher = new TargetMatcher();
  private final FollowStateMachine stateMachine =
      new FollowStateMachine(matcher, controlGenerator);
  private final ActionArbitrator actionArbitrator = new ActionArbitrator();
  private ReIDCoordinator reidCoordinator;

  private final List<DrawBox> drawBoxes = new ArrayList<>();
  private int drawFrameWidth = 0;
  private int drawFrameHeight = 0;
  private int drawSensorOrientation = 0;

  private final Paint targetBoxPaint = new Paint();
  private final Paint candidateBoxPaint = new Paint();
  private final Paint personBoxPaint = new Paint();
  private final Paint failBoxPaint = new Paint();
  private final Paint boxTextPaint = new Paint();

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    targetBoxPaint.setColor(Color.GREEN);
    targetBoxPaint.setStyle(Paint.Style.STROKE);
    targetBoxPaint.setStrokeWidth(8.0f);
    candidateBoxPaint.setColor(Color.YELLOW);
    candidateBoxPaint.setStyle(Paint.Style.STROKE);
    candidateBoxPaint.setStrokeWidth(8.0f);
    personBoxPaint.setColor(Color.WHITE);
    personBoxPaint.setStyle(Paint.Style.STROKE);
    personBoxPaint.setStrokeWidth(6.0f);
    failBoxPaint.setColor(Color.RED);
    failBoxPaint.setStyle(Paint.Style.STROKE);
    failBoxPaint.setStrokeWidth(8.0f);
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
    reidCoordinator = new ReIDCoordinator(requireActivity(), getNumThreads());

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

    binding.btnConfirm.setOnClickListener(
        v -> {
          if (reidCoordinator != null) reidCoordinator.confirmGallery();
          stateMachine.confirm();
        });
    binding.btnRetake.setOnClickListener(
        v -> {
          if (reidCoordinator != null) reidCoordinator.reset();
          stateMachine.retake();
        });
    binding.btnCancel.setOnClickListener(
        v -> {
          if (reidCoordinator != null) reidCoordinator.reset();
          stateMachine.cancel();
        });

    binding.startSwitch.setChecked(false);
    binding.startSwitch.setOnClickListener(
        v -> {
          if (binding.startSwitch.isChecked()) {
            binding.modelSpinner.setEnabled(false);
            if (reidCoordinator != null) reidCoordinator.reset();
            stateMachine.startCapture();
          } else {
            binding.modelSpinner.setEnabled(true);
            if (reidCoordinator != null) reidCoordinator.reset();
            stateMachine.cancel();
            resetUiToIdle();
          }
        });
  }

  private void resetUiToIdle() {
    updateCommandText(getString(R.string.cart_sim_idle));
    updateDebugInfo(FollowState.IDLE, new Control(0f, 0f), 0, 0f, null, null, null);
    if (binding != null) {
      binding.confirmPanel.setVisibility(View.GONE);
      binding.countdownText.setVisibility(View.GONE);
      binding.trackingOverlay.postInvalidate();
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
            FollowState currentState = stateMachine.getState();
            if (currentState == FollowState.CAPTURE_TARGET && reidCoordinator != null) {
              reidCoordinator.collectInitializationCandidate(
                  workingFrame, selectLargest(mappedRecognitions));
            }
            TargetMatcher.MatchResult legacyMatch =
                matcher.match(
                    mappedRecognitions, workingFrame, stateMachine.getMemory(), frameW, frameH);
            IdentityEvidence identity =
                reidCoordinator == null
                    ? null
                    : reidCoordinator.evaluate(
                        mappedRecognitions,
                        workingFrame,
                        stateMachine.getMemory(),
                        currentState,
                        frameW,
                        frameH,
                        legacyMatch.score,
                        legacyMatch.matched,
                        legacyMatch.best);
            FollowStateMachine.FrameResult fr =
                stateMachine.onFrame(
                    mappedRecognitions, workingFrame, frameW, frameH, sensorOrientation, identity);
            fr.behaviorDecision = decideBehavior(fr, frameW, frameH);

            updateDrawState(fr, frameW, frameH, sensorOrientation);
            updateCommandText(commandForState(fr));
            float fps = lastProcessingTimeMs > 0 ? 1000f / lastProcessingTimeMs : 0f;
            updateDebugInfo(
                fr.state,
                fr.control,
                fr.persons.size(),
                fps,
                fr.distanceEstimate,
                fr.behaviorDecision,
                fr.identityEvidence);
            updateUiForState(fr);
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
      FollowStateMachine.FrameResult fr, int frameW, int frameH, int sensorOrientation) {
    drawBoxes.clear();
    for (Detector.Recognition r : fr.persons) {
      if (r == null || r.getLocation() == null) continue;
      int colorType = COLOR_NORMAL;
      if (r == fr.target) {
        colorType = fr.matched ? COLOR_TARGET : COLOR_FAIL;
      } else if (r == fr.candidate) {
        colorType = COLOR_CANDIDATE;
      }
      drawBoxes.add(new DrawBox(new RectF(r.getLocation()), colorType));
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
      Paint paint;
      String label = null;
      switch (box.colorType) {
        case COLOR_TARGET:
          paint = targetBoxPaint;
          label = "目标";
          break;
        case COLOR_CANDIDATE:
          paint = candidateBoxPaint;
          label = "候选";
          break;
        case COLOR_FAIL:
          paint = failBoxPaint;
          label = "匹配失败";
          break;
        default:
          paint = personBoxPaint;
          break;
      }
      float cornerSize = Math.min(rect.width(), rect.height()) / 8.0f;
      canvas.drawRoundRect(rect, cornerSize, cornerSize, paint);
      if (label != null) {
        canvas.drawText(label, rect.left + cornerSize, rect.top, boxTextPaint);
      }
    }
  }

  private String commandForState(FollowStateMachine.FrameResult fr) {
    if (fr.behaviorDecision != null) {
      switch (fr.behaviorDecision.selectedAction) {
        case LOCAL_SEARCH_LEFT:
          return HumanCommandInterpreter.CMD_TURN_LEFT;
        case LOCAL_SEARCH_RIGHT:
          return HumanCommandInterpreter.CMD_TURN_RIGHT;
        case BLOCKED_WAIT:
          return "前方受阻，请停止等待";
        case MOTION_STOP:
        case HARD_STOP:
        case EMERGENCY_STOP:
          return HumanCommandInterpreter.CMD_STOP;
        case REACQUIRE_HOLD:
          return "疑似目标，请停止确认";
        case FOLLOW_SLOW:
        case FOLLOW_CAUTION:
        default:
          break;
      }
    }
    switch (fr.state) {
      case IDLE:
        return "待命，打开 Start 开始采集目标";
      case CAPTURE_TARGET:
        return "采集中，请保持站立";
      case LOCKED_PENDING_CONFIRM:
        return "请确认是否跟随此人";
      case CONFIRMED_ARMED:
        return "已确认，请回到车前";
      case REACQUIRE_TARGET:
        return "重识别中…";
      case READY_TO_FOLLOW:
        return fr.countdownSec >= 0 ? fr.countdownSec + " 秒后启动" : "准备启动";
      case FOLLOW:
      case FOLLOW_CAUTION:
        if (fr.distanceEstimate != null) {
          return interpreter.interpret(fr.control, fr.state, fr.distanceEstimate.state);
        }
        return interpreter.interpret(fr.control, fr.state, fr.tooClose);
      case IDENTITY_UNCERTAIN:
        return "身份不确定，请停止";
      case LOST:
        return "目标丢失，请停止";
      case SEARCH:
        return "原地搜索中…";
      case STOP:
        return "已停止";
      default:
        return "请停止";
    }
  }

  private void updateCommandText(String text) {
    if (binding == null) return;
    requireActivity().runOnUiThread(() -> binding.commandText.setText(text));
  }

  private void updateUiForState(FollowStateMachine.FrameResult fr) {
    if (binding == null) return;
    requireActivity()
        .runOnUiThread(
            () -> {
              if (binding == null) return;
              boolean showConfirm = fr.state == FollowState.LOCKED_PENDING_CONFIRM;
              binding.confirmPanel.setVisibility(showConfirm ? View.VISIBLE : View.GONE);
              if (showConfirm && fr.snapshot != null) {
                binding.snapshotView.setImageBitmap(fr.snapshot);
              }
              boolean showCountdown = fr.state == FollowState.READY_TO_FOLLOW;
              binding.countdownText.setVisibility(showCountdown ? View.VISIBLE : View.GONE);
              if (showCountdown) {
                binding.countdownText.setText(
                    fr.countdownSec >= 0 ? String.valueOf(fr.countdownSec) : "");
              }
            });
  }

  private void updateDebugInfo(
      FollowState state,
      Control control,
      int persons,
      float fps,
      ImageSetpointDistanceEstimator.DistanceEstimate dist,
      BehaviorDecisionResult behaviorDecision,
      IdentityEvidence identityEvidence) {
    if (binding == null) return;
    float forward = (control.getLeft() + control.getRight()) / 2f;
    float turn = (control.getRight() - control.getLeft()) / 2f;
    String distLine;
    if (dist != null) {
      distLine =
          String.format(
              Locale.US,
              "dist=%s\nhScale=%.2f\naScale=%.2f\nbShift=%+.3f\ndistConf=%.2f",
              dist.state.name(),
              dist.heightScale,
              dist.areaScale,
              dist.bottomShift,
              dist.confidence);
    } else {
      distLine = "dist=-";
    }
    String behaviorLine;
    if (behaviorDecision != null) {
      behaviorLine =
          String.format(
              Locale.US,
              "action=%s\nactionReason=%s\nsafetyBlock=%s\nactionConf=%.2f",
              behaviorDecision.selectedAction.name(),
              behaviorDecision.actionReason,
              behaviorDecision.safetyBlockReason == null ? "-" : behaviorDecision.safetyBlockReason,
              behaviorDecision.confidence);
      if (behaviorDecision.traversabilityEvidence != null) {
        TraversabilityEvidence trav = behaviorDecision.traversabilityEvidence;
        behaviorLine +=
            String.format(
                Locale.US,
                "\ncenterBlocked=%s\nfreeLCR=%.2f/%.2f/%.2f\ntravReason=%s",
                trav.centerBlocked,
                trav.leftFreeScore,
                trav.centerFreeScore,
                trav.rightFreeScore,
                trav.reason);
      }
    } else {
      behaviorLine = "action=-\nactionReason=-\nsafetyBlock=-\nactionConf=0.00";
    }
    String identityLine = buildIdentityDebugLine(identityEvidence);
    String info =
        String.format(
            Locale.US,
            "state=%s\nforward=%.2f\nturn=%.2f\nleft=%.2f\nright=%.2f\npersons=%d\nfps=%.1f\n%s\n%s\n%s",
            state.name(),
            forward,
            turn,
            control.getLeft(),
            control.getRight(),
            persons,
            fps,
            distLine,
            behaviorLine,
            identityLine);
    requireActivity().runOnUiThread(() -> binding.debugInfo.setText(info));
  }

  private String buildIdentityDebugLine(IdentityEvidence identity) {
    if (identity == null) {
      return "reidAvailable=false\ngallerySize=0\nbestScore=0.000\nsecondScore=0.000\nmargin=0.000\nweak/mid/strong=false/false/false\nbboxDefault=false bboxStrict=false prediction=false\nstableMatchCount=0\ncandidateSwitchCount=0\nreidLatencyMs=0\nreidReason=-";
    }
    ReIDMatchResult reid = identity.reidMatch;
    BboxContinuityEvidence bbox = identity.bboxContinuity;
    boolean reidAvailable = reid != null && reid.reidAvailable;
    int gallerySize = reid == null ? 0 : reid.gallerySize;
    float best = reid == null ? 0f : reid.bestScore;
    float second = reid == null ? 0f : reid.secondScore;
    float margin = reid == null ? 0f : reid.margin;
    long latency = reid == null ? 0L : reid.latencyMs;
    String reason = reid == null ? identity.reason : reid.reason;
    return String.format(
        Locale.US,
        "reidAvailable=%s\ngallerySize=%d\nbestScore=%.3f\nsecondScore=%.3f\nmargin=%.3f\nweak/mid/strong=%s/%s/%s\nbboxDefault=%s bboxStrict=%s prediction=%s\nstableMatchCount=%d\ncandidateSwitchCount=%d\nreidLatencyMs=%d\nreidReason=%s",
        reidAvailable,
        gallerySize,
        best,
        second,
        margin,
        identity.weakOk(),
        identity.midOk(),
        identity.strongOk(),
        bbox != null && bbox.bboxDefaultOk,
        bbox != null && bbox.bboxStrictOk,
        bbox != null && bbox.predictionOk,
        identity.stableMatchCount,
        identity.candidateSwitchCount,
        latency,
        reason == null ? "-" : reason);
  }

  private BehaviorDecisionResult decideBehavior(
      FollowStateMachine.FrameResult fr, int frameW, int frameH) {
    IdentityEvidence identity =
        fr.identityEvidence != null
            ? fr.identityEvidence
            : new IdentityEvidence(
                fr.matched ? fr.matchScore : 0f,
                fr.matchScore,
                fr.matched,
                fr.matched ? "matched" : "not_matched");
    DistanceEvidence distance;
    if (fr.distanceEstimate != null) {
      distance =
          new DistanceEvidence(
              fr.distanceEstimate.state,
              fr.distanceEstimate.confidence,
              fr.distanceEstimate.failureReason);
    } else {
      distance = new DistanceEvidence(DistanceState.UNKNOWN, 0f, "distance_not_available");
    }
    TraversabilityEvidence traversability = estimateTraversability(fr, frameW, frameH);
    SystemSafetyEvidence safety =
        new SystemSafetyEvidence(
            false, true, detector != null, detector == null ? "detector_not_ready" : "ok");
    BehaviorDecisionResult decision =
        actionArbitrator.decide(
            fr.state, identity, distance, traversability, safety, stateMachine.getMemory(), frameW);
    return new BehaviorDecisionResult(
        decision.state,
        decision.selectedAction,
        decision.actionReason,
        decision.safetyBlockReason,
        decision.confidence,
        distance,
        traversability);
  }

  private TraversabilityEvidence estimateTraversability(
      FollowStateMachine.FrameResult fr, int frameW, int frameH) {
    if (fr == null || fr.persons == null || frameW <= 0 || frameH <= 0) {
      return new TraversabilityEvidence(1f, 1f, 1f, false, "default_clear");
    }
    boolean centerBlocked = false;
    float centerFreeScore = 1f;
    for (Detector.Recognition person : fr.persons) {
      if (person == null || person == fr.target || person.getLocation() == null) continue;
      RectF b = person.getLocation();
      float cxRatio = b.centerX() / frameW;
      boolean inCenter = cxRatio >= 0.33f && cxRatio <= 0.67f;
      boolean lowerBodyRisk = b.bottom >= frameH * 0.55f;
      boolean largeEnough = b.width() * b.height() >= frameW * frameH * 0.03f;
      if (inCenter && lowerBodyRisk && largeEnough) {
        centerBlocked = true;
        centerFreeScore = Math.min(centerFreeScore, 0.2f);
      }
    }
    return new TraversabilityEvidence(
        1f,
        centerFreeScore,
        1f,
        centerBlocked,
        centerBlocked ? "non_target_in_center_corridor" : "default_clear");
  }

  protected Model getModel() {
    return model;
  }

  private static Detector.Recognition selectLargest(List<Detector.Recognition> persons) {
    Detector.Recognition target = null;
    float maxArea = -1f;
    if (persons == null) return null;
    for (Detector.Recognition r : persons) {
      if (r == null || r.getLocation() == null) continue;
      RectF loc = r.getLocation();
      float area = loc.width() * loc.height();
      if (area > maxArea) {
        maxArea = area;
        target = r;
      }
    }
    return target;
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
    final int colorType;

    DrawBox(RectF location, int colorType) {
      this.location = location;
      this.colorType = colorType;
    }
  }
}
