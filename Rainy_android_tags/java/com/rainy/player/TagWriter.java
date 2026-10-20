package com.rainy.player;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.*;
import java.security.MessageDigest;

/**
 * Безопасная запись тегов в mp3, лежащий в общей папке (content://...).
 * 1) копирует оригинал во внутренний кэш (это же резервная копия);
 * 2) собирает новый файл с тегом через {@link Id3Writer} (с проверкой, что аудио не изменилось);
 * 3) перезаписывает оригинал через ContentResolver и сверяет хеш;
 * 4) при любой ошибке записи возвращает оригинал из резервной копии.
 */
public final class TagWriter {
    private TagWriter() {}

    public static void writeToUri(Context ctx, Uri uri, String title, String artist, byte[] cover) throws IOException {
        File dir = new File(ctx.getCacheDir(), "tagwrite");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File src = File.createTempFile("src", ".mp3", dir);
        File dst = File.createTempFile("dst", ".mp3", dir);
        try {
            ContentResolver cr = ctx.getContentResolver();
            try (InputStream in = cr.openInputStream(uri); OutputStream os = new FileOutputStream(src)) {
                if (in == null) throw new IOException("cannot open source");
                copy(in, os);
            }
            if (!looksLikeMp3(src)) throw new IOException("unsupported: not an mp3");
            if (dir.getUsableSpace() < src.length() * 3L + (cover == null ? 0 : cover.length)) throw new IOException("no space in cache");

            Id3Writer.write(src, dst, title, artist, cover);
            String want = sha256(dst);

            try {
                try (OutputStream os = cr.openOutputStream(uri, "wt")) {
                    if (os == null) throw new IOException("cannot open target");
                    try (InputStream in = new FileInputStream(dst)) { copy(in, os); }
                }
                try (InputStream in = cr.openInputStream(uri)) {
                    if (in == null || !want.equals(sha256(in))) throw new IOException("verify failed after write");
                }
            } catch (IOException | RuntimeException e) {
                // откат: возвращаем оригинал
                try (OutputStream os = cr.openOutputStream(uri, "wt"); InputStream in = new FileInputStream(src)) {
                    if (os != null) copy(in, os);
                } catch (Exception ignored) { }
                throw e;
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
    }

    private static boolean looksLikeMp3(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[3];
            if (in.read(h) < 3) return false;
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return true;
            return (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0;
        }
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return sha256(in); }
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
