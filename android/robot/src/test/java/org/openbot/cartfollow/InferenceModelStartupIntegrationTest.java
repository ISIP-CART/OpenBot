package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.fragment.app.FragmentActivity;
import java.io.IOException;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openbot.tflite.Model;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InferenceModelStartupIntegrationTest {
  public static class Screen extends BaseCartFollowFragment {
    boolean acceptTasks;
    int submittedTasks;
    int configurationChanges;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
      return new FrameLayout(requireContext());
    }

    @Override
    public void onViewCreated(View view, Bundle state) {}

    @Override
    protected synchronized boolean runInBackground(Runnable task) {
      if (!acceptTasks) return false;
      submittedTasks++;
      task.run();
      return true;
    }

    @Override
    protected void onInferenceConfigurationChanged() {
      configurationChanges++;
      super.onInferenceConfigurationChanged();
    }

    boolean detectorReady() {
      return isDetectorReady();
    }

    String modelError() {
      return getInferenceErrorMessage();
    }

    void receiveCameraFrameBeforeResume() {
      Bitmap frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
      processFrame(frame, null, 100L, 0L, 0);
      frame.recycle();
    }
  }

  @Test
  public void hiddenControlsStillResolveModelAndReachReadyAfterEarlySubmission() {
    Screen screen = attachScreen();
    View hiddenModelParent = new View(screen.requireContext());
    View hiddenModelSpinner = new View(screen.requireContext());
    hiddenModelParent.setVisibility(View.GONE);
    hiddenModelSpinner.setVisibility(View.GONE);
    Model model =
        DetectorModelResolverTest.model(3, DetectorModelResolver.DEFAULT_MODEL_NAME);
    assertSame(
        model,
        screen.initializeDetectorModel(Collections.singletonList(model), model.name).model);

    screen.setDetectorLoaderForTest(
        (activity, selected, device, threads) ->
            new BaseCartFollowFragment.LoadedDetector(
                null, 300, 300, new RectF(0f, 0f, 1f, 1f), false));

    screen.acceptTasks = false;
    screen.receiveCameraFrameBeforeResume();
    assertFalse(screen.detectorReady());

    screen.acceptTasks = true;
    screen.retryNetworkConfigurationForCachedFrame();
    assertTrue(screen.detectorReady());
    assertEquals(1, screen.submittedTasks);
    assertEquals(View.GONE, hiddenModelParent.getVisibility());
    assertEquals(View.GONE, hiddenModelSpinner.getVisibility());
  }

  @Test
  public void recreatedViewResolvesSavedModelWithoutSpinnerCallback() {
    Model model =
        DetectorModelResolverTest.model(3, DetectorModelResolver.DEFAULT_MODEL_NAME);
    Screen first = attachScreen();
    Screen recreated = attachScreen();
    assertSame(
        model,
        first.initializeDetectorModel(Collections.singletonList(model), model.name).model);
    assertSame(
        model,
        recreated.initializeDetectorModel(Collections.singletonList(model), model.name).model);
    assertEquals(1, first.configurationChanges);
    assertEquals(1, recreated.configurationChanges);
  }

  @Test
  public void repeatedSelectionDoesNotReconfigureAndLoaderFailureIsVisible() {
    Screen screen = attachScreen();
    Model model =
        DetectorModelResolverTest.model(3, DetectorModelResolver.DEFAULT_MODEL_NAME);
    screen.initializeDetectorModel(Collections.singletonList(model), "");
    screen.initializeDetectorModel(Collections.singletonList(model), model.name);
    assertEquals(1, screen.configurationChanges);

    screen.setDetectorLoaderForTest(
        (activity, selected, device, threads) -> {
          throw new IOException("test loader failure");
        });
    screen.acceptTasks = true;
    screen.requestNetworkConfiguration(1280, 720);
    assertFalse(screen.detectorReady());
    assertTrue(screen.modelError().contains("test loader failure"));
  }

  @Test
  public void missingModelsProduceErrorInsteadOfSilentLoading() {
    Screen screen = attachScreen();
    screen.initializeDetectorModel(Collections.emptyList(), "missing");
    assertFalse(screen.detectorReady());
    assertTrue(screen.modelError().contains("未找到人物检测模型"));
  }

  private static Screen attachScreen() {
    FragmentActivity activity =
        Robolectric.buildActivity(FragmentActivity.class).create().start().get();
    Screen screen = new Screen();
    activity
        .getSupportFragmentManager()
        .beginTransaction()
        .add(android.R.id.content, screen)
        .commitNow();
    return screen;
  }
}
