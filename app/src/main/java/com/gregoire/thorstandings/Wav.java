package com.gregoire.thorstandings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** A 16-bit PCM WAV file, read by hand (pure Java, so it can be tested on a computer). Anything unusual gives null. */
final class Wav {
    final short[] pcm;      // interleaved if stereo
    final int channels, rate;

    private Wav(short[] pcm, int channels, int rate) { this.pcm = pcm; this.channels = channels; this.rate = rate; }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static int le16(byte[] b, int p) { return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8); }
    private static int le32(byte[] b, int p) { return le16(b, p) | (le16(b, p + 2) << 16); }
    private static boolean tag(byte[] b, int p, String s) {
        for (int i = 0; i < 4; i++) if (b[p + i] != s.charAt(i)) return false;
        return true;
    }

    /** null if it isn't a plain 16-bit PCM mono/stereo WAV. */
    static Wav parse(byte[] b) {
        try {
            if (b == null || b.length < 44 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null;
            int fmt = 0, ch = 0, rate = 0, bits = 0, dataAt = -1, dataLen = 0;
            int pos = 12;
            while (pos + 8 <= b.length) {
                int size = le32(b, pos + 4);
                if (size < 0) size = b.length - pos - 8;
                if (tag(b, pos, "fmt ") && size >= 16 && pos + 8 + 16 <= b.length) {
                    fmt = le16(b, pos + 8); ch = le16(b, pos + 10); rate = le32(b, pos + 12); bits = le16(b, pos + 22);
                } else if (tag(b, pos, "data")) {
                    dataAt = pos + 8;
                    dataLen = Math.min(size, b.length - dataAt);
                    break;
                }
                pos += 8 + size + (size & 1);
            }
            if ((fmt != 1 && fmt != 0xFFFE) || bits != 16 || (ch != 1 && ch != 2) || rate < 8000 || dataAt < 0 || dataLen < 2 * ch) return null;
            int n = dataLen / 2 / ch * ch;
            short[] pcm = new short[n];
            for (int i = 0; i < n; i++) pcm[i] = (short) le16(b, dataAt + i * 2);
            return new Wav(pcm, ch, rate);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Interleaved stereo (a mono file is played in both ears). */
    short[] stereo() {
        if (channels == 2) return pcm;
        short[] out = new short[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) { out[i * 2] = pcm[i]; out[i * 2 + 1] = pcm[i]; }
        return out;
    }
}
