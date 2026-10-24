package com.x3card.car3d;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 宿主 UiPlus 给外部应用的接口（com.wzw.bydmodule 的 UiplusExternalService，AIDL IUiplusApi，跑在宿主的 :remote 进程）。
 * 协议是从宿主 APK 2.2.1.10 反编译出来的（安卓那份接口文档没公开），和网页卡片是同一套方法：
 * - openSession(传输版本 3, 自己的包名, 回调) → {result: {sessionId, permissions: {readData, executeActions}, backendReady}} 或 {error}；
 *   宿主核对包名、调用方 UID、签名 SHA-256、首次安装时间，所以重装（先卸载）要重新授权，adb install -r 不用。
 * - request(会话号, 请求号, 方法, 参数 JSON)：单向调用，结果、订阅推送、会话事件都从回调 onMessage(会话号, JSON) 回来：
 *   {requestId, result} / {requestId, error: {code, message}}；{subscriptionId, data}；{type: 'session', event: authorizationChanged / backendReady / closing}。
 * - 读数据要 readData、操作要 executeActions：发 authorization.request {permissions: {...}}，宿主弹确认页（一分钟最多 3 次）。
 * - openCover(会话号, 封面令牌) → 封面图片的文件描述符。媒体快照里的 coverUrl 是 https://media.uiplus.invalid/<令牌>，要传的是最后那段令牌
 *   （宿主每个会话只留最近两个令牌）。
 * - 没有授权的会话 2 分钟、有授权的 10 分钟没动静会被宿主关掉（有订阅时不关）；关了、宿主进程死了都自动重连。
 * - 授权申请挂在发出申请的那个会话上：确认页弹出来时（在多窗口里会把应用整个盖住、应用进后台）会话不能关，不然车主点了允许宿主也存不上
 *   （宿主 2.2.1.6 报"授权未保存，请重新申请"）。所以进后台不马上断开（background：30 秒后断，等授权时最多 5 分钟），回到前台再问一次授权状态。
 * 阻塞的跨进程调用都放在自己的线程里，交给界面的回调都回到主线程。
 */
public final class Uiplus implements Host {
    public interface Listener {
        /** connecting / ready / closed / error（detail 是给人看的说明） */
        void onState(String state, String detail);
        void onPermissions(boolean read, boolean exec);
        /** 订阅推送 {subscriptionId, data | error} */
        void onEvent(JSONObject msg);
    }

    public interface Reply {
        void done(JSONObject result, String code, String message);
    }

    static final String TAG = "car3d";
    private static final String PKG = "com.wzw.bydmodule";
    private static final String SVC = "com.wzw.bydmodule.uiplus.UiplusExternalService";
    private static final String API = "com.wzw.bydmodule.uiplus.IUiplusApi";
    private static final String CB = "com.wzw.bydmodule.uiplus.IUiplusCallback";
    private static final int T_OPEN = 1, T_AUTH_STATE = 2, T_REQUEST = 4, T_CLOSE = 6, T_COVER = 7;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("uiplus");
    private final Handler work;
    private final Map<String, Reply> pending = new HashMap<>();
    private IBinder remote;
    private String sid;
    private boolean started, bound, read, exec, authPending, backend;
    private final Runnable later = this::stop;
    private int seq, gen;

    /** 宿主回调进来的 Binder（IUiplusCallback.onMessage 是单向调用：code 1，两个字符串） */
    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(CB); return true; }
            if (code != 1) return false;
            data.enforceInterface(CB);
            final String s = data.readString(), json = data.readString();
            main.post(() -> dispatch(s, json));
            return true;
        }
    };

    private final IBinder.DeathRecipient death = () -> main.post(() -> lost("宿主进程重启了"));

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            remote = b;
            try { b.linkToDeath(death, 0); } catch (Exception e) { Log.w(TAG, "linkToDeath", e); }
            final int g = gen;
            work.post(() -> open(g));
        }

        @Override
        public void onServiceDisconnected(ComponentName n) { lost("和宿主断开了"); }
    };

    public Uiplus(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        thread.start();
        work = new Handler(thread.getLooper());
    }

    public boolean canRead() { return read; }
    public boolean canExec() { return exec; }
    public boolean ready() { return sid != null; }
    /** 宿主后端（连车机的那一头）好了没有：开机时宿主可能比我们晚，没好之前订阅会报 BACKEND_UNAVAILABLE，好了宿主发 backendReady */
    public boolean backendReady() { return sid != null && backend; }
    public String session() { return sid; }

    /** 界面显示时连上，藏起来时断开（宿主那边的订阅、会话跟着释放） */
    public void start() {
        main.removeCallbacks(later);
        if (started) { refresh(); return; }
        started = true;
        bind();
    }

    /** 进后台：先不断开（宿主的授权确认页会把应用盖住，授权申请挂在这个会话上），30 秒后再断；等授权时最多等 5 分钟 */
    public void background() {
        if (!started) return;
        main.removeCallbacks(later);
        main.postDelayed(later, authPending ? 300000 : 30000);
    }

    /** 问一次宿主现在的授权（回到前台时：确认页上点了什么，会话事件可能已经错过了） */
    private void refresh() {
        final String s = sid;
        final IBinder r = remote;
        if (s == null || r == null) return;
        work.post(() -> {
            String out = null;
            Parcel d = Parcel.obtain(), rep = Parcel.obtain();
            try {
                d.writeInterfaceToken(API);
                d.writeString(s);
                r.transact(T_AUTH_STATE, d, rep, 0);
                rep.readException();
                out = rep.readString();
            } catch (Exception e) {
                Log.w(TAG, "getAuthorizationState: " + e.getMessage());
            } finally {
                d.recycle();
                rep.recycle();
            }
            final String o = out;
            main.post(() -> {
                if (!s.equals(sid) || o == null) return;
                Log.w(TAG, "authorizationState → " + o);
                try { JSONObject res = new JSONObject(o).optJSONObject("result"); if (res != null) perms(res.optJSONObject("permissions")); } catch (Exception ignored) { }
            });
        });
    }

    /** 发授权申请（宿主弹确认页）；结果回来之前进后台也不断开 */
    public void authorize(JSONObject permissions, Reply reply) {
        authPending = true;
        try {
            request("authorization.request", new JSONObject().put("permissions", permissions), (res, code, msg) -> {
                authPending = false;
                Log.w(TAG, "authorization → " + res + (code != null ? " / " + code + " " + msg : ""));
                if (res != null) perms(res.optJSONObject("permissions"));
                if (reply != null) reply.done(res, code, msg);
            });
        } catch (Exception e) {
            authPending = false;
        }
    }

    public void stop() {
        if (!started) return;
        started = false;
        authPending = false;
        gen++;
        main.removeCallbacksAndMessages(null);
        final String s = sid;
        final IBinder r = remote;
        sid = null;
        backend = false;
        failAll("SESSION_CLOSED", "已断开");
        if (s != null && r != null) work.post(() -> call(r, T_CLOSE, s, null, false));
        if (bound) { try { ctx.unbindService(conn); } catch (Exception ignored) { } }
        bound = false;
        if (r != null) { try { r.unlinkToDeath(death, 0); } catch (Exception ignored) { } }
        remote = null;
    }

    private void bind() {
        listener.onState("connecting", "正在连接宿主…");
        Intent it = new Intent().setClassName(PKG, SVC);
        try {
            bound = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (!bound) {
            listener.onState("error", "没找到宿主的 UiPlus 服务（要装 2.2.1.10 及以后的宿主）");
            retry(10000);
        }
    }

    private void lost(String why) {
        if (!started) return;
        Log.w(TAG, "lost: " + why);
        sid = null;
        backend = false;
        failAll("SESSION_CLOSED", why);
        listener.onState("closed", why + "，重新连接…");
        if (bound) { try { ctx.unbindService(conn); } catch (Exception ignored) { } }
        bound = false;
        remote = null;
        gen++;
        retry(2000);
    }

    private void retry(long ms) {
        main.postDelayed(() -> { if (started && !bound) bind(); }, ms);
    }

    /** 工作线程：开会话 */
    private void open(final int g) {
        IBinder r = remote;
        if (r == null) return;
        String out = null, err = null;
        Parcel d = Parcel.obtain(), rep = Parcel.obtain();
        try {
            d.writeInterfaceToken(API);
            d.writeInt(3);
            d.writeString(ctx.getPackageName());
            d.writeStrongBinder(callback);
            r.transact(T_OPEN, d, rep, 0);
            rep.readException();
            out = rep.readString();
        } catch (Exception e) {
            err = e.getMessage();
        } finally {
            d.recycle();
            rep.recycle();
        }
        final String o = out, e = err;
        main.post(() -> {
            if (g != gen || !started) return;
            Log.w(TAG, "openSession → " + o + (e != null ? " / " + e : ""));
            try {
                JSONObject j = new JSONObject(o == null ? "{}" : o);
                if (j.has("error")) {
                    JSONObject x = j.getJSONObject("error");
                    listener.onState("error", "宿主拒绝了：" + x.optString("message", x.optString("code")));
                    retry(15000);
                    bound = false;
                    return;
                }
                JSONObject res = j.getJSONObject("result");
                sid = res.getString("sessionId");
                backend = res.optBoolean("backendReady");
                perms(res.optJSONObject("permissions"));
                listener.onState("ready", res.optBoolean("backendReady") ? "已连接" : "已连接（宿主后端还没好）");
            } catch (Exception x) {
                listener.onState("error", "连不上宿主：" + (e != null ? e : x.getMessage()));
                lost("会话没开成");
            }
        });
    }

    private void perms(JSONObject p) {
        if (p == null) return;
        boolean r = p.optBoolean("readData"), x = p.optBoolean("executeActions");
        if (r == read && x == exec) return;
        read = r;
        exec = x;
        listener.onPermissions(r, x);
    }

    /** 发一个请求（方法、参数同网页卡片的 uiplus），结果回到主线程 */
    public void request(String method, JSONObject params, Reply reply) {
        final String s = sid;
        final IBinder r = remote;
        if (s == null || r == null) { if (reply != null) reply.done(null, "SESSION_CLOSED", "还没连上宿主"); return; }
        final String id = "r" + (++seq);
        if (reply != null) pending.put(id, reply);
        final String body = (params == null ? new JSONObject() : params).toString();
        work.post(() -> {
            Parcel d = Parcel.obtain();
            try {
                d.writeInterfaceToken(API);
                d.writeString(s);
                d.writeString(id);
                d.writeString(method);
                d.writeString(body);
                r.transact(T_REQUEST, d, null, IBinder.FLAG_ONEWAY);
            } catch (Exception e) {
                main.post(() -> { Reply rp = pending.remove(id); if (rp != null) rp.done(null, "DEVICE_ERROR", String.valueOf(e.getMessage())); });
            } finally {
                d.recycle();
            }
        });
    }

    /** 读封面（工作线程里阻塞调用；拿不到返回 null）：url 是媒体快照里的 coverUrl，交给宿主的是最后那段令牌 */
    public ParcelFileDescriptor openCover(String url) {
        String s = sid;
        IBinder r = remote;
        if (s == null || r == null || url == null) return null;
        String token = url.substring(url.lastIndexOf('/') + 1);
        Parcel d = Parcel.obtain(), rep = Parcel.obtain();
        try {
            d.writeInterfaceToken(API);
            d.writeString(s);
            d.writeString(token);
            r.transact(T_COVER, d, rep, 0);
            rep.readException();
            return rep.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(rep) : null;
        } catch (Exception e) {
            Log.w(TAG, "openCover " + url + ": " + e.getMessage());
            return null;
        } finally {
            d.recycle();
            rep.recycle();
        }
    }

    /** 关会话等只要发出去、不要结果的调用 */
    private void call(IBinder r, int code, String a, String b, boolean twoWay) {
        Parcel d = Parcel.obtain(), rep = Parcel.obtain();
        try {
            d.writeInterfaceToken(API);
            d.writeString(a);
            if (b != null) d.writeString(b);
            r.transact(code, d, rep, 0);
            rep.readException();
        } catch (Exception e) {
            Log.w(TAG, "call " + code + ": " + e.getMessage());
        } finally {
            d.recycle();
            rep.recycle();
        }
    }

    private void dispatch(String s, String json) {
        if (!started || s == null || !s.equals(sid)) return;
        JSONObject m;
        try { m = new JSONObject(json); } catch (Exception e) { Log.w(TAG, "坏消息：" + json); return; }
        if (m.has("requestId")) {
            Reply rp = pending.remove(m.optString("requestId"));
            if (rp == null) return;
            JSONObject err = m.optJSONObject("error");
            if (err != null) rp.done(null, err.optString("code"), err.optString("message"));
            else rp.done(m.optJSONObject("result"), null, null);
            return;
        }
        if ("session".equals(m.optString("type"))) {
            String ev = m.optString("event");
            Log.w(TAG, "session event: " + json);
            if ("authorizationChanged".equals(ev)) perms(m.optJSONObject("permissions"));
            else if ("backendReady".equals(ev)) { backend = true; listener.onState("ready", "已连接"); }
            else if ("closing".equals(ev)) lost("宿主关了会话");
            return;
        }
        if (m.has("subscriptionId")) listener.onEvent(m);
    }

    private void failAll(String code, String msg) {
        Map<String, Reply> p = new HashMap<>(pending);
        pending.clear();
        for (Reply r : p.values()) r.done(null, code, msg);
    }

    public void quit() {
        stop();
        thread.quitSafely();
    }
}
