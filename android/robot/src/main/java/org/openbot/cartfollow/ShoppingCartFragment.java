package org.openbot.cartfollow;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.SavedStateHandle;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import org.openbot.R;
import org.openbot.cartfollow.voice.CartVoiceGuidance;

/** Simplified release presentation of the real-cart follow pipeline. */
public class ShoppingCartFragment extends RealCartFollowFragment {
  public static final int FIXED_MAXIMUM_GEAR = 21;
  private static final String WELCOME_SPOKEN_KEY = "shopping_cart_welcome_spoken";
  private final CartVoiceGuidance voice = new CartVoiceGuidance(true);
  private boolean lastConnected;
  private String lastLogError = "";
  private boolean fallbackWelcomeSpoken;
  private boolean positioningTimeoutStopped;

  @Override
  protected void onCartFollowViewCreated() {
    super.onCartFollowViewCreated();
    setMode(RealCartSafetyController.Mode.AUTO);
    applyReleasePresentation();
    binding.shoppingStartSwitch.setEnabled(false);
    binding.shoppingConnectBle.setOnClickListener(
        v -> Navigation.findNavController(requireView()).navigate(R.id.open_bluetooth_fragment));
    binding.shoppingStartSwitch.setOnClickListener(
        v -> {
          if (!binding.shoppingStartSwitch.isChecked()) {
            voice.event(CartVoiceGuidance.Event.USER_STOP);
            stopReleaseFollowing("user_start_off");
            return;
          }
          voice.newSession();
          String error = startReleaseFollowing();
          if (error != null) {
            binding.shoppingStartSwitch.setChecked(false);
            binding.shoppingStatus.setText(error);
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
          } else {
            positioningTimeoutStopped = false;
          }
        });
    binding.shoppingEmergencyStop.setOnClickListener(
        v -> { voice.event(CartVoiceGuidance.Event.EMERGENCY); triggerEmergencyStop(); });
  }

  @Override public synchronized void onResume() {
    super.onResume();
    voice.start(requireContext());
    if (markWelcomeSpoken()) {
      ReleaseStartReadiness readiness = getReleaseStartReadiness(SystemClock.elapsedRealtime());
      voice.speakWelcome(
          readiness.state == ReleaseStartReadiness.State.BLE_DISCONNECTED
              ? R.string.shopping_cart_welcome_disconnected
              : R.string.shopping_cart_welcome_connected);
    }
    if (binding != null) applyReleasePresentation();
  }

  @Override protected void onCartFollowPause() {
    voice.stop();
    super.onCartFollowPause();
  }

  @Override protected void onFrameUiApplied(FollowStateMachine.FrameResult frame) {
    super.onFrameUiApplied(frame);
    if (binding == null || frame == null) return;
    binding.confirmPanel.setVisibility(View.GONE);
    boolean positioningTimedOut =
        frame.initializationPositioningEvidence != null
            && frame.initializationPositioningEvidence.phase
                == InitializationPositioningEvidence.Phase.TIMEOUT;
    if (binding.shoppingStartSwitch.isChecked())
      binding.shoppingStatus.setText(releaseStatus(frame));
    voice.onFrame(frame);
    if (positioningTimedOut && binding.shoppingStartSwitch.isChecked()) {
      positioningTimeoutStopped = true;
      recordControlEvent(
          "positioning_timeout_stop",
          "reverse_ms=" + frame.initializationPositioningEvidence.reverseElapsedMs);
      stopReleaseFollowing("positioning_timeout");
      if (binding != null)
        binding.shoppingStatus.setText(
            "取景未完成，小车已经停止。请调整站位后重新点击 Start");
    }
    binding.confirmPanel.bringToFront();
    binding.shoppingEmergencyStop.bringToFront();
  }

  @Override protected void onReleaseRunStateChanged(boolean running, String reason) {
    if (binding == null) return;
    binding.shoppingStartSwitch.setChecked(running);
    binding.shoppingStartSwitch.setText(running ? "停止跟随" : "Start · 开始跟随");
    if (!running && "ble_not_ready".equals(reason)) voice.event(CartVoiceGuidance.Event.DISCONNECTED);
  }

  @Override protected void onRealUiRefreshed(
      String connection, boolean emergency, ReleaseStartReadiness readiness) {
    if (binding == null) return;
    boolean connected = connection.startsWith("BLE 已就绪");
    boolean bleConnected = readiness.state != ReleaseStartReadiness.State.BLE_DISCONNECTED;
    binding.shoppingConnectBle.setText(
        connected ? "已连接" : connection.contains("等待") ? "正在连接" : "连接蓝牙");
    binding.shoppingEmergencyStop.setEnabled(!emergency);
    boolean running = binding.shoppingStartSwitch.isChecked();
    binding.shoppingStartSwitch.setEnabled(running || readiness.ready());
    if (!running)
      binding.shoppingStatus.setText(
          positioningTimeoutStopped && readiness.ready()
              ? "取景未完成，小车已经停止。请调整站位后重新点击 Start"
              : readiness.message);
    if (lastConnected && !bleConnected) voice.event(CartVoiceGuidance.Event.DISCONNECTED);
    lastConnected = bleConnected;
  }

  private boolean markWelcomeSpoken() {
    try {
      if (NavHostFragment.findNavController(this).getCurrentBackStackEntry() == null)
        return markFallbackWelcomeSpoken();
      SavedStateHandle state =
          NavHostFragment.findNavController(this)
              .getCurrentBackStackEntry()
              .getSavedStateHandle();
      return markWelcomeSpoken(state);
    } catch (IllegalStateException error) {
      return markFallbackWelcomeSpoken();
    }
  }

  static boolean markWelcomeSpoken(SavedStateHandle state) {
    if (Boolean.TRUE.equals(state.get(WELCOME_SPOKEN_KEY))) return false;
    state.set(WELCOME_SPOKEN_KEY, true);
    return true;
  }

  private boolean markFallbackWelcomeSpoken() {
    if (fallbackWelcomeSpoken) return false;
    fallbackWelcomeSpoken = true;
    return true;
  }

  @Override protected boolean diagnosticsEnabledByDefault() { return true; }
  @Override protected String diagnosticEntryName() { return "ShoppingCart"; }
  @Override protected boolean useReleaseTargetOverlay() { return true; }
  @Override protected boolean isReleasePresentation() { return true; }
  @Override protected boolean autoConfirmCapturedTarget() { return true; }
  @Override protected int releaseMaximumGear() { return FIXED_MAXIMUM_GEAR; }

  @Override protected void onDiagnosticWriteFailure(String error) {
    if (error == null || error.equals(lastLogError) || !isAdded()) return;
    lastLogError = error;
    Toast.makeText(requireContext(), "本地记录写入失败，请检查存储空间", Toast.LENGTH_LONG).show();
  }

  void applyReleasePresentation() {
    binding.shoppingReleasePanel.setVisibility(View.VISIBLE);
    binding.commandText.setVisibility(View.GONE);
    binding.steeringPanel.setVisibility(View.GONE);
    binding.simulatorExperimentScroll.setVisibility(View.GONE);
    binding.debugPanel.setVisibility(View.GONE);
    binding.realControlPanel.setVisibility(View.GONE);
    binding.bottomPanel.setVisibility(View.GONE);
    binding.countdownText.setVisibility(View.GONE);
    binding.btnCancel.setVisibility(View.GONE);
    binding.btnConfirm.setVisibility(View.GONE);
    binding.btnRetake.setVisibility(View.GONE);
    if (binding.confirmPanel.getParent() != binding.shoppingReleasePanel) {
      ((ViewGroup) binding.confirmPanel.getParent()).removeView(binding.confirmPanel);
      ConstraintLayout.LayoutParams p = new ConstraintLayout.LayoutParams(
          0, ViewGroup.LayoutParams.WRAP_CONTENT);
      p.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
      p.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
      p.bottomToTop = R.id.shopping_emergency_stop;
      p.bottomMargin = Math.round(8 * getResources().getDisplayMetrics().density);
      binding.shoppingReleasePanel.addView(binding.confirmPanel, p);
    }
    binding.confirmPanel.setVisibility(View.GONE);
    binding.confirmPanel.bringToFront();
    binding.shoppingEmergencyStop.bringToFront();
  }

  static String releaseStatus(FollowStateMachine.FrameResult frame) {
    if (frame == null) return "准备中";
    if (frame.initializationPositioningEvidence != null
        && frame.initializationPositioningEvidence.phase
            == InitializationPositioningEvidence.Phase.TIMEOUT)
      return "取景未完成，小车已经停止。请调整站位后重新点击 Start";
    switch (frame.state) {
      case CAPTURE_TARGET: return "请站到镜头前并保持稳定";
      case LOCKED_PENDING_CONFIRM: return "目标轨迹失效，正在重新采集";
      case AUTO_POSITIONING:
        if (frame.initializationPositioningEvidence == null
            && frame.distanceDiagnosticText != null
            && frame.distanceDiagnosticText.contains("采集完成"))
          return "目标采集完成，请保持站立";
        if (frame.initializationPositioningEvidence != null) {
          switch (frame.initializationPositioningEvidence.phase) {
            case REVERSING: return "正在自动后退取景";
            case SETTLING:
            case READY: return "全身已入镜，正在初始化";
            case TIMEOUT:
              return "取景未完成，小车已经停止。请调整站位后重新点击 Start";
            default: break;
          }
        }
        return "请保持站立";
      case DISTANCE_CALIBRATION:
      case CONFIRMED_ARMED:
      case REACQUIRE_TARGET: return "全身已入镜，正在初始化";
      case READY_TO_FOLLOW: return "三秒后开始跟随";
      case IDENTITY_UNCERTAIN:
        if (frame.detectionTierEvidence != null
            && (frame.detectionTierEvidence.selectedCandidateIsLowConfidence
                || !frame.detectionTierEvidence.lowConfidencePersons.isEmpty()))
          return "目标检测不稳定";
        return frame.simulatorIdentity != null && frame.simulatorIdentity.trackId >= 0
            ? "正在确认目标" : "目标暂时离开画面，正在安全寻找";
      case FOLLOW:
      case FOLLOW_CAUTION:
        if (frame.simulatorIdentity != null
            && (!frame.simulatorIdentity.motionAllowed
                || (!frame.simulatorIdentity.authorized
                    && !frame.simulatorIdentity.isContinuous())))
          return frame.detectionTierEvidence != null
                  && frame.detectionTierEvidence.selectedCandidateIsLowConfidence
              ? "目标检测不稳定"
              : "正在确认目标";
        return frame.behaviorDecision != null
          && frame.behaviorDecision.selectedAction == BehaviorAction.BLOCKED_WAIT
          ? "前方受阻，已暂停" : "正在跟随";
      case LOST:
      case SEARCH:
      case DIRECTED_REACQUIRE: return "目标暂时离开画面，正在安全寻找";
      case STOP: return "小车已停止，请重新开始";
      default: return "连接蓝牙后，点击 Start 开始";
    }
  }
}
