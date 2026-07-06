package org.openbot.cropcollector;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.openbot.R;
import org.openbot.common.CameraFragment;
import org.openbot.databinding.FragmentPersonCropCollectorBinding;
import org.openbot.tflite.Model;
import org.openbot.utils.Enums;

public class PersonCropCollectorFragment extends CameraFragment {

  private FragmentPersonCropCollectorBinding binding;

  @Override
  public View onCreateView(
      @NotNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentPersonCropCollectorBinding.inflate(inflater, container, false);
    return inflateFragment(binding, inflater, container);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    List<String> models = getModelNames(f -> f.type.equals(Model.TYPE.DETECTOR));
    initModelSpinner(binding.modelSpinner, models, preferencesManager.getObjectNavModel());

    setAnalyserResolution(Enums.Preview.HD.getValue());

    binding.statusText.setText(getString(R.string.person_crop_idle));
    binding.btnStart.setEnabled(true);
    binding.btnStop.setEnabled(false);
  }

  @Override
  protected void processFrame(android.graphics.Bitmap bitmap, ImageProxy image) {
  }

  @Override
  protected void processUSBData(String data) {
  }

  @Override
  protected void processControllerKeyData(String commandType) {
  }
}
