package com.x3card.car3d;

import android.content.Context;
import android.util.Log;

import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.Gltf;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解好的车模几何存一份（应用的缓存目录，系统空间不够时可能被清掉，清掉了下次照常解）：导入的车模包几何是压缩过的（EXT_meshopt_compression），
 * 每次打开都要解压（海豹 06 在车机上 0.55～0.95 秒），双面材质还要理顺绕向（0.35～0.45 秒）。第一次画出车以后把解好、理顺好的数据写下来，
 * 下次打开直接映射进来用。按车模文件的大小和修改时间认，换了车模、恢复默认时删掉。只多占一份不压缩的几何（海豹 06 约 57MB）
 */
final class GeoCache {
    private GeoCache() {}
    private static final int MAGIC = 0x43473344, VER = 1; // 'D3GC'
    private static File dir(Context c) { return new File(c.getCacheDir(), "geo"); }
    private static File file(Context c) { return new File(dir(c), "geo.bin"); }

    /** 这个车模的缓存键：只有导入的车模包（默认车模没压缩） */
    static String key(Context c, CarModel.Rec r) {
        if (r == null || r.builtin) return null;
        File f = CarModel.file(c);
        return f.exists() ? "v" + VER + ":" + f.length() + ":" + f.lastModified() : null;
    }
    static void clear(Context c) {
        File[] l = dir(c).listFiles();
        if (l != null) for (File f : l) f.delete();
    }

    /** 读缓存：键对得上才用（映射成私有的：后面再改也只改内存里的那份） */
    static Gltf.Pre load(Context c, String key) {
        File f = file(c);
        if (key == null || !f.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw"); FileChannel ch = raf.getChannel()) { // 私有映射要能写的通道（改的只是内存里那份）
            ByteBuffer b = ch.map(FileChannel.MapMode.PRIVATE, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != MAGIC || b.getInt(4) != VER) return null;
            int kl = b.getInt(8);
            byte[] kb = new byte[kl];
            ByteBuffer d = b.duplicate(); d.position(12); d.get(kb);
            if (!key.equals(new String(kb, StandardCharsets.UTF_8))) return null;
            int p = 12 + kl, nv = b.getInt(p), nw = b.getInt(p + 4);
            p += 8;
            int[] vi = new int[nv], len = new int[nv];
            for (int i = 0; i < nv; i++) { vi[i] = b.getInt(p); len[i] = b.getInt(p + 4); p += 8; }
            Gltf.Pre pre = new Gltf.Pre();
            for (int i = 0; i < nw; i++) { pre.wound.add(b.getInt(p)); p += 4; }
            p = (p + 15) & ~15;
            for (int i = 0; i < nv; i++) {
                if (p + len[i] > b.capacity()) return null;
                ByteBuffer s = b.duplicate(); s.position(p); s.limit(p + len[i]);
                pre.views.put(vi[i], s.slice().order(ByteOrder.LITTLE_ENDIAN));
                p += (len[i] + 3) & ~3;
            }
            return pre;
        } catch (Exception e) { Log.w("car3d", "车模几何缓存读不出来", e); return null; }
    }

    private static Thread saving;
    /** 画出车以后在后台写一份（同一时间只写一份；写的时候不再有人改这些数据：理顺绕向在建车时就做完了） */
    static synchronized void save(Context c, String key, Gltf g, Obj3D model) {
        if (key == null || g == null || model == null || saving != null) return;
        final Context app = c.getApplicationContext();
        saving = new Thread(() -> {
            try { Thread.sleep(3000); write(app, key, g, model); }
            catch (Exception e) { Log.w("car3d", "车模几何缓存没写成", e); file(app).delete(); }
            finally { synchronized (GeoCache.class) { saving = null; } }
        }, "geo-cache");
        saving.setPriority(Thread.MIN_PRIORITY);
        saving.start();
    }
    private static void write(Context c, String key, Gltf g, Obj3D model) throws Exception {
        List<Integer> mv = g.meshoptViews();
        if (mv.isEmpty()) return;
        List<ByteBuffer> data = new ArrayList<>();
        for (int vi : mv) { ByteBuffer v = g.decoded(vi); if (v == null) return; data.add(v); } // 有没解出来的：不存（下次照常解）
        // 在原来的索引上理顺过绕向的（复制一份再改的不算：那份不在这些数据里）
        final Set<Integer> wound = new HashSet<>();
        final Map<Geo, Boolean> seen = new IdentityHashMap<>();
        model.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Geo geo = ((Mesh) o).geometry;
            if (geo == null || seen.put(geo, true) != null) return;
            Object ia = geo.ud.get("ixAcc");
            if (ia instanceof Integer && geo.ud.containsKey("wound") && !geo.ud.containsKey("woundCopy")) wound.add((Integer) ia);
        });
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        int head = 12 + kb.length + 8 + mv.size() * 8 + wound.size() * 4;
        ByteBuffer h = ByteBuffer.allocate((head + 15) & ~15).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(MAGIC).putInt(VER).putInt(kb.length).put(kb).putInt(mv.size()).putInt(wound.size());
        for (int i = 0; i < mv.size(); i++) h.putInt(mv.get(i)).putInt(data.get(i).capacity());
        for (int w : wound) h.putInt(w);
        h.position(0);
        File d = dir(c);
        d.mkdirs();
        clear(c);
        File tmp = new File(d, "geo.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp); FileChannel ch = out.getChannel()) {
            while (h.hasRemaining()) ch.write(h);
            ByteBuffer pad = ByteBuffer.allocate(4);
            for (ByteBuffer v : data) {
                ByteBuffer s = v.duplicate(); s.clear();
                while (s.hasRemaining()) ch.write(s);
                int p = (4 - v.capacity() % 4) % 4;
                if (p > 0) { pad.clear(); pad.limit(p); ch.write(pad); }
            }
            out.getFD().sync();
        }
        if (!tmp.renameTo(file(c))) { tmp.delete(); return; }
        Log.w("car3d", "车模几何缓存：" + mv.size() + " 块、" + (file(c).length() >> 20) + "MB，理顺过绕向的索引 " + wound.size() + " 个");
    }
}
