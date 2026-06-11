# LifeTrace

Personal life-tracking Android client for a self-hosted **life-monitor** hub.

A lightweight foreground-service app that samples your own device signals and posts them to
your own server (over Tailscale), and surfaces a nightly AI check-in + insights. Built for
quantified-self / healthy-habit tracking on your own hardware. No third-party servers.

## What it collects (Standard tier)
- App screen-time: per-app foreground seconds + open counts (`UsageStatsManager`)
- Location (periodic, low-power last-known fix)
- Network context (wifi SSID / connection type)
- Battery state

All of it goes only to the `BASE_URL` you configure — your own life-monitor instance.

## Screens
- **Status** - grant permissions, start background tracking, upload now
- **Check-in** - answer the 5 nightly questions your agent generates
- **Insights** - read the daily summaries / nudges your agent writes

## Build
1. `cp secrets.properties.example secrets.properties` and fill in `BASE_URL` + `INGEST_TOKEN`.
2. `./gradlew assembleDebug`
3. APK at `app/build/outputs/apk/debug/app-debug.apk`.

Toolchain: AGP 8.2.2, Kotlin 1.9.22, Gradle 8.5, compileSdk 34, minSdk 26 (Compose).
`secrets.properties` and `local.properties` are gitignored — no credentials live in this repo.

## Install
Sideload the APK, or add this repo to [Obtainium](https://github.com/ImranR98/Obtainium)
to auto-update from releases. After install, open the app and grant Usage Access, Location,
Notifications, and disable battery optimization, then tap "Start background tracking".

## Companion
The server side (ingest API, SQLite store, nightly self-evolving agent, dashboard) is the
`life-monitor` project, which runs on the same machine that hosts `BASE_URL`.
