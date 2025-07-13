# Hibiscus-Sync-Plugin für diverse Banken
Unterstützung für den Umsatz- und Saldoabruf bei folgenden Banken

 - American Express Kreditkarte, getestet mit Payback-AMEX-Karte
 - Hanseatic Bank Kreditkarten, getestet mit AWA7
 - BBVA Girokonto (nur Umsatzabruf)

## Einrichtung
Entweder das Plugin als ZIP-File aus den Github-Releases in Hibiscus importieren, oder in den Jameica-Einstellungen unter *Plugins / Repositories bearbeiten* die URL https://www.gnampf.cyou/hibiscus ergänzen und unter *verfügbare Plugins* auswählen. Beim ersten Mal muss das Zertifikat akzeptiert werden, daher hier zum Abgleich:
- Common Name (CN) *syncusgnampfus updates*
- Organisation (O) *gnampf*
- SHA256: 24:EC:21:F3:F3:B4:60:EF:1F:87:22:E0:CC:D3:A5:66:7F:0A:C8:4C:D2:6F:AA:B1:1E:8B:A0:FF:53:77:A1:BB
- SHA1: 79:F6:57:C5:FB:45:AA:D1:4C:59:ED:B1:16:AD:59:82:BE:18:FB:8F
  
Konten müssen von Hand angelegt werden, dafür unter **Konten** auf **Konto manuell anlegen** gehen. Keinen Haken bei **Offline-Konto** setzen!

### American Express
hier muss nur die Kundenkennung mit dem Benutzernamen fürs Onlinebanking gefüllt und im Dropdown **Zugangsweg** *AMEX* ausgewählt werden. Die restlichen Felder können frei vergeben werden.
In den Synchronisationsoptionen des Kontos kann für die "TAN-Verfahren" eine Reihenfolge vergeben werden. Das erste gefundene Verfahren wird dann verwendet. Standard ist *ESA* für erst Email, dann SMS, dann App (ja, ich kann Banking-Apps nicht leiden!). Durch Umstellen der Buchstaben sind andere Prios möglich.

### Hanseatic Bank
Kundenkennung muss der Benutzerkennung fürs Onlinebanking entsprechen, IBAN der IBAN für die Kreditkarte (im Onlinebanking unter *Menü / Meine Kreditkarte* links unten zu finden. BLZ & Kontonummer werden damit automatisch gefüllt. Unter **Zugangsweg** *HanseaticBank* auswählen.

### BBVA
Kundenkennung muss hier dem Benutzernamen fürs Onlinebanking entsprechen, IBAN der IBAN fürs Konto. BLZ & Kontonummer werden damit automatisch gefüllt. Unter **Zugangsweg** *BBVA* auswählen.

## Wechsel von Mashup
Sowohl die Hanseatic-Kreditkarten-Konten als auch die American Express-Konten, die mit Mashup angelegt wurden, können weiter verwendet werden. Dazu in den Einstellungen vom Konto die Zugangsart von *non-HBCI (Hibiscus-Mashup)* auf *AMEX* bzw. *HanseaticBank* ändern. Im Falle von Amex vor dem ersten Rundruf noch in die Synchronisierungsoptionen des Kontos gehen und sicherstellen das ein Haken bei *Inhalte statt TransaktionsId vergleichen (Daten+bernahme von Mashup)* gesetzt ist, da andernfalls Duplikate angelegt werden. Der Haken wird nach dem ersten erfolgreichen Sync entfernt und nicht mehr benötigt, da ab jetzt die eindeutigen Transaktions-IDs gepflegt sind

## Bekannte Probleme
- Unter seinem richtigen Namen wird das Plugin leider nach Mashup & Co geladen, und hat dann mit veralteten Abhängigkeiten zu kämpfen. Bis zum Fix einfach das Verzeichnis SyncusGnampfus im Jamecia-Plugins-Verzeichnis in aSyncusGnampfus umbenennen und Jameica neu starten. 
- AMEX wurde nicht mit der App getestet. Kann klappen, muss aber nicht. Evtl. kracht es schon wenn die App registriert ist, bitte testen und Feedback geben
- AMEX unterstützt nur eine Karte je Account. Was passiert wenn in einem Account mehrere Karten hinterlegt sind? Findets heraus und berichtet ;-)
- bei Umstellung von Mashup kann es bei der Hanseaticbank noch einmalig zu Doppeleinträgen kommen
- Hanseaticbank verlangt alle paar Monate bei der Anmeldung eine SMS-Tan, das Plugin kann die aktuell noch nicht liefern (sondern nur beim Abruf älterer Umsätze). Einfach einmalig per Browser ins Onlinebanking einloggen, dann funktioniert das Plugin wieder

