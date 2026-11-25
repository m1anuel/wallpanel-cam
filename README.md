# Wallpanel Kamera

Kleine Android-App für das rk3566-Wallpanel. Sie sendet die Frontkamera als RTSP-Stream und lässt sich über einen MQTT-Schalter in Home Assistant ein- und ausschalten.

Es gibt kein Launcher-Icon. Die MQTT-Daten öffnest du über die Benachrichtigung „Wallpanel Kamera“. Das Passwort wird nur auf dem Gerät gespeichert.

Bauen:

```bash
./build.sh
```
