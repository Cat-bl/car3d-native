package com.x3card.car3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.util.Log;

import com.x3card.car3d.gl.Attr;
import com.x3card.car3d.gl.Camera;
import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.M3;
import com.x3card.car3d.gl.M4;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.RT;
import com.x3card.car3d.gl.Renderer;
import com.x3card.car3d.gl.Tex;
import com.x3card.car3d.gl.V3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把贴纸贴到车上（照网页版 carwrap.js 的投影部分逐段搬）：沿一个方向把贴图投过去，按车身的曲面生成一层薄网格（沿法线外推一点）。
 * 只贴看得见的那层（沿投影方向离屏画一张深度图，被挡住的不贴）；背对投影方向太多的不贴、快到时淡出；包住（上下包、前后包）按超椭圆截面展开。
 * 网格按会动的部件分组挂（门、引擎盖、尾门的铰链组、车窗、车身），开门时门上那块跟着走。贴图按格子记哪里有内容，网格只铺在有内容的格子里。
 * 原生版只显示（贴纸在网页版里调），建车时一次贴好：全部在 GL 线程里
 */
final class WrapCut {
    /** 车况页给的：车、零件、哪些能贴、会动的部件、材质设置 */
    interface Ctx {
        Obj3D root(); WrapArt.Dims dims(); Renderer renderer(); Context android();
        boolean shadow(); float envK();
        void atRest(Runnable r);
        List<Mesh> occluders();
        /** 贴在哪种零件上（body / glass / both）的网格 */
        List<Mesh> targets(String on);
        /** 网格属于哪个会动的部件；都不是就是车身（root） */
        Obj3D owner(Mesh m);
        /** 车模包里第 i 张 glTF 图片的字节 */
        ByteBuffer image(int i);
    }
    private final Ctx ctx;
    private final Map<String, G> gc = new HashMap<>();
    private final Map<String, Ent> tex = new HashMap<>();
    int meshes;

    WrapCut(Ctx ctx) { this.ctx = ctx; }

    // ================= 贴图 =================
    static final class Ent { Tex tex; WrapArt.Mask mask; float aspect; }
    private static final List<String> PLACE = Arrays.asList("id", "at", "dir", "up", "rot", "w", "sx", "flip", "fv", "both", "on", "alpha", "hide", "fin", "rough", "metal", "wm", "side");
    /** 贴图的键：内容一样的贴纸共用一张贴图 */
    private String texKey(JSONObject w) {
        TreeMap<String, Object> o = new TreeMap<>();
        for (Iterator<String> it = w.keys(); it.hasNext(); ) { String k = it.next(); if (!PLACE.contains(k)) o.put(k, w.opt(k)); }
        if ("stripe".equals(w.optString("kind"))) { o.put("p", new TreeMap<>(WrapArt.params(w))); WrapArt.Dims d = ctx.dims(); o.put("dims", Math.round(d.hw * 100) + "," + Math.round(d.h * 100) + "," + Math.round(d.hl * 100)); }
        return o.toString();
    }
    /** 拿贴图（同一次建车里一样的共用）：画拉花、写字、加工图片；做不出来 null */
    private Ent texGet(JSONObject w) {
        String key = texKey(w);
        if (tex.containsKey(key)) return tex.get(key);
        Bitmap c = null;
        try {
            String kind = w.optString("kind");
            if ("stripe".equals(kind)) c = WrapArt.stripeCanvas(w, ctx.dims());
            else if ("img".equals(kind)) {
                Bitmap raw = w.opt("img") instanceof Number ? WrapArt.decode(ctx.image(w.optInt("img"))) : null;
                if (raw != null) { c = WrapArt.processImage(raw, w); raw.recycle(); }
            } else {
                String f = w.optString("font", "");
                android.graphics.Typeface fam = WrapArt.font(ctx.android(), f);
                if (fam == null && f.startsWith("u:") && w.opt("raster") instanceof Number) { // 没有这个字体：用导出时画好的图
                    Bitmap raw = WrapArt.decode(ctx.image(w.optInt("raster")));
                    if (raw != null) { c = WrapArt.processImage(raw, new JSONObject()); raw.recycle(); }
                }
                if (c == null) c = WrapArt.textCanvas(w, fam);
            }
        } catch (Exception e) { Log.w("car3d", "贴纸的贴图没做出来", e); c = null; }
        Ent e = null;
        if (c != null && c.getWidth() > 0) {
            e = new Ent();
            e.mask = WrapArt.maskOf(c);
            e.aspect = c.getHeight() / (float) c.getWidth();
            e.tex = Tex.canvas(c, false);
            e.tex.premultiplyAlpha = true; // 预乘透明度取样：缩小、斜着看时透明的边不会混进黑色
            e.tex.anisotropy = 4;
            c.recycle();
        }
        tex.put(key, e);
        return e;
    }

    // ================= 投影：收集 =================
    private static final int RES = 1024, CELLS = 64;
    static final class Basis { V3 n, b1, b2; }
    /** 投影方向 n 的平面坐标轴：b2 尽量朝上（车顶往下投时朝车头），b1 = b2 × n */
    private static Basis basis(V3 n) {
        V3 ref = Math.abs(n.y) < 0.9f ? new V3(0, 1, 0) : new V3(0, 0, 1), b2 = ref.clone().addScaled(n, -ref.dot(n)).normalize();
        Basis b = new Basis();
        b.n = n.clone(); b.b2 = b2; b.b1 = new V3().crossVectors(b2, n);
        return b;
    }
    private static final double[] UNPACK = { 255.0 / 256 / 16777216 / 255, 255.0 / 256 / 65536 / 255, 255.0 / 256 / 256 / 255, 255.0 / 256 / 255 };
    /** 沿投影方向的正交深度图：每个像素离投影平面最近的车身表面（米，没有是无穷大） */
    private float[] depthMap(Basis B, V3 O, float R, float e1, float e2, int W, int H) {
        Renderer r = ctx.renderer();
        Obj3D root = ctx.root();
        RT rt = new RT(W, H, GLES30.GL_UNSIGNED_BYTE, true, false, 0, GLES30.GL_NEAREST);
        Mat dm = new Mat(Mat.DEPTH);
        dm.depthPacking = true; dm.side = Mat.DOUBLE;
        Camera cam = Camera.ortho(-e1, e1, e2, -e2, 0.01f, 2 * R);
        root.updateMatrixWorld(true);
        cam.position.copy(O).addScaled(B.n, R).applyMatrix4(root.matrixWorld);
        cam.up.copy(B.b2).transformDirection(root.matrixWorld);
        cam.lookAt(O.clone().applyMatrix4(root.matrixWorld));
        cam.updateMatrixWorld(true);
        final Map<Mesh, Boolean> occ = new IdentityHashMap<>();
        for (Mesh m : ctx.occluders()) occ.put(m, true);
        final List<Object[]> keep = new ArrayList<>();
        boolean vis = root.visible;
        root.visible = true;
        root.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Mesh m = (Mesh) o;
            keep.add(new Object[] { m, m.material, m.visible });
            if (occ.containsKey(m)) m.material = dm; else m.visible = false;
        });
        ByteBuffer px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
        RT old = r.getRenderTarget();
        float cr = r.clearR, cg = r.clearG, cb = r.clearB, ca = r.clearA;
        boolean ac = r.autoClear;
        try {
            r.setRenderTarget(rt);
            r.clearR = r.clearG = r.clearB = r.clearA = 1;
            r.clear(true, true);
            r.autoClear = false;
            r.render(root, cam);
            rt.readPixels(px);
        } finally {
            for (Object[] k : keep) { ((Mesh) k[0]).material = (Mat) k[1]; ((Mesh) k[0]).visible = (Boolean) k[2]; }
            root.visible = vis;
            r.clearR = cr; r.clearG = cg; r.clearB = cb; r.clearA = ca; r.autoClear = ac;
            r.setRenderTarget(old);
            rt.dispose(); dm.dispose();
        }
        float[] out = new float[W * H];
        float span = 2 * R - 0.01f;
        for (int i = 0; i < W * H; i++) {
            int o4 = i * 4;
            double d = (px.get(o4) & 255) * UNPACK[0] + (px.get(o4 + 1) & 255) * UNPACK[1] + (px.get(o4 + 2) & 255) * UNPACK[2] + (px.get(o4 + 3) & 255) * UNPACK[3];
            out[i] = d >= 0.9999 ? Float.POSITIVE_INFINITY : 0.01f + (float) d * span;
        }
        return out;
    }
    /** 收集到的一个网格：投到平面上的坐标、离平面的距离、法线朝投影方向的程度；顶点写进组里时的编号缓存 */
    static final class MS {
        Mesh m; Obj3D owner; M4 Mo; M3 No; float os; float[] PX, PY, PD, F; Attr P, N; int[] vmap, vst; Grp vgrp;
    }
    static final class G {
        Basis B; V3 O; float e1, e2, cs, R; int gw, gh; int[] start, items, tris, stamp; List<MS> meshes = new ArrayList<>(); int st;
    }
    /** 收集（按投影方向 + 贴在哪缓存）：门窗盖摆回关着的样子再收集 */
    private G gather(final V3 n, final String on) {
        String key = on + ":" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f", n.x, n.y, n.z);
        if (gc.containsKey(key)) return gc.get(key);
        final G[] g = new G[1];
        ctx.atRest(() -> g[0] = collect(n, on));
        gc.put(key, g[0]);
        return g[0];
    }
    private G collect(V3 n, String on) {
        WrapArt.Dims dm = ctx.dims();
        final Basis B = basis(n);
        final V3 O = new V3(0, dm.h / 2, 0);
        final float R = (float) Math.sqrt(dm.hw * dm.hw + dm.h * dm.h / 4 + dm.hl * dm.hl) + 0.3f;
        float e1 = Math.abs(B.b1.x) * dm.hw + Math.abs(B.b1.y) * dm.h / 2 + Math.abs(B.b1.z) * dm.hl + 0.08f, e2 = Math.abs(B.b2.x) * dm.hw + Math.abs(B.b2.y) * dm.h / 2 + Math.abs(B.b2.z) * dm.hl + 0.08f;
        int W = e1 >= e2 ? RES : Math.max(16, Math.round(RES * e1 / e2)), H = e1 >= e2 ? Math.max(16, Math.round(RES * e2 / e1)) : RES;
        float pxm = Math.max(2 * e1 / W, 2 * e2 / H);
        float[] depth = depthMap(B, O, R, e1, e2, W, H);
        float tol0 = 0.006f * 2 * dm.hl;
        Obj3D root = ctx.root();
        M4 rootInv = root.matrixWorld.clone().invert();
        G G = new G();
        G.B = B; G.O = O; G.e1 = e1; G.e2 = e2; G.R = R;
        IntBuf tris = new IntBuf();
        for (Mesh m : ctx.targets(on)) {
            Geo g = m.geometry;
            Attr pos = g.attr("position"), nor = g.attr("normal"), ix = g.index;
            if (pos == null || nor == null) continue;
            M4 M = new M4().multiplyMatrices(rootInv, m.matrixWorld);
            float[] e = M.e, ne = new M3().getNormalMatrix(M).e;
            int nv = pos.count;
            MS ms = new MS();
            ms.m = m; ms.P = pos; ms.N = nor;
            ms.PX = new float[nv]; ms.PY = new float[nv]; ms.PD = new float[nv]; ms.F = new float[nv];
            for (int i = 0; i < nv; i++) {
                float x = pos.get(i, 0), y = pos.get(i, 1), z = pos.get(i, 2);
                float wx = e[0] * x + e[4] * y + e[8] * z + e[12] - O.x, wy = e[1] * x + e[5] * y + e[9] * z + e[13] - O.y, wz = e[2] * x + e[6] * y + e[10] * z + e[14] - O.z;
                ms.PX[i] = wx * B.b1.x + wy * B.b1.y + wz * B.b1.z; ms.PY[i] = wx * B.b2.x + wy * B.b2.y + wz * B.b2.z; ms.PD[i] = wx * B.n.x + wy * B.n.y + wz * B.n.z;
                float a = nor.get(i, 0), b = nor.get(i, 1), c = nor.get(i, 2);
                float qx = ne[0] * a + ne[3] * b + ne[6] * c, qy = ne[1] * a + ne[4] * b + ne[7] * c, qz = ne[2] * a + ne[5] * b + ne[8] * c, ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
                if (ql == 0) ql = 1;
                ms.F[i] = (qx * B.n.x + qy * B.n.y + qz * B.n.z) / ql;
            }
            ms.owner = ctx.owner(m);
            ms.Mo = ms.owner.matrixWorld.clone().invert().multiply(m.matrixWorld);
            ms.No = new M3().getNormalMatrix(ms.Mo);
            ms.os = ms.owner.matrixWorld.getMaxScaleOnAxis();
            ms.vmap = new int[nv]; ms.vst = new int[nv];
            int slot = G.meshes.size();
            G.meshes.add(ms);
            int total = ix != null ? ix.count : nv;
            for (int k = 0; k + 2 < total; k += 3) {
                int A = ix != null ? ix.idx(k) : k, Bv = ix != null ? ix.idx(k + 1) : k + 1, C = ix != null ? ix.idx(k + 2) : k + 2;
                float fa = (ms.F[A] + ms.F[Bv] + ms.F[C]) / 3;
                if (fa < 0.12f || Math.max(ms.F[A], Math.max(ms.F[Bv], ms.F[C])) < 0.2f) continue; // 背对投影方向（拉成长条的）不贴
                float cx = (ms.PX[A] + ms.PX[Bv] + ms.PX[C]) / 3, cy = (ms.PY[A] + ms.PY[Bv] + ms.PY[C]) / 3, me = R - (ms.PD[A] + ms.PD[Bv] + ms.PD[C]) / 3;
                float tol = tol0 + 0.6f * pxm * (float) Math.sqrt(Math.max(0, 1 - fa * fa)) / Math.max(0.12f, fa);
                if (occluded(depth, W, H, e1, e2, cx, cy, me, tol)) { // 中心被挡住：三个角往中心收一点再看（挨着轮廓的）
                    boolean seen = false;
                    for (int v : new int[] { A, Bv, C }) if (!seen && !occluded(depth, W, H, e1, e2, ms.PX[v] * 0.8f + cx * 0.2f, ms.PY[v] * 0.8f + cy * 0.2f, R - ms.PD[v], tol)) seen = true;
                    if (!seen) continue;
                }
                tris.add(slot); tris.add(A); tris.add(Bv); tris.add(C);
            }
        }
        // 按格子分桶（三角形的外框跨几个格子就放进几个格子）
        float cs = 2 * Math.max(e1, e2) / CELLS;
        int gw = (int) Math.ceil(2 * e1 / cs), gh = (int) Math.ceil(2 * e2 / cs), nt = tris.n / 4;
        int[] cnt = new int[gw * gh + 1], tr = tris.toArray();
        for (int j = 0; j < nt; j++) { int[] rg = range(G, tr, j, cs, gw, gh, e1, e2); for (int y = rg[2]; y <= rg[3]; y++) for (int x = rg[0]; x <= rg[1]; x++) cnt[y * gw + x + 1]++; }
        for (int j = 0; j < gw * gh; j++) cnt[j + 1] += cnt[j];
        int[] fill = Arrays.copyOf(cnt, gw * gh), items = new int[cnt[gw * gh]];
        for (int j = 0; j < nt; j++) { int[] rg = range(G, tr, j, cs, gw, gh, e1, e2); for (int y = rg[2]; y <= rg[3]; y++) for (int x = rg[0]; x <= rg[1]; x++) items[fill[y * gw + x]++] = j; }
        G.cs = cs; G.gw = gw; G.gh = gh; G.start = cnt; G.items = items; G.tris = tr; G.stamp = new int[nt];
        return G;
    }
    private static boolean occluded(float[] depth, int W, int H, float e1, float e2, float x, float y, float me, float tol) {
        int cx = (int) Math.floor((x + e1) / (2 * e1) * W), cy = (int) Math.floor((y + e2) / (2 * e2) * H);
        if (cx < 0 || cy < 0 || cx >= W || cy >= H) return true;
        return me - depth[cy * W + cx] > tol;
    }
    private static int[] range(G G, int[] tr, int t, float cs, int gw, int gh, float e1, float e2) {
        MS ms = G.meshes.get(tr[t * 4]);
        int a = tr[t * 4 + 1], b = tr[t * 4 + 2], c = tr[t * 4 + 3];
        int x0 = Math.max(0, (int) Math.floor((Math.min(ms.PX[a], Math.min(ms.PX[b], ms.PX[c])) + e1) / cs)), x1 = Math.min(gw - 1, (int) Math.floor((Math.max(ms.PX[a], Math.max(ms.PX[b], ms.PX[c])) + e1) / cs));
        int y0 = Math.max(0, (int) Math.floor((Math.min(ms.PY[a], Math.min(ms.PY[b], ms.PY[c])) + e2) / cs)), y1 = Math.min(gh - 1, (int) Math.floor((Math.max(ms.PY[a], Math.max(ms.PY[b], ms.PY[c])) + e2) / cs));
        return new int[] { x0, x1, y0, y1 };
    }

    // ================= 投影：切 =================
    /** 多边形裁剪（Sutherland–Hodgman）：顶点 [s, t, 重心坐标 a, b, c] */
    private static List<double[]> clip(List<double[]> poly, int k, double lo, int sg) {
        List<double[]> out = new ArrayList<>();
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            double[] p = poly.get(i), q = poly.get((i + 1) % n);
            double dp = (p[k] - lo) * sg, dq = (q[k] - lo) * sg;
            if (dp >= 0) out.add(p);
            if ((dp >= 0) != (dq >= 0)) { double f = dp / (dp - dq); out.add(new double[] { p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f, p[2] + (q[2] - p[2]) * f, p[3] + (q[3] - p[3]) * f, p[4] + (q[4] - p[4]) * f }); }
        }
        return out;
    }
    private static List<double[]> clipBox(List<double[]> poly, double s0, double s1, double t0, double t1) {
        poly = clip(poly, 0, s0, 1); if (poly.size() < 3) return poly;
        poly = clip(poly, 0, s1, -1); if (poly.size() < 3) return poly;
        poly = clip(poly, 1, t0, 1); if (poly.size() < 3) return poly;
        return clip(poly, 1, t1, -1);
    }
    private static float smooth(float a, float b, float x) { float t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }
    /** 一组（一个会动的部件上的）网格数据 */
    static final class Grp { Obj3D owner; FloatBuf p = new FloatBuf(), n = new FloatBuf(), uv = new FloatBuf(), a = new FloatBuf(); IntBuf idx = new IntBuf(); int count; }
    interface Each { void each(java.util.function.IntConsumer fn); }
    interface ST { double[] of(MS ms, int i); }
    interface Front { boolean of(double area); }
    /** 切的公共部分（同 cutInto）：三角形按贴图坐标和有内容的格子裁剪，按会动的部件分组写进 out */
    private void cutInto(Map<Obj3D, Grp> out, G G, Each each, ST st, Front front, Ent e, float off) {
        final int stp = ++G.st, gx = e.mask.gx, gy = e.mask.gy;
        final byte[] bits = e.mask.bits;
        final V3 v3 = new V3(), n3 = new V3();
        each.each(t -> {
            if (G.stamp[t] == stp) return;
            G.stamp[t] = stp;
            MS ms = G.meshes.get(G.tris[t * 4]);
            int A = G.tris[t * 4 + 1], Bv = G.tris[t * 4 + 2], C = G.tris[t * 4 + 3];
            double[] sa = st.of(ms, A), sb = st.of(ms, Bv), sc = st.of(ms, C);
            if (sa == null || sb == null || sc == null) return;
            double[] S = { sa[0], sb[0], sc[0] }, T = { sa[1], sb[1], sc[1] };
            if ((S[0] < 0 && S[1] < 0 && S[2] < 0) || (S[0] > 1 && S[1] > 1 && S[2] > 1) || (T[0] < 0 && T[1] < 0 && T[2] < 0) || (T[0] > 1 && T[1] > 1 && T[2] > 1)) return;
            double area = (S[1] - S[0]) * (T[2] - T[0]) - (S[2] - S[0]) * (T[1] - T[0]);
            boolean rev = !front.of(area);
            List<double[]> poly = new ArrayList<>(Arrays.asList(new double[] { S[0], T[0], 1, 0, 0 }, new double[] { S[1], T[1], 0, 1, 0 }, new double[] { S[2], T[2], 0, 0, 1 }));
            double mnS = Math.min(S[0], Math.min(S[1], S[2])), mxS = Math.max(S[0], Math.max(S[1], S[2])), mnT = Math.min(T[0], Math.min(T[1], T[2])), mxT = Math.max(T[0], Math.max(T[1], T[2]));
            double sMin = Math.max(0, mnS), sMax = Math.min(1, mxS), tMin = Math.max(0, mnT), tMax = Math.min(1, mxT);
            int gx0 = Math.min(gx - 1, (int) Math.floor(sMin * gx)), gx1 = Math.min(gx - 1, (int) Math.floor(sMax * gx)), gy0 = Math.min(gy - 1, (int) Math.floor(tMin * gy)), gy1 = Math.min(gy - 1, (int) Math.floor(tMax * gy));
            boolean all = true, any = false;
            for (int b1 = gy0; b1 <= gy1; b1++) for (int a1 = gx0; a1 <= gx1; a1++) { if (bits[b1 * gx + a1] != 0) any = true; else all = false; }
            if (!any) return;
            Grp o = out.get(ms.owner);
            if (o == null) { o = new Grp(); o.owner = ms.owner; out.put(ms.owner, o); }
            boolean inside = sMin == mnS && sMax == mxS && tMin == mnT && tMax == mxT;
            if (all) {
                if (inside) emit(o, ms, A, Bv, C, poly, rev, true, stp, v3, n3, off); // 整个在框里、格子都有内容：不用裁，顶点和邻居共用
                else { List<double[]> pc = clipBox(poly, 0, 1, 0, 1); if (pc.size() >= 3) emit(o, ms, A, Bv, C, pc, rev, false, stp, v3, n3, off); }
                return;
            }
            for (int b1 = gy0; b1 <= gy1; b1++) for (int a1 = gx0; a1 <= gx1; a1++) { // 只留有内容的格子
                if (bits[b1 * gx + a1] == 0) continue;
                List<double[]> p2 = clipBox(poly, a1 / (double) gx, (a1 + 1) / (double) gx, b1 / (double) gy, (b1 + 1) / (double) gy);
                if (p2.size() >= 3) emit(o, ms, A, Bv, C, p2, rev, false, stp, v3, n3, off);
            }
        });
    }
    /** 一个顶点（重心坐标插值）写进这一组，返回编号；没被裁过的原顶点共用 */
    private static int vert(Grp o, MS ms, int A, int Bv, int C, double[] q, int orig, int stp, V3 v3, V3 n3, float off) {
        if (orig >= 0 && ms.vst[orig] == stp && ms.vgrp == o) return ms.vmap[orig];
        float a = (float) q[2], b = (float) q[3], c = (float) q[4];
        Attr P = ms.P, N = ms.N;
        v3.set(P.get(A, 0) * a + P.get(Bv, 0) * b + P.get(C, 0) * c, P.get(A, 1) * a + P.get(Bv, 1) * b + P.get(C, 1) * c, P.get(A, 2) * a + P.get(Bv, 2) * b + P.get(C, 2) * c).applyMatrix4(ms.Mo);
        n3.set(N.get(A, 0) * a + N.get(Bv, 0) * b + N.get(C, 0) * c, N.get(A, 1) * a + N.get(Bv, 1) * b + N.get(C, 1) * c, N.get(A, 2) * a + N.get(Bv, 2) * b + N.get(C, 2) * c).applyMatrix3(ms.No).normalize();
        v3.addScaled(n3, off / ms.os);
        o.p.add(v3.x); o.p.add(v3.y); o.p.add(v3.z); o.n.add(n3.x); o.n.add(n3.y); o.n.add(n3.z); o.uv.add((float) q[0]); o.uv.add((float) q[1]);
        o.a.add(smooth(0.12f, 0.4f, ms.F[A] * a + ms.F[Bv] * b + ms.F[C] * c));
        int id = o.count++;
        if (orig >= 0) { ms.vst[orig] = stp; ms.vmap[orig] = id; ms.vgrp = o; }
        return id;
    }
    private static void emit(Grp o, MS ms, int A, int Bv, int C, List<double[]> poly, boolean rev, boolean plain, int stp, V3 v3, V3 n3, float off) {
        int[] origs = { A, Bv, C }, ids = new int[poly.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = vert(o, ms, A, Bv, C, poly.get(i), plain ? origs[i] : -1, stp, v3, n3, off);
        for (int i = 1; i + 1 < ids.length; i++) { if (rev) { o.idx.add(ids[0]); o.idx.add(ids[i + 1]); o.idx.add(ids[i]); } else { o.idx.add(ids[0]); o.idx.add(ids[i]); o.idx.add(ids[i + 1]); } }
    }
    /** 平贴（同 cutPlanar）：一个框只查框盖住的格子 */
    private void cutPlanar(Map<Obj3D, Grp> out, G G, final float[] fr, Ent e, float off) {
        // fr：c1 c2 ux uy vx vy hw hh
        final float hand = fr[2] * fr[5] - fr[3] * fr[4];
        float ax = Math.abs(fr[2] * fr[6]) + Math.abs(fr[4] * fr[7]), ay = Math.abs(fr[3] * fr[6]) + Math.abs(fr[5] * fr[7]);
        final int x0 = Math.max(0, (int) Math.floor((fr[0] - ax + G.e1) / G.cs)), x1 = Math.min(G.gw - 1, (int) Math.floor((fr[0] + ax + G.e1) / G.cs));
        final int y0 = Math.max(0, (int) Math.floor((fr[1] - ay + G.e2) / G.cs)), y1 = Math.min(G.gh - 1, (int) Math.floor((fr[1] + ay + G.e2) / G.cs));
        cutInto(out, G, fn -> {
            for (int cy = y0; cy <= y1; cy++) for (int cx = x0; cx <= x1; cx++) { int cell = cy * G.gw + cx; for (int k = G.start[cell]; k < G.start[cell + 1]; k++) fn.accept(G.items[k]); }
        }, (ms, i) -> {
            double dx = ms.PX[i] - fr[0], dy = ms.PY[i] - fr[1];
            return new double[] { ((dx * fr[2] + dy * fr[3]) / fr[6] + 1) / 2, ((dx * fr[4] + dy * fr[5]) / fr[7] + 1) / 2 };
        }, area -> area * hand >= 0, e, off);
    }

    // ================= 包住：横截面当成圆角矩形展开 =================
    static final class Ring { int N; float[] L; float per, a, b; }
    private static final Map<String, Ring> RINGS = new HashMap<>();
    /** 超椭圆 |x/a|^4 + |y/b|^4 = 1 按角度（-π～π）累加的周长表 */
    private static synchronized Ring ring(float a, float b) {
        String key = String.format(java.util.Locale.US, "%.3f,%.3f", a, b);
        if (RINGS.containsKey(key)) return RINGS.get(key);
        int N = 1440;
        float[] L = new float[N + 1];
        double px = 0, py = 0;
        for (int i = 0; i <= N; i++) {
            double f = -Math.PI + 2 * Math.PI * i / N, c = Math.cos(f), s = Math.sin(f), r = 1 / Math.pow(Math.pow(Math.abs(c / a), 4) + Math.pow(Math.abs(s / b), 4), 0.25), x = r * c, y = r * s;
            if (i > 0) L[i] = L[i - 1] + (float) Math.sqrt((x - px) * (x - px) + (y - py) * (y - py));
            px = x; py = y;
        }
        Ring R = new Ring();
        R.N = N; R.L = L; R.per = L[N]; R.a = a; R.b = b;
        RINGS.put(key, R);
        return R;
    }
    private static float arcAt(Ring R, double f) { double u = (f + Math.PI) / (2 * Math.PI) * R.N; int i = (int) Math.max(0, Math.min(R.N - 1, Math.floor(u))); return (float) (R.L[i] + (R.L[i + 1] - R.L[i]) * (u - i)); }
    private static double[] boundary(Ring R, double f) { double c = Math.cos(f), s = Math.sin(f), r = 1 / Math.pow(Math.pow(Math.abs(c / R.a), 4) + Math.pow(Math.abs(s / R.b), 4), 0.25); return new double[] { r * c, r * s }; }
    /** 包住的一个框（同 cutWrap）：截面上贴纸覆盖的角度范围按 8 个方向收集，每个三角形归到法线所在的那一格；顶点按展开后的坐标算 s、t */
    private void cutWrap(Map<Obj3D, Grp> out, Frame f, Ent e, float off, String on) {
        WrapArt.Dims d = ctx.dims();
        final V3 e1, e2, ax;
        final Ring Rg;
        if ("v".equals(f.wm)) { e1 = new V3(1, 0, 0); e2 = new V3(0, 1, 0); ax = new V3(0, 0, 1); Rg = ring(d.hw, d.h / 2); }
        else { e1 = new V3(0, 0, 1); e2 = new V3(1, 0, 0); ax = new V3(0, 1, 0); Rg = ring(d.hl, d.hw); }
        final V3 O = new V3(0, d.h / 2, 0), rel = f.c.clone().sub(O);
        double fc = Math.atan2(rel.dot(e2), rel.dot(e1));
        final float ac = arcAt(Rg, fc), along0 = rel.dot(ax);
        double[] b0 = boundary(Rg, fc - 0.002), b1 = boundary(Rg, fc + 0.002);
        V3 tg = e1.clone().multiplyScalar((float) (b1[0] - b0[0])).addScaled(e2, (float) (b1[1] - b0[1])).normalize(); // 截面在中心处的切线（周长增加的方向）
        final float uA = f.u.dot(ax), uT = f.u.dot(tg), vA = f.v.dot(ax), vT = f.v.dot(tg), hand = uA * vT - uT * vA;
        float ext = Math.abs(uT) * f.hw + Math.abs(vT) * f.hh;
        final float per = Rg.per;
        for (int k = 0; k < 8; k++) {
            double th = -Math.PI + k * Math.PI / 4;
            if (Math.abs(wrapD(arcAt(Rg, th) - ac, per)) > ext + per / 8) continue; // 这一格离贴纸的范围超过一格：用不到
            final V3 dk = e1.clone().multiplyScalar((float) Math.cos(th)).addScaled(e2, (float) Math.sin(th));
            final G G = gather(dk, on);
            final Basis B = G.B;
            final V3 OG = G.O, P = new V3(), A3 = new V3(), B3 = new V3(), C3 = new V3();
            final int kk = k;
            final float hwF = f.hw, hhF = f.hh;
            cutInto(out, G, fn -> {
                int nt = G.tris.length / 4;
                for (int t = 0; t < nt; t++) {
                    MS ms = G.meshes.get(G.tris[t * 4]);
                    wp(A3, OG, B, ms, G.tris[t * 4 + 1]); wp(B3, OG, B, ms, G.tris[t * 4 + 2]); wp(C3, OG, B, ms, G.tris[t * 4 + 3]);
                    float nx = (B3.y - A3.y) * (C3.z - A3.z) - (B3.z - A3.z) * (C3.y - A3.y), ny = (B3.z - A3.z) * (C3.x - A3.x) - (B3.x - A3.x) * (C3.z - A3.z), nz = (B3.x - A3.x) * (C3.y - A3.y) - (B3.y - A3.y) * (C3.x - A3.x);
                    float c1 = nx * e1.x + ny * e1.y + nz * e1.z, c2 = nx * e2.x + ny * e2.y + nz * e2.z;
                    if (c1 * dk.dot(e1) + c2 * dk.dot(e2) < 0) { c1 = -c1; c2 = -c2; } // 绕向反了的面
                    float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (nl == 0) nl = 1;
                    if (Math.sqrt(c1 * c1 + c2 * c2) < 0.3f * nl) continue; // 法线差不多顺着轴
                    int sec = (int) (Math.round((Math.atan2(c2, c1) + Math.PI) / (Math.PI / 4)) % 8);
                    if (sec == kk) fn.accept(t);
                }
            }, (ms, i) -> {
                V3 q = wp(P, OG, B, ms, i).sub(O);
                float al = q.dot(ax) - along0, ar = wrapD(arcAt(Rg, Math.atan2(q.dot(e2), q.dot(e1))) - ac, per);
                return new double[] { ((al * uA + ar * uT) / hwF + 1) / 2, ((al * vA + ar * vT) / hhF + 1) / 2 };
            }, area -> area * hand <= 0, e, off); // 展开的坐标里，朝外的面是顺时针
        }
    }
    private static float wrapD(float x, float per) { x = (x % per + per) % per; return x > per / 2 ? x - per : x; }
    private static V3 wp(V3 t, V3 O, Basis B, MS ms, int i) { return t.copy(O).addScaled(B.b1, ms.PX[i]).addScaled(B.b2, ms.PY[i]).addScaled(B.n, ms.PD[i]); }
    private static Geo geomOf(Grp o) {
        int nv = o.count;
        float[] col = new float[nv * 4];
        for (int i = 0; i < nv; i++) { col[i * 4] = col[i * 4 + 1] = col[i * 4 + 2] = 1; col[i * 4 + 3] = Math.round(o.a.get(i) * 255) / 255f; }
        float[] nn = o.n.toArray(), uv = o.uv.toArray();
        for (int i = 0; i < nn.length; i++) nn[i] = Math.round(Math.max(-1, Math.min(1, nn[i])) * 127) / 127f; // 同网页版存成 8 位
        for (int i = 0; i < uv.length; i++) uv[i] = Math.round(Math.max(0, Math.min(1, uv[i])) * 65535) / 65535f;
        Geo g = new Geo();
        g.set("position", Attr.floats(o.p.toArray(), 3));
        g.set("normal", Attr.floats(nn, 3));
        g.set("uv", Attr.floats(uv, 2));
        g.set("color", Attr.floats(col, 4));
        g.setIndex(Attr.index(o.idx.toArray(), nv));
        g.computeBounds();
        return g;
    }

    // ================= 一张贴纸的投影框 =================
    static final class Frame { V3 n, u, v, c; float hw, hh; String wm = ""; }
    private static V3 vec(JSONArray a) { return new V3((float) a.optDouble(0), (float) a.optDouble(1), (float) a.optDouble(2)); }
    /** 自由摆放的贴纸：图片朝上的方向（没给就按朝向猜：侧面、车头车尾朝上；车顶上朝车尾） */
    private static V3 upOf(V3 n, JSONArray up) {
        V3 v = up != null ? vec(up) : n.y > 0.85f ? new V3(0, 0, -1) : n.y < -0.85f ? new V3(0, 0, 1) : new V3(0, 1, 0);
        v.addScaled(n, -v.dot(n));
        if (v.lengthSq() < 1e-6f) { boolean side = Math.abs(n.y) < 0.9f; v.copy(side ? new V3(0, 1, 0) : new V3(0, 0, 1)).addScaled(n, -(side ? n.y : n.z)); }
        return v.normalize();
    }
    /** 这张贴纸投到车上的几个框（同 frames） */
    private static List<Frame> frames(JSONObject w, WrapArt.Dims d, float aspect) {
        List<Frame> out = new ArrayList<>();
        if ("stripe".equals(w.optString("kind"))) {
            if (WrapArt.topOf(w)) { out.add(fr(new V3(0, 1, 0), new V3(-1, 0, 0), new V3(0, 0, 1), new V3(0, d.h, 0), d.hw, d.hl)); return out; }
            String sd = w.optString("side", "both");
            if (!"R".equals(sd)) out.add(fr(new V3(1, 0, 0), new V3(0, 0, -1), new V3(0, 1, 0), new V3(d.hw, d.h / 2, 0), d.hl, d.h / 2));
            if (!"L".equals(sd)) out.add(fr(new V3(-1, 0, 0), new V3(0, 0, -1), new V3(0, 1, 0), new V3(-d.hw, d.h / 2, 0), d.hl, d.h / 2)); // 右侧：左侧照镜子
            return out;
        }
        V3 n = vec(w.optJSONArray("dir")).normalize(), v = upOf(n, w.optJSONArray("up"));
        float r = (float) w.optDouble("rot", 0) * WrapArt.DEG;
        v = v.clone().multiplyScalar((float) Math.cos(r)).add(new V3().crossVectors(n, v).multiplyScalar((float) Math.sin(r))); // 绕 n 转
        V3 u = new V3().crossVectors(v, n);
        if (w.optBoolean("flip")) u.negate();
        if (w.optBoolean("fv")) v.negate();
        float L2 = (float) w.optDouble("w", 0.6) / 2, sx = (float) w.optDouble("sx", 1), hw = (aspect > 1 ? L2 / aspect : L2) * sx, hh = aspect > 1 ? L2 : L2 * aspect;
        V3 c = vec(w.optJSONArray("at"));
        Frame a = fr(n, u, v, c, hw, hh);
        a.wm = w.optString("wm", "");
        out.add(a);
        String both = w.optString("both", "");
        if (!both.isEmpty()) { // 另一侧也贴一张：图片照镜子（mirror），文字不照（same）
            V3 n2 = mir(n), v2 = mir(v), u2 = "mirror".equals(both) ? mir(u) : new V3().crossVectors(v2, n2).multiplyScalar(w.optBoolean("flip") ? -1 : 1);
            Frame b = fr(n2, u2, v2, mir(c), hw, hh);
            b.wm = a.wm;
            out.add(b);
        }
        return out;
    }
    private static V3 mir(V3 a) { return new V3(-a.x, a.y, a.z); }
    private static Frame fr(V3 n, V3 u, V3 v, V3 c, float hw, float hh) { Frame f = new Frame(); f.n = n; f.u = u; f.v = v; f.c = c; f.hw = hw; f.hh = hh; return f; }
    /** 世界方向的框 → 收集结果的平面坐标 [c1, c2, ux, uy, vx, vy, hw, hh] */
    private static float[] planar(G G, Frame f) {
        Basis B = G.B;
        V3 rel = f.c.clone().sub(G.O);
        return new float[] { rel.dot(B.b1), rel.dot(B.b2), f.u.dot(B.b1), f.u.dot(B.b2), f.v.dot(B.b1), f.v.dot(B.b2), f.hw, f.hh };
    }

    // ================= 质感、材质 =================
    private static final Map<String, float[]> FIN = new HashMap<>();
    static { FIN.put("gloss", new float[] { 0.3f, 0 }); FIN.put("satin", new float[] { 0.5f, 0 }); FIN.put("matte", new float[] { 0.85f, 0 }); FIN.put("metal", new float[] { 0.28f, 0.85f }); FIN.put("holo", new float[] { 0.22f, 0.35f }); }
    private static final String MAP_FS = "#ifdef USE_MAP\n\tvec4 texelColor = texture2D( map, vUv );\n\tvec3 wc = texelColor.rgb / max( texelColor.a, 1e-3 );\n" +
        "\twc = mix( wc * 0.0773993808, pow( wc * 0.9478672986 + vec3( 0.0521327014 ), vec3( 2.4 ) ), step( vec3( 0.04045 ), wc ) );\n" +
        "\tdiffuseColor *= vec4( wc, texelColor.a );\n#endif";
    /** 贴纸的材质（同 wrapMat）：贴图按预乘透明度取样后除回去、转线性，顶点透明度淡出边缘；质感按预设（镭射用薄膜干涉） */
    private Mat wrapMat(Ent e, JSONObject w) {
        float[] fin = FIN.containsKey(w.optString("fin")) ? FIN.get(w.optString("fin")) : FIN.get("gloss");
        boolean holo = "holo".equals(w.optString("fin"));
        Mat m = holo ? Mat.physical() : Mat.standard();
        m.map = e.tex; m.transparent = true; m.depthWrite = false; m.vertexColors = true;
        m.roughness = w.has("rough") ? (float) w.optDouble("rough") : fin[0];
        m.metalness = w.has("metal") ? (float) w.optDouble("metal") : fin[1];
        m.polygonOffset = true; m.polygonOffsetFactor = -2; m.polygonOffsetUnits = -2;
        m.opacity = (float) w.optDouble("alpha", 1);
        m.envMapIntensity = ctx.envK();
        if (holo) { m.iridescence = 1; m.iridescenceIOR = 1.8f; m.iridescenceThickMin = 250; m.iridescenceThickMax = 900; }
        m.onBeforeCompile = (vs, fs, u) -> new String[] { vs, fs.replace("#include <map_fragment>", MAP_FS) };
        m.cacheKey = "d3wrap";
        return m;
    }

    // ================= 一台车的贴纸 =================
    /** 按顺序贴上（顺序 = 叠放顺序，后面的在上面）；藏起来的不贴 */
    void apply(JSONArray list) {
        WrapArt.Dims d = ctx.dims();
        float off = Math.min(0.002f, Math.max(0.0008f, 2 * d.hl * 0.0003f));
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject w = list.optJSONObject(i);
            if (w == null || w.optBoolean("hide")) continue;
            try {
                Ent e = texGet(w);
                if (e == null) continue;
                String on = w.optString("on", "body");
                Map<Obj3D, Grp> parts = new LinkedHashMap<>();
                for (Frame f : frames(w, d, e.aspect)) {
                    if (!f.wm.isEmpty()) cutWrap(parts, f, e, off, on);
                    else { G G = gather(f.n, on); cutPlanar(parts, G, planar(G, f), e, off); }
                }
                Mat mat = wrapMat(e, w);
                for (Grp p : parts.values()) {
                    if (p.idx.n == 0) continue;
                    Mesh mesh = new Mesh(geomOf(p), mat);
                    mesh.renderOrder = 3 + i;
                    mesh.receiveShadow = ctx.shadow();
                    mesh.put("d3wrap", w.optString("id"));
                    p.owner.add(mesh);
                    meshes++;
                }
            } catch (Exception er) { Log.w("car3d", "贴纸没贴上", er); }
        }
        gc.clear(); // 收集的缓存不要了（大车模几十 MB）
    }

    // ---------------- 能变长的数组 ----------------
    static final class FloatBuf {
        float[] a = new float[256]; int n;
        void add(float v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        float get(int i) { return a[i]; }
        float[] toArray() { return Arrays.copyOf(a, n); }
    }
    static final class IntBuf {
        int[] a = new int[256]; int n;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        int[] toArray() { return Arrays.copyOf(a, n); }
    }
}
