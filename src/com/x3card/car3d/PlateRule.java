package com.x3card.car3d;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.util.HashMap;
import java.util.Map;

/**
 * 车牌号的规矩（照网页版 cartune.js 的 PRULE 那一套）：国标 GA 36-2018（表 3～6）和应急救援号牌的通知。
 * 省份简称、发牌机关代号（字母）、序号，字母不用 I 和 O；4、5 位序号里字母最多 2 个；
 * 新能源 6 位序号：小型第 1 位 D A B C E（纯电）或 F G H J K（非纯电）、第 2 位字母或数字、其余数字，大型前 5 位数字、第 6 位 D～K；
 * 使馆 3 位外交机构编号 + 3 位序号，全是数字；领馆省份 + 3 位编号 + 2 位序号（第 2 位可以是字母）；应急：省份 + 队伍代号（X 消防救援、S 森林消防）+ 4 位序号
 */
final class PlateRule {
    private PlateRule() {}
    static final String PROVS = "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新", PLET = "ABCDEFGHJKLMNPQRSTUVWXYZ", PDIG = "0123456789", NEV = "DABCEFGHJK";
    /** 每一位：p 省份、l 发牌机关代号、s 序号（字母最多 2 个）、d 数字、a 字母或数字、n 新能源类别；别的是只能用这几个字（只有一个字的是固定的，自动补上） */
    private static final Map<String, String> RULE = new HashMap<>();
    static {
        for (String s : new String[] { "blue", "yellow", "lowspeed", "custom" }) RULE.put(s, "p l s s s s s");
        RULE.put("green", "p l n a d d d d"); RULE.put("bignev", "p l d d d d d n");
        RULE.put("police", "p l s s s s 警"); RULE.put("coach", "p l s s s s 学"); RULE.put("trailer", "p l s s s s 挂");
        RULE.put("hkmo", "粤 Z s s s s 港澳"); RULE.put("emergency", "p XS s s s s 应 急");
        RULE.put("embassy", "d d d d d d 使"); RULE.put("consulate", "p d d d d a 领");
    }
    /** 牌种（一行 5 个）和说明 */
    static final String[][] TYPES = { { "blue", "蓝牌", "小型汽车：蓝底白字，440×140 毫米" }, { "green", "绿牌", "小型新能源汽车：渐变绿底黑字，480×140，8 位" },
        { "yellow", "黄牌", "大型汽车：黄底黑字，前牌单行 440×140，后牌双行 440×220" }, { "bignev", "黄绿牌", "大型新能源汽车：黄绿底黑字，480×140，8 位" },
        { "police", "警车", "警用汽车：白底黑字、红\"警\"字，后牌的间隔符是一道短横" }, { "emergency", "应急", "应急救援（消防）：白底黑字、红\"应急\"，480×140，前牌的队伍代号是红的" },
        { "coach", "教练", "教练汽车：黄底黑字，最后一位是\"学\"" }, { "trailer", "挂车", "挂车：黄底黑字，双行 440×220，最后一位是\"挂\"" },
        { "lowspeed", "低速车", "低速货车、三轮汽车：黄底黑字，双行 300×165" }, { "embassy", "使馆", "使馆汽车：黑底白字，3 位外交机构编号 + 3 位序号 + \"使\"" },
        { "consulate", "领馆", "领馆汽车：黑底白字，省份 + 3 位外交机构编号 + 2 位序号 + \"领\"" }, { "hkmo", "港澳", "港澳入出境车：黑底白字，粤 Z 开头，最后一位是\"港\"或\"澳\"" },
        { "custom", "自定义", "版式同蓝牌，底色、字色自己定" } };
    /** 间隔符前面有几个字（别的是 2） */
    private static int gap(String style) {
        switch (style) { case "police": case "emergency": case "trailer": case "lowspeed": return 1; case "embassy": return 3; case "consulate": return 4; default: return 2; }
    }
    static String typeName(String style) { for (String[] t : TYPES) if (t[0].equals(style)) return t[1]; return TYPES[0][1]; }
    static String typeNote(String style) { for (String[] t : TYPES) if (t[0].equals(style)) return t[2]; return TYPES[0][2]; }

    /** 一位：k 是哪一类（x 是只能用 set 里的这几个字），fix 是固定的字 */
    static final class Q { final char k; final String set; final boolean fix; Q(char k, String set, boolean fix) { this.k = k; this.set = set; this.fix = fix; } }
    private static final Map<String, Q[]> cache = new HashMap<>();
    static synchronized Q[] rule(String style) {
        String r = RULE.containsKey(style) ? RULE.get(style) : RULE.get("blue");
        Q[] out = cache.get(r);
        if (out != null) return out;
        String[] ks = r.split(" ");
        out = new Q[ks.length];
        for (int i = 0; i < ks.length; i++) {
            String k = ks[i];
            String m = "p".equals(k) ? PROVS : "l".equals(k) ? PLET : "d".equals(k) ? PDIG : "a".equals(k) ? PLET + PDIG : "n".equals(k) ? NEV : "s".equals(k) ? "" : null;
            out[i] = m != null ? new Q(k.charAt(0), m, false) : new Q('x', k, k.length() == 1);
        }
        cache.put(r, out);
        return out;
    }
    static int len(String style) { return rule(style).length; }
    /** 号已经是 t 时，下一位能敲哪些字；满了返回空 */
    static String next(String style, String t) {
        Q[] R = rule(style);
        if (t.length() >= R.length) return "";
        Q q = R[t.length()];
        if (q.k != 's') return q.set;
        int lt = 0;
        for (int i = 0; i < t.length(); i++) if (R[i].k == 's' && PLET.indexOf(t.charAt(i)) >= 0) lt++;
        return lt >= 2 ? PDIG : PLET + PDIG;
    }
    /** 紧跟着的固定字自动补上 */
    static String fill(String style, String t) {
        Q[] R = rule(style);
        while (t.length() < R.length && R[t.length()].fix) t += R[t.length()].set;
        return t;
    }
    /** 删一个：连带后面自动补的固定字一起退（开头固定的粤 Z 删不掉） */
    static String back(String style, String t) {
        Q[] R = rule(style);
        while (t.length() > 0 && t.length() <= R.length && R[t.length() - 1].fix) t = t.substring(0, t.length() - 1);
        return fill(style, t.isEmpty() ? t : t.substring(0, t.length() - 1));
    }
    /** 自己敲的有几位（不算自动补的固定字） */
    static int typed(String style, String t) {
        Q[] R = rule(style);
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (i < R.length && !R[i].fix) n++;
        return n;
    }
    /** 把号转成这种牌的：省份、发牌机关代号、序号各归各位，放不进去的字跳过（固定的字、新能源类别字母不带过去；换成小型新能源第 3 位补个 D）。
        换牌种、读进来的号（以前存的不合国标的）都走这里 */
    static String conv(String from, String to, String t) {
        Q[] R = rule(from), S = rule(to);
        String p = "", l = "";
        StringBuilder ser = new StringBuilder();
        t = t == null ? "" : t;
        for (int i = 0; i < t.length() && i < R.length; i++) {
            Q q = R[i];
            String c = String.valueOf(t.charAt(i));
            if (q.fix) { if (PROVS.contains(q.set)) p = q.set; else if (PLET.contains(q.set)) l = q.set; continue; } // 港澳牌固定的粤 Z 也带过去
            if (q.k == 'n') continue;
            if (q.k == 'p') p = c;
            else if (q.k == 'l' || q.k == 'x') { if (PLET.contains(c)) l = c; }
            else ser.append(c);
        }
        String out = fill(to, "");
        int j = 0;
        while (out.length() < S.length) {
            Q q2 = S[out.length()];
            String nx = next(to, out), c = "";
            if (q2.k == 'p') c = p;
            else if (q2.k == 'l' || q2.k == 'x') c = l;
            else if (q2.k == 'n') { if ("green".equals(to) && j < ser.length()) c = NEV.indexOf(ser.charAt(j)) >= 0 ? String.valueOf(ser.charAt(j++)) : "D"; }
            else { while (j < ser.length() && nx.indexOf(ser.charAt(j)) < 0) j++; c = j < ser.length() ? String.valueOf(ser.charAt(j++)) : ""; }
            if (c.isEmpty() || !nx.contains(c)) break;
            out = fill(to, out + c);
        }
        return out;
    }
    /** 合不合这种牌的规矩（没敲完的也算合） */
    static boolean ok(String style, String t) {
        if (t.length() > len(style)) return false;
        for (int i = 0; i < t.length(); i++) if (next(style, t.substring(0, i)).indexOf(t.charAt(i)) < 0) return false;
        return true;
    }
    /** 号码条：已经敲的 + 没敲的空位（固定的字也显示出来），间隔符照这种牌放；红字（警、应急，应急前牌的队伍代号）标红 */
    static CharSequence num(String style, String t, String which, int red) {
        Q[] R = rule(style);
        int g = gap(style);
        int[] reds = "police".equals(style) ? new int[] { 6 } : "emergency".equals(style) ? ("front".equals(which) ? new int[] { 1, 6, 7 } : new int[] { 6, 7 }) : new int[0];
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < R.length; i++) {
            if (i == g) out.append("green".equals(style) || "bignev".equals(style) ? " " : "·");
            if (i == 2 && ("trailer".equals(style) || "lowspeed".equals(style))) out.append(' '); // 双行牌：上排、下排之间空一格
            String c = i < t.length() ? String.valueOf(t.charAt(i)) : R[i].fix ? R[i].set : "＿";
            int a = out.length();
            out.append(c);
            for (int r : reds) if (r == i) out.setSpan(new ForegroundColorSpan(red), a, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }
    /** 下一位该敲什么（显示在按键上面） */
    static String hint(String style, String t) {
        Q[] R = rule(style);
        int n = t.length();
        if (n >= R.length) return "号码齐了";
        Q q = R[n];
        int left = 0;
        for (int i = n; i < R.length; i++) if (!R[i].fix) left++;
        String at = "第 " + (n + 1) + " 位：", more = "（还差 " + left + " 位）";
        if (q.k == 'p') return at + "省份简称";
        if (q.k == 'l') return at + "发牌机关代号（字母）";
        if ("XS".equals(q.set)) return at + "队伍代号，X 是消防救援、S 是森林消防";
        if ("港澳".equals(q.set)) return "最后一位：港是香港、澳是澳门";
        if (q.k == 'n') return (n == 2 ? at : "最后一位：") + "D A B C E 是纯电动，F G H J K 是插电混动、增程";
        if ("embassy".equals(style) || "consulate".equals(style)) {
            int e = n - ("consulate".equals(style) ? 1 : 0);
            return at + (e < 3 ? "驻华外交机构编号，数字" : q.k == 'a' ? "序号，数字或字母" : "序号，数字") + more;
        }
        if (q.k == 's') return at + (PDIG.equals(next(style, t)) ? "数字，序号里已经有 2 个字母了" : "字母或数字，序号里字母最多 2 个") + more;
        return at + (q.k == 'a' ? "字母或数字" : "数字") + more;
    }
    /** 有没有省份那一位（有才出省份那排键） */
    static boolean hasProv(String style) { for (Q q : rule(style)) if (q.k == 'p') return true; return false; }
    /** 不在省份、字母、数字里、又要敲的字（港澳牌的"港 澳"） */
    static String extra(String style) {
        StringBuilder s = new StringBuilder();
        for (Q q : rule(style)) if (q.k == 'x' && !q.fix) for (char c : q.set.toCharArray()) if ((PLET + PDIG).indexOf(c) < 0 && s.indexOf(String.valueOf(c)) < 0) s.append(c);
        return s.toString();
    }
}
