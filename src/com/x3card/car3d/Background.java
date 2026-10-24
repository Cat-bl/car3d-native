package com.x3card.car3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 车况页背景的图片（同网页版 bgSet / bgGet）：选好的图片缩到最长边 1920 以内存成 JPEG，放在应用自己的目录里 */
final class Background {
    private Background() {}
    static final int MAX = 1920;
    static File file(Context c) { return new File(c.getFilesDir(), "bg.jpg"); }
    static boolean has(Context c) { return file(c).exists(); }
    static Bitmap load(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
    /** 存一张新的（读不出来抛错，错误信息给人看） */
    static void save(Context c, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        if (o.outWidth <= 0) throw new IllegalArgumentException("这张图片用不了");
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= MAX) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap bm;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, o2); }
        if (bm == null) throw new IllegalArgumentException("这张图片用不了");
        float k = Math.min(1, MAX / (float) Math.max(bm.getWidth(), bm.getHeight()));
        if (k < 1) { Bitmap r = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true); bm.recycle(); bm = r; }
        File tmp = new File(c.getFilesDir(), "bg.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { bm.compress(Bitmap.CompressFormat.JPEG, 90, out); }
        bm.recycle();
        File f = file(c);
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }
}
