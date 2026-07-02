# FamilyHub Deployment and Debug Status

Last updated: 2026-07-02

This file records the known-good local test setup and the current Ubuntu cloud deployment. Keep it updated whenever ports, domains, deployment scripts, or Android endpoint configuration changes.

## Current Cloud Deployment

Production domain:

```text
streamforsoul.com
```

Public backend base URL used by Android release/cloud builds:

```text
https://streamforsoul.com:8443/familyhub/
```

Public LiveKit WebSocket URL returned by backend:

```text
wss://streamforsoul.com:8443/livekit/
```

Cloud server public IP detected during deployment:

```text
103.30.77.178
```

Cloud install paths:

```text
Uploaded backend source: /opt/FamilyHubBackend
Runtime install root:    /opt/familyhub
Runtime backend:         /opt/familyhub/backend
Runtime storage:         /opt/familyhub/storage
Runtime uploads:         /opt/familyhub/storage/uploads
Runtime env file:        /opt/familyhub/familyhub.env
LiveKit config:          /opt/familyhub/livekit/livekit.yaml
Management script:       /opt/familyhub/manage-familyhub.sh
```

Cloud systemd services:

```text
familyhub.service          FastAPI backend
familyhub-livekit.service  LiveKit server via Docker
nginx.service              Existing shared reverse proxy
```

Cloud internal ports:

```text
127.0.0.1:8891  FamilyHub FastAPI backend
0.0.0.0:7880    LiveKit HTTP/WebSocket, proxied by nginx /livekit
0.0.0.0:7881    LiveKit ICE TCP, public firewall required
0.0.0.0:7882    LiveKit ICE UDP, public firewall required
```

Cloud Nginx:

```text
Target site config: /etc/nginx/sites-available/livestream
Enabled site link:  /etc/nginx/sites-enabled/livestream
FamilyHub snippet: /etc/nginx/snippets/familyhub-locations.conf
HTTPS port:        8443
```

Nginx routes added for FamilyHub:

```text
/familyhub/  -> http://127.0.0.1:8891/
/livekit     -> http://127.0.0.1:7880
```

UFW/firewall state from deployment:

```text
UFW is active.
8443/tcp is allowed.
7881/tcp is allowed.
7882/udp is allowed.
```

The cloud provider security group must also allow:

```text
8443 TCP
7881 TCP
7882 UDP
```

Confirmed cloud checks:

```text
https://streamforsoul.com:8443/familyhub/health -> {"status":"ok","version":"0.3.0",...}
https://streamforsoul.com:8443/livekit/         -> HTTP 200 OK
familyhub.service                               -> active
familyhub-livekit.service                       -> active
nginx.service                                   -> active
```

## Cloud Management Commands

Run on the Ubuntu server:

```bash
bash /opt/familyhub/manage-familyhub.sh status
bash /opt/familyhub/manage-familyhub.sh start
bash /opt/familyhub/manage-familyhub.sh stop
bash /opt/familyhub/manage-familyhub.sh restart
bash /opt/familyhub/manage-familyhub.sh enable
bash /opt/familyhub/manage-familyhub.sh disable
bash /opt/familyhub/manage-familyhub.sh logs
bash /opt/familyhub/manage-familyhub.sh logs-livekit
bash /opt/familyhub/manage-familyhub.sh health
bash /opt/familyhub/manage-familyhub.sh ports
bash /opt/familyhub/manage-familyhub.sh nginx-test
bash /opt/familyhub/manage-familyhub.sh livekit-config
bash /opt/familyhub/manage-familyhub.sh fix-livekit-ip 103.30.77.178
```

To save resources when FamilyHub is not in use:

```bash
bash /opt/familyhub/manage-familyhub.sh stop
```

To stop it and prevent auto-start after reboot:

```bash
bash /opt/familyhub/manage-familyhub.sh disable
```

To turn it back on:

```bash
bash /opt/familyhub/manage-familyhub.sh enable
bash /opt/familyhub/manage-familyhub.sh start
```

Do not stop `nginx.service` for FamilyHub downtime unless intentionally taking down other existing services too.

## Cloud Deployment Scripts

Deployment scripts are stored inside the backend folder so copying `FamilyHubBackend` to the server is enough:

```text
FamilyHubBackend/deploy/ubuntu/deploy-familyhub.sh
FamilyHubBackend/deploy/ubuntu/manage-familyhub.sh
FamilyHubBackend/deploy/ubuntu/familyhub.deploy.conf.example
FamilyHubBackend/deploy/ubuntu/familyhub-nginx-locations.conf.template
FamilyHubBackend/deploy/ubuntu/update-android-cloud-url.ps1
```

Typical cloud deployment flow:

```bash
cd /opt/FamilyHubBackend
bash -n deploy/ubuntu/deploy-familyhub.sh
bash -n deploy/ubuntu/manage-familyhub.sh
bash deploy/ubuntu/deploy-familyhub.sh
```

For this server, use the script defaults unless the environment changes. Important defaults:

```text
DOMAIN=streamforsoul.com
PUBLIC_BASE_PATH=/familyhub
PUBLIC_BASE_URL=https://streamforsoul.com:8443/familyhub
LIVEKIT_PUBLIC_URL=wss://streamforsoul.com:8443/livekit/
CINEMA_BASE_URL=https://streamforsoul.com:8443/cinema/
NGINX_SITE_CONF=/etc/nginx/sites-available/livestream
BACKEND_PORT=8891
LIVEKIT_HTTP_PORT=7880
LIVEKIT_TCP_PORT=7881
LIVEKIT_UDP_PORT=7882
```

## Cinema Integration

The existing Cinema system is not modified by FamilyHub. FamilyHub only provides a login-protected launch bridge for the Android app:

```text
POST /familyhub/integrations/cinema/launch
GET  /familyhub/integrations/launch/{one_time_code}
```

Android calls the POST endpoint with its normal `X-Token`, receives a short-lived `launch_url`, then opens that URL in an in-app WebView. The backend consumes the one-time code and redirects into the existing Cinema URL with the existing Cinema watch token.

Cloud env values used by this bridge:

```text
FAMILYHUB_CINEMA_BASE_URL=https://streamforsoul.com:8443/cinema/
FAMILYHUB_AUTH_TOKENS_DB=/opt/auth/data/tokens.db
FAMILYHUB_AUTH_KEYS_DIR=/opt/auth/keys
FAMILYHUB_INTEGRATION_LAUNCH_TTL_SECONDS=120
```

Optional fallbacks are supported but should usually stay empty so tokens remain managed by the existing auth system:

```text
FAMILYHUB_CINEMA_WATCH_TOKEN=
FAMILYHUB_CINEMA_ADMIN_TOKEN=
FAMILYHUB_INTEGRATION_ADMINS=alice
```

If Cinema launch returns HTTP 503, first check that the existing auth system has an active Cinema watch token and that the FamilyHub backend user can read `/opt/auth/data/tokens.db`.

Cinema admin launch requires both:

```text
the current FamilyHub user nickname or id is listed in FAMILYHUB_INTEGRATION_ADMINS
an existing Cinema admin token exists at /opt/auth/keys/cinema_admin.key, or FAMILYHUB_CINEMA_ADMIN_TOKEN is set
```

### Android Cinema WebView Status

Cinema is currently integrated in the Android app as an authenticated in-app WebView, not as a native reimplementation of the Cinema frontend. This keeps the existing Cinema system untouched while allowing FamilyHub login users to enter Cinema without storing a long-lived Cinema token in the app.

Android implementation notes:

```text
FamilyHubApp/app/src/main/java/code/name/monkey/retromusic/activities/CinemaActivity.kt
FamilyHubApp/app/src/main/res/layout/activity_cinema_web.xml
FamilyHubApp/app/src/main/java/code/name/monkey/retromusic/fragments/familyhub/FamilyHubFeatureFragment.kt
```

Current Android Cinema behavior:

```text
Cinema tab now shows a choice page instead of auto-opening Cinema.
Watch Cinema opens normal Cinema viewing/library.
Cinema Admin opens the existing Cinema admin console if the FamilyHub account is allowed.
Both modes use the same dedicated full-screen CinemaActivity.
The Activity requests a one-time FamilyHub launch URL from the backend.
The WebView follows the launch URL into the existing Cinema site.
Mobile CSS fixes are injected from the app side only; Cinema source files are not modified.
Library scrolling has an Android WebView touch fallback because the embedded page can feel sticky on mobile.
The watch page receives a small Mobile viewing banner under the Cinema mode banner.
That banner exposes a Landscape/Portrait button through an Android Javascript bridge.
```

Verified on Android:

```text
Cinema launch from FamilyHub login works.
Cinema tab choice page works.
Cinema library opens.
Library scrolling works, though not perfectly smooth yet.
Video playback page opens.
Sync playback works.
Joining a room works.
Returning to the library works.
Leaving/exiting works.
Mobile viewing Landscape/Portrait button is visible on the watch page.
Cinema Admin entry is wired to /integrations/cinema/admin/launch and should be tested after cloud redeploy.
```

Known Android Cinema limitations:

```text
Scrolling is usable but still not fully native-smooth.
Danmaku and subtitle buttons were observed as unreliable in the WebView and are deferred.
The Cinema UI is still the existing desktop/web UI with app-side mobile patches, not a fully native Android UI.
```

Backend password hashing depends on `passlib` plus `bcrypt`. Keep `bcrypt` on 4.0.x because `passlib 1.7.x` is not compatible with `bcrypt 5.x`, and newer 4.x versions print a harmless backend-version warning:

```text
passlib>=1.7.4
bcrypt>=4.0.1,<4.1.0
```

This server must use an explicit LiveKit node IP. Do not rely on LiveKit public-IP auto-detection here; it has previously failed with `panic: invalid argument to Intn` and caused `/livekit/` to return nginx 502.

Expected `/opt/familyhub/livekit/livekit.yaml`:

```yaml
port: 7880
bind_addresses:
  - 0.0.0.0
rtc:
  node_ip: 103.30.77.178
  tcp_port: 7881
  udp_port: 7882
  use_external_ip: false
keys:
  familyhub: <secret>
```

If LiveKit ever falls back to `use_external_ip: true` or starts crashing, run:

```bash
bash /opt/familyhub/manage-familyhub.sh fix-livekit-ip 103.30.77.178
```

## Local Development Setup

Local Windows workspace:

```text
C:\Users\18408\Desktop\Git\FamilyHub
```

Local backend path:

```text
C:\Users\18408\Desktop\Git\FamilyHub\FamilyHubBackend
```

Local Android app path:

```text
C:\Users\18408\Desktop\Git\FamilyHub\FamilyHubApp
```

Local LiveKit server binary:

```text
C:\Users\18408\Desktop\Git\tools\livekit-server.exe
```

Local services and ports:

```text
127.0.0.1:8000  FamilyHub FastAPI backend
0.0.0.0:7880    Local LiveKit HTTP/WebSocket
0.0.0.0:7881    Local LiveKit ICE TCP
0.0.0.0:7882    Local LiveKit ICE UDP
```

Android emulator reaches the Windows host with:

```text
10.0.2.2
```

Local Android backend URL:

```text
http://10.0.2.2:8000
```

Local backend LiveKit URL returned to emulator:

```text
ws://10.0.2.2:7880
```

Local LiveKit dev credentials:

```text
FAMILYHUB_LIVEKIT_API_KEY=devkey
FAMILYHUB_LIVEKIT_API_SECRET=secret
```

Local test accounts:

```text
alice / pass1234
bob   / pass1234
```

Local backend start command:

```powershell
$Backend = "C:\Users\18408\Desktop\Git\FamilyHub\FamilyHubBackend"
cd $Backend
$env:FAMILYHUB_LIVEKIT_URL='ws://10.0.2.2:7880'
$env:FAMILYHUB_LIVEKIT_API_KEY='devkey'
$env:FAMILYHUB_LIVEKIT_API_SECRET='secret'
$env:FAMILYHUB_LIVE_ROOM_STALE_SECONDS='600'
$env:FAMILYHUB_LIVE_ROOM_CLEANUP_INTERVAL_SECONDS='30'
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Local LiveKit start command:

```powershell
& "C:\Users\18408\Desktop\Git\tools\livekit-server.exe" --dev --bind 0.0.0.0
```

## Android Endpoint Switching

The Android endpoint is currently stored in:

```text
FamilyHubApp/app/src/main/java/code/name/monkey/retromusic/SyncTuneConfig.kt
```

Local emulator value:

```kotlin
const val SERVER_BASE_URL = "http://10.0.2.2:8000"
```

Cloud value:

```kotlin
const val SERVER_BASE_URL = "https://streamforsoul.com:8443/familyhub/"
```

To switch to cloud from the repo root on Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\FamilyHubBackend\deploy\ubuntu\update-android-cloud-url.ps1 -ServerBaseUrl "https://streamforsoul.com:8443/familyhub/"
```

To switch back to local emulator:

```powershell
powershell -ExecutionPolicy Bypass -File .\FamilyHubBackend\deploy\ubuntu\update-android-cloud-url.ps1 -ServerBaseUrl "http://10.0.2.2:8000"
```

After switching, rebuild the APK:

```powershell
cd C:\Users\18408\Desktop\Git\FamilyHub\FamilyHubApp
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Debug APK:

```text
FamilyHubApp/app/build/outputs/apk/normal/debug/app-normal-debug.apk
```

## Verified Local Live Features

The following were verified locally before cloud deployment:

```text
alice creates a room
bob sees alice's room from the live list
bob joins as viewer
camera live video is published and viewed on physical phones
screen sharing is published and viewed on physical phones
alice End causes bob to auto-exit
bob manual exit refreshes the room list
ended rooms disappear from backend list
stale rooms are cleaned through heartbeat timeout
```

Emulator camera rendering is not considered a reliable final test. Real video must be tested on physical phones against the cloud deployment.

## Live Quality Presets

Mobile live currently supports camera, microphone, and Android screen sharing.

Room creation accepts these quality values:

```text
ultra
high
hd
standard
smooth
original    legacy alias, treated as high by Android
```

Camera presets:

```text
ultra      1080p60  8 Mbps
high       1080p30  5 Mbps
hd          720p60  4 Mbps
standard   720p30  2 Mbps
smooth     480p24  900 Kbps
```

Screen-share presets:

```text
ultra      1440p30  10 Mbps
high       1080p60  8 Mbps
hd         1080p30  5 Mbps
standard   720p30  2.5 Mbps
smooth     720p15  1.5 Mbps
```

Important deployment note: Android cloud builds using the quality picker require the cloud backend to be updated too. Older backend code may reject new values such as `ultra`, `high`, `hd`, and `standard` with HTTP 422.

The host can also change quality while a live room is running. Runtime quality switching:

```text
PATCH /live/rooms/{room_id}/quality
body: {"quality":"ultra|high|hd|standard|smooth|original"}
host only
```

Android applies the new preset by updating LiveKit capture/publish defaults, then restarting the local camera or screen-share track. Viewers keep watching through LiveKit and poll room status to notice the updated room metadata.

## Current Limitations

Quality presets are client-side capture and publish defaults. The backend records and validates the chosen quality, but it does not transcode video. Cloud CPU is therefore not used for quality conversion.

## Common Debug Commands

Cloud backend:

```bash
curl -fsS https://streamforsoul.com:8443/familyhub/health
curl -fsS http://127.0.0.1:8891/health
journalctl -u familyhub -n 120 --no-pager -l
```

Cloud LiveKit:

```bash
curl -i --max-time 10 https://streamforsoul.com:8443/livekit/
journalctl -u familyhub-livekit -n 120 --no-pager -l
sudo cat /opt/familyhub/livekit/livekit.yaml
```

Cloud Nginx:

```bash
sudo nginx -t
sudo cat /etc/nginx/snippets/familyhub-locations.conf
sudo nginx -T | grep -n "familyhub\|livekit" -A 20 -B 20
```

Cloud ports:

```bash
sudo ss -lntup | grep -E ':(8891|7880|7881|7882)\b'
sudo ufw status verbose | grep -E '7881|7882|8443|Status'
```

Android logs:

```powershell
adb logcat -d -t 300 | Select-String -Pattern "LiveRoomActivity","LiveKit","AndroidRuntime","Camera","heartbeat"
```

Android Cinema logs:

```powershell
adb logcat -d -t 300 | Select-String -Pattern "CinemaActivity","WebView","chromium","InputDispatcher","AndroidRuntime"
```

## Next Integration Plan

Do not modify the existing `LiveStreamCinemaAndAuthSystem/cinema` or `livestream` services unless a later task explicitly approves a coordinated change. FamilyHub should keep treating them as working external systems and integrate through configured URLs, nginx routes, and short-lived FamilyHub launch bridges.

### Livestream Audit

The existing `LiveStreamCinemaAndAuthSystem/livestream` service has been inspected read-only. It is a Flask/SRS live room app, separate from FamilyHub native LiveKit rooms.

Observed existing livestream entry points:

```text
/watch?token=...       production OBS/SRS live viewing page
/test-watch?token=...  test OBS/SRS live viewing page
/admin?token=...       admin/control page
/monitor?token=...     stream monitor page
```

Observed auth behavior:

```text
watch pages verify auth watch tokens through the shared auth system
publish/admin routes verify stream/admin tokens through the shared auth system
rooms are named live and test in the shared auth database
playback uses SRS HTTP-FLV/HLS paths, not LiveKit
```

Recommended FamilyHub approach:

```text
Add a FamilyHub-authenticated launch bridge similar to Cinema.
Read an active existing auth watch token for room=live or room=test.
Issue a short-lived one-time FamilyHub launch URL.
Redirect into the existing livestream /watch or /test-watch URL with the existing watch token.
Open that URL in an Android WebView.
Keep the existing livestream Flask/SRS code unchanged.
```

FamilyHub implementation added:

```text
POST /familyhub/integrations/livestream/launch?env=live
POST /familyhub/integrations/livestream/launch?env=test
```

Cloud/env values:

```text
FAMILYHUB_LIVESTREAM_BASE_URL=https://streamforsoul.com:8443/
FAMILYHUB_LIVESTREAM_WATCH_TOKEN=
FAMILYHUB_LIVESTREAM_TEST_WATCH_TOKEN=
```

The watch token fallbacks should usually stay empty. FamilyHub should read active `room=live` or `room=test` watch tokens from `/opt/auth/data/tokens.db`.

Android implementation:

```text
FamilyHubApp/app/src/main/java/code/name/monkey/retromusic/activities/LivestreamActivity.kt
More tab -> OBS Livestream -> existing livestream /watch page in a WebView
```

Recommended next order:

```text
1. Keep Cinema in maintenance mode.
   Only fix mobile WebView issues that block watching.

2. Integrate existing livestream viewing.
   Goal: let the Android app watch the already-working OBS/SRS livestream path.
   Preferred approach: add a FamilyHub-authenticated launch bridge similar to Cinema.
   Avoid rewriting the existing livestream service.

3. Integrate music after livestream viewing is stable.
   Preserve the original local music player behavior.
   Then add cloud upload/list/sync playback flows in small steps.

4. Revisit native UI replacement only after web integrations prove the workflows.
   Cinema/livestream can stay WebView-backed while the app shell, auth, and navigation stabilize.
```
