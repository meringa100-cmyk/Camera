# 360Eyes Duo — bouwen vanaf je telefoon

1. Maak/open een GitHub-repository.
2. Upload alle bestanden uit deze map naar de repository.
3. Open **Actions**.
4. Kies **Build 360Eyes Duo APK**.
5. Tik **Run workflow**.
6. Wacht tot de build groen is.
7. Open de workflow-run en download artifact **360Eyes-Duo-debug**.
8. Pak de ZIP uit en installeer `app-debug.apk` op je Samsung.

De APK gebruikt rechtstreeks RTSP:
- Camera 1: 192.168.2.26
- Camera 2: 192.168.2.27
- Pad: /cam/realmonitor?channel=0&subtype=1

De telefoon moet voor het livebeeld op hetzelfde wifi-netwerk als de camera's zitten.
