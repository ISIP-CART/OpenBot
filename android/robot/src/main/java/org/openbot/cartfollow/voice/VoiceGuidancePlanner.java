package org.openbot.cartfollow.voice;

import java.util.HashMap;
import java.util.Map;
import org.openbot.cartfollow.BehaviorAction;
import org.openbot.cartfollow.FollowState;
import org.openbot.cartfollow.FollowStateMachine;
import org.openbot.cartfollow.InitializationPositioningEvidence;

/** Converts structured follow stages into sparse prompts; display strings never drive behavior. */
final class VoiceGuidancePlanner {
  static final long REPEAT_INTERVAL_MS = 8000L;
  static final class Prompt {
    final int textRes; final boolean urgent; final String key;
    Prompt(int textRes, boolean urgent) { this(textRes, urgent, String.valueOf(textRes)); }
    Prompt(int textRes, boolean urgent, String key) { this.textRes=textRes; this.urgent=urgent; this.key=key; }
  }
  private final Map<String, Long> spokenAt = new HashMap<>();
  private String previousKey = "idle";

  Prompt onFrame(FollowStateMachine.FrameResult frame, long nowMs) {
    if (frame == null || frame.state == null) return null;
    Prompt candidate = candidate(frame);
    if (candidate == null) return null;
    boolean changed = !candidate.key.equals(previousKey);
    previousKey = candidate.key;
    Long last = spokenAt.get(candidate.key);
    if (!changed || (last != null && nowMs - last < REPEAT_INTERVAL_MS)) return null;
    spokenAt.put(candidate.key, nowMs);
    return candidate;
  }

  Prompt system(int textRes, String key) { previousKey=key; return new Prompt(textRes,true,key); }
  void reset() { previousKey="idle"; spokenAt.clear(); }

  private Prompt candidate(FollowStateMachine.FrameResult frame) {
    InitializationPositioningEvidence p = frame.initializationPositioningEvidence;
    if (p != null && p.phase == InitializationPositioningEvidence.Phase.TIMEOUT)
      return new Prompt(VoicePrompts.POSITIONING_TIMEOUT,true,"positioning_timeout");
    if (frame.state == FollowState.AUTO_POSITIONING && p != null) {
      switch (p.phase) {
        case REVERSING: return new Prompt(VoicePrompts.POSITIONING_REVERSE,false,"positioning_reverse");
        case SETTLING:
        case READY: return new Prompt(VoicePrompts.CALIBRATION,false,"initializing_full_body");
        default:
          boolean interrupted=p.reason!=null&&(p.reason.contains("missing")||p.reason.contains("competing")||p.reason.contains("track"));
          return new Prompt(interrupted?VoicePrompts.POSITIONING_INTERRUPTED:VoicePrompts.POSITIONING,
              interrupted,interrupted?"positioning_interrupted":"positioning_wait");
      }
    }
    switch (frame.state) {
      case CAPTURE_TARGET: return new Prompt(VoicePrompts.CAPTURE,false,"capture");
      case LOCKED_PENDING_CONFIRM: return new Prompt(VoicePrompts.CONFIRM,false,"confirm");
      case DISTANCE_CALIBRATION:
      case CONFIRMED_ARMED:
      case REACQUIRE_TARGET: return new Prompt(VoicePrompts.CALIBRATION,false,"initializing_full_body");
      case READY_TO_FOLLOW: return new Prompt(VoicePrompts.COUNTDOWN,false,"countdown");
      case FOLLOW: return new Prompt(VoicePrompts.FOLLOW,false,"follow");
      case IDENTITY_UNCERTAIN: return new Prompt(VoicePrompts.IDENTITY_UNCERTAIN,true,"identity_uncertain");
      case LOST: return new Prompt(VoicePrompts.LOST,true,"lost");
      case SEARCH:
      case DIRECTED_REACQUIRE:
        if(frame.realDriveResult!=null && frame.realDriveResult.reason!=null
            && frame.realDriveResult.reason.startsWith("corner_")) return null;
        if(frame.behaviorDecision!=null&&(frame.behaviorDecision.selectedAction==BehaviorAction.FOLLOW_SLOW
            ||frame.behaviorDecision.selectedAction==BehaviorAction.FOLLOW_CAUTION)) return null;
        return new Prompt(VoicePrompts.SEARCH,false,"search");
      case STOP: return new Prompt(VoicePrompts.STOPPED,true,"stopped");
      default: return null;
    }
  }
}
