package org.openbot.cartfollow;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.RectF;
import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openbot.tflite.Detector.Recognition;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShoppingCartAutomaticConfirmationTest {
  private static TargetTrackManager tracks(BaseCartFollowFragment fragment) throws Exception {
    Field field = BaseCartFollowFragment.class.getDeclaredField("targetTrackManager");
    field.setAccessible(true);
    return (TargetTrackManager) field.get(fragment);
  }

  private static FollowStateMachine.FrameResult capture(
      BaseCartFollowFragment fragment, Recognition person, int trackId, Bitmap frame) {
    fragment.stateMachine.startCapture();
    FollowStateMachine.FrameResult result = null;
    FollowStateMachine.InitializationObservation observation =
        new FollowStateMachine.InitializationObservation(person, trackId, true);
    for (int i = 0; i < fragment.stateMachine.CAPTURE_FRAMES; i++) {
      result =
          fragment.stateMachine.onFrame(
              Collections.singletonList(person), frame, 300, 300, 0, null, null, observation);
    }
    return result;
  }

  @Test public void shoppingCartConfirmsExactCapturedTrackInCompletionFrame() throws Exception {
    ShoppingCartFragment fragment = new ShoppingCartFragment();
    Recognition person =
        new Recognition("person", "person", .99f, new RectF(50, 20, 250, 290), 0);
    TargetTrackManager manager = tracks(fragment);
    manager.update(Collections.singletonList(person), 300, 300, 100);
    int trackId = manager.getTrackForRecognition(person).trackId;
    Bitmap bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
    FollowStateMachine.FrameResult pending = capture(fragment, person, trackId, bitmap);
    assertEquals(FollowState.LOCKED_PENDING_CONFIRM, pending.state);

    FollowStateMachine.FrameResult confirmed =
        fragment.applyAutomaticConfirmation(
            pending,
            new FollowStateMachine.InitializationObservation(person, trackId, true),
            Collections.singletonList(person),
            null);

    assertEquals(FollowState.AUTO_POSITIONING, confirmed.state);
    assertEquals(trackId, manager.getLockedTrackId());
    assertEquals(FollowState.AUTO_POSITIONING, fragment.stateMachine.getState());
    assertTrue(confirmed.distanceDiagnosticText.contains("采集完成"));
    bitmap.recycle();
  }

  @Test public void missingExactTrackRestartsCaptureInsteadOfLockingAnotherPerson()
      throws Exception {
    ShoppingCartFragment fragment = new ShoppingCartFragment();
    Recognition person =
        new Recognition("person", "person", .99f, new RectF(50, 20, 250, 290), 0);
    Bitmap bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
    FollowStateMachine.FrameResult pending = capture(fragment, person, 99, bitmap);

    FollowStateMachine.FrameResult retry =
        fragment.applyAutomaticConfirmation(
            pending,
            new FollowStateMachine.InitializationObservation(person, 99, true),
            Collections.singletonList(person),
            null);

    assertEquals(FollowState.CAPTURE_TARGET, retry.state);
    assertEquals(-1, tracks(fragment).getLockedTrackId());
    bitmap.recycle();
  }

  @Test public void debugEntryKeepsManualConfirmation() {
    BaseCartFollowFragment fragment = new HumanCartSimulatorFragment();
    assertFalse(fragment.autoConfirmCapturedTarget());
  }
}
