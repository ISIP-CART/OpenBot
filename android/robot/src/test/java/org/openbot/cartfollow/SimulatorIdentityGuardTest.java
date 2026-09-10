package org.openbot.cartfollow;

import static org.junit.Assert.*;

import org.junit.Test;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class SimulatorIdentityGuardTest {
  @Test
  public void alreadyVerifiedTargetCanEnterFollowOnCacheWithoutLosingContinuityEligibility() {
    SimulatorIdentityGuard guard = new SimulatorIdentityGuard();
    guard.begin(1);
    for (int i = 1; i <= 3; i++)
      guard.update(
          1,
          i,
          i * 300,
          i * 300,
          1,
          1,
          true,
          true,
          evidence(1, i, i * 300, i, .99f, 0f, true),
          false,
          new SimulatorContinuityTracker.Evidence(
              true,
              "continuous_observations",
              3,
              null,
              new android.graphics.RectF(100, 100, 180, 280)),
          false);
    assertTrue(
        guard.update(
                1,
                4,
                950,
                950,
                1,
                1,
                true,
                true,
                evidence(1, 3, 900, 3, .99f, 0f, false),
                false,
                new SimulatorContinuityTracker.Evidence(
                    true,
                    "continuous_observations",
                    3,
                    null,
                    new android.graphics.RectF(100, 100, 180, 280)),
                true)
            .authorized);
    assertEquals(SimulatorIdentityGuard.State.TRACK_STABLE, continuous(guard, 5, 1200, .82f).state);
  }

  static SimulatorIdentityGuard.Decision continuous(
      SimulatorIdentityGuard guard, int frame, long now, float score) {
    return guard.update(
        1,
        frame,
        now,
        now,
        1,
        1,
        true,
        true,
        evidence(1, frame, now, frame, score, 0f, true),
        false,
        new SimulatorContinuityTracker.Evidence(
            true,
            "continuous_observations",
            3,
            null,
            new android.graphics.RectF(100, 100, 180, 280)),
        true);
  }

  @Test
  public void continuousLowAppearanceDoesNotStopMeasuredTarget() {
    SimulatorIdentityGuard guard = new SimulatorIdentityGuard();
    guard.begin(1);
    for (int i = 1; i <= 3; i++) continuous(guard, i, i * 300, .99f);
    SimulatorIdentityGuard.Decision hold = continuous(guard, 4, 1200, .69f);
    assertEquals(SimulatorIdentityGuard.State.TRACK_STABLE, hold.state);
    assertFalse(hold.authorized);
    assertTrue(hold.retainTarget && hold.samplingAllowed);
    assertTrue(hold.motionAllowed);
    continuous(guard, 5, 1500, .99f);
    continuous(guard, 6, 1800, .69f);
    continuous(guard, 7, 2100, .69f);
    SimulatorIdentityGuard.Decision expired = continuous(guard, 8, 2400, .69f);
    assertEquals(SimulatorIdentityGuard.State.TRACK_STABLE, expired.state);
    assertTrue(expired.samplingAllowed);
    assertTrue(expired.motionAllowed);
    continuous(guard, 9, 2700, .99f);
    continuous(guard, 10, 3000, .99f);
    assertTrue(continuous(guard, 11, 3300, .99f).isContinuous());
  }

  @Test
  public void continuousLowScoreMovesAndUnambiguousCrowdFreezesLearning() {
    SimulatorIdentityGuard guard = new SimulatorIdentityGuard();
    guard.begin(1);
    for (int i = 1; i <= 3; i++) continuous(guard, i, i * 300, .99f);
    SimulatorIdentityGuard.Decision low = continuous(guard, 4, 1200, .65f);
    assertTrue(low.samplingAllowed);
    assertTrue(low.motionAllowed); // One weak observation is a low-gear transition, not a hand-off.
    SimulatorIdentityGuard.Decision crowd =
        guard.update(
            1,
            5,
            1500,
            1500,
            1,
            1,
            true,
            true,
            evidence(1, 5, 1500, 5, .75f, 0f, true),
            true,
            new SimulatorContinuityTracker.Evidence(
                true, "test", 3, null, new android.graphics.RectF(100, 100, 180, 280)),
            true);
    assertTrue(crowd.motionAllowed);
    assertFalse(crowd.samplingAllowed);
    assertTrue(continuous(guard, 6, 1800, .75f).retainTarget);
  }

  private final SimulatorIdentityGuard guard = new SimulatorIdentityGuard();

  private static ReIDMatchResult evidence(
      int track,
      long frame,
      long time,
      long observation,
      float score,
      float second,
      boolean fresh) {
    return new ReIDMatchResult(score, second, 0, 8, true, 30, "fresh", score, 0f, observation)
        .withBinding(track, time, frame, fresh, 0, false);
  }

  private SimulatorIdentityGuard.Decision fresh(int track, long frame, long time) {
    return guard.update(
        1,
        frame,
        time,
        time + 40,
        track,
        1,
        true,
        true,
        evidence(track, frame, time, frame, .97f, .1f, true));
  }

  @Test
  public void threeIndependentFreshObservationsAuthorizeLocalRecovery() {
    guard.begin(1);
    assertFalse(fresh(1, 1, 100).authorized);
    assertFalse(fresh(1, 2, 400).authorized);
    assertTrue(fresh(1, 3, 700).authorized);
    assertTrue(
        guard.update(1, 4, 750, 790, 1, 1, true, true, evidence(1, 3, 700, 3, .97f, .1f, false))
            .authorized);
  }

  @Test
  public void cachedAndRepeatedObservationNeverAdvanceVerification() {
    guard.begin(1);
    fresh(1, 1, 100);
    for (int i = 2; i < 10; i++) {
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1,
              i,
              100 + i * 20,
              140 + i * 20,
              1,
              1,
              true,
              true,
              evidence(1, 1, 100, 1, .97f, .1f, false));
      assertFalse(result.authorized);
      assertEquals(1, result.freshMatches);
    }
  }

  @Test
  public void recordedT1ToT2SequenceCannotAuthorizeKnownDistractorEvenAtPersistentHighScore() {
    guard.begin(1);
    guard.rememberDistractor(2);
    fresh(1, 1, 100);
    fresh(1, 2, 400);
    assertTrue(fresh(1, 3, 700).authorized);
    assertFalse(
        guard.update(
                1, 4, 1000, 1040, 1, 1, true, true, evidence(1, 4, 1000, 4, .8709f, .8682f, true))
            .authorized);
    for (int i = 5; i < 20; i++) {
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1,
              i,
              i * 300,
              i * 300 + 40,
              2,
              1,
              true,
              true,
              evidence(2, i, i * 300, i, .8783f, 0f, i % 2 == 0));
      assertFalse(result.authorized);
      assertTrue(result.needsConfirmation);
      assertEquals("known_distractor", result.reason);
    }
  }

  @Test
  public void remoteCandidateAutomaticallyVerifiesAfterFiveFreshMatchesAcross1200Ms() {
    guard.begin(1);
    for (int i = 1; i <= 5; i++) {
      SimulatorIdentityGuard.Decision result = global(2, i, i * 300);
      assertEquals(SimulatorIdentityGuard.RecoveryType.GLOBAL, result.recoveryType);
      assertEquals(5, result.requiredFreshMatches);
      assertEquals(1200L, result.requiredSpanMs);
      assertEquals(i, result.freshMatches);
      assertEquals((i - 1) * 300L, result.recoverySpanMs);
      assertEquals(i == 5, result.authorized);
      assertEquals(i == 5, result.motionAllowed);
      assertFalse(result.needsConfirmation || result.samplingAllowed);
      assertEquals(
          i == 5 ? SimulatorIdentityGuard.State.VERIFIED : SimulatorIdentityGuard.State.AUTO_VERIFY,
          result.state);
      if (i < 5) assertTrue(result.recoveryProgress < 1f);
      else assertEquals(1f, result.recoveryProgress, 0f);
    }
  }

  @Test
  public void sessionTrackAndFrameProvenanceCannotBeBorrowed() {
    guard.begin(1);
    assertFalse(
        guard.update(0, 10, 100, 120, 1, 1, true, true, evidence(1, 10, 100, 10, .99f, 0f, true))
            .authorized);
    assertFalse(
        guard.update(1, 1, 100, 120, 1, 1, true, true, evidence(2, 1, 100, 1, .99f, 0f, true))
            .authorized);
    assertEquals(
        0,
        guard.update(1, 2, 200, 220, 1, 1, true, true, evidence(1, 1, 100, 2, .99f, 0f, true))
            .freshMatches);
  }

  @Test
  public void slowFrameAndGapResetAuthorizationWithoutExtendingTimestamp() {
    guard.begin(1);
    fresh(1, 1, 100);
    fresh(1, 2, 400);
    assertTrue(fresh(1, 3, 700).authorized);
    assertEquals(
        "frame_stale",
        guard.update(1, 4, 800, 1400, 1, 1, true, true, evidence(1, 4, 800, 4, .99f, .1f, true))
            .reason);
    assertEquals(1, fresh(1, 5, 1500).freshMatches);
    assertEquals(1, fresh(1, 6, 2101).freshMatches);
  }

  @Test
  public void lowConfidenceCannotAuthorizeAndNewSessionClearsExclusions() {
    guard.begin(1);
    guard.rememberDistractor(2);
    guard.begin(2);
    assertFalse(guard.isDistractor(2));
    assertFalse(
        guard.update(2, 1, 100, 120, 2, 1, false, true, evidence(2, 1, 100, 1, .99f, .1f, true))
            .authorized);
  }

  @Test
  public void crossingHistoryDoesNotPermanentlyBlockGlobalRecoveryOfANewTrack() {
    guard.begin(1);
    fresh(1, 1, 100);
    fresh(1, 2, 400);
    fresh(1, 3, 700);
    guard.update(
        1, 4, 1000, 1040, 1, 1, true, true, evidence(1, 4, 1000, 4, .87f, .868f, true), true);
    for (int i = 5; i < 10; i++) {
      SimulatorIdentityGuard.Decision decision = global(7, i, i * 300);
      assertEquals(i == 9, decision.authorized);
      assertFalse(decision.needsConfirmation);
    }
  }

  @Test
  public void localRecoveryRemainsThreeFreshMatchesWithoutHistoricalAmbiguityVeto() {
    guard.begin(1);
    guard.update(1, 1, 100, 120, 1, 1, true, true, evidence(1, 1, 100, 1, .97f, .1f, true), true);
    assertFalse(fresh(1, 2, 400).authorized);
    fresh(1, 3, 700);
    assertTrue(fresh(1, 4, 1000).authorized);
    for (int i = 5; i <= 9; i++) {
      long time = 1300 + (i - 5) * 300L;
      ReIDMatchResult reid = evidence(2, i, time, i, .97f, .1f, true);
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1, i, time, time, 2, 1, true, true, reid, 1, false, false, reid, null, false);
      assertEquals(SimulatorIdentityGuard.RecoveryType.GLOBAL, result.recoveryType);
      assertFalse(result.needsConfirmation);
      assertEquals(i == 9, result.authorized);
    }
  }

  private SimulatorIdentityGuard.Decision global(int track, long frame, long time) {
    return global(
        track, frame, time, false, 1, false, evidence(track, frame, time, frame, .97f, .1f, true));
  }

  private SimulatorIdentityGuard.Decision global(
      int track,
      long frame,
      long time,
      boolean local,
      int count,
      boolean ambiguous,
      ReIDMatchResult reid) {
    return guard.update(
        1,
        frame,
        time,
        time,
        track,
        1,
        true,
        local,
        evidence(track, frame, time, frame, .99f, 0f, true),
        count,
        ambiguous,
        true,
        reid,
        null,
        false);
  }

  @Test
  public void newLocalTrackAfterLostMustUseGlobalVerification() {
    guard.begin(1);
    for (int i = 1; i <= 5; i++) {
      SimulatorIdentityGuard.Decision result =
          global(2, i, i * 300, true, 1, false, evidence(2, i, i * 300, i, .97f, .1f, true));
      assertEquals(SimulatorIdentityGuard.RecoveryType.GLOBAL, result.recoveryType);
      assertEquals(i == 5, result.authorized);
    }
  }

  @Test
  public void newTrackAfterMissingTargetUsesGlobalEvenWithoutExplicitLostFlag() {
    guard.begin(1);
    fresh(1, 1, 100);
    guard.update(1, 2, 400, 400, -1, 1, false, false, null);
    for (int i = 3; i <= 7; i++) {
      ReIDMatchResult reid = evidence(2, i, i * 300, i, .97f, .1f, true);
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1, i, i * 300, i * 300, 2, 1, true, true, reid, 1, false, false, reid, null, false);
      assertEquals(SimulatorIdentityGuard.RecoveryType.GLOBAL, result.recoveryType);
      assertEquals(i == 7, result.authorized);
    }
  }

  @Test
  public void rapidMatchesAndCachesCannotSatisfyMinimumSpan() {
    guard.begin(1);
    for (int i = 1; i <= 5; i++) assertFalse(global(2, i, i * 100).authorized);
    SimulatorIdentityGuard.Decision cached =
        global(2, 6, 900, false, 1, false, evidence(2, 5, 500, 5, .97f, .1f, false));
    assertEquals(5, cached.freshMatches);
    assertEquals(400L, cached.recoverySpanMs);
    assertFalse(cached.authorized);
    assertFalse(global(2, 7, 1000).authorized);
    assertTrue(global(2, 8, 1300).authorized);
  }

  @Test
  public void multiPersonTimeoutStopsMotionButKeepsOriginalTrackContext() {
    SimulatorIdentityGuard guard = new SimulatorIdentityGuard();
    guard.begin(1);
    for (int i = 1; i <= 3; i++) continuous(guard, i, i * 300, .99f);
    SimulatorContinuityTracker.Evidence continuity =
        new SimulatorContinuityTracker.Evidence(
            true,
            "continuous_observations",
            3,
            new BboxContinuityEvidence(0f, 0f, 1f, 0f, "ok"),
            new android.graphics.RectF(100, 100, 180, 280));
    guard.update(
        1, 4, 1200, 1200, 1, 1, true, true,
        evidence(1, 4, 1200, 4, .82f, 0f, true), true, continuity, true);
    guard.update(
        1, 5, 1500, 1500, 1, 1, true, true,
        evidence(1, 5, 1500, 5, .82f, 0f, true), true, continuity, true);
    guard.update(
        1, 6, 1800, 1800, 1, 1, true, true,
        evidence(1, 6, 1800, 6, .82f, 0f, true), true, continuity, true);
    guard.update(
        1, 7, 2100, 2100, 1, 1, true, true,
        evidence(1, 7, 2100, 7, .82f, 0f, true), true, continuity, true);
    SimulatorIdentityGuard.Decision waiting =
        guard.update(
            1, 8, 2301, 2301, 1, 1, true, true,
            evidence(1, 8, 2301, 8, .82f, 0f, true), true, continuity, true);
    assertEquals("multi_check_timeout", waiting.reason);
    assertTrue(waiting.retainTarget);
    assertFalse(waiting.motionAllowed);
    SimulatorIdentityGuard.Decision resumed =
        guard.update(
            1, 9, 2400, 2400, 1, 1, true, true,
            evidence(1, 9, 2400, 9, .82f, 0f, true), false, continuity, true);
    assertTrue(resumed.motionAllowed);
    assertEquals(SimulatorIdentityGuard.State.TRACK_STABLE, resumed.state);
  }

  @Test
  public void independentLocalGeometrySurvivesOneTrackerBboxJump() {
    SimulatorIdentityGuard guard = new SimulatorIdentityGuard();
    guard.begin(1);
    for (int i = 1; i <= 3; i++) continuous(guard, i, i * 300, .99f);
    SimulatorIdentityGuard.Decision decision =
        guard.update(
            1,
            4,
            1200,
            1200,
            1,
            1,
            true,
            true,
            evidence(1, 4, 1200, 4, .55f, 0f, true),
            false,
            new SimulatorContinuityTracker.Evidence(
                false,
                "bbox_jump",
                0,
                new BboxContinuityEvidence(0.05f, 0.04f, 1.05f, 0.02f, "identity_local"),
                new android.graphics.RectF(102, 100, 182, 280)),
            true);
    assertEquals(SimulatorIdentityGuard.State.TRACK_STABLE, decision.state);
    assertTrue(decision.retainTarget);
  }

  @Test
  public void globalMediumFreshScoresPauseWithoutErasingStrongProgress() {
    guard.begin(1);
    assertEquals(1, global(2, 1, 300).freshMatches);
    SimulatorIdentityGuard.Decision medium =
        global(2, 2, 600, false, 1, false, evidence(2, 2, 600, 2, .75f, .10f, true));
    assertEquals(1, medium.freshMatches);
    assertEquals("global_reid_medium_hold", medium.reason);
    assertFalse(medium.motionAllowed);
    assertEquals(2, global(2, 3, 900).freshMatches);
    assertEquals(3, global(2, 4, 1200).freshMatches);
    assertEquals(4, global(2, 5, 1500).freshMatches);
    assertTrue(global(2, 6, 1800).authorized);
  }

  @Test
  public void globalCachedScoresNeitherCountNorClearWithinEvidenceGap() {
    guard.begin(1);
    assertEquals(1, global(2, 1, 300).freshMatches);
    SimulatorIdentityGuard.Decision cached =
        global(2, 2, 600, false, 1, false, evidence(2, 1, 300, 1, .97f, .10f, false));
    assertEquals(1, cached.freshMatches);
    assertFalse(cached.motionAllowed);
    assertEquals(2, global(2, 3, 700).freshMatches);
  }

  @Test
  public void twoFreshLowScoresClearGlobalWindow() {
    guard.begin(1);
    assertEquals(1, global(2, 1, 300).freshMatches);
    SimulatorIdentityGuard.Decision firstLow =
        global(2, 2, 600, false, 1, false, evidence(2, 2, 600, 2, .65f, .10f, true));
    assertEquals(1, firstLow.freshMatches);
    assertEquals("global_reid_low_hold", firstLow.reason);
    SimulatorIdentityGuard.Decision secondLow =
        global(2, 3, 900, false, 1, false, evidence(2, 3, 900, 3, .60f, .10f, true));
    assertEquals(0, secondLow.freshMatches);
    assertEquals("global_reid_low_reset", secondLow.reason);
    assertEquals(1, global(2, 4, 1200).freshMatches);
  }

  @Test
  public void globalVerificationWindowRestartsAfterTwoSecondsEvenWithMediumEvidence() {
    guard.begin(1);
    global(2, 1, 100);
    global(2, 2, 500);
    global(2, 3, 900);
    global(2, 4, 1300);
    SimulatorIdentityGuard.Decision medium =
        global(2, 5, 1700, false, 1, false, evidence(2, 5, 1700, 5, .75f, .10f, true));
    assertEquals(4, medium.freshMatches);
    SimulatorIdentityGuard.Decision restarted = global(2, 6, 2101);
    assertFalse(restarted.authorized);
    assertEquals(1, restarted.freshMatches);
    assertEquals(0L, restarted.recoverySpanMs);
  }

  @Test
  public void cachesNeverCountOrBridgeAnExpiredFreshGap() {
    guard.begin(1);
    global(2, 1, 100);
    for (int i = 2; i <= 5; i++) {
      SimulatorIdentityGuard.Decision result =
          global(2, i, i * 100, false, 1, false, evidence(2, 1, 100, 1, .97f, .1f, false));
      assertEquals(1, result.freshMatches);
      assertEquals(0L, result.recoverySpanMs);
      assertFalse(result.authorized);
    }
    assertEquals(1, global(2, 6, 601).freshMatches);
  }

  @Test
  public void fiveHundredMsGapIsAllowedButLongerGapResets() {
    guard.begin(1);
    for (int i = 1; i <= 5; i++) assertEquals(i == 5, global(2, i, i * 500).authorized);
    SimulatorIdentityGuard.Decision expired = global(2, 6, 3001);
    assertFalse(expired.authorized);
    assertEquals(1, expired.freshMatches);
    assertEquals(0L, expired.recoverySpanMs);
  }

  @Test
  public void bothCurrentCrowdAndSinglePersonAssociationAmbiguityResetGlobalVerification() {
    for (boolean association : new boolean[] {false, true}) {
      guard.begin(1);
      for (int i = 1; i <= 4; i++) global(2, i, i * 300);
      SimulatorIdentityGuard.Decision blocked =
          global(
              2,
              5,
              1500,
              false,
              association ? 1 : 2,
              association,
              evidence(2, 5, 1500, 5, .99f, .1f, true));
      assertEquals(0, blocked.freshMatches);
      assertEquals(0L, blocked.recoverySpanMs);
      assertEquals(SimulatorIdentityGuard.State.AUTO_VERIFY, blocked.state);
      assertEquals(
          association ? "global_association_ambiguous" : "global_multiple_candidates",
          blocked.reason);
      assertFalse(blocked.motionAllowed || blocked.samplingAllowed || blocked.needsConfirmation);
      for (int i = 6; i <= 10; i++) assertEquals(i == 10, global(2, i, i * 300).authorized);
    }
  }

  @Test
  public void globalNeverBorrowsStrongLocalScoresOrRelaxedPoseAnchor() {
    float[][] scores = {
      {.849f, 0f, .9f},
      {.9f, .821f, .9f},
      {Float.NaN, 0f, .9f},
      {.99f, Float.NaN, .9f},
      {.99f, 0f, Float.NaN},
      {Float.POSITIVE_INFINITY, 0f, .9f}
    };
    for (float[] values : scores) {
      guard.begin(1);
      for (int i = 1; i <= 6; i++) {
        ReIDMatchResult reid =
            new ReIDMatchResult(values[0], values[1], 0, 8, true, 30, "fresh", values[2], .99f, i)
                .withBinding(1, i * 300, i, true, 0, true);
        SimulatorIdentityGuard.Decision result = global(1, i, i * 300, false, 1, false, reid);
        assertFalse(result.authorized || result.motionAllowed || result.samplingAllowed);
        assertEquals(0, result.freshMatches);
      }
    }
  }

  @Test
  public void globalAcceptsApprovedAdaptiveWithoutAnchorFloor() {
    guard.begin(1);
    for (int i = 1; i <= 5; i++) {
      ReIDMatchResult reid =
          new ReIDMatchResult(.85f, .76f, 0, 8, true, 30, "fresh", .1f, .85f, i)
              .withBinding(2, i * 300, i, true, 0, false);
      assertEquals(i == 5, global(2, i, i * 300, false, 1, false, reid).authorized);
    }
  }

  @Test
  public void globalScoresRequireTheirOwnAvailableCurrentTrackBinding() {
    guard.begin(1);
    ReIDMatchResult[] invalid = {
      null,
      ReIDMatchResult.unavailable("test", 8),
      evidence(3, 1, 300, 1, .99f, 0f, true),
      evidence(2, 1, -1, 1, .99f, 0f, true),
      evidence(2, 1, 301, 1, .99f, 0f, true)
    };
    for (ReIDMatchResult reid : invalid) {
      guard.begin(1);
      SimulatorIdentityGuard.Decision result = global(2, 1, 300, false, 1, false, reid);
      assertFalse(result.authorized);
      assertEquals("identity_evidence_insufficient", result.reason);
      assertEquals(0, result.freshMatches);
    }
  }

  @Test
  public void repeatedIdsWrongFramesAndWrongTimestampsNeverCountForGlobalRecovery() {
    guard.begin(1);
    global(2, 1, 100);
    ReIDMatchResult[] invalid = {
      evidence(2, 2, 200, 1, .99f, 0f, true),
      evidence(2, 2, 300, 3, .99f, 0f, true),
      evidence(2, 4, 399, 4, .99f, 0f, true),
      evidence(2, 5, 500, 5, .99f, 0f, false)
    };
    for (int i = 0; i < invalid.length; i++) {
      SimulatorIdentityGuard.Decision result =
          global(2, i + 2, (i + 2) * 100, false, 1, false, invalid[i]);
      assertEquals(1, result.freshMatches);
      assertEquals(0L, result.recoverySpanMs);
      assertFalse(result.authorized);
    }
  }

  @Test
  public void changingTracksResetsGlobalProgressAndKnownDistractorsAlwaysReject() {
    guard.begin(1);
    for (int i = 1; i <= 4; i++) global(2, i, i * 300);
    assertEquals(1, global(3, 5, 1500).freshMatches);
    assertEquals(1, global(2, 6, 1800).freshMatches);
    guard.rememberDistractor(2);
    for (int i = 7; i <= 12; i++) {
      SimulatorIdentityGuard.Decision result = global(2, i, i * 300);
      assertEquals("known_distractor", result.reason);
      assertFalse(result.authorized || result.motionAllowed || result.samplingAllowed);
    }
  }

  @Test
  public void globalVerificationCannotDowngradeWhenCandidateBecomesLocal() {
    guard.begin(1);
    global(2, 1, 300);
    for (int i = 2; i <= 5; i++) {
      ReIDMatchResult reid = evidence(2, i, i * 300, i, .99f, 0f, true);
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1, i, i * 300, i * 300, 2, 1, true, true, reid, 1, false, false, reid, null, false);
      assertEquals(SimulatorIdentityGuard.RecoveryType.GLOBAL, result.recoveryType);
      assertEquals(i == 5, result.authorized);
    }
  }

  @Test
  public void relockedGlobalTargetCanResumeLocalContinuityWithoutAnotherStop() {
    guard.begin(1);
    for (int i = 1; i <= 5; i++) global(2, i, i * 300);
    SimulatorIdentityGuard.Decision relocked =
        guard.update(
            1,
            6,
            1600,
            1600,
            2,
            2,
            true,
            true,
            evidence(2, 5, 1500, 5, .97f, .1f, false),
            1,
            false,
            false,
            null,
            new SimulatorContinuityTracker.Evidence(
                true,
                "continuous_observations",
                3,
                null,
                new android.graphics.RectF(100, 100, 180, 280)),
            true);
    assertTrue(relocked.authorized && relocked.motionAllowed && relocked.samplingAllowed);
    assertEquals(SimulatorIdentityGuard.RecoveryType.LOCAL, relocked.recoveryType);
  }

  @Test
  public void legacyOverloadCannotTreatLocalScoresAsExplicitGlobalEvidence() {
    guard.begin(1);
    for (int i = 1; i <= 6; i++) {
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1,
              i,
              i * 300,
              i * 300,
              2,
              1,
              true,
              false,
              evidence(2, i, i * 300, i, .99f, 0f, true));
      assertFalse(result.authorized || result.needsConfirmation);
      assertEquals(SimulatorIdentityGuard.State.AUTO_VERIFY, result.state);
      assertEquals(0, result.freshMatches);
    }
  }

  @Test
  public void globalHardGatesRemainClosedDespiteOtherwiseStrongEvidence() {
    for (int gate = 0; gate < 5; gate++) {
      guard.begin(1);
      for (int i = 1; i <= 4; i++) global(2, i, i * 300);
      ReIDMatchResult reid = evidence(2, 5, 1500, 5, .99f, 0f, true);
      SimulatorIdentityGuard.Decision blocked =
          guard.update(
              1,
              5,
              1500,
              gate == 0 ? 2001 : gate == 1 ? 1499 : 1500,
              2,
              gate == 2 ? -1 : 1,
              gate != 3,
              false,
              reid,
              gate == 4 ? 0 : 1,
              false,
              true,
              reid,
              null,
              true);
      assertFalse(blocked.authorized || blocked.motionAllowed || blocked.samplingAllowed);
      assertEquals(0, blocked.freshMatches);
      assertEquals(1, global(2, 6, 2100).freshMatches);
    }
  }

  @Test
  public void obsoleteGlobalFramesNeitherAuthorizeNorModifyCurrentSessionProgress() {
    guard.begin(1);
    for (int i = 1; i <= 4; i++) global(2, i, i * 300);
    ReIDMatchResult reid = evidence(2, 5, 1500, 5, .99f, 0f, true);
    assertFalse(
        guard.update(0, 5, 1500, 1500, 2, 1, true, false, reid, 1, false, true, reid, null, false)
            .authorized);
    assertFalse(global(2, 4, 1500).authorized);
    assertTrue(global(2, 5, 1500).authorized);
    guard.begin(2);
    SimulatorIdentityGuard.Decision reset =
        guard.update(
            2,
            1,
            1800,
            1800,
            2,
            1,
            true,
            false,
            reid,
            1,
            false,
            true,
            evidence(2, 1, 1800, 1, .99f, 0f, true),
            null,
            false);
    assertFalse(reset.authorized);
    assertEquals(1, reset.freshMatches);
  }

  @Test
  public void associationAmbiguityAloneCancelsLocalContinuity() {
    guard.begin(1);
    for (int i = 1; i <= 3; i++) continuous(guard, i, i * 300, .99f);
    ReIDMatchResult reid = evidence(1, 4, 1200, 4, .75f, 0f, true);
    SimulatorIdentityGuard.Decision blocked =
        guard.update(
            1,
            4,
            1200,
            1200,
            1,
            1,
            true,
            true,
            reid,
            1,
            true,
            false,
            reid,
            new SimulatorContinuityTracker.Evidence(
                true, "test", 3, null, new android.graphics.RectF(100, 100, 180, 280)),
            true);
    assertFalse(blocked.motionAllowed || blocked.samplingAllowed || blocked.retainTarget);
    assertFalse(continuous(guard, 5, 1500, .75f).retainTarget);
  }

  @Test
  public void localPoseSupportStillAllowsExistingAnchorRelaxation() {
    guard.begin(1);
    for (int i = 1; i <= 3; i++) {
      ReIDMatchResult reid =
          new ReIDMatchResult(.90f, .1f, 0, 8, true, 30, "fresh", .60f, .90f, i)
              .withBinding(1, i * 300, i, true, 0, true);
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1, i, i * 300, i * 300, 1, 1, true, true, reid, 1, false, false, null, null, false);
      assertEquals(SimulatorIdentityGuard.RecoveryType.LOCAL, result.recoveryType);
      assertEquals(3, result.requiredFreshMatches);
      assertEquals(i == 3, result.authorized);
    }
  }

  @Test
  public void spatiallyLocalNewTrackAlwaysRequiresGlobalWithoutALossFlag() {
    guard.begin(1);
    for (int i = 1; i <= 5; i++) {
      ReIDMatchResult reid = evidence(2, i, i * 300, i, .99f, 0f, true);
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1, i, i * 300, i * 300, 2, 1, true, true, reid, 1, false, false, reid, null, true);
      assertEquals(SimulatorIdentityGuard.RecoveryType.GLOBAL, result.recoveryType);
      assertEquals(5, result.requiredFreshMatches);
      assertEquals(i == 5, result.authorized);
    }
  }

  @Test
  public void sameTrackAfterLongTrustedEvidenceGapRequiresGlobalDespiteLocalGeometry() {
    guard.begin(1);
    for (int i = 1; i <= 3; i++) fresh(1, i, i * 300);
    for (int i = 4; i <= 8; i++) {
      long time = 30900 + (i - 4) * 300L;
      ReIDMatchResult reid = evidence(1, i, time, i, .99f, 0f, true);
      SimulatorIdentityGuard.Decision result =
          guard.update(1, i, time, time, 1, 1, true, true, reid, 1, false, false, reid, null, true);
      assertEquals(SimulatorIdentityGuard.RecoveryType.GLOBAL, result.recoveryType);
      assertEquals(i == 8, result.authorized);
    }
  }

  @Test
  public void shortTrustedGapStillAllowsThreeMatchLocalRecovery() {
    guard.begin(1);
    for (int i = 1; i <= 3; i++) fresh(1, i, i * 300);
    for (int i = 4; i <= 6; i++) {
      SimulatorIdentityGuard.Decision result = fresh(1, i, 1500 + (i - 4) * 300L);
      assertEquals(SimulatorIdentityGuard.RecoveryType.LOCAL, result.recoveryType);
      assertEquals(i == 6, result.authorized);
    }
  }

  @Test
  public void continuouslyVisibleWeakObservationsKeepLocalRecoveryWithoutAuthorizing() {
    guard.begin(1);
    for (int i = 1; i <= 3; i++) continuous(guard, i, i * 300, .99f);
    for (int i = 4; i <= 13; i++) {
      float score = i % 3 == 0 ? .99f : .65f;
      assertFalse(continuous(guard, i, i * 300, score).authorized);
    }
    for (int i = 14; i <= 16; i++) {
      ReIDMatchResult reid = evidence(1, i, i * 300, i, .99f, 0f, true);
      SimulatorIdentityGuard.Decision result =
          guard.update(
              1,
              i,
              i * 300,
              i * 300,
              1,
              1,
              true,
              true,
              reid,
              1,
              false,
              false,
              reid,
              new SimulatorContinuityTracker.Evidence(
                  true,
                  "continuous_observations",
                  3,
                  null,
                  new android.graphics.RectF(100, 100, 180, 280)),
              true);
      assertEquals(SimulatorIdentityGuard.RecoveryType.LOCAL, result.recoveryType);
      assertFalse(result.authorized);
      assertTrue(result.motionAllowed);
    }
  }
}
