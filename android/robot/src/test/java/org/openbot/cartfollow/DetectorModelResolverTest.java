package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.openbot.tflite.Model;

public class DetectorModelResolverTest {
  @Test
  public void savedDetectorIsSelectedByExactNormalizedName() {
    Model fallback = model(3, DetectorModelResolver.DEFAULT_MODEL_NAME);
    Model saved = model(4, "MobileNetV3-320.tflite");
    DetectorModelResolver.Result result =
        DetectorModelResolver.resolve(Arrays.asList(fallback, saved), "MobileNetV3-320");
    assertSame(saved, result.model);
    assertFalse(result.usedFallback);
  }

  @Test
  public void emptyOrInvalidSavedValueUsesBuiltInModel() {
    Model fallback = model(3, DetectorModelResolver.DEFAULT_MODEL_NAME);
    List<Model> models = Collections.singletonList(fallback);
    DetectorModelResolver.Result empty = DetectorModelResolver.resolve(models, "");
    DetectorModelResolver.Result invalid = DetectorModelResolver.resolve(models, "removed.tflite");
    assertSame(fallback, empty.model);
    assertTrue(empty.usedFallback);
    assertSame(fallback, invalid.model);
    assertTrue(invalid.usedFallback);
  }

  @Test
  public void unavailableModelListReturnsActionableError() {
    DetectorModelResolver.Result empty =
        DetectorModelResolver.resolve(Collections.emptyList(), "anything");
    assertNull(empty.model);
    assertTrue(empty.error.contains("未找到人物检测模型"));

    Model unrelated =
        new Model(
            1,
            Model.CLASS.AUTOPILOT,
            Model.TYPE.CMDNAV,
            "CIL-Mobile-Cmd.tflite",
            Model.PATH_TYPE.ASSET,
            "networks/autopilot.tflite",
            "256x96");
    DetectorModelResolver.Result noFallback =
        DetectorModelResolver.resolve(Collections.singletonList(unrelated), "");
    assertNull(noFallback.model);
    assertEquals("内置人物检测模型不可用，请重新安装应用", noFallback.error);
  }

  static Model model(int id, String name) {
    return new Model(
        id,
        Model.CLASS.MOBILENET,
        Model.TYPE.DETECTOR,
        name,
        Model.PATH_TYPE.ASSET,
        "networks/" + name,
        "300x300");
  }
}
