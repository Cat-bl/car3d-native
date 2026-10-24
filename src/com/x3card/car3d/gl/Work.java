package com.x3card.car3d.gl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 载入车模时分到几个处理器核上同时做的活（解压几何、理顺绕向、解贴图）：线程数 = 核数，闲 5 秒就收掉 */
public final class Work {
    private Work() {}
    private static ThreadPoolExecutor pool;

    private static synchronized ThreadPoolExecutor pool() {
        if (pool == null) {
            int n = Math.max(2, Runtime.getRuntime().availableProcessors());
            pool = new ThreadPoolExecutor(n, n, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "load");
                t.setDaemon(true);
                return t;
            });
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }
    public static <T> Future<T> submit(Callable<T> c) { return pool().submit(c); }

    public interface Each<T> { void run(T t) throws Exception; }
    /** 每一项各交给一个线程，全做完才返回；有一项出错就抛出第一个错 */
    public static <T> void all(List<T> items, Each<T> fn) throws Exception {
        if (items.size() <= 1) { for (T t : items) fn.run(t); return; }
        List<Future<Object>> fs = new ArrayList<>(items.size());
        for (final T t : items) fs.add(submit(() -> { fn.run(t); return null; }));
        Exception first = null;
        for (Future<Object> f : fs) {
            try { f.get(); }
            catch (ExecutionException e) { if (first == null) first = e.getCause() instanceof Exception ? (Exception) e.getCause() : new RuntimeException(e.getCause()); }
        }
        if (first != null) throw first;
    }
}
