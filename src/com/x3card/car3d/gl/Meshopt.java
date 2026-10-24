package com.x3card.car3d.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** EXT_meshopt_compression 的解码（照 meshoptimizer 的 vertexcodec.cpp / indexcodec.cpp 的标量版本逐行搬，MIT，见 demo3d/LICENSE-decoders.txt）：
    顶点（版本 0、1）、三角形索引、索引序列。网页版导出车模包时只用这三种、不带滤波（数据一点不变），所以滤波不支持 */
public final class Meshopt {
    private Meshopt() {}

    /** 解一个 bufferView：mode 是 ATTRIBUTES / TRIANGLES / INDICES，返回 count × stride 字节 */
    public static ByteBuffer decode(String mode, String filter, int count, int stride, ByteBuffer src) {
        if (filter != null && !"NONE".equals(filter)) throw new IllegalArgumentException("meshopt 滤波 " + filter + " 不支持");
        byte[] in = new byte[src.remaining()];
        src.duplicate().get(in);
        byte[] out = new byte[count * stride];
        int r;
        if ("ATTRIBUTES".equals(mode)) r = vertex(out, count, stride, in);
        else if ("TRIANGLES".equals(mode)) r = index(out, count, stride, in);
        else if ("INDICES".equals(mode)) r = sequence(out, count, stride, in);
        else throw new IllegalArgumentException("meshopt 模式 " + mode + " 不认识");
        if (r != 0) throw new IllegalStateException("meshopt 解码失败 " + mode + " " + r);
        ByteBuffer b = ByteBuffer.allocateDirect(out.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(out).position(0);
        return b;
    }

    // ---------------- 顶点 ----------------
    private static final int VERTEX_HEADER = 0xa0, BLOCK_BYTES = 8192, BLOCK_MAX = 256, GROUP = 16, GROUP_LIMIT = 24, TAIL_V0 = 32, TAIL_V1 = 24;
    private static final int[] BITS_V0 = { 0, 2, 4, 8 }, BITS_V1 = { 0, 1, 2, 4, 8 };

    private static int blockSize(int vertexSize) { int r = (BLOCK_BYTES / vertexSize) & ~(GROUP - 1); return Math.min(r, BLOCK_MAX); }

    static int vertex(byte[] dst, int count, int size, byte[] buf) {
        int data = 0, end = buf.length;
        if (end - data < 1) return -2;
        int header = buf[data++] & 255;
        if ((header & 0xf0) != VERTEX_HEADER) return -1;
        int version = header & 0x0f;
        if (version > 1) return -1;
        int tail = size + (version == 0 ? 0 : size / 4), tailMin = version == 0 ? TAIL_V0 : TAIL_V1, tailPad = Math.max(tail, tailMin);
        if (end - data < tailPad) return -2;
        int tailAt = end - tail;
        byte[] last = new byte[256];
        System.arraycopy(buf, tailAt, last, 0, size);
        int channels = version == 0 ? -1 : tailAt + size;
        int bs = blockSize(size), off = 0;
        int[] pos = { data };
        byte[] buffer = new byte[BLOCK_MAX * 4], transposed = new byte[BLOCK_BYTES];
        while (off < count) {
            int n = Math.min(bs, count - off);
            if (!block(buf, pos, end, dst, off * size, n, size, last, channels, version, buffer, transposed)) return -2;
            off += n;
        }
        if (end - pos[0] != tailPad) return -3;
        return 0;
    }
    private static boolean block(byte[] buf, int[] pos, int end, byte[] dst, int dstOff, int n, int size, byte[] last, int channels, int version, byte[] buffer, byte[] transposed) {
        int aligned = (n + GROUP - 1) & ~(GROUP - 1);
        int data = pos[0];
        int controlSize = version == 0 ? 0 : size / 4;
        if (end - data < controlSize) return false;
        int control = data;
        data += controlSize;
        for (int k = 0; k < size; k += 4) {
            int ctrlByte = version == 0 ? 0 : buf[control + k / 4] & 255;
            for (int j = 0; j < 4; j++) {
                int ctrl = (ctrlByte >> (j * 2)) & 3;
                if (ctrl == 3) {
                    if (end - data < n) return false;
                    System.arraycopy(buf, data, buffer, j * n, n);
                    data += n;
                } else if (ctrl == 2) {
                    java.util.Arrays.fill(buffer, j * n, j * n + n, (byte) 0);
                } else {
                    data = bytes(buf, data, end, buffer, j * n, aligned, version == 0 ? BITS_V0 : BITS_V1, version == 0 ? 0 : ctrl);
                    if (data < 0) return false;
                }
            }
            int channel = version == 0 ? 0 : buf[channels + k / 4] & 255;
            switch (channel & 3) {
                case 0: deltas(buffer, transposed, k, n, size, last, k, 1, false, 0); break;
                case 1: deltas(buffer, transposed, k, n, size, last, k, 2, false, 0); break;
                case 2: deltas(buffer, transposed, k, n, size, last, k, 4, true, (32 - (channel >> 4)) & 31); break;
                default: return false;
            }
        }
        System.arraycopy(transposed, 0, dst, dstOff, n * size);
        System.arraycopy(transposed, size * (n - 1), last, 0, size);
        pos[0] = data;
        return true;
    }
    /** 一组组的字节（每组 16 个）：头里每组 2 位选位宽 */
    private static int bytes(byte[] buf, int data, int end, byte[] out, int outOff, int len, int[] bits, int bitsOff) {
        int headerSize = (len / GROUP + 3) / 4;
        if (end - data < headerSize) return -1;
        int header = data;
        data += headerSize;
        for (int i = 0; i < len; i += GROUP) {
            if (end - data < GROUP_LIMIT) return -1;
            int ho = i / GROUP;
            int bk = ((buf[header + ho / 4] & 255) >> ((ho % 4) * 2)) & 3;
            data = group(buf, data, out, outOff + i, bits[bitsOff + bk]);
        }
        return data;
    }
    private static int group(byte[] buf, int data, byte[] out, int o, int bits) {
        switch (bits) {
            case 0: java.util.Arrays.fill(out, o, o + GROUP, (byte) 0); return data;
            case 8: System.arraycopy(buf, data, out, o, GROUP); return data + GROUP;
            case 1: {
                int var = data + 2;
                for (int g = 0; g < 2; g++) {
                    int b = buf[data++] & 255;
                    b = (int) ((((b * 0x80200802L) & 0x0884422110L) * 0x0101010101L) >>> 32) & 255; // 8 位倒过来
                    for (int t = 0; t < 8; t++) {
                        int enc = b >> 7; b = (b << 1) & 255;
                        int ev = buf[var] & 255;
                        out[o++] = (byte) (enc == 1 ? ev : enc);
                        if (enc == 1) var++;
                    }
                }
                return var;
            }
            case 2: case 4: {
                int per = 8 / bits, groups = GROUP / per, mask = (1 << bits) - 1;
                int var = data + groups;
                for (int g = 0; g < groups; g++) {
                    int b = buf[data++] & 255;
                    for (int t = 0; t < per; t++) {
                        int enc = b >> (8 - bits); b = (b << bits) & 255;
                        int ev = buf[var] & 255;
                        out[o++] = (byte) (enc == mask ? ev : enc);
                        if (enc == mask) var++;
                    }
                }
                return var;
            }
            default: return data;
        }
    }
    /** 按差值（或异或）还原，转成交错的顶点（同 decodeDeltas4） */
    private static void deltas(byte[] buffer, byte[] tr, int k0, int n, int size, byte[] last, int lastOff, int ts, boolean xor, int rot) {
        int b = 0;
        for (int k = 0; k < 4; k += ts) {
            int vo = k0 + k;
            long p = 0;
            for (int j = 0; j < ts; j++) p |= (long) (last[lastOff + k + j] & 255) << (8 * j);
            long mask = ts == 4 ? 0xffffffffL : (1L << (ts * 8)) - 1;
            for (int i = 0; i < n; i++) {
                long v = 0;
                for (int j = 0; j < ts; j++) v |= (long) (buffer[b + i + n * j] & 255) << (8 * j);
                if (xor) { long r = rot == 0 ? v : ((v << rot) | (v >>> ((32 - rot) & 31))) & 0xffffffffL; v = (r ^ p) & mask; }
                else { long uz = ((0 - (v & 1)) ^ (v >>> 1)) & mask; v = (uz + p) & mask; }
                for (int j = 0; j < ts; j++) tr[vo + j] = (byte) (v >>> (j * 8));
                p = v;
                vo += size;
            }
            b += n * ts;
        }
    }

    // ---------------- 三角形索引 ----------------
    private static final int INDEX_HEADER = 0xe0, SEQ_HEADER = 0xd0;

    private static int[] vbyte(byte[] buf, int data) {
        int lead = buf[data++] & 255;
        if (lead < 128) return new int[] { lead, data };
        int result = lead & 127, shift = 7;
        for (int i = 0; i < 4; i++) {
            int g = buf[data++] & 255;
            result |= (g & 127) << shift;
            shift += 7;
            if (g < 128) break;
        }
        return new int[] { result, data };
    }
    private static void put(byte[] out, int idx, int size, int v) {
        if (size == 2) { out[idx * 2] = (byte) v; out[idx * 2 + 1] = (byte) (v >> 8); }
        else { out[idx * 4] = (byte) v; out[idx * 4 + 1] = (byte) (v >> 8); out[idx * 4 + 2] = (byte) (v >> 16); out[idx * 4 + 3] = (byte) (v >>> 24); }
    }

    static int index(byte[] out, int count, int size, byte[] buf) {
        if (buf.length < 1 + count / 3 + 16) return -2;
        if (((buf[0] & 255) & 0xf0) != INDEX_HEADER) return -1;
        int version = buf[0] & 0x0f;
        if (version > 1) return -1;
        int[][] edge = new int[16][2];
        int[] vfifo = new int[16];
        for (int[] e : edge) { e[0] = -1; e[1] = -1; }
        java.util.Arrays.fill(vfifo, -1);
        int eo = 0, vo = 0, next = 0, last = 0, w = 0;
        int fecmax = version >= 1 ? 13 : 15;
        int code = 1, codeEnd = code + count / 3, data = codeEnd, safeEnd = buf.length - 16, table = safeEnd;
        while (code < codeEnd) {
            int codetri = buf[code++] & 255;
            if (codetri < 0xf0) {
                int fe = codetri >> 4;
                int a = edge[(eo - 1 - fe) & 15][0], b = edge[(eo - 1 - fe) & 15][1], c;
                int fec = codetri & 15;
                if (fec < fecmax) {
                    int cf = vfifo[(vo - 1 - fec) & 15];
                    c = fec == 0 ? next : cf;
                    int fec0 = fec == 0 ? 1 : 0;
                    next += fec0;
                    vfifo[vo] = c; vo = (vo + fec0) & 15;
                } else {
                    if (data > safeEnd) return -2;
                    if (fec != 15) c = last + (fec * 2 - 27);
                    else { int[] r = vbyte(buf, data); data = r[1]; int v = r[0]; c = last + ((v >>> 1) ^ -(v & 1)); }
                    last = c;
                    vfifo[vo] = c; vo = (vo + 1) & 15;
                }
                edge[eo][0] = c; edge[eo][1] = b; eo = (eo + 1) & 15;
                edge[eo][0] = a; edge[eo][1] = c; eo = (eo + 1) & 15;
                put(out, w++, size, a); put(out, w++, size, b); put(out, w++, size, c);
            } else if (codetri < 0xfe) {
                int codeaux = buf[table + (codetri & 15)] & 255;
                int feb = codeaux >> 4, fec = codeaux & 15;
                int a = next++;
                int bf = vfifo[(vo - feb) & 15], b = feb == 0 ? next : bf;
                int feb0 = feb == 0 ? 1 : 0;
                next += feb0;
                int cf = vfifo[(vo - fec) & 15], c = fec == 0 ? next : cf;
                int fec0 = fec == 0 ? 1 : 0;
                next += fec0;
                put(out, w++, size, a); put(out, w++, size, b); put(out, w++, size, c);
                vfifo[vo] = a; vo = (vo + 1) & 15;
                vfifo[vo] = b; vo = (vo + feb0) & 15;
                vfifo[vo] = c; vo = (vo + fec0) & 15;
                edge[eo][0] = b; edge[eo][1] = a; eo = (eo + 1) & 15;
                edge[eo][0] = c; edge[eo][1] = b; eo = (eo + 1) & 15;
                edge[eo][0] = a; edge[eo][1] = c; eo = (eo + 1) & 15;
            } else {
                if (data > safeEnd) return -2;
                int codeaux = buf[data++] & 255;
                int fea = codetri == 0xfe ? 0 : 15, feb = codeaux >> 4, fec = codeaux & 15;
                if (codeaux == 0) next = 0;
                int a = fea == 0 ? next++ : 0;
                int b = feb == 0 ? next++ : vfifo[(vo - feb) & 15];
                int c = fec == 0 ? next++ : vfifo[(vo - fec) & 15];
                if (fea == 15) { int[] r = vbyte(buf, data); data = r[1]; int v = r[0]; last = a = last + ((v >>> 1) ^ -(v & 1)); }
                if (feb == 15) { int[] r = vbyte(buf, data); data = r[1]; int v = r[0]; last = b = last + ((v >>> 1) ^ -(v & 1)); }
                if (fec == 15) { int[] r = vbyte(buf, data); data = r[1]; int v = r[0]; last = c = last + ((v >>> 1) ^ -(v & 1)); }
                put(out, w++, size, a); put(out, w++, size, b); put(out, w++, size, c);
                vfifo[vo] = a; vo = (vo + 1) & 15;
                vfifo[vo] = b; vo = (vo + ((feb == 0) || (feb == 15) ? 1 : 0)) & 15;
                vfifo[vo] = c; vo = (vo + ((fec == 0) || (fec == 15) ? 1 : 0)) & 15;
                edge[eo][0] = b; edge[eo][1] = a; eo = (eo + 1) & 15;
                edge[eo][0] = c; edge[eo][1] = b; eo = (eo + 1) & 15;
                edge[eo][0] = a; edge[eo][1] = c; eo = (eo + 1) & 15;
            }
        }
        if (data != safeEnd) return -3;
        return 0;
    }

    static int sequence(byte[] out, int count, int size, byte[] buf) {
        if (buf.length < 1 + count + 4) return -2;
        if (((buf[0] & 255) & 0xf0) != SEQ_HEADER) return -1;
        if ((buf[0] & 0x0f) > 1) return -1;
        int data = 1, safeEnd = buf.length - 4;
        int[] last = new int[2];
        for (int i = 0; i < count; i++) {
            if (data >= safeEnd) return -2;
            int[] r = vbyte(buf, data); data = r[1];
            int v = r[0], cur = v & 1;
            v >>>= 1;
            int d = (v >>> 1) ^ -(v & 1), idx = last[cur] + d;
            last[cur] = idx;
            put(out, i, size, idx);
        }
        if (data != safeEnd) return -3;
        return 0;
    }
}
