package org.openbot.cartfollow;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openbot.tflite.Detector;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
public class ReIDAdaptiveGalleryTest {
  @Test
  public void adaptiveModePromotesOnlyAfterThreeFreshEligibleMatches() {
    QueueExtractor extractor = new QueueExtractor();
    for (int i = 0; i < 12; i++) extractor.add(1f, 0f);
    for (int i = 0; i < 3; i++) extractor.add(0.90f, (float) Math.sqrt(0.19));
    ReIDCoordinator coordinator = initialized(extractor, GalleryUpdateStatus.Mode.ADAPTIVE);

    GalleryUpdateStatus first = eligibleUpdate(coordinator, 1000);
    assertEquals(1, first.pendingConfirmations);
    ShadowSystemClock.advanceBy(Duration.ofMillis(301));
    GalleryUpdateStatus second = eligibleUpdate(coordinator, 1301);
    assertEquals(2, second.pendingConfirmations);
    ShadowSystemClock.advanceBy(Duration.ofMillis(301));
    GalleryUpdateStatus third = eligibleUpdate(coordinator, 1602);
    assertEquals("promoted", third.event);
    assertEquals(1, third.adaptiveSize);
    assertEquals(8, third.anchorSize);
  }

  @Test
  public void staticModeNeverStartsAdaptiveCandidate() {
    QueueExtractor extractor = new QueueExtractor();
    for (int i = 0; i < 12; i++) extractor.add(1f, 0f);
    extractor.add(0.90f, (float) Math.sqrt(0.19));
    ReIDCoordinator coordinator = initialized(extractor, GalleryUpdateStatus.Mode.STATIC);
    GalleryUpdateStatus result = eligibleUpdate(coordinator, 1000);
    assertEquals(0, result.pendingConfirmations);
    assertEquals(0, result.adaptiveSize);
    assertEquals("static_mode", result.reason);
  }

  @Test
  public void initializationSupplementAcceptsOnlyExactTrackAndSessionWithoutSimilarityGate() {
    QueueExtractor extractor = new QueueExtractor();
    for (int i = 0; i < 12; i++) extractor.add(1f, 0f);
    extractor.add(0.2f, 0.98f);
    ReIDCoordinator coordinator = initialized(extractor, GalleryUpdateStatus.Mode.ADAPTIVE);
    Bitmap frame = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
    long now = SystemClock.elapsedRealtime();
    long session = coordinator.getSessionEpoch();
    ReIDCoordinator.InitializationSampleResult wrong =
        coordinator.collectConfirmedInitializationCandidate(
            frame, person(), 0, 2, 1, now, 20, session);
    assertFalse(wrong.accepted);
    assertEquals("confirmed_track_mismatch", wrong.reason);
    ReIDCoordinator.InitializationSampleResult accepted =
        coordinator.collectConfirmedInitializationCandidate(
            frame, person(), 0, 1, 1, now, 21, session);
    assertTrue(accepted.accepted);
    assertEquals(1, accepted.sampleCount);
    assertEquals(9, coordinator.getGallerySize());
    assertTrue(coordinator.provenanceManifest().get(0).contains("initialization_continuity"));
    ReIDCoordinator.InitializationSampleResult duplicate =
        coordinator.collectConfirmedInitializationCandidate(
            frame, person(), 0, 1, 1, now, 21, session);
    assertFalse(duplicate.accepted);
    assertEquals("duplicate_frame", duplicate.reason);
    coordinator.closeInitializationSupplement();
    assertEquals(
        "sampling_closed",
        coordinator
            .collectConfirmedInitializationCandidate(frame, person(), 0, 1, 1, now, 22, session)
            .reason);
    frame.recycle();
  }

  @Test
  public void stablePoseChangeEntersQuarantineThenPromotes() {
    QueueExtractor extractor = new QueueExtractor();
    for (int i = 0; i < 12; i++) extractor.add(1f, 0f);
    float y = (float) Math.sqrt(1f - 0.75f * 0.75f);
    for (int i = 0; i < 5; i++) extractor.add(0.75f, y);
    ReIDCoordinator coordinator = initialized(extractor, GalleryUpdateStatus.Mode.ADAPTIVE);

    GalleryUpdateStatus status = null;
    for (int i = 0; i < 5; i++) {
      ShadowSystemClock.advanceBy(Duration.ofMillis(401));
      status = eligibleUpdate(coordinator, 1000L + i * 401L);
    }

    assertEquals("quarantine_promoted", status.event);
    assertEquals(1, status.adaptiveSize);
    assertEquals(0, status.quarantineSize);
  }

  private static ReIDCoordinator initialized(
      QueueExtractor extractor, GalleryUpdateStatus.Mode mode) {
    ReIDCoordinator coordinator = new ReIDCoordinator(extractor);
    coordinator.setGalleryMode(mode);
    Bitmap frame = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
    Detector.Recognition person = person();
    for (int i = 0; i < 12; i++) coordinator.collectInitializationCandidate(frame, person, 0);
    coordinator.confirmGallery();
    frame.recycle();
    return coordinator;
  }

  private static GalleryUpdateStatus eligibleUpdate(ReIDCoordinator coordinator, long now) {
    now = SystemClock.elapsedRealtime();
    Bitmap frame = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
    Detector.Recognition person = person();
    IdentityEvidence base =
        coordinator.evaluate(
            Collections.singletonList(person),
            frame,
            new TargetMemory(),
            FollowState.FOLLOW,
            100,
            200,
            0,
            0f,
            false,
            null);
    IdentityEvidence identity =
        new IdentityEvidence(
            0.9f,
            0.9f,
            true,
            "test",
            base.reidMatch,
            new BboxContinuityEvidence(0f, 0f, 1f, 0f, "test"),
            5,
            0,
            person,
            1,
            1,
            -1,
            1,
            10,
            0,
            0.90f,
            0.25f,
            0.15f,
            0.06f,
            0f,
            5,
            0,
            "test");
    GalleryUpdateStatus result =
        coordinator.maybeUpdateAdaptiveGallery(
            person,
            FollowState.FOLLOW,
            new BehaviorDecisionResult(
                FollowState.FOLLOW, BehaviorAction.FOLLOW_SLOW, "test", null, 1f),
            identity,
            1,
            100,
            200,
            0,
            now);
    frame.recycle();
    return result;
  }

  private static Detector.Recognition person() {
    return new Detector.Recognition("1", "person", 0.95f, new RectF(10, 10, 60, 160), 0);
  }

  private static final class QueueExtractor implements ReIDFeatureExtractor {
    private final Queue<float[]> features = new ArrayDeque<>();

    void add(float x, float y) {
      features.add(new float[] {x, y});
    }

    @Override
    public float[] extract(Bitmap personCrop) {
      return features.remove();
    }
  }
}
