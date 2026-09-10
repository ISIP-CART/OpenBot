package org.openbot.cartfollow.voice;

import org.openbot.cartfollow.FollowStateMachine;
import org.openbot.cartfollow.HumanCartSimulatorFragment;

/**
 * Independent simulator entry with event-driven Chinese voice guidance and no real vehicle control.
 */
public class VoiceCartSimulatorFragment extends HumanCartSimulatorFragment {
  private final CartVoiceGuidance voice = new CartVoiceGuidance();

  @Override
  public synchronized void onResume() {
    voice.start(requireContext());
    voice.speakWelcome(VoicePrompts.WELCOME);
    super.onResume();
  }

  @Override
  protected void onFrameUiApplied(FollowStateMachine.FrameResult frame) {
    super.onFrameUiApplied(frame);
    if (frame == null || !isAdded()) return;
    voice.onFrame(frame);
  }

  @Override
  protected void onFollowSessionReset() {
    super.onFollowSessionReset();
    voice.reset();
  }

  @Override
  protected void onCartFollowPause() {
    voice.stop();
    super.onCartFollowPause();
  }
}
