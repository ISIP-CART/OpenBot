package org.openbot.cartfollow;

import android.graphics.RectF;

public class ActionArbitrator {

  public BehaviorDecisionResult decide(
      FollowState state,
      IdentityEvidence identity,
      DistanceEvidence distance,
      TraversabilityEvidence traversability,
      SystemSafetyEvidence safety,
      TargetMemory memory,
      int frameW) {
    if (safety != null && safety.emergencyStop) {
      return result(state, BehaviorAction.EMERGENCY_STOP, "emergency_stop", safety.reason, 0f);
    }
    if (safety != null && (!safety.communicationOk || !safety.detectorOk)) {
      return result(state, BehaviorAction.EMERGENCY_STOP, "system_not_ready", safety.reason, 0f);
    }
    if (state == FollowState.STOP) {
      return result(state, BehaviorAction.HARD_STOP, "state_stop", "hard_stop_state", 0f);
    }
    if (state == FollowState.REACQUIRE_TARGET) {
      float conf = identity == null ? 0f : identity.confidence;
      return result(state, BehaviorAction.REACQUIRE_HOLD, "reacquire_confirming", null, conf);
    }
    if (state == FollowState.LOCKED_PENDING_CONFIRM
        || state == FollowState.CONFIRMED_ARMED
        || state == FollowState.READY_TO_FOLLOW
        || state == FollowState.CAPTURE_TARGET
        || state == FollowState.IDLE) {
      return result(state, BehaviorAction.MOTION_STOP, "not_ready_to_follow", "motion_stop", 0f);
    }
    if (state == FollowState.LOST || state == FollowState.SEARCH) {
      BehaviorAction searchAction = searchAction(memory, frameW);
      if (searchAction == BehaviorAction.MOTION_STOP) {
        return result(state, searchAction, "target_lost_no_last_bbox", "motion_stop", 0f);
      }
      return result(state, searchAction, "target_lost_last_side", null, 0.2f);
    }
    if (identity != null && !identity.matched) {
      return result(state, BehaviorAction.MOTION_STOP, "identity_unmatched", identity.reason, 0f);
    }
    if (traversability != null && traversability.centerBlocked) {
      return result(state, BehaviorAction.BLOCKED_WAIT, "center_blocked", traversability.reason, 0f);
    }
    if (distance != null
        && (distance.state == DistanceState.UNKNOWN || distance.state == DistanceState.TOO_CLOSE)) {
      return result(
          state,
          BehaviorAction.MOTION_STOP,
          "distance_" + distance.state.name().toLowerCase(),
          distance.reason,
          distance.confidence);
    }
    if (state == FollowState.FOLLOW && distance != null && distance.state == DistanceState.OK) {
      return result(
          state,
          BehaviorAction.FOLLOW_CAUTION,
          "identity_ok_distance_ok",
          null,
          identityConfidence(identity));
    }
    return result(
        state,
        BehaviorAction.FOLLOW_SLOW,
        "identity_ok_follow_allowed",
        null,
        identityConfidence(identity));
  }

  private static BehaviorDecisionResult result(
      FollowState state,
      BehaviorAction action,
      String actionReason,
      String safetyBlockReason,
      float confidence) {
    return new BehaviorDecisionResult(state, action, actionReason, safetyBlockReason, confidence);
  }

  private static float identityConfidence(IdentityEvidence identity) {
    return identity == null ? 0f : identity.confidence;
  }

  private static BehaviorAction searchAction(TargetMemory memory, int frameW) {
    if (memory == null || frameW <= 0) return BehaviorAction.MOTION_STOP;
    RectF lastBbox = memory.getLastBbox();
    if (lastBbox == null) return BehaviorAction.MOTION_STOP;
    return lastBbox.centerX() < frameW / 2f
        ? BehaviorAction.LOCAL_SEARCH_LEFT
        : BehaviorAction.LOCAL_SEARCH_RIGHT;
  }
}
