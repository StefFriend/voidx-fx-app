# VoidX FX (Android)

App di controllo per il firmware FW1 "VX AMP" del VoidX DSPModule, via Bluetooth LE.
L'interfaccia è la stessa pagina servita dalla scheda in Wi-Fi (`app/src/main/assets/index.html`,
copiata da `nam1/main/web/index.html` del firmware); le richieste `/api/...` passano dal Bluetooth.

- Sulla scheda: IN/OUT/SISTEMA → Connessione = **Bluetooth**.
- La app cerca il dispositivo "VoidX-FX" (servizio 9f1c0001-5658-4658-9a3b-2c9d0a6e1f10) e si collega.
- Compilazione: GitHub Actions (`.github/workflows/build.yml`) produce `app-debug.apk` tra gli artifact.
