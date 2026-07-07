package org.openbot.cartfollow;

public class BehaviorDecisionResult {
  public final FollowState state;
  public final BehaviorAction selectedAction;
  public final String actionReason;
  public final String safetyBlockReason;
  public final float confidence;
  public final DistanceEvidence distanceEvidence;
  public final TraversabilityEvidence traversabilityEvidence;

  public BehaviorDecisionResult(
      FollowState state,
      BehaviorAction selectedAction,
      String actionReason,
      String safetyBlockReason,
      float confidence) {
    this(state, selectedAction, actionReason, safetyBlockReason, confidence, null, null);
  }

  public BehaviorDecisionResult(
      FollowState state,
      BehaviorAction selectedAction,
      String actionReason,
      String safetyBlockReason,
      float confidence,
      DistanceEvidence distanceEvidence,
      TraversabilityEvidence traversabilityEvidence) {
    this.state = state;
    this.selectedAction = selectedAction;
    this.actionReason = actionReason;
    this.safetyBlockReason = safetyBlockReason;
    this.confidence = confidence;
    this.distanceEvidence = distanceEvidence;
    this.traversabilityEvidence = traversabilityEvidence;
  }
}
