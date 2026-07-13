package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowSessionGuardTest {
  @Test
  public void confirmationIsOnlyVisibleForActivePendingSession() {
    assertTrue(
        BaseCartFollowFragment.shouldShowConfirmation(true, FollowState.LOCKED_PENDING_CONFIRM));
    assertFalse(
        BaseCartFollowFragment.shouldShowConfirmation(false, FollowState.LOCKED_PENDING_CONFIRM));
    assertFalse(BaseCartFollowFragment.shouldShowConfirmation(true, FollowState.IDLE));
    assertFalse(BaseCartFollowFragment.shouldShowConfirmation(true, FollowState.CONFIRMED_ARMED));
  }

  @Test
  public void expiredConfirmationPanelIsHidden() {
    Context context = ApplicationProvider.getApplicationContext();
    View panel = new View(context);

    assertTrue(
        BaseCartFollowFragment.updateConfirmationVisibility(
            panel, true, FollowState.LOCKED_PENDING_CONFIRM));
    assertEquals(View.VISIBLE, panel.getVisibility());

    assertFalse(
        BaseCartFollowFragment.updateConfirmationVisibility(panel, false, FollowState.IDLE));
    assertEquals(View.GONE, panel.getVisibility());
  }
}
