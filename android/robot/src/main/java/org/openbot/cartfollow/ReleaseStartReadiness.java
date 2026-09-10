package org.openbot.cartfollow;

/** One source of truth for whether the release UI may start autonomous following. */
final class ReleaseStartReadiness {
  enum State {
    EMERGENCY,
    BLE_DISCONNECTED,
    FIRMWARE_WAIT,
    MODEL_LOADING,
    MODEL_ERROR,
    CAMERA_WAIT,
    READY
  }

  final State state;
  final String message;

  private ReleaseStartReadiness(State state, String message) {
    this.state = state;
    this.message = message;
  }

  boolean ready() {
    return state == State.READY;
  }

  static ReleaseStartReadiness evaluate(
      boolean emergency,
      boolean bleConnected,
      boolean firmwareReady,
      boolean detectorReady,
      String modelError,
      boolean cameraReady) {
    if (emergency) {
      return new ReleaseStartReadiness(
          State.EMERGENCY, "急停已锁存，请重启小车后重新连接");
    }
    if (!bleConnected) {
      return new ReleaseStartReadiness(State.BLE_DISCONNECTED, "请先连接蓝牙");
    }
    if (!firmwareReady) {
      return new ReleaseStartReadiness(State.FIRMWARE_WAIT, "蓝牙已连接，正在等待小车就绪");
    }
    if (!cameraReady) {
      return new ReleaseStartReadiness(State.CAMERA_WAIT, "相机画面正在准备");
    }
    if (!detectorReady) {
      if (modelError != null && !modelError.trim().isEmpty()) {
        return new ReleaseStartReadiness(State.MODEL_ERROR, modelError);
      }
      return new ReleaseStartReadiness(State.MODEL_LOADING, "相机模型正在准备");
    }
    return new ReleaseStartReadiness(State.READY, "已就绪，点击 Start 开始");
  }
}
