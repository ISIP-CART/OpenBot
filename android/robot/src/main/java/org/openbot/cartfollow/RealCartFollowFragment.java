package org.openbot.cartfollow;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import androidx.navigation.Navigation;
import org.openbot.BuildConfig;
import org.openbot.R;
import org.openbot.vehicle.Control;

/** Camera-based cart following with BLE manual control and guarded experimental autonomy. */
public class RealCartFollowFragment extends BaseCartFollowFragment {
  private static final long COMMAND_REPEAT_MS = 100L;
  private static final long HANDSHAKE_RETRY_MS = 500L;
  private static final long AUTO_UNLOCK_HOLD_MS = 2000L;

  private final RealCartSafetyController safetyController = new RealCartSafetyController();
  private final ManualControlArbiter manualControlArbiter = new ManualControlArbiter();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private volatile RealCartSafetyController.Output latestOutput =
      RealCartSafetyController.stop("idle");
  private boolean schedulerRunning;
  private long lastHandshakeRequestMs;
  private View activeManualButton;

  private final Runnable commandScheduler =
      new Runnable() {
        @Override
        public void run() {
          if (!schedulerRunning || binding == null) return;
          updateConnectionState();
          long now = SystemClock.elapsedRealtime();
          if (vehicle.isBleSerialReady()
              && !vehicle.isCartFirmwareReady()
              && now - lastHandshakeRequestMs >= HANDSHAKE_RETRY_MS) {
            vehicle.requestVehicleConfig();
            lastHandshakeRequestMs = now;
          }
          RealCartSafetyController.Output watchdog = safetyController.watchdog(now);
          if (watchdog != null) latestOutput = watchdog;
          if (safetyController.getMode() == RealCartSafetyController.Mode.MANUAL
              && manualControlArbiter.getActiveControl() == null) {
            latestOutput = RealCartSafetyController.stop("manual_idle");
          }
          sendOutput(latestOutput);
          refreshRealUi();
          mainHandler.postDelayed(this, COMMAND_REPEAT_MS);
        }
      };

  @Override
  protected void onCartFollowViewCreated() {
    vehicle.useBluetoothConnection();
    binding.realControlPanel.setVisibility(View.VISIBLE);
    binding.realModeGroup.check(R.id.real_mode_manual);
    binding.startSwitch.setChecked(false);
    binding.startSwitch.setEnabled(false);

    binding.realModeGroup.addOnButtonCheckedListener(
        (group, checkedId, isChecked) -> {
          if (!isChecked) return;
          setMode(
              checkedId == R.id.real_mode_auto
                  ? RealCartSafetyController.Mode.AUTO
                  : RealCartSafetyController.Mode.MANUAL);
        });

    installDeadMan(
        binding.driveForward,
        ManualControlArbiter.Control.FORWARD,
        RealCartSafetyController.MANUAL_FORWARD,
        RealCartSafetyController.MANUAL_FORWARD);
    installDeadMan(
        binding.driveBackward,
        ManualControlArbiter.Control.BACKWARD,
        -RealCartSafetyController.MANUAL_REVERSE,
        -RealCartSafetyController.MANUAL_REVERSE);
    installDeadMan(
        binding.driveLeft,
        ManualControlArbiter.Control.LEFT,
        -RealCartSafetyController.MANUAL_TURN,
        RealCartSafetyController.MANUAL_TURN);
    installDeadMan(
        binding.driveRight,
        ManualControlArbiter.Control.RIGHT,
        RealCartSafetyController.MANUAL_TURN,
        -RealCartSafetyController.MANUAL_TURN);

    binding.connectBle.setOnClickListener(
        v -> Navigation.findNavController(requireView()).navigate(R.id.open_bluetooth_fragment));
    installAutoUnlock();
    binding
        .getRoot()
        .getViewTreeObserver()
        .addOnWindowFocusChangeListener(
            hasFocus -> {
              if (!hasFocus
                  && binding != null
                  && safetyController.getMode() == RealCartSafetyController.Mode.MANUAL) {
                invalidateManualControl("window_focus_lost", true);
              }
            });
    binding.emergencyStop.setOnClickListener(
        v -> {
          safetyController.latchEmergency();
          invalidateManualControl("emergency_stop", true);
          vehicle.emergencyStop();
          binding.startSwitch.setChecked(false);
          binding.startSwitch.setEnabled(false);
          stateMachine.cancel();
          refreshRealUi();
        });
    setMode(RealCartSafetyController.Mode.MANUAL);
  }

  @Override
  public synchronized void onResume() {
    super.onResume();
    safetyController.setForeground(true);
    if (vehicle.isBleSerialReady()) vehicle.startHeartbeat();
    startScheduler();
  }

  @Override
  protected void onCartFollowPause() {
    safetyController.setForeground(false);
    invalidateManualControl("paused", true);
    vehicle.stopHeartbeat();
    schedulerRunning = false;
    mainHandler.removeCallbacks(commandScheduler);
    if (binding != null) {
      binding.startSwitch.setChecked(false);
      binding.startSwitch.setEnabled(false);
    }
    stateMachine.cancel();
  }

  @Override
  protected boolean isInferenceEnabled() {
    return safetyController.getMode() == RealCartSafetyController.Mode.AUTO
        && safetyController.isAutoUnlocked()
        && binding.startSwitch.isChecked();
  }

  @Override
  protected void onFollowFrame(FollowStateMachine.FrameResult frameResult) {
    latestOutput = safetyController.auto(frameResult, SystemClock.elapsedRealtime());
  }

  @Override
  protected SystemSafetyEvidence createSystemSafetyEvidence() {
    boolean communicationReady = vehicle != null && vehicle.isCartFirmwareReady();
    return new SystemSafetyEvidence(
        safetyController.isEmergencyLatched(),
        communicationReady,
        isDetectorReady(),
        communicationReady ? (isDetectorReady() ? "ok" : "detector_not_ready") : "ble_not_ready");
  }

  @Override
  protected void processUSBData(String data) {
    updateConnectionState();
  }

  private void setMode(RealCartSafetyController.Mode mode) {
    invalidateManualControl("mode_change", true);
    safetyController.setMode(mode);
    stateMachine.cancel();
    binding.startSwitch.setChecked(false);
    boolean auto = mode == RealCartSafetyController.Mode.AUTO;
    binding.manualDriveControls.setVisibility(auto ? View.GONE : View.VISIBLE);
    binding.unlockAuto.setVisibility(auto ? View.VISIBLE : View.GONE);
    binding.realSafetyNotice.setVisibility(auto ? View.VISIBLE : View.GONE);
    binding.startSwitch.setEnabled(false);
    refreshRealUi();
  }

  private void installDeadMan(
      View button, ManualControlArbiter.Control control, int left, int right) {
    button.setOnTouchListener(
        (view, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
              int downPointerId = event.getPointerId(event.getActionIndex());
              RealCartSafetyController.Output nextOutput = safetyController.manual(left, right);
              if (nextOutput.isStop()) {
                invalidateManualControl("manual_blocked", true);
                view.setPressed(false);
                return true;
              }

              ManualControlArbiter.PressResult pressResult =
                  manualControlArbiter.press(control, downPointerId);
              if (pressResult.replacedActiveControl) {
                if (activeManualButton != null && activeManualButton != view) {
                  activeManualButton.setPressed(false);
                }
                latestOutput = nextOutput;
                sendReplacementOutput(nextOutput, pressResult.generation);
              } else {
                latestOutput = nextOutput;
                sendOutput(latestOutput);
              }
              activeManualButton = view;
              view.setPressed(true);
              return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_OUTSIDE:
              int upPointerId = event.getPointerId(event.getActionIndex());
              view.setPressed(false);
              if (manualControlArbiter.release(control, upPointerId)) {
                activeManualButton = null;
                latestOutput = RealCartSafetyController.stop("manual_release");
                sendOutput(latestOutput);
              }
              return true;
            case MotionEvent.ACTION_CANCEL:
              view.setPressed(false);
              if (manualControlArbiter.getActiveControl() == control) {
                invalidateManualControl("manual_cancel", true);
              }
              return true;
            default:
              return true;
          }
        });
  }

  private void installAutoUnlock() {
    final Runnable unlock =
        () -> {
          if (binding != null && binding.unlockAuto.isPressed() && safetyController.unlockAuto()) {
            binding.startSwitch.setEnabled(true);
            refreshRealUi();
          }
        };
    binding.unlockAuto.setOnTouchListener(
        (view, event) -> {
          if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            view.setPressed(true);
            mainHandler.postDelayed(unlock, AUTO_UNLOCK_HOLD_MS);
          } else if (event.getActionMasked() == MotionEvent.ACTION_UP
              || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            view.setPressed(false);
            mainHandler.removeCallbacks(unlock);
          }
          return true;
        });
  }

  private void updateConnectionState() {
    boolean serialReady = vehicle != null && vehicle.isBleSerialReady();
    boolean firmwareReady = vehicle != null && vehicle.isCartFirmwareReady();
    safetyController.setConnection(serialReady, firmwareReady);
    if (!firmwareReady) {
      invalidateManualControl("ble_not_ready", false);
    }
  }

  private void invalidateManualControl(String reason, boolean sendStop) {
    manualControlArbiter.clear();
    if (activeManualButton != null) activeManualButton.setPressed(false);
    activeManualButton = null;
    latestOutput = RealCartSafetyController.stop(reason);
    if (sendStop) sendOutput(latestOutput);
  }

  private void startScheduler() {
    if (schedulerRunning) return;
    schedulerRunning = true;
    mainHandler.post(commandScheduler);
  }

  private void sendOutput(RealCartSafetyController.Output output) {
    if (vehicle == null || output == null) return;
    int multiplier = Math.max(1, vehicle.getSpeedMultiplier());
    vehicle.setControl(
        new Control(output.left / (float) multiplier, output.right / (float) multiplier));
  }

  private void sendReplacementOutput(RealCartSafetyController.Output output, long generation) {
    if (vehicle == null || output == null) return;
    int multiplier = Math.max(1, vehicle.getSpeedMultiplier());
    vehicle.setControlReplacing(
        new Control(output.left / (float) multiplier, output.right / (float) multiplier),
        generation);
  }

  private void refreshRealUi() {
    if (binding == null) return;
    requireActivity()
        .runOnUiThread(
            () -> {
              if (binding == null) return;
              String connection =
                  vehicle.isCartFirmwareReady()
                      ? "BLE 已就绪 · CART_AT8236"
                      : vehicle.isBleSerialReady() ? "BLE 已连接 · 等待固件握手" : "BLE 未连接";
              String output =
                  latestOutput == null ? "0,0" : latestOutput.left + "," + latestOutput.right;
              ManualControlArbiter.Control active = manualControlArbiter.getActiveControl();
              binding.realConnectionStatus.setText(
                  connection
                      + " | output="
                      + output
                      + " | direction="
                      + (active == null ? "STOP" : active.name())
                      + " | ble="
                      + vehicle.getBleWriteStatus()
                      + " | build="
                      + BuildConfig.VERSION_NAME);
              boolean emergency = safetyController.isEmergencyLatched();
              binding.emergencyStop.setEnabled(!emergency);
              binding.unlockAuto.setEnabled(!emergency && vehicle.isCartFirmwareReady());
              if (emergency) {
                binding.realSafetyNotice.setVisibility(View.VISIBLE);
                binding.realSafetyNotice.setText("急停已锁存，请重启 ESP32 后重新连接");
              } else {
                binding.realSafetyNotice.setText("近场传感器未接入，仅限空旷实验");
              }
            });
  }
}
