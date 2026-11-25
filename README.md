# Wallpanel Kamera

Version 2.0. Kleine Android-App für das rk3566-Wallpanel. Sie sendet die Frontkamera als RTSP-Stream und lässt sich über einen MQTT-Schalter in Home Assistant ein- und ausschalten.

Es gibt kein Launcher-Icon. Die MQTT-Daten und die Bildrate (5, 10, 15 oder 20) öffnest du über die Benachrichtigung „Wallpanel Kamera“. Ohne Auswahl bleibt der Stream bei 20 Bildern pro Sekunde. Das Passwort wird nur auf dem Gerät gespeichert.

Bauen:

```bash
./build.sh
```
