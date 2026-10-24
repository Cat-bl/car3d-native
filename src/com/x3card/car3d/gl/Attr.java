package com.x3card.car3d.gl;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 顶点属性 / 索引（同 three.js 的 BufferAttribute / InterleavedBufferAttribute）：数据在 Buf（直接内存，GLB 里一个 bufferView 一块，交错的几个属性共用），
    按 offset、stride 取；量化的（normalized 的整数）原样交给 GL，处理器上读时换算成浮点（同 three 的 denormalize） */
public final class Attr {
    /** 一块 GL 缓冲：数据 + 上传过的版本 */
    public static final class Buf {
        public ByteBuffer data;
        public int id, version, uploaded = -1;
        public Buf(ByteBuffer d) { data = d.order(ByteOrder.LITTLE_ENDIAN); }
        public static Buf alloc(int bytes) { return new Buf(ByteBuffer.allocateDirect(Math.max(4, bytes))); }
        /** 绑到 target 上（数据变了重新上传） */
        public void bind(int target) {
            if (id == 0) { int[] a = new int[1]; GLES30.glGenBuffers(1, a, 0); id = a[0]; }
            GLES30.glBindBuffer(target, id);
            if (uploaded != version) {
                data.position(0);
                GLES30.glBufferData(target, data.capacity(), data, GLES30.GL_STATIC_DRAW);
                uploaded = version;
            }
        }
        public void free() { if (id != 0) { GLES30.glDeleteBuffers(1, new int[] { id }, 0); id = 0; uploaded = -1; } }
    }

    public final Buf buf;
    /** 第一个元素在 buf 里的字节位置、相邻两个元素隔多少字节（0 = 紧挨着） */
    public final int offset, stride;
    public final int itemSize, type, count;
    public final boolean normalized;

    public Attr(Buf buf, int offset, int stride, int itemSize, int type, boolean normalized, int count) {
        this.buf = buf; this.offset = offset; this.itemSize = itemSize; this.type = type; this.normalized = normalized; this.count = count;
        this.stride = stride > 0 ? stride : itemSize * size(type);
    }
    public static int size(int type) {
        switch (type) {
            case GLES30.GL_BYTE: case GLES30.GL_UNSIGNED_BYTE: return 1;
            case GLES30.GL_SHORT: case GLES30.GL_UNSIGNED_SHORT: case GLES30.GL_HALF_FLOAT: return 2;
            default: return 4;
        }
    }
    /** 新建一个紧挨着的浮点属性 */
    public static Attr floats(float[] a, int itemSize) {
        Buf b = Buf.alloc(a.length * 4);
        b.data.asFloatBuffer().put(a);
        return new Attr(b, 0, 0, itemSize, GLES30.GL_FLOAT, false, a.length / itemSize);
    }
    /** 新建索引（顶点不超过 65535 时用 16 位） */
    public static Attr index(int[] a, int maxVertex) {
        if (maxVertex < 65536) {
            Buf b = Buf.alloc(a.length * 2);
            for (int i = 0; i < a.length; i++) b.data.putShort(i * 2, (short) a[i]);
            return new Attr(b, 0, 0, 1, GLES30.GL_UNSIGNED_SHORT, false, a.length);
        }
        Buf b = Buf.alloc(a.length * 4);
        b.data.asIntBuffer().put(a);
        return new Attr(b, 0, 0, 1, GLES30.GL_UNSIGNED_INT, false, a.length);
    }

    /** 第 i 个元素的第 k 个分量（浮点；量化的按 three 的规则换算） */
    public float get(int i, int k) {
        int p = offset + i * stride + k * size(type);
        ByteBuffer d = buf.data;
        switch (type) {
            case GLES30.GL_FLOAT: return d.getFloat(p);
            case GLES30.GL_UNSIGNED_SHORT: { int v = d.getShort(p) & 0xffff; return normalized ? v / 65535f : v; }
            case GLES30.GL_SHORT: { int v = d.getShort(p); return normalized ? Math.max(v / 32767f, -1f) : v; }
            case GLES30.GL_UNSIGNED_BYTE: { int v = d.get(p) & 0xff; return normalized ? v / 255f : v; }
            case GLES30.GL_BYTE: { int v = d.get(p); return normalized ? Math.max(v / 127f, -1f) : v; }
            case GLES30.GL_UNSIGNED_INT: return (float) (d.getInt(p) & 0xffffffffL);
            case GLES30.GL_INT: return d.getInt(p);
            default: return 0;
        }
    }
    public float getX(int i) { return get(i, 0); }
    public float getY(int i) { return get(i, 1); }
    public float getZ(int i) { return get(i, 2); }
    public float getW(int i) { return get(i, 3); }
    /** 索引（整数） */
    public int idx(int i) {
        int p = offset + i * stride;
        switch (type) {
            case GLES30.GL_UNSIGNED_SHORT: return buf.data.getShort(p) & 0xffff;
            case GLES30.GL_UNSIGNED_BYTE: return buf.data.get(p) & 0xff;
            default: return buf.data.getInt(p);
        }
    }
    /** 改索引（理顺绕向用），改完 buf.version++ */
    public void setIdx(int i, int v) {
        int p = offset + i * stride;
        switch (type) {
            case GLES30.GL_UNSIGNED_SHORT: buf.data.putShort(p, (short) v); break;
            case GLES30.GL_UNSIGNED_BYTE: buf.data.put(p, (byte) v); break;
            default: buf.data.putInt(p, v);
        }
    }
    public V3 v3(int i, V3 t) { return t.set(get(i, 0), get(i, 1), get(i, 2)); }
}
