package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InferenceStartupCoordinatorTest {
  @Test
  public void rejectedSubmissionDoesNotLeavePending() {
    InferenceStartupCoordinator state = new InferenceStartupCoordinator();
    assertTrue(state.begin(10L));
    state.submissionRejected();
    assertFalse(state.isPending());
    assertTrue(state.begin(11L));
  }

  @Test
  public void completionClearsPending() {
    InferenceStartupCoordinator state = new InferenceStartupCoordinator();
    state.begin(100L);
    assertEquals(25L, state.pendingAgeMs(125L));
    state.complete();
    assertFalse(state.isPending());
    assertEquals(-1L, state.pendingAgeMs(126L));
  }

  @Test
  public void firstTimeoutRetriesAndSecondFails() {
    InferenceStartupCoordinator state = new InferenceStartupCoordinator();
    state.begin(1_000L);
    assertEquals(
        InferenceStartupCoordinator.TimeoutOutcome.NONE,
        state.checkTimeout(1_000L + InferenceStartupCoordinator.LOAD_TIMEOUT_MS - 1L));
    assertEquals(
        InferenceStartupCoordinator.TimeoutOutcome.RETRY,
        state.checkTimeout(1_000L + InferenceStartupCoordinator.LOAD_TIMEOUT_MS));
    assertEquals(1, state.timeoutRetries());

    state.begin(7_000L);
    assertEquals(
        InferenceStartupCoordinator.TimeoutOutcome.FAILED,
        state.checkTimeout(7_000L + InferenceStartupCoordinator.LOAD_TIMEOUT_MS));
    assertFalse(state.isPending());
  }

  @Test
  public void resetStartsANewResumeSession() {
    InferenceStartupCoordinator state = new InferenceStartupCoordinator();
    state.begin(0L);
    state.checkTimeout(InferenceStartupCoordinator.LOAD_TIMEOUT_MS);
    state.reset();
    assertFalse(state.isPending());
    assertEquals(0, state.timeoutRetries());
    assertTrue(state.begin(20_000L));
  }
}
