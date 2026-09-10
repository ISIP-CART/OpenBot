package org.openbot.cartfollow;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SteeringTuningRecorderTest {
  @Test
  public void shutdownIsIdempotentAndLateRecordIsIgnored() {
    SteeringTuningRecorder recorder =
        new SteeringTuningRecorder(RuntimeEnvironment.getApplication());
    recorder.shutdown();
    recorder.shutdown();
    recorder.record("late_window_focus", 100, null, "");
    assertTrue(recorder.isClosedForTest());
  }
}
