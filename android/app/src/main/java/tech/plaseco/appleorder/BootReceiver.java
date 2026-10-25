package tech.plaseco.appleorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机、App 更新后自动恢复订单提醒（需要员工在手机设置里允许“自启动”）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (NotifyService.isEnabled(context)) NotifyService.start(context);
    }
}
