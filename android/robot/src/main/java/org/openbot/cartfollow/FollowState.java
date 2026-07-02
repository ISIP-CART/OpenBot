package org.openbot.cartfollow;

public enum FollowState {
  IDLE,
  CAPTURE_TARGET,
  LOCKED_PENDING_CONFIRM,
  CONFIRMED_ARMED,
  REACQUIRE_TARGET,
  READY_TO_FOLLOW,
  FOLLOW,
  LOST,
  SEARCH,
  STOP
}
