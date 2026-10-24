package com.x3card.car3d;

import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

/** 应用跟宿主打交道的接口：车上是真宿主（Uiplus，宿主给外部应用的 Binder 接口），没装宿主、不是比亚迪车机时是调试宿主（DebugHost，模拟车况） */
interface Host {
    /** 界面显示时连上 */
    void start();
    /** 进后台（真宿主过一阵才断开） */
    void background();
    void stop();
    void quit();
    /** 发一个请求（方法、参数同网页卡片的 uiplus），结果回到主线程 */
    void request(String method, JSONObject params, Uiplus.Reply reply);
    void authorize(JSONObject permissions, Uiplus.Reply reply);
    boolean ready();
    boolean backendReady();
    boolean canRead();
    boolean canExec();
    String session();
    ParcelFileDescriptor openCover(String url);
}
