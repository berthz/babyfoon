# Babyfoon (2 Android-telefoons via wifi)

Eén app voor beide telefoons. Op de ene kies je **BABY**, op de andere **OUDERS**.

## Hoe het werkt
- **Baby-telefoon**: neemt continu de microfoon op (16 kHz mono) en stuurt het via TCP (poort 50505)
  naar de ouder-telefoon. Kondigt zich elke seconde aan via UDP-broadcast (poort 50506).
- **Ouder-telefoon**: zoekt de baby-telefoon automatisch (of via ingevuld IP), speelt het geluid af,
  verbindt zelf opnieuw, en **piept + trilt** als de verbinding wegvalt (herhaalt elke minuut).
- Beide draaien als *foreground service* met wake lock en wifi lock, dus ze blijven werken met
  vergrendeld scherm. Er staat een permanente melding zolang de babyfoon actief is.

## Bouwen
Open de map `babyfoon` in Android Studio en kies *Build > Build APK(s)*.
Of op de commandline (JDK 17 + Android SDK nodig): `gradle assembleDebug`.
De APK staat dan in `app/build/outputs/apk/debug/app-debug.apk`.

## Installeren en gebruiken
1. Zet de APK op beide telefoons en installeer (sta "onbekende apps" toe).
2. Beide telefoons op **hetzelfde wifi-netwerk**.
3. Open de app, geef toestemming voor microfoon en meldingen.
4. Druk op beide telefoons op **"Batterijbesparing uitzetten voor deze app"**.
5. Baby-telefoon: **"Dit is de BABY-telefoon"**. Ouder-telefoon: **"Dit is de OUDER-telefoon"**.
6. Vergrendel gerust beide schermen; liefst beide aan de oplader.

Werkt automatisch zoeken niet (sommige routers/gastnetwerken blokkeren broadcasts of verkeer
tussen apparaten), vul dan op de ouder-telefoon het IP-adres in dat op de baby-telefoon staat.

### Samsung / Xiaomi / Huawei e.d.
Deze merken sluiten apps soms toch af. Zet extra:
- Samsung: Instellingen > Batterij > Achtergrondgebruik > Babyfoon op *Nooit in slaapstand*.
- Xiaomi: App-info > Autostart aan, Batterijbesparing *Geen beperkingen*.
Zie ook https://dontkillmyapp.com.
