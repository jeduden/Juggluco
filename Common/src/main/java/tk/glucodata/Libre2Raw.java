/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */

package tk.glucodata;

/**
 * Diagnostic-only port of the open-source FreeStyle Libre 2 BLE decryption
 * (dabear/LibreTransmitter PreLibre2.swift), used to recover the per-minute RAW
 * sensor signal from the streamed BLE packet. Juggluco's own Abbott P2 path
 * (V2()/processTooth) only yields the calibrated value, never raw.
 *
 * The cipher key and algorithm are the community-reverse-engineered Libre 2
 * keystream cipher; {@link #decrypt} XORs the 44-byte payload out of the 46-byte
 * BLE packet keyed by the sensor UID, and {@link #parse} extracts the ten 14-bit
 * raw measurements (7 per-minute "trend" + 3 fifteen-minute "history"). A valid
 * CRC16 (with the Uwe Petersen bit-reverse + byte-swap) confirms correct
 * decryption.
 *
 * The UID bytes the cipher needs (id[0..5]) are exactly the six bytes encoded in
 * the displayed sensor serial (getserial base32), so they are derived offline
 * from the serial string in {@link #uidFromSerial} - the two NFC-prefix bytes
 * are never used by the cipher.
 */
public final class Libre2Raw {
    private static final int[] KEY = {0xA0C5, 0x6860, 0x0000, 0x14C6};

    private static int u16(int x) { return x & 0xFFFF; }
    private static int U16(int hi, int lo) { return ((hi & 0xFF) << 8) | (lo & 0xFF); }

    private static int op(int value) {
        int res = value >> 2;
        if ((value & 1) != 0) res ^= KEY[1];
        if ((value & 2) != 0) res ^= KEY[0];
        return u16(res);
    }

    private static int[] processCrypto(int[] in) {
        int r0 = u16(op(in[0]) ^ in[3]);
        int r1 = u16(op(r0) ^ in[2]);
        int r2 = u16(op(r1) ^ in[1]);
        int r3 = u16(op(r2) ^ in[0]);
        int r4 = op(r3);
        int r5 = op(u16(r4 ^ r0));
        int r6 = op(u16(r5 ^ r1));
        int r7 = op(u16(r6 ^ r2));
        int f1 = u16(r0 ^ r4), f2 = u16(r1 ^ r5), f3 = u16(r2 ^ r6), f4 = u16(r3 ^ r7);
        return new int[]{f4, f3, f2, f1};
    }

    private static int[] prepareVariables(int[] id, int x, int y) {
        int s1 = u16(U16(id[5], id[4]) + x + y);
        int s2 = u16(U16(id[3], id[2]) + KEY[2]);
        int s3 = u16(U16(id[1], id[0]) + x * 2);
        int s4 = u16(0x241a ^ KEY[3]);
        return new int[]{s1, s2, s3, s4};
    }

    private static int[] usefulFunction(int[] id, int x, int y) {
        int[] bk = processCrypto(prepareVariables(id, x, y));
        int r1 = bk[0] ^ 0x4163;
        int r2 = bk[1] ^ 0x4344;
        return new int[]{r1 & 0xFF, (r1 >> 8) & 0xFF, r2 & 0xFF, (r2 >> 8) & 0xFF};
    }

    /** Decrypt the 46-byte assembled BLE packet (pre-V2) with the 6-byte UID id[0..5]; returns 44 bytes. */
    public static byte[] decrypt(int[] id, byte[] data) {
        int[] d = usefulFunction(id, 0x1b, 0x1b6a);
        int x = (U16(d[1], d[0]) ^ U16(d[3], d[2])) | 0x63;
        int y = U16(data[1] & 0xFF, data[0] & 0xFF) ^ 0x63;
        byte[] keystream = new byte[64];
        int[] ik = processCrypto(prepareVariables(id, x, y));
        int p = 0;
        for (int r = 0; r < 8; r++) {
            keystream[p++] = (byte) ik[0];        keystream[p++] = (byte) (ik[0] >> 8);
            keystream[p++] = (byte) ik[1];        keystream[p++] = (byte) (ik[1] >> 8);
            keystream[p++] = (byte) ik[2];        keystream[p++] = (byte) (ik[2] >> 8);
            keystream[p++] = (byte) ik[3];        keystream[p++] = (byte) (ik[3] >> 8);
            ik = processCrypto(ik);
        }
        byte[] out = new byte[data.length - 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ((data[i + 2] & 0xFF) ^ (keystream[i] & 0xFF));
        return out;
    }

    private static final int[] CRC16TABLE = buildCrcTable();

    // CRC16 with the Uwe Petersen (2016) modification: table loop, then reverse
    // all 16 bits, then byte-swap. Without this the decrypted payload never validates.
    static int crc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++)
            crc = ((crc >> 8) ^ CRC16TABLE[(crc ^ (data[i] & 0xFF)) & 0xFF]) & 0xFFFF;
        int rev = 0;
        for (int i = 0; i < 16; i++) { rev = ((rev << 1) | (crc & 1)) & 0xFFFF; crc >>= 1; }
        return ((rev & 0xFF) << 8) | (rev >> 8);
    }

    private static int readBits(byte[] buf, int byteOffset, int bitOffset, int bitCount) {
        if (bitCount == 0) return 0;
        int res = 0;
        for (int i = 0; i < bitCount; i++) {
            int t = byteOffset * 8 + bitOffset + i;
            if (t >= 0 && ((buf[t >> 3] >> (t & 7)) & 1) != 0) res |= (1 << i);
        }
        return res;
    }

    // The 7 "trend" slots are not contiguous minutes: they sit at these minute
    // offsets back from `age` (the rest of the 15-min window arrives via the
    // 3 history slots and via the overlap of successive 2-minute packets).
    private static final int[] TREND_OFFSETS = {0, 2, 4, 6, 7, 12, 15};

    /** Parsed raw result. trendRaw[i] is the raw at sensor id trendId[i] (= age - TREND_OFFSETS[i]). */
    public static final class Result {
        public final boolean crcValid;
        public final int age;          // sensor lifetime minutes (id of the current/most-recent measurement)
        public final int[] trendRaw;   // 7 raw values (14-bit) at the trend offsets
        public final int[] trendId;    // sensor id (minute) for each trendRaw entry
        Result(boolean crcValid, int age, int[] trendRaw, int[] trendId) {
            this.crcValid = crcValid; this.age = age;
            this.trendRaw = trendRaw; this.trendId = trendId;
        }
    }

    /** Parse a 44-byte decrypted payload into the 7 trend raw values + their sensor ids. */
    public static Result parse(byte[] d) {
        int age = ((d[41] & 0xFF) << 8) | (d[40] & 0xFF);
        int calc = crc16(d, 42);
        int enc = ((d[42] & 0xFF) << 8) | (d[43] & 0xFF);
        int[] trend = new int[7];
        int[] trendId = new int[7];
        for (int i = 0; i < 7; i++) {
            trend[i] = readBits(d, i * 4, 0, 0xe);
            trendId[i] = age - TREND_OFFSETS[i];
        }
        return new Result(calc == enc, age, trend, trendId);
    }

    /** Decrypt + parse in one step; returns null if the packet length is wrong. */
    public static Result extract(int[] id, byte[] packet) {
        if (packet == null || packet.length != 46) return null;
        return parse(decrypt(id, packet));
    }

    // --- serial -> UID (invert getserial base32, serial.cpp). selnum base32 alphabet. ---
    private static final String SELNUM = "0123456789ACDEFGHJKLMNPQRTUVWXYZ";

    /**
     * Derive the six UID bytes id[0..5] (all the cipher needs) from the displayed
     * sensor serial suffix (e.g. "3MH01L277F8"). Returns null if it can't decode.
     */
    public static int[] uidFromSerial(String serial) {
        if (serial == null) return null;
        // Use the last 11 chars (family + 10 base32) in case a prefix/dash is present.
        String s = serial.trim();
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(dash + 1);
        if (s.length() < 11) return null;
        s = s.substring(s.length() - 11);
        int[] c = new int[11];
        for (int i = 0; i < 11; i++) {
            int idx = SELNUM.indexOf(Character.toUpperCase(s.charAt(i)));
            if (idx < 0) return null;
            c[i] = idx;
        }
        int[] b = new int[6];
        b[5] = (c[1] << 3) | ((c[2] >> 2) & 7);
        b[4] = ((c[2] & 3) << 6) | (c[3] << 1) | ((c[4] >> 4) & 1);
        b[3] = ((c[4] & 15) << 4) | ((c[5] >> 1) & 15);
        b[2] = ((c[5] & 1) << 7) | (c[6] << 2) | ((c[7] >> 3) & 3);
        b[1] = ((c[7] & 7) << 5) | c[8];
        b[0] = (c[9] << 3) | ((c[10] >> 2) & 7);
        for (int i = 0; i < 6; i++) b[i] &= 0xFF;
        return b;
    }

    private static int[] buildCrcTable() {
        // Standard CRC16 (poly 0x8408 reflected) table, matching CRC.swift's crc16table.
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++)
                crc = ((crc & 1) != 0) ? ((crc >> 1) ^ 0x8408) : (crc >> 1);
            t[i] = crc & 0xFFFF;
        }
        return t;
    }

    private Libre2Raw() {}
}
