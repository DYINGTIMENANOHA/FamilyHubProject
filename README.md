# FamilyHub

FamilyHub is an integrated self-hosted family/friends service app. This repository contains the Android app and the unified backend that will replace the former SyncTune-only prototype.

## Layout

- `FamilyHubApp/`: Android client. Music remains as one module, while live streaming, cinema, account, and future feature pages become first-class app areas.
- `FamilyHubBackend/`: FastAPI backend for accounts, devices, music, live rooms, cinema integration, and admin commands.

## Integration Boundary

Existing external systems such as `LiveStreamCinemaAndAuthSystem` are not modified from this repository. FamilyHub should integrate them through configured URLs, reverse-proxy paths, or read-only references unless a later step explicitly requires coordinated changes.

## Media Direction

- Native mobile live rooms: LiveKit/WebRTC, optimized for Android camera and screen streaming.
- Legacy/external live streams: SRS + ffmpeg, exposed to the app through FamilyHubBackend and nginx-backed HTTPS URLs.
- Cinema: integrated as an app module after the FamilyHub app shell and account system are stable.

## Current Migration Stage

The app shell, FamilyHub backend account flow, native LiveKit rooms, Android camera/screen sharing, cloud deployment scripts, and Cinema launch bridge are now in active test shape.

See `DEPLOYMENT_STATUS.md` for the current known-good local/cloud configuration, Android endpoint switching commands, service management commands, verified live-room behavior, and the Cinema WebView integration status.
