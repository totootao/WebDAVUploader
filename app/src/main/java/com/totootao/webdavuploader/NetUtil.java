package com.totootao.webdavuploader;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * 网络状态判断：本应用只在 WiFi 下同步。
 */
public class NetUtil {

    /** 当前活动网络是否为 WiFi（VPN over WiFi 也判定为 WiFi）。 */
    public static boolean isWifi(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities cap = cm.getNetworkCapabilities(n);
        if (cap == null) return false;
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
}
