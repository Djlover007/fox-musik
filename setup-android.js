// Wird beim Bauen automatisch ausgeführt (siehe package.json, "capacitor:sync:before").
// Kopiert die Suche für Musik in das Android-Projekt und trägt die Berechtigung ein.
const fs = require('fs');
const path = require('path');

const dir = path.join('android', 'app', 'src', 'main', 'java', 'de', 'foxmusik', 'app');
if (!fs.existsSync(dir)) {
  console.error('FEHLER: Ordner nicht gefunden: ' + dir);
  process.exit(1);
}
for (const f of ['MainActivity.java', 'MediaScanPlugin.java']) {
  fs.copyFileSync(f, path.join(dir, f));
  console.log('kopiert: ' + f);
}

const manifest = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifest, 'utf8');
if (!xml.includes('READ_MEDIA_AUDIO')) {
  xml = xml.replace(
    '</manifest>',
    '    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />\n' +
    '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n' +
    '</manifest>'
  );
  fs.writeFileSync(manifest, xml);
  console.log('Berechtigungen eingetragen');
}
