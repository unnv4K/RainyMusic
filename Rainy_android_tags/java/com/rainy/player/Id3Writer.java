package com.rainy.player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Лёгкий писатель ID3v2 для .mp3 на чистом Java (без сторонних библиотек).
 * Заменяет кадры TIT2 (название), TPE1 (исполнитель) и APIC (обложка), все остальные кадры сохраняет как есть.
 * Аудиопоток копируется байт в байт. Версия тега: 2.4, если в файле уже 2.4, иначе 2.3 (теги 2.2 конвертируются в 2.3).
 */
public final class Id3Writer {
    private Id3Writer() {}

    private static final int PADDING = 256;
    private static final long MAX_TAG = 64L * 1024 * 1024;

    private static final class Frame {
        String id; int f0, f1; byte[] data;
        Frame(String id, int f0, int f1, byte[] data) { this.id = id; this.f0 = f0; this.f1 = f1; this.data = data; }
    }

    private static final class Parsed {
        int version = 0;          // 0 = тега нет
        long audioStart = 0;      // смещение начала аудио
        List<Frame> frames = new ArrayList<>();
    }

    /** title/artist: null = оставить, "" = удалить. cover: null = оставить, пустой массив = удалить, иначе заменить. */
    public static void write(File in, File out, String title, String artist, byte[] cover) throws IOException {
        Parsed p;
        try (RandomAccessFile raf = new RandomAccessFile(in, "r")) { p = parse(raf); }
        int outVer = p.version == 4 ? 4 : 3;

        List<Frame> frames = new ArrayList<>();
        for (Frame f : p.frames) {
            if (f.id.equals("TIT2") && title != null) continue;
            if (f.id.equals("TPE1") && artist != null) continue;
            if (f.id.equals("APIC") && cover != null) continue;
            frames.add(f);
        }
        List<Frame> head = new ArrayList<>();
        if (title != null && !title.isEmpty()) head.add(new Frame("TIT2", 0, 0, textFrame(title, outVer)));
        if (artist != null && !artist.isEmpty()) head.add(new Frame("TPE1", 0, 0, textFrame(artist, outVer)));
        if (cover != null && cover.length > 0) head.add(new Frame("APIC", 0, 0, apicFrame(cover)));
        head.addAll(frames);

        long body = 0;
        for (Frame f : head) body += 10 + f.data.length;
        long size = body + PADDING;
        if (size > 0x0FFFFFFFL) throw new IOException("tag too large");

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            os.write(new byte[]{'I', 'D', '3', (byte) outVer, 0, 0});
            os.write(synchsafe((int) size));
            for (Frame f : head) {
                os.write(f.id.getBytes(StandardCharsets.ISO_8859_1));
                os.write(outVer == 4 ? synchsafe(f.data.length) : be32(f.data.length));
                os.write(f.f0); os.write(f.f1);
                os.write(f.data);
            }
            os.write(new byte[PADDING]);
            try (RandomAccessFile raf = new RandomAccessFile(in, "r")) {
                raf.seek(p.audioStart);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = raf.read(buf)) > 0) os.write(buf, 0, n);
            }
        }
        // проверка: аудио в результате той же длины, что и в исходнике
        long expected = in.length() - p.audioStart;
        long got = audioLength(out);
        if (got != expected) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IOException("verify failed: audio " + got + " != " + expected);
        }
    }

    /** Длина аудиоданных после ID3v2-тега (для проверки результата). */
    public static long audioLength(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return raf.length() - tagEnd(raf);
        }
    }

    private static long tagEnd(RandomAccessFile raf) throws IOException {
        byte[] h = new byte[10];
        raf.seek(0);
        if (raf.read(h) < 10 || h[0] != 'I' || h[1] != 'D' || h[2] != '3') return 0;
        int ver = h[3] & 0xFF;
        if (ver < 2 || ver > 4) return 0;
        if (!ss(h[6]) || !ss(h[7]) || !ss(h[8]) || !ss(h[9])) return 0;
        long size = unsynchsafe(h, 6);
        long end = 10 + size + (ver == 4 && (h[5] & 0x10) != 0 ? 10 : 0);
        return end > raf.length() ? 0 : end;
    }

    private static boolean ss(byte b) { return (b & 0x80) == 0; }

    private static Parsed parse(RandomAccessFile raf) throws IOException {
        Parsed p = new Parsed();
        byte[] h = new byte[10];
        raf.seek(0);
        if (raf.read(h) < 10 || h[0] != 'I' || h[1] != 'D' || h[2] != '3') return p;
        int ver = h[3] & 0xFF, flags = h[5] & 0xFF;
        if (ver < 2 || ver > 4 || !ss(h[6]) || !ss(h[7]) || !ss(h[8]) || !ss(h[9])) return p;
        long size = unsynchsafe(h, 6);
        if (size > MAX_TAG || 10 + size > raf.length()) return p;
        byte[] tag = new byte[(int) size];
        raf.readFully(tag);
        p.version = ver;
        p.audioStart = 10 + size + (ver == 4 && (flags & 0x10) != 0 ? 10 : 0);
        boolean tagUnsync = (flags & 0x80) != 0 && ver >= 3;
        if (tagUnsync) tag = unsync(tag);
        int pos = 0;
        if ((flags & 0x40) != 0 && ver >= 3 && tag.length >= 4) {      // расширенный заголовок пропускаем
            int es = ver == 4 ? (int) unsynchsafe(tag, 0) : (int) (readBe32(tag, 0) + 4);
            if (es > 0 && es <= tag.length) pos = es;
        }
        int idl = ver == 2 ? 3 : 4, hl = ver == 2 ? 6 : 10;
        while (pos + hl <= tag.length) {
            if (tag[pos] == 0) break;                                   // дальше padding
            String id = new String(tag, pos, idl, StandardCharsets.ISO_8859_1);
            if (!id.matches("[A-Z0-9]{3,4}")) break;
            long fs;
            int f0 = 0, f1 = 0;
            if (ver == 2) fs = ((tag[pos + 3] & 0xFFL) << 16) | ((tag[pos + 4] & 0xFFL) << 8) | (tag[pos + 5] & 0xFFL);
            else {
                fs = ver == 4 ? unsynchsafe(tag, pos + 4) : readBe32(tag, pos + 4);
                f0 = tag[pos + 8] & 0xFF; f1 = tag[pos + 9] & 0xFF;
            }
            int d0 = pos + hl;
            if (fs < 0 || d0 + fs > tag.length) break;
            byte[] data = Arrays.copyOfRange(tag, d0, (int) (d0 + fs));
            pos = (int) (d0 + fs);
            if (ver == 4 && tagUnsync) f1 &= ~0x02;                     // кадры уже «разсинхронизированы» вместе с тегом
            if (ver == 2) {
                String nid = map22(id);
                if (nid == null) continue;
                if (id.equals("PIC")) { data = pic22to23(data); if (data == null) continue; }
                p.frames.add(new Frame(nid, 0, 0, data));
            } else {
                // кадры со сжатием/шифрованием в v2.3 (биты 0x80/0x40 первого байта флагов) копировать безопасно нельзя
                if (ver == 3 && (f1 & 0xC0) != 0) continue;
                if (ver == 4 && (f1 & 0x0C) != 0) continue;
                p.frames.add(new Frame(id, f0, f1, data));
            }
        }
        return p;
    }

    private static String map22(String id) {
        switch (id) {
            case "TT2": return "TIT2"; case "TP1": return "TPE1"; case "TAL": return "TALB"; case "TP2": return "TPE2";
            case "TRK": return "TRCK"; case "TYE": return "TYER"; case "TCO": return "TCON"; case "TCM": return "TCOM";
            case "COM": return "COMM"; case "PIC": return "APIC"; case "TPA": return "TPOS"; case "ULT": return "USLT";
            default: return null;
        }
    }

    private static byte[] pic22to23(byte[] d) {
        if (d.length < 6) return null;
        int enc = d[0];
        String fmt = new String(d, 1, 3, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        String mime = fmt.equals("jpg") ? "image/jpeg" : "image/" + fmt;
        int type = d[4];
        int p = 5;
        if (enc == 1 || enc == 2) { while (p + 1 < d.length && !(d[p] == 0 && d[p + 1] == 0)) p += 2; p += 2; }
        else { while (p < d.length && d[p] != 0) p++; p++; }
        if (p > d.length) return null;
        byte[] md = mime.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); o.write(md, 0, md.length); o.write(0); o.write(type); o.write(0);
        o.write(d, p, d.length - p);
        return o.toByteArray();
    }

    private static byte[] textFrame(String s, int ver) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 127) { ascii = false; break; }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (ascii) { o.write(0); o.write(s.getBytes(StandardCharsets.ISO_8859_1), 0, s.length()); }
        else if (ver == 4) { o.write(3); byte[] b = s.getBytes(StandardCharsets.UTF_8); o.write(b, 0, b.length); }
        else { o.write(1); o.write(0xFF); o.write(0xFE); byte[] b = s.getBytes(StandardCharsets.UTF_16LE); o.write(b, 0, b.length); }
        return o.toByteArray();
    }

    private static byte[] apicFrame(byte[] img) {
        boolean png = img.length > 8 && (img[0] & 0xFF) == 0x89 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G';
        byte[] mime = (png ? "image/png" : "image/jpeg").getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); o.write(mime, 0, mime.length); o.write(0); o.write(3); o.write(0);
        o.write(img, 0, img.length);
        return o.toByteArray();
    }

    private static byte[] unsync(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length);
        for (int i = 0; i < b.length; i++) { o.write(b[i]); if ((b[i] & 0xFF) == 0xFF && i + 1 < b.length && b[i + 1] == 0) i++; }
        return o.toByteArray();
    }

    private static long unsynchsafe(byte[] b, int o) {
        return ((b[o] & 0x7FL) << 21) | ((b[o + 1] & 0x7FL) << 14) | ((b[o + 2] & 0x7FL) << 7) | (b[o + 3] & 0x7FL);
    }
    private static long readBe32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }
    private static byte[] synchsafe(int v) { return new byte[]{(byte) ((v >> 21) & 0x7F), (byte) ((v >> 14) & 0x7F), (byte) ((v >> 7) & 0x7F), (byte) (v & 0x7F)}; }
    private static byte[] be32(int v) { return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}; }
}
