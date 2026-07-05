/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */

#pragma once
// Diagnostic: store of per-minute RAW glucose recovered from the Libre 2 BLE stream
// (ported decryption in Java's Libre2Raw -> JNI storeStreamRaw). Kept separate from
// the mmap'd Glucose slots so it never conflicts with stream/scan saves or the
// valid()-gated calibrated-curve logic; the curve overlay draws teal dots straight
// from here.
//
// Persisted per-sensor: each point is appended to "perminraw.dat" in the sensor's
// own directory (where the other sensor data lives), and loadPerminRaw() reloads it
// into memory when streaming (re)starts, so the overlay survives app restarts. The
// per-sensor file means a sensor swap can't mix data (different directory).
#include <map>
#include <mutex>
#include <string>
#include <cstdint>
#include <cstdio>
#include <android/log.h>

struct PerminRawPt { uint32_t time; uint16_t raw; };

// minute id (sensor lifecount) -> {wall time, raw}. Keyed (and thus ordered) by id,
// which is monotonic with wall time within one sensor. Bounded to the most recent
// entries. Cleared when a new sensor is detected (id resets low) so eviction never
// throws away the fresh sensor's points.
inline std::map<int, PerminRawPt> g_perminRaw;
inline std::mutex                 g_perminRawMutex;
inline int                        g_perminMaxId = -1;
inline std::string                g_perminRawPath;   // per-sensor file; empty = no persistence

namespace perminraw_detail {
    constexpr size_t maxpts = 4500;   // ~3 days of per-minute points
    // On-disk record: int32 id, uint32 time, uint16 raw (native byte order; the file
    // is private to this device so portability isn't a concern).
    inline void writeRecord(FILE *f, int id, uint32_t time, uint16_t raw) {
        fwrite(&id, sizeof(int), 1, f);
        fwrite(&time, sizeof(uint32_t), 1, f);
        fwrite(&raw, sizeof(uint16_t), 1, f);
    }
}

inline void storePerminRaw(int id, uint32_t time, uint16_t raw) {
    if(!raw || !time || id < 0) return;
    std::string path;
    {
        std::lock_guard<std::mutex> lk(g_perminRawMutex);
        // A new sensor restarts its lifecount near 0: a large drop in id means the
        // existing entries belong to a previous sensor - drop them so erase(begin())
        // (which removes the smallest id) keeps evicting the genuinely oldest minute.
        constexpr int resetgap = 120;
        if(g_perminMaxId >= 0 && id < g_perminMaxId - resetgap) {
            g_perminRaw.clear();
            g_perminMaxId = -1;
            }
        if(id > g_perminMaxId) g_perminMaxId = id;
        g_perminRaw[id] = {time, raw};
        while(g_perminRaw.size() > perminraw_detail::maxpts) g_perminRaw.erase(g_perminRaw.begin());
        path = g_perminRawPath;   // snapshot so the file append below runs outside the lock
        }
    // Append (10 bytes, once per packet) outside the map lock so the render thread is
    // never blocked on disk I/O. Growth is bounded by the compaction in loadPerminRaw.
    if(!path.empty()) {
        FILE *f = fopen(path.c_str(), "ab");
        if(f) { perminraw_detail::writeRecord(f, id, time, raw); fclose(f); }
        }
}

// Load a per-sensor file into the map (replacing current contents) and compact it on
// disk. Called when streaming (re)starts so the overlay is present right after an app
// restart, before the first fresh packet arrives.
inline void loadPerminRaw(const char *path) {
    if(!path || !*path) return;
    std::lock_guard<std::mutex> lk(g_perminRawMutex);
    g_perminRaw.clear();
    g_perminMaxId = -1;
    g_perminRawPath = path;
    FILE *f = fopen(path, "rb");
    if(!f) {
        __android_log_print(ANDROID_LOG_ERROR, "JuggSensor", "perminraw: no persisted file yet (%s)", path);
        return;
        }
    size_t recordsRead = 0;
    int id; uint32_t t; uint16_t raw;
    while(fread(&id, sizeof(int), 1, f) == 1
       && fread(&t, sizeof(uint32_t), 1, f) == 1
       && fread(&raw, sizeof(uint16_t), 1, f) == 1) {
        ++recordsRead;
        if(raw && t && id >= 0) {
            g_perminRaw[id] = {t, raw};
            if(id > g_perminMaxId) g_perminMaxId = id;
            }
        }
    fclose(f);
    while(g_perminRaw.size() > perminraw_detail::maxpts) g_perminRaw.erase(g_perminRaw.begin());
    __android_log_print(ANDROID_LOG_ERROR, "JuggSensor",
            "perminraw: loaded %zu points (%zu records) from %s",
            g_perminRaw.size(), recordsRead, path);
    // Compact only once the append-only log has grown well past the live set (it is
    // reloaded on every reconnect, so don't rewrite the whole file each time). This
    // bounds the file at ~2x the live size while keeping reconnects cheap.
    if(recordsRead > 2 * perminraw_detail::maxpts && !g_perminRaw.empty()) {
        if(FILE *w = fopen(path, "wb")) {
            for(const auto &kv : g_perminRaw)
                perminraw_detail::writeRecord(w, kv.first, kv.second.time, kv.second.raw);
            fclose(w);
            }
        }
}
