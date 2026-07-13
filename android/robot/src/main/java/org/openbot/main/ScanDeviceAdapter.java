package org.openbot.main;

import android.content.Context;
import android.graphics.Color;
import android.util.SparseArray;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.ficat.easyble.BleDevice;
import java.util.List;
import org.openbot.R;

public class ScanDeviceAdapter extends CommonRecyclerViewAdapter<BleDevice> {
  public interface ConnectionStatusProvider {
    String getStatus(BleDevice device);
  }

  private final ConnectionStatusProvider statusProvider;

  public ScanDeviceAdapter(
      @NonNull Context context,
      @NonNull List<BleDevice> dataList,
      @NonNull SparseArray<int[]> resLayoutAndViewIds,
      @NonNull ConnectionStatusProvider statusProvider) {
    super(context, dataList, resLayoutAndViewIds);
    this.statusProvider = statusProvider;
  }

  @Override
  public int getItemResLayoutType(int position) {
    return R.layout.ble_listview_tv;
  }

  @Override
  public void bindDataToItem(MyViewHolder holder, BleDevice data, int position) {
    TextView tvName = (TextView) holder.mViews.get(R.id.ble_name);
    TextView tvAddress = (TextView) holder.mViews.get(R.id.ble_address);
    TextView tvConnectionState = (TextView) holder.mViews.get(R.id.ble_connection_state);
    tvName.setText(data.name);
    tvAddress.setText(data.address);
    String status = statusProvider.getStatus(data);
    tvConnectionState.setText(status);
    if ("连接中".equals(status) || "初始化串口".equals(status) || "自动重试".equals(status)) {
      tvConnectionState.setTextColor(Color.BLUE);
    } else if ("断开".equals(status)) {
      tvConnectionState.setTextColor(Color.RED);
    } else {
      tvConnectionState.setTextColor(Color.BLACK);
    }
  }
}
