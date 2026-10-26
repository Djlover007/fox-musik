// Wird vom GitHub-Workflow nach "npx cap add android" ausgeführt.
// Kopiert die Java-Dateien ins Android-Projekt und trägt Berechtigungen + Dienst ein.
const fs = require('fs');
const path = require('path');

const dir = path.join('android', 'app', 'src', 'main', 'java', 'de', 'foxmusik', 'app');
if (!fs.existsSync(dir)) {
  console.error('FEHLER: Ordner nicht gefunden: ' + dir);
  process.exit(1);
}
for (const f of ['MainActivity.java', 'MediaScanPlugin.java', 'PlaybackPlugin.java', 'PlaybackService.java']) {
  fs.copyFileSync(f, path.join(dir, f));
  console.log('kopiert: ' + f);
}

const manifest = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(manifest, 'utf8');
const perms = [
  '<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />',
  '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />',
  '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
  '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />',
  '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
  '<uses-permission android:name="android.permission.WAKE_LOCK" />'
];
const missing = perms.filter(p => !xml.includes(p.match(/android:name="([^"]+)"/)[1]));
if (missing.length) {
  xml = xml.replace('</manifest>', missing.map(p => '    ' + p + '\n').join('') + '</manifest>');
  console.log('Berechtigungen eingetragen: ' + missing.length);
}
if (!xml.includes('PlaybackService')) {
  xml = xml.replace(
    '</application>',
    '    <service android:name="de.foxmusik.app.PlaybackService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>'
  );
  console.log('Wiedergabe-Dienst eingetragen');
}
fs.writeFileSync(manifest, xml);
