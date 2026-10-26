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

for (const f of ['MainActivity.java', 'MediaScanPlugin.java', 'PlaybackPlugin.java', 'PlaybackService.java', 'UpdatePlugin.java']) {
  const src = fs.readFileSync(f, 'utf8').replace(/^\s*package\s+[\w.]+\s*;/m, 'package ' + pkg + ';');
  fs.writeFileSync(path.join(dir, f), src);
  console.log('kopiert: ' + f);
}
const main = fs.readFileSync(path.join(dir, 'MainActivity.java'), 'utf8');
if (!main.includes('registerPlugin(MediaScanPlugin.class)') || !main.includes('registerPlugin(PlaybackPlugin.class)') || !main.includes('registerPlugin(UpdatePlugin.class)')) {
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
  '<uses-permission android:name="android.permission.WAKE_LOCK" />',
  '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />'
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
if (!xml.includes('foxupdate')) {
  xml = xml.replace(
    '</application>',
    '    <provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.foxupdate" android:exported="false" android:grantUriPermissions="true">\n' +
    '        <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/fox_paths" />\n' +
    '    </provider>\n    </application>'
  );
  console.log('Update-Dateifreigabe eingetragen');
}
fs.writeFileSync(manifest, xml);

const res = path.join('android', 'app', 'src', 'main', 'res');
fs.mkdirSync(path.join(res, 'xml'), { recursive: true });
fs.writeFileSync(path.join(res, 'xml', 'fox_paths.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n<paths>\n    <cache-path name="update" path="." />\n</paths>\n');

// ---- Versionsnummer = Build-Nummer (nötig, damit Android das Update annimmt) ----
const bn = process.env.BUILD_NUMBER;
if (bn) {
  const gradle = path.join('android', 'app', 'build.gradle');
  const g = fs.readFileSync(gradle, 'utf8');
  const g2 = g.replace(/versionCode\s+\d+/, 'versionCode ' + bn).replace(/versionName\s+"[^"]*"/, 'versionName "1.' + bn + '"');
  if (g2 === g) { console.error('FEHLER: versionCode in build.gradle nicht gefunden'); process.exit(1); }
  fs.writeFileSync(gradle, g2);
  console.log('Version: ' + bn);
}

// ---- Fuchs-Icon (Ordner "icons") ----
if (fs.existsSync('icons')) {
  for (const d of fs.readdirSync('icons', { withFileTypes: true })) {
    if (!d.isDirectory()) continue;
    const to = path.join(res, d.name);
    fs.mkdirSync(to, { recursive: true });
    for (const f of fs.readdirSync(path.join('icons', d.name))) {
      fs.copyFileSync(path.join('icons', d.name, f), path.join(to, f));
    }
  }
  fs.mkdirSync(path.join(res, 'values'), { recursive: true });
  fs.writeFileSync(path.join(res, 'values', 'fox_colors.xml'),
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="fox_icon_bg">#2C66F5</color>\n</resources>\n');
  const adaptive =
    '<?xml version="1.0" encoding="utf-8"?>\n' +
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n' +
    '    <background android:drawable="@color/fox_icon_bg"/>\n' +
    '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n' +
    '</adaptive-icon>\n';
  fs.mkdirSync(path.join(res, 'mipmap-anydpi-v26'), { recursive: true });
  fs.writeFileSync(path.join(res, 'mipmap-anydpi-v26', 'ic_launcher.xml'), adaptive);
  fs.writeFileSync(path.join(res, 'mipmap-anydpi-v26', 'ic_launcher_round.xml'), adaptive);
  console.log('Fuchs-Icon eingebaut');
} else {
  console.error('FEHLER: Ordner "icons" fehlt. Schritt "Fuchs-Icon erzeugen" (make-icons.py) hat keine Bilder angelegt.');
  process.exit(1);
}
console.log('OK: Musik-Suche und Wiedergabe-Dienst eingebaut');
