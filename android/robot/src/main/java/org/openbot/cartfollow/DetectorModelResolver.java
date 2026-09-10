package org.openbot.cartfollow;

import java.util.List;
import org.openbot.tflite.Model;

/** Resolves the detector model independently from the visibility or callbacks of its spinner. */
final class DetectorModelResolver {
  static final String DEFAULT_MODEL_NAME = "MobileNetV1-300.tflite";

  static final class Result {
    final Model model;
    final boolean usedFallback;
    final String error;

    Result(Model model, boolean usedFallback, String error) {
      this.model = model;
      this.usedFallback = usedFallback;
      this.error = error == null ? "" : error;
    }

    boolean available() {
      return model != null;
    }
  }

  private DetectorModelResolver() {}

  static Result resolve(List<Model> models, String savedName) {
    if (models == null || models.isEmpty()) {
      return new Result(null, false, "未找到人物检测模型，请检查应用资源");
    }
    String requested = normalize(savedName);
    if (!requested.isEmpty()) {
      Model saved = findDetector(models, requested);
      if (saved != null) return new Result(saved, false, "");
    }
    Model fallback = findDetector(models, normalize(DEFAULT_MODEL_NAME));
    if (fallback == null) {
      return new Result(null, false, "内置人物检测模型不可用，请重新安装应用");
    }
    return new Result(fallback, true, "");
  }

  private static Model findDetector(List<Model> models, String normalizedName) {
    for (Model candidate : models) {
      if (candidate == null || candidate.type != Model.TYPE.DETECTOR) continue;
      if (normalize(candidate.name).equals(normalizedName)) return candidate;
    }
    return null;
  }

  private static String normalize(String name) {
    if (name == null) return "";
    String value = name.trim();
    int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
    if (slash >= 0) value = value.substring(slash + 1);
    if (value.toLowerCase(java.util.Locale.US).endsWith(".tflite")) {
      value = value.substring(0, value.length() - ".tflite".length());
    }
    return value.toLowerCase(java.util.Locale.US);
  }
}
