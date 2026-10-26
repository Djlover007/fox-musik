// Wird vom GitHub-Workflow nach "npx cap add android" ausgeführt.
// Kopiert die Java-Dateien ins Android-Projekt (egal wie das Paket heißt),
// trägt Berechtigungen + Dienst ein und prüft, dass die Plugins wirklich registriert sind.
const fs = require('fs');
const path = require('path');

function findFile(dir, name) {
  if (!fs.existsSync(dir)) return null;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { const r = findFile(p, name); if (r) return r; }
    else if (e.name === name) return p;
  }
  return null;
}

const javaRoot = path.join('android', 'app', 'src', 'main', 'java');
const generated = findFile(javaRoot, 'MainActivity.java');
if (!generated) {
  console.error('FEHLER: MainActivity.java im Android-Projekt nicht gefunden (' + javaRoot + ')');
  process.exit(1);
}
const dir = path.dirname(generated);
const pkg = (fs.readFileSync(generated, 'utf8').match(/^\s*package\s+([\w.]+)\s*;/m) || [])[1];
if (!pkg) { console.error('FEHLER: Paketname nicht lesbar'); process.exit(1); }
console.log('Android-Paket: ' + pkg + ' (' + dir + ')');

for (const f of ['MainActivity.java', 'MediaScanPlugin.java', 'PlaybackPlugin.java', 'PlaybackService.java']) {
  const src = fs.readFileSync(f, 'utf8').replace(/^\s*package\s+[\w.]+\s*;/m, 'package ' + pkg + ';');
  fs.writeFileSync(path.join(dir, f), src);
  console.log('kopiert: ' + f);
}
const main = fs.readFileSync(path.join(dir, 'MainActivity.java'), 'utf8');
if (!main.includes('registerPlugin(MediaScanPlugin.class)') || !main.includes('registerPlugin(PlaybackPlugin.class)')) {
  console.error('FEHLER: Plugins sind in MainActivity nicht registriert');
  process.exit(1);
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
    '    <service android:name="' + pkg + '.PlaybackService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    </application>'
  );
  console.log('Wiedergabe-Dienst eingetragen');
}
fs.writeFileSync(manifest, xml);
console.log('OK: Musik-Suche und Wiedergabe-Dienst eingebaut');
