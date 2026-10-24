package com.x3card.car3d.gl;

import android.opengl.GLES30;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 读 GLB，照 three.js r149 的 GLTFLoader 建场景（同步版）：网格只有一个图元时就是 Mesh，几个图元时是 Group 套着几个 Mesh；
    一个网格被几个节点用时每处各克隆一份；材质按"有没有切线 / 顶点色 / 法线"克隆（没切线时法线贴图 y 反过来）、缓存；
    名字按 PropertyBinding.sanitizeNodeName 整理、重名加 _1 _2；几何体的外框取 POSITION 的 min / max。
    这些规则决定了网格、材质的编号（车模配置按编号引用），要和网页版一模一样。
    支持的扩展：KHR_mesh_quantization、KHR_texture_transform、KHR_materials_clearcoat / ior / specular / unlit / emissive_strength / sheen / iridescence /
    transmission / volume、EXT_texture_webp、EXT_meshopt_compression（Meshopt.java）。贴图先留着原来的图片文件，第一次画的时候才解码上传 */
public final class Gltf {
    public final JSONObject json;
    public final Obj3D scene;
    private final ByteBuffer bin;
    private final ByteBuffer[] views;
    private final Map<Integer, Attr> accCache = new HashMap<>();
    private final Map<String, Attr.Buf> ibCache = new HashMap<>();
    private final Map<String, Geo> primCache = new HashMap<>();
    private final Map<Integer, Mat> matCache = new HashMap<>();
    private final Map<String, Mat> finalCache = new HashMap<>();
    private final Map<String, Tex> texCache = new HashMap<>();
    private final Map<Integer, Tex> srcCache = new HashMap<>();
    private final Map<Integer, Obj3D> meshCache = new HashMap<>();
    private final Map<Integer, Obj3D> nodeCache = new HashMap<>();
    private final Set<String> names = new HashSet<>();
    private final Map<Integer, int[]> meshRefs = new HashMap<>();
    private Mat defaultMat;
    private final Set<String> exts = new HashSet<>();
    private final Set<Integer> boneNodes = new HashSet<>(), skinnedMeshes = new HashSet<>();

    public static final class Glb { public JSONObject json; public ByteBuffer bin; }

    /** 拆开 GLB：JSON 和二进制块 */
    public static Glb read(ByteBuffer b) throws Exception {
        b = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 20 || b.getInt(0) != 0x46546C67) throw new IllegalArgumentException("不是 GLB 文件");
        int len = Math.min(b.getInt(8), b.capacity()), o = 12;
        Glb g = new Glb();
        while (o + 8 <= len) {
            int cl = b.getInt(o), ct = b.getInt(o + 4);
            if (ct == 0x4E4F534A) {
                byte[] s = new byte[cl];
                ByteBuffer d = b.duplicate(); d.position(o + 8); d.get(s);
                g.json = new JSONObject(new String(s, StandardCharsets.UTF_8));
            } else if (ct == 0x004E4942) {
                ByteBuffer d = b.duplicate(); d.position(o + 8); d.limit(o + 8 + cl);
                g.bin = d.slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            o += 8 + cl;
        }
        if (g.json == null) throw new IllegalArgumentException("GLB 里没有 JSON");
        return g;
    }

    /** 建场景的进度（0～1，按建好的网格数），导入车模时给进度条用 */
    public interface Progress { void at(float f); }
    private Progress prog;
    private int built, meshTotal = 1;
    private float decShare; // 进度里解压几何占的份额（有压缩过的几何时前 60%）

    /** 解好的几何（应用存下来的缓存）：bufferView 编号 → 解好的数据；在原来的索引上理顺过绕向的索引访问器编号 */
    public static final class Pre { public final Map<Integer, ByteBuffer> views = new HashMap<>(); public final Set<Integer> wound = new HashSet<>(); }
    private Pre pre;
    public Gltf(ByteBuffer glb) throws Exception { this(glb, null, null); }
    public Gltf(ByteBuffer glb, Progress pr) throws Exception { this(glb, pr, null); }
    public Gltf(ByteBuffer glb, Progress pr, Pre pre) throws Exception {
        prog = pr;
        this.pre = pre;
        Glb g = read(glb);
        json = g.json;
        bin = g.bin;
        JSONArray eu = json.optJSONArray("extensionsUsed");
        if (eu != null) for (int i = 0; i < eu.length(); i++) exts.add(eu.getString(i));
        JSONArray bv = json.optJSONArray("bufferViews");
        views = new ByteBuffer[bv == null ? 0 : bv.length()];
        JSONArray ms = json.optJSONArray("meshes");
        meshTotal = Math.max(1, ms == null ? 0 : ms.length());
        predecode(bv);
        markDefs();
        JSONArray scenes = json.optJSONArray("scenes");
        int si = json.optInt("scene", 0);
        Obj3D root = new Obj3D();
        root.put("isGroup", true);
        if (scenes != null && si < scenes.length()) {
            JSONObject sd = scenes.getJSONObject(si);
            if (!sd.optString("name").isEmpty()) root.name = uniqueName(sd.getString("name"));
            extras(root, sd);
            JSONArray nodes = sd.optJSONArray("nodes");
            List<Obj3D> top = new ArrayList<>();
            // 先按节点顺序深度优先把节点名字都占好（JS 里节点名字在同步阶段就占了，网格的名字在之后的异步回调里才取）
            if (nodes != null) for (int i = 0; i < nodes.length(); i++) reserveNames(nodes.getInt(i));
            if (nodes != null) for (int i = 0; i < nodes.length(); i++) top.add(node(nodes.getInt(i)));
            for (Obj3D o : top) root.add(o);
        }
        scene = root;
        prog = null;
    }

    // ---------------- 名字 ----------------
    private static String sanitize(String n) { return n.replaceAll("\\s", "_").replaceAll("[\\[\\]\\.:/]", ""); }
    private String uniqueName(String orig) {
        String s = sanitize(orig == null ? "" : orig), name = s;
        for (int i = 1; names.contains(name); i++) name = s + "_" + i;
        names.add(name);
        return name;
    }
    private final Map<Integer, String> nodeNames = new HashMap<>();
    private final Set<Integer> camNames = new HashSet<>(), lightNames = new HashSet<>();
    private void reserveNames(int ni) {
        if (nodeNames.containsKey(ni)) return;
        JSONObject nd = json.optJSONArray("nodes").optJSONObject(ni);
        nodeNames.put(ni, !nd.optString("name").isEmpty() ? uniqueName(nd.optString("name")) : "");
        // 相机、灯在同步阶段建好、取名字（同 loadCamera / loadLight），各只建一次
        if (nd.has("camera") && camNames.add(nd.optInt("camera"))) {
            JSONObject cd = json.optJSONArray("cameras") != null ? json.optJSONArray("cameras").optJSONObject(nd.optInt("camera")) : null;
            String n = cd != null ? cd.optString("name") : "";
            uniqueName(n.isEmpty() ? "camera_" + nd.optInt("camera") : n);
        }
        JSONObject ne = nd.optJSONObject("extensions"), lp = ne == null ? null : ne.optJSONObject("KHR_lights_punctual");
        if (lp != null && lightNames.add(lp.optInt("light"))) {
            JSONObject le = json.optJSONObject("extensions"), ll = le == null ? null : le.optJSONObject("KHR_lights_punctual");
            JSONArray la = ll == null ? null : ll.optJSONArray("lights");
            String n = la != null && la.optJSONObject(lp.optInt("light")) != null ? la.optJSONObject(lp.optInt("light")).optString("name") : "";
            uniqueName(n.isEmpty() ? "light_" + lp.optInt("light") : n);
        }
        JSONArray ch = nd.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) reserveNames(ch.optInt(i));
    }

    private void markDefs() {
        JSONArray nodes = json.optJSONArray("nodes"), skins = json.optJSONArray("skins");
        if (skins != null) for (int s = 0; s < skins.length(); s++) {
            JSONArray j = skins.optJSONObject(s).optJSONArray("joints");
            if (j != null) for (int i = 0; i < j.length(); i++) boneNodes.add(j.optInt(i));
        }
        if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n.has("mesh")) {
                int m = n.optInt("mesh");
                int[] r = meshRefs.get(m);
                if (r == null) meshRefs.put(m, r = new int[2]);
                r[0]++;
                if (n.has("skin")) skinnedMeshes.add(m);
            }
        }
    }

    // ---------------- 节点 ----------------
    private Obj3D node(int ni) throws Exception {
        Obj3D cached = nodeCache.get(ni);
        if (cached != null) return cached;
        JSONObject nd = json.getJSONArray("nodes").getJSONObject(ni);
        String nodeName = nodeNames.containsKey(ni) ? nodeNames.get(ni) : (!nd.optString("name").isEmpty() ? uniqueName(nd.optString("name")) : "");
        List<Obj3D> objects = new ArrayList<>();
        if (nd.has("mesh")) {
            int mi = nd.getInt("mesh");
            Obj3D m = mesh(mi);
            int[] r = meshRefs.get(mi);
            if (r != null && r[0] > 1) { m = cloneObj(m); m.name += "_instance_" + (r[1]++); }
            objects.add(m);
        }
        if (nd.has("camera")) { Obj3D c = new Obj3D(); c.put("isCamera", true); objects.add(c); }
        JSONObject ex = nd.optJSONObject("extensions");
        if (ex != null && ex.has("KHR_lights_punctual")) { Obj3D l = new Obj3D(); l.put("isLight", true); objects.add(l); }
        List<Obj3D> children = new ArrayList<>();
        JSONArray ch = nd.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) children.add(node(ch.getInt(i)));
        Obj3D node;
        if (boneNodes.contains(ni)) node = new Obj3D();
        else if (objects.size() > 1) { node = new Obj3D(); node.put("isGroup", true); }
        else if (objects.size() == 1) node = objects.get(0);
        else node = new Obj3D();
        if (node != (objects.isEmpty() ? null : objects.get(0))) for (Obj3D o : objects) node.add(o);
        if (!nd.optString("name").isEmpty()) { node.put("name", nd.optString("name")); node.name = nodeName; }
        extras(node, nd);
        JSONArray mx = nd.optJSONArray("matrix");
        if (mx != null) {
            M4 m = new M4();
            for (int i = 0; i < 16; i++) m.e[i] = (float) mx.getDouble(i);
            node.applyMatrix4(m);
        } else {
            JSONArray t = nd.optJSONArray("translation"), r = nd.optJSONArray("rotation"), s = nd.optJSONArray("scale");
            if (t != null) node.position.set(t.getDouble(0), t.getDouble(1), t.getDouble(2));
            if (r != null) node.quaternion.set((float) r.getDouble(0), (float) r.getDouble(1), (float) r.getDouble(2), (float) r.getDouble(3));
            if (s != null) node.scale.set(s.getDouble(0), s.getDouble(1), s.getDouble(2));
        }
        node.put("node", ni);
        for (Obj3D c : children) node.add(c);
        nodeCache.put(ni, node);
        return node;
    }
    private static void extras(Obj3D o, JSONObject def) {
        JSONObject x = def.optJSONObject("extras");
        if (x == null) return;
        for (Iterator<String> it = x.keys(); it.hasNext(); ) { String k = it.next(); o.ud.put(k, x.opt(k)); }
    }
    /** 复制一个节点（同 Object3D.clone(true)：几何体、材质共用） */
    public static Obj3D cloneObj(Obj3D o) {
        Obj3D c;
        if (o instanceof Mesh) {
            Mesh m = (Mesh) o, n = new Mesh(m.geometry, m.material);
            n.skinned = m.skinned;
            c = n;
        } else c = new Obj3D();
        c.name = o.name;
        c.position.copy(o.position); c.quaternion.copy(o.quaternion); c.scale.copy(o.scale);
        c.matrix.copy(o.matrix); c.matrixWorld.copy(o.matrixWorld);
        c.layers = o.layers; c.visible = o.visible; c.castShadow = o.castShadow; c.receiveShadow = o.receiveShadow;
        c.frustumCulled = o.frustumCulled; c.renderOrder = o.renderOrder;
        c.ud.putAll(o.ud);
        for (Obj3D ch : o.children) c.add(cloneObj(ch));
        return c;
    }

    // ---------------- 网格 ----------------
    private Obj3D mesh(int mi) throws Exception {
        Obj3D cached = meshCache.get(mi);
        if (cached != null) return cached;
        JSONObject md = json.getJSONArray("meshes").getJSONObject(mi);
        JSONArray prims = md.getJSONArray("primitives");
        List<Obj3D> meshes = new ArrayList<>();
        for (int i = 0; i < prims.length(); i++) {
            JSONObject p = prims.getJSONObject(i);
            Mat mat = p.has("material") ? material(p.getInt("material")) : defMat();
            Geo g = geometry(p);
            int mode = p.optInt("mode", 4);
            Obj3D o;
            if (mode == 4 || mode == 5 || mode == 6) {
                if (mode != 4) g = toTriangles(g, mode);
                Mesh m = new Mesh(g, mat);
                m.skinned = skinnedMeshes.contains(mi);
                assignFinal(m);
                o = m;
            } else { o = new Obj3D(); o.put("isLine", true); } // 线、点：不画，也不算网格（编号只数网格）
            o.name = uniqueName(!md.optString("name").isEmpty() ? md.optString("name") : "mesh_" + mi);
            extras(o, md);
            o.put("gl", new int[] { mi, i });
            meshes.add(o);
        }
        Obj3D out;
        if (meshes.size() == 1) out = meshes.get(0);
        else { out = new Obj3D(); out.put("isGroup", true); for (Obj3D m : meshes) out.add(m); }
        meshCache.put(mi, out);
        if (prog != null) prog.at(decShare + (1 - decShare) * ++built / (float) meshTotal);
        return out;
    }
    private Mat defMat() {
        if (defaultMat == null) { defaultMat = Mat.standard(); defaultMat.metalness = 1; defaultMat.roughness = 1; }
        return defaultMat;
    }
    /** 同 assignFinalMaterial：按几何体克隆材质（缓存），有 AO 贴图、没有第二套贴图坐标时借第一套 */
    private void assignFinal(Mesh m) {
        Geo g = m.geometry;
        Mat mat = m.material;
        boolean tan = g.attr("tangent") == null, vc = g.attr("color") != null, flat = g.attr("normal") == null;
        if (tan || vc || flat) {
            String key = "Cloned:" + mat.id + ":" + (tan ? "derivative-tangents:" : "") + (vc ? "vertex-colors:" : "") + (flat ? "flat-shading:" : "");
            Mat c = finalCache.get(key);
            if (c == null) {
                c = mat.clone();
                c.ud.putAll(mat.ud);
                if (vc) c.vertexColors = true;
                if (flat) c.flatShading = true;
                if (tan && c.type != Mat.BASIC) { c.normalScaleY *= -1; c.clearcoatNormalScaleY *= -1; }
                finalCache.put(key, c);
            }
            mat = c;
        }
        if (mat.aoMap != null && g.attr("uv2") == null && g.attr("uv") != null) g.set("uv2", g.attr("uv"));
        m.material = mat;
    }
    private Geo geometry(JSONObject p) throws Exception {
        JSONObject at = p.getJSONObject("attributes");
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = at.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        StringBuilder k = new StringBuilder();
        k.append(p.has("indices") ? String.valueOf(p.getInt("indices")) : "undefined").append(':');
        for (String a : keys) k.append(a).append(':').append(at.getInt(a)).append(';');
        k.append(':').append(p.has("mode") ? String.valueOf(p.getInt("mode")) : "undefined");
        Geo cached = primCache.get(k.toString());
        if (cached != null) return cached;
        Geo g = new Geo();
        for (Iterator<String> it = at.keys(); it.hasNext(); ) {
            String n = it.next();
            String tn = attrName(n);
            if (g.attr(tn) != null) continue;
            g.attributes.put(tn, accessor(at.getInt(n)));
        }
        if (p.has("indices")) {
            int ia = p.getInt("indices");
            g.index = accessor(ia);
            g.ud.put("ixAcc", ia);
            if (pre != null && pre.wound.contains(ia)) g.ud.put("wound", 1); // 缓存里的索引已经理顺过了
        }
        extrasGeo(g, p);
        bounds(g, p);
        primCache.put(k.toString(), g);
        return g;
    }
    private static String attrName(String n) {
        switch (n) {
            case "POSITION": return "position"; case "NORMAL": return "normal"; case "TANGENT": return "tangent";
            case "TEXCOORD_0": return "uv"; case "TEXCOORD_1": return "uv2"; case "COLOR_0": return "color";
            case "WEIGHTS_0": return "skinWeight"; case "JOINTS_0": return "skinIndex";
            default: return n.toLowerCase();
        }
    }
    private static void extrasGeo(Geo g, JSONObject def) {
        JSONObject x = def.optJSONObject("extras");
        if (x == null) return;
        for (Iterator<String> it = x.keys(); it.hasNext(); ) { String k = it.next(); g.ud.put(k, x.opt(k)); }
    }
    /** 外框取 POSITION 的 min / max（量化的按比例换算），包围球是外框的外接球（同 computeBounds） */
    private void bounds(Geo g, JSONObject p) throws Exception {
        JSONObject at = p.getJSONObject("attributes");
        if (!at.has("POSITION")) return;
        JSONObject acc = json.getJSONArray("accessors").getJSONObject(at.getInt("POSITION"));
        JSONArray mn = acc.optJSONArray("min"), mx = acc.optJSONArray("max");
        if (mn == null || mx == null) return;
        Box3 b = new Box3(new V3(mn.getDouble(0), mn.getDouble(1), mn.getDouble(2)), new V3(mx.getDouble(0), mx.getDouble(1), mx.getDouble(2)));
        if (acc.optBoolean("normalized")) {
            float s = normScale(acc.getInt("componentType"));
            b.min.multiplyScalar(s); b.max.multiplyScalar(s);
        }
        g.setBoundingBox(b);
        g.setSphere(b.getCenter(new V3()), b.min.distanceTo(b.max) / 2);
    }
    private static float normScale(int ct) {
        switch (ct) { case 5120: return 1 / 127f; case 5121: return 1 / 255f; case 5122: return 1 / 32767f; case 5123: return 1 / 65535f; default: return 1; }
    }
    /** 三角带 / 三角扇 → 三角形（同 BufferGeometryUtils.toTrianglesDrawMode） */
    private static Geo toTriangles(Geo g, int mode) {
        int n = g.index != null ? g.index.count : g.attr("position").count;
        int[] src = new int[n];
        for (int i = 0; i < n; i++) src[i] = g.index != null ? g.index.idx(i) : i;
        List<Integer> out = new ArrayList<>();
        if (mode == 6) for (int i = 1; i < n - 1; i++) { out.add(src[0]); out.add(src[i]); out.add(src[i + 1]); }
        else for (int i = 0; i < n - 2; i++) {
            if (i % 2 == 0) { out.add(src[i]); out.add(src[i + 1]); out.add(src[i + 2]); }
            else { out.add(src[i + 2]); out.add(src[i + 1]); out.add(src[i]); }
        }
        int[] a = new int[out.size()];
        int max = 0;
        for (int i = 0; i < a.length; i++) { a[i] = out.get(i); max = Math.max(max, a[i]); }
        Geo r = new Geo();
        r.attributes.putAll(g.attributes);
        r.index = Attr.index(a, max);
        r.setBoundingBox(g.boundingBox());
        r.ud.putAll(g.ud);
        return r;
    }

    // ---------------- 数据 ----------------
    private ByteBuffer buffer(int bi) {
        JSONObject bd = json.optJSONArray("buffers").optJSONObject(bi);
        String uri = bd.optString("uri", null);
        if (uri == null) return bi == 0 ? bin : null;
        int c = uri.indexOf(',');
        if (uri.startsWith("data:") && c > 0) {
            byte[] d = Base64.decode(uri.substring(c + 1), Base64.DEFAULT);
            ByteBuffer b = ByteBuffer.allocateDirect(d.length).order(ByteOrder.LITTLE_ENDIAN);
            b.put(d).position(0);
            return b;
        }
        throw new IllegalArgumentException("车模包里引用了外部文件：" + uri);
    }
    /** 压缩过的几何先分到几个处理器核上同时解开（每一段互不相干），建场景时直接用；参数在这个线程里先取好 */
    private void predecode(JSONArray bv) throws Exception {
        if (bv == null || !exts.contains("EXT_meshopt_compression")) { pre = null; return; }
        if (pre != null) { // 缓存里的要和这个车模对得上（每块都有、大小一样），有一块对不上整份不用
            for (int i = 0; i < bv.length() && pre != null; i++) {
                JSONObject v = bv.getJSONObject(i), ex = v.optJSONObject("extensions"), mo = ex == null ? null : ex.optJSONObject("EXT_meshopt_compression");
                if (mo == null) continue;
                ByteBuffer c = pre.views.get(i);
                if (c == null || c.capacity() != mo.getInt("count") * mo.getInt("byteStride")) pre = null;
            }
            if (pre != null) { for (Map.Entry<Integer, ByteBuffer> e : pre.views.entrySet()) if (e.getKey() < views.length) views[e.getKey()] = e.getValue(); return; }
        }
        List<Integer> vis = new ArrayList<>();
        List<java.util.concurrent.Future<ByteBuffer>> fs = new ArrayList<>();
        for (int i = 0; i < bv.length(); i++) {
            JSONObject v = bv.getJSONObject(i), ex = v.optJSONObject("extensions"), mo = ex == null ? null : ex.optJSONObject("EXT_meshopt_compression");
            if (mo == null) continue;
            final ByteBuffer src = slice(buffer(mo.optInt("buffer")), mo.optInt("byteOffset", 0), mo.optInt("byteLength", 0));
            final String mode = mo.optString("mode"), filter = mo.optString("filter", "NONE");
            final int count = mo.getInt("count"), stride = mo.getInt("byteStride");
            vis.add(i);
            fs.add(Work.submit(() -> Meshopt.decode(mode, filter, count, stride, src)));
        }
        if (fs.isEmpty()) return;
        decShare = 0.6f;
        for (int k = 0; k < fs.size(); k++) {
            try { views[vis.get(k)] = fs.get(k).get(); }
            catch (java.util.concurrent.ExecutionException e) { /* 解不开的先空着：用到时 view() 再解一遍、照原来报错（没用到的不影响载入） */ }
            if (prog != null && (k & 15) == 15) prog.at(decShare * (k + 1) / fs.size());
        }
    }
    /** 压缩过（EXT_meshopt_compression）的 bufferView 编号 */
    public List<Integer> meshoptViews() {
        List<Integer> l = new ArrayList<>();
        JSONArray bv = json.optJSONArray("bufferViews");
        for (int i = 0; bv != null && i < bv.length(); i++) { JSONObject ex = bv.optJSONObject(i).optJSONObject("extensions"); if (ex != null && ex.has("EXT_meshopt_compression")) l.add(i); }
        return l;
    }
    /** 解好的数据（还没解过返回 null） */
    public ByteBuffer decoded(int vi) { return vi >= 0 && vi < views.length ? views[vi] : null; }
    /** 解好的几何是从缓存里拿的 */
    public boolean fromCache() { return pre != null; }
    private ByteBuffer view(int vi) throws Exception {
        if (views[vi] != null) return views[vi];
        JSONObject v = json.getJSONArray("bufferViews").getJSONObject(vi);
        JSONObject ex = v.optJSONObject("extensions"), mo = ex == null ? null : ex.optJSONObject("EXT_meshopt_compression");
        ByteBuffer r;
        if (mo != null) {
            ByteBuffer src = slice(buffer(mo.optInt("buffer")), mo.optInt("byteOffset", 0), mo.optInt("byteLength", 0));
            r = Meshopt.decode(mo.optString("mode"), mo.optString("filter", "NONE"), mo.getInt("count"), mo.getInt("byteStride"), src);
        } else {
            ByteBuffer b = buffer(v.getInt("buffer"));
            if (b == null) throw new IllegalArgumentException("bufferView " + vi + " 没有数据");
            r = slice(b, v.optInt("byteOffset", 0), v.getInt("byteLength"));
        }
        return views[vi] = r;
    }
    private static ByteBuffer slice(ByteBuffer b, int off, int len) {
        ByteBuffer d = b.duplicate();
        d.position(off); d.limit(off + len);
        return d.slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static int itemSize(String t) {
        switch (t) { case "SCALAR": return 1; case "VEC2": return 2; case "VEC3": return 3; case "VEC4": return 4; case "MAT2": return 4; case "MAT3": return 9; case "MAT4": return 16; default: return 1; }
    }
    private static int compSize(int ct) { return ct == 5120 || ct == 5121 ? 1 : ct == 5122 || ct == 5123 ? 2 : 4; }
    /** 同 loadAccessor：交错的按 (bufferView, 类型, 第几片, 数量) 共用一块缓冲；稀疏的复制一份再改 */
    public Attr accessor(int ai) throws Exception {
        Attr c = accCache.get(ai);
        if (c != null) return c;
        JSONObject a = json.getJSONArray("accessors").getJSONObject(ai);
        int ct = a.getInt("componentType"), n = a.getInt("count"), is = itemSize(a.getString("type")), es = compSize(ct), ib = es * is;
        boolean norm = a.optBoolean("normalized");
        int off = a.optInt("byteOffset", 0);
        Attr r;
        if (!a.has("bufferView")) {
            r = new Attr(Attr.Buf.alloc(n * ib), 0, 0, is, ct, norm, n);
        } else {
            int vi = a.getInt("bufferView");
            JSONObject vd = json.getJSONArray("bufferViews").getJSONObject(vi);
            int stride = vd.optInt("byteStride", 0);
            ByteBuffer v = view(vi);
            if (stride > 0 && stride != ib) {
                int slice = off / stride;
                String key = vi + ":" + ct + ":" + slice + ":" + n;
                Attr.Buf b = ibCache.get(key);
                if (b == null) { b = new Attr.Buf(slice(v, slice * stride, Math.min(n * stride, v.capacity() - slice * stride))); ibCache.put(key, b); }
                r = new Attr(b, off % stride, stride, is, ct, norm, n);
            } else {
                r = new Attr(new Attr.Buf(slice(v, off, n * ib)), 0, 0, is, ct, norm, n);
            }
        }
        JSONObject sp = a.optJSONObject("sparse");
        if (sp != null) {
            Attr.Buf copy = Attr.Buf.alloc(n * ib);
            for (int i = 0; i < n; i++) for (int k = 0; k < ib; k++) copy.data.put(i * ib + k, r.buf.data.get(r.offset + i * r.stride + k));
            JSONObject si = sp.getJSONObject("indices"), sv = sp.getJSONObject("values");
            ByteBuffer iv = view(si.getInt("bufferView")), vv = view(sv.getInt("bufferView"));
            int ict = si.getInt("componentType"), io = si.optInt("byteOffset", 0), vo = sv.optInt("byteOffset", 0), cnt = sp.getInt("count");
            for (int i = 0; i < cnt; i++) {
                int idx = ict == 5121 ? iv.get(io + i) & 255 : ict == 5123 ? iv.getShort(io + i * 2) & 0xffff : iv.getInt(io + i * 4);
                for (int k = 0; k < ib; k++) copy.data.put(idx * ib + k, vv.get(vo + i * ib + k));
            }
            r = new Attr(copy, 0, 0, is, ct, norm, n);
        }
        accCache.put(ai, r);
        return r;
    }

    // ---------------- 材质 ----------------
    private Mat material(int mi) throws Exception {
        Mat cached = matCache.get(mi);
        if (cached != null) return cached;
        JSONObject md = json.getJSONArray("materials").getJSONObject(mi);
        JSONObject ex = md.optJSONObject("extensions");
        if (ex == null) ex = new JSONObject();
        Mat m;
        if (ex.has("KHR_materials_unlit")) {
            m = Mat.basic();
            JSONObject mr = md.optJSONObject("pbrMetallicRoughness");
            if (mr != null) {
                JSONArray cf = mr.optJSONArray("baseColorFactor");
                if (cf != null) { m.color.setRGB((float) cf.getDouble(0), (float) cf.getDouble(1), (float) cf.getDouble(2)); m.opacity = (float) cf.getDouble(3); }
                if (mr.has("baseColorTexture")) m.map = tex(mr.getJSONObject("baseColorTexture"), true);
            }
        } else {
            boolean phys = false;
            for (String e : new String[] { "KHR_materials_clearcoat", "KHR_materials_iridescence", "KHR_materials_sheen", "KHR_materials_transmission", "KHR_materials_volume", "KHR_materials_ior", "KHR_materials_specular" })
                if (ex.has(e)) phys = true; // 材质扩展（插件）不看 extensionsUsed，同 r149
            m = phys ? Mat.physical() : Mat.standard();
            JSONObject mr = md.optJSONObject("pbrMetallicRoughness");
            if (mr == null) mr = new JSONObject();
            JSONArray cf = mr.optJSONArray("baseColorFactor");
            if (cf != null) { m.color.setRGB((float) cf.getDouble(0), (float) cf.getDouble(1), (float) cf.getDouble(2)); m.opacity = (float) cf.getDouble(3); }
            if (mr.has("baseColorTexture")) m.map = tex(mr.getJSONObject("baseColorTexture"), true);
            m.metalness = (float) mr.optDouble("metallicFactor", 1);
            m.roughness = (float) mr.optDouble("roughnessFactor", 1);
            if (mr.has("metallicRoughnessTexture")) { m.metalnessMap = tex(mr.getJSONObject("metallicRoughnessTexture"), false); m.roughnessMap = tex(mr.getJSONObject("metallicRoughnessTexture"), false); }
            JSONObject e;
            if ((e = ext(ex, "KHR_materials_emissive_strength")) != null && e.has("emissiveStrength")) m.emissiveIntensity = (float) e.getDouble("emissiveStrength");
            if ((e = ext(ex, "KHR_materials_clearcoat")) != null) {
                if (e.has("clearcoatFactor")) m.clearcoat = (float) e.getDouble("clearcoatFactor");
                if (e.has("clearcoatTexture")) m.clearcoatMap = tex(e.getJSONObject("clearcoatTexture"), false);
                if (e.has("clearcoatRoughnessFactor")) m.clearcoatRoughness = (float) e.getDouble("clearcoatRoughnessFactor");
                if (e.has("clearcoatRoughnessTexture")) m.clearcoatRoughnessMap = tex(e.getJSONObject("clearcoatRoughnessTexture"), false);
                if (e.has("clearcoatNormalTexture")) {
                    JSONObject t = e.getJSONObject("clearcoatNormalTexture");
                    m.clearcoatNormalMap = tex(t, false);
                    if (t.has("scale")) m.clearcoatNormalScaleX = m.clearcoatNormalScaleY = (float) t.getDouble("scale");
                }
            }
            if ((e = ext(ex, "KHR_materials_iridescence")) != null) {
                if (e.has("iridescenceFactor")) m.iridescence = (float) e.getDouble("iridescenceFactor");
                if (e.has("iridescenceTexture")) m.iridescenceMap = tex(e.getJSONObject("iridescenceTexture"), false);
                if (e.has("iridescenceIor")) m.iridescenceIOR = (float) e.getDouble("iridescenceIor");
                m.iridescenceThickMin = 100; m.iridescenceThickMax = 400;
                if (e.has("iridescenceThicknessMinimum")) m.iridescenceThickMin = (float) e.getDouble("iridescenceThicknessMinimum");
                if (e.has("iridescenceThicknessMaximum")) m.iridescenceThickMax = (float) e.getDouble("iridescenceThicknessMaximum");
                if (e.has("iridescenceThicknessTexture")) m.iridescenceThicknessMap = tex(e.getJSONObject("iridescenceThicknessTexture"), false);
            }
            if ((e = ext(ex, "KHR_materials_sheen")) != null) {
                m.sheenColor.setRGB(0, 0, 0); m.sheenRoughness = 0; m.sheen = 1;
                JSONArray sc = e.optJSONArray("sheenColorFactor");
                if (sc != null) m.sheenColor.setRGB((float) sc.getDouble(0), (float) sc.getDouble(1), (float) sc.getDouble(2));
                if (e.has("sheenRoughnessFactor")) m.sheenRoughness = (float) e.getDouble("sheenRoughnessFactor");
                if (e.has("sheenColorTexture")) m.sheenColorMap = tex(e.getJSONObject("sheenColorTexture"), true);
                if (e.has("sheenRoughnessTexture")) m.sheenRoughnessMap = tex(e.getJSONObject("sheenRoughnessTexture"), false);
            }
            if ((e = ext(ex, "KHR_materials_transmission")) != null) {
                if (e.has("transmissionFactor")) m.transmission = (float) e.getDouble("transmissionFactor");
                if (e.has("transmissionTexture")) m.transmissionMap = tex(e.getJSONObject("transmissionTexture"), false);
            }
            if ((e = ext(ex, "KHR_materials_volume")) != null) {
                m.thickness = (float) e.optDouble("thicknessFactor", 0);
                if (e.has("thicknessTexture")) m.thicknessMap = tex(e.getJSONObject("thicknessTexture"), false);
                double ad = e.optDouble("attenuationDistance", 0);
                m.attenuationDistance = ad == 0 ? Float.POSITIVE_INFINITY : (float) ad;
                JSONArray ac = e.optJSONArray("attenuationColor");
                if (ac != null) m.attenuationColor.setRGB((float) ac.getDouble(0), (float) ac.getDouble(1), (float) ac.getDouble(2));
            }
            if ((e = ext(ex, "KHR_materials_ior")) != null) m.ior = (float) e.optDouble("ior", 1.5);
            if ((e = ext(ex, "KHR_materials_specular")) != null) {
                m.specularIntensity = (float) e.optDouble("specularFactor", 1);
                if (e.has("specularTexture")) m.specularIntensityMap = tex(e.getJSONObject("specularTexture"), false);
                JSONArray sc = e.optJSONArray("specularColorFactor");
                m.specularColor.setRGB(sc != null ? (float) sc.getDouble(0) : 1, sc != null ? (float) sc.getDouble(1) : 1, sc != null ? (float) sc.getDouble(2) : 1);
                if (e.has("specularColorTexture")) m.specularColorMap = tex(e.getJSONObject("specularColorTexture"), true);
            }
        }
        if (md.optBoolean("doubleSided")) m.side = Mat.DOUBLE;
        String am = md.optString("alphaMode", "OPAQUE");
        if ("BLEND".equals(am)) { m.transparent = true; m.depthWrite = false; }
        else { m.transparent = false; if ("MASK".equals(am)) m.alphaTest = (float) md.optDouble("alphaCutoff", 0.5); }
        boolean basic = m.type == Mat.BASIC;
        if (md.has("normalTexture") && !basic) {
            JSONObject t = md.getJSONObject("normalTexture");
            m.normalMap = tex(t, false);
            m.normalScaleX = m.normalScaleY = (float) t.optDouble("scale", 1);
        }
        if (md.has("occlusionTexture") && !basic) {
            JSONObject t = md.getJSONObject("occlusionTexture");
            m.aoMap = tex(t, false);
            if (t.has("strength")) m.aoMapIntensity = (float) t.getDouble("strength");
        }
        if (md.has("emissiveFactor") && !basic) { JSONArray e = md.getJSONArray("emissiveFactor"); m.emissive.setRGB((float) e.getDouble(0), (float) e.getDouble(1), (float) e.getDouble(2)); }
        if (md.has("emissiveTexture") && !basic) m.emissiveMap = tex(md.getJSONObject("emissiveTexture"), true);
        if (md.has("name")) m.name = md.getString("name");
        JSONObject x = md.optJSONObject("extras");
        if (x != null) for (Iterator<String> it = x.keys(); it.hasNext(); ) { String k = it.next(); m.ud.put(k, x.opt(k)); }
        m.ud.put("gltf", mi);
        matCache.put(mi, m);
        return m;
    }
    private JSONObject ext(JSONObject ex, String n) { return ex.optJSONObject(n); }

    // ---------------- 贴图 ----------------
    /** 同 assignTexture：按 (图片, 采样器) 共用；带贴图坐标变换的另复制一份；sRGB 只会被设成真（同 three：一张图既当颜色又当别的时都按 sRGB） */
    private Tex tex(JSONObject def, boolean srgb) throws Exception {
        int ti = def.getInt("index");
        JSONObject td = json.getJSONArray("textures").getJSONObject(ti);
        int src = td.optInt("source", -1);
        JSONObject tex = td.optJSONObject("extensions");
        if (tex != null && tex.optJSONObject("EXT_texture_webp") != null && exts.contains("EXT_texture_webp")) src = tex.getJSONObject("EXT_texture_webp").optInt("source", src);
        if (src < 0) return null;
        String key = src + ":" + (td.has("sampler") ? td.get("sampler") : "undefined");
        Tex t = texCache.get(key);
        if (t == null) {
            Tex base = srcCache.get(src);
            if (base == null) { base = image(src); srcCache.put(src, base); t = base; }
            else t = copyTex(base);
            JSONArray samplers = json.optJSONArray("samplers");
            JSONObject s = samplers != null && td.has("sampler") ? samplers.optJSONObject(td.getInt("sampler")) : null;
            if (s == null) s = new JSONObject();
            t.magFilter = s.optInt("magFilter", GLES30.GL_LINEAR);
            t.minFilter = s.optInt("minFilter", GLES30.GL_LINEAR_MIPMAP_LINEAR);
            t.wrapS = s.optInt("wrapS", GLES30.GL_REPEAT);
            t.wrapT = s.optInt("wrapT", GLES30.GL_REPEAT);
            t.name = td.optString("name", json.getJSONArray("images").getJSONObject(src).optString("name", ""));
            t.ud.put("gltfTex", ti);
            texCache.put(key, t);
        }
        JSONObject ex = def.optJSONObject("extensions"), tr = ex == null || !exts.contains("KHR_texture_transform") ? null : ex.optJSONObject("KHR_texture_transform");
        if (tr != null && (tr.has("offset") || tr.has("rotation") || tr.has("scale"))) {
            Tex c = copyTex(t);
            c.srgb = t.srgb;
            JSONArray o = tr.optJSONArray("offset"), sc = tr.optJSONArray("scale");
            if (o != null) { c.offX = (float) o.getDouble(0); c.offY = (float) o.getDouble(1); }
            if (tr.has("rotation")) c.rotation = (float) tr.getDouble("rotation");
            if (sc != null) { c.repX = (float) sc.getDouble(0); c.repY = (float) sc.getDouble(1); }
            t = c;
        }
        if (srgb) t.srgb = true;
        return t;
    }
    /** 第 si 张 glTF 图片的原始字节（贴纸的图片：不被材质引用的那几张） */
    public ByteBuffer imageBytes(int si) { try { return image(si).src; } catch (Exception e) { return null; } }
    private Tex image(int si) throws Exception {
        JSONObject im = json.getJSONArray("images").getJSONObject(si);
        Tex t = new Tex();
        if (im.has("bufferView")) t.src = view(im.getInt("bufferView"));
        else {
            String uri = im.optString("uri", "");
            int c = uri.indexOf(',');
            if (!uri.startsWith("data:") || c < 0) throw new IllegalArgumentException("车模包里引用了外部图片：" + uri);
            byte[] d = Base64.decode(uri.substring(c + 1), Base64.DEFAULT);
            t.src = ByteBuffer.allocateDirect(d.length);
            t.src.put(d).position(0);
        }
        t.mime = im.optString("mimeType", "");
        if (t.mime.isEmpty()) t.mime = im.optString("uri", "").matches("(?i).*\\.jpe?g($|\\?).*|^data:image/jpeg.*") ? "image/jpeg" : "image/png";
        t.ud.put("mimeType", t.mime);
        return t;
    }
    public static Tex copyTex(Tex b) {
        Tex t = new Tex();
        t.src = b.src; t.mime = b.mime; t.pixels = b.pixels; t.width = b.width; t.height = b.height;
        t.srgb = b.srgb; t.magFilter = b.magFilter; t.minFilter = b.minFilter; t.wrapS = b.wrapS; t.wrapT = b.wrapT;
        t.offX = b.offX; t.offY = b.offY; t.repX = b.repX; t.repY = b.repY; t.rotation = b.rotation; t.cx = b.cx; t.cy = b.cy;
        t.generateMipmaps = b.generateMipmaps; t.premultiplyAlpha = b.premultiplyAlpha; t.name = b.name;
        t.ud.putAll(b.ud);
        return t;
    }
}
