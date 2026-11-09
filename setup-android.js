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

for (const f of ['MainActivity.java', 'MediaScanPlugin.java', 'PlaybackPlugin.java', 'PlaybackService.java', 'UpdatePlugin.java', 'SpotifyLoginActivity.java', 'NeowakeActivity.java', 'NwProxy.java', 'RadioProxy.java', 'FoxSession.java', 'FoxWidget.java', 'FoxBrowserService.java', 'FoxAlarm.java', 'RadioRec.java', 'FoxWidgetLarge.java', 'PartyServer.java']) {
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
  '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />',
  '<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />',
  '<uses-permission android:name="android.permission.USE_EXACT_ALARM" />',
  '<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />',
  '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />'
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
if (!xml.includes('com.spotify.music')) {
  xml = xml.replace('</manifest>', '    <queries>\n        <package android:name="com.spotify.music" />\n        <intent><action android:name="android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL" /></intent>\n    </queries>\n</manifest>');
  console.log('Spotify-Sichtbarkeit eingetragen');
}
if (!xml.includes('FoxBrowserService')) {
  xml = xml.replace(
    '</application>',
    '    <service android:name="' + pkg + '.FoxBrowserService" android:exported="true" android:label="Fox Musik">\n' +
    '        <intent-filter><action android:name="android.media.browse.MediaBrowserService" /></intent-filter>\n' +
    '    </service>\n' +
    '    <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />\n' +
    '    <receiver android:name="' + pkg + '.FoxWidget" android:exported="false" android:label="Fox Musik">\n' +
    '        <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>\n' +
    '        <meta-data android:name="android.appwidget.provider" android:resource="@xml/fox_widget_info" />\n' +
    '    </receiver>\n' +
    '    <receiver android:name="' + pkg + '.FoxWidgetLarge" android:exported="false" android:label="Fox Musik groß">\n' +
    '        <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>\n' +
    '        <meta-data android:name="android.appwidget.provider" android:resource="@xml/fox_widget_large_info" />\n' +
    '    </receiver>\n' +
    '    <receiver android:name="' + pkg + '.FoxAlarm" android:exported="false">\n' +
    '        <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /><action android:name="android.intent.action.MY_PACKAGE_REPLACED" /></intent-filter>\n' +
    '    </receiver>\n' +
    '    </application>'
  );
  console.log('Android Auto, Widget und Wecker eingetragen');
}
if (!xml.includes('NeowakeActivity')) {
  xml = xml.replace(
    '</application>',
    '    <activity android:name="' + pkg + '.NeowakeActivity" android:exported="false" android:label="Neowake" android:launchMode="singleTask" android:taskAffinity="' + pkg + '.neowake" android:configChanges="orientation|screenSize|keyboardHidden|uiMode" />\n    </application>'
  );
  console.log('Neowake-Sparte eingetragen');
}
if (!xml.includes('SpotifyLoginActivity')) {
  xml = xml.replace(
    '</application>',
    '    <activity android:name="' + pkg + '.SpotifyLoginActivity" android:exported="false" android:label="Spotify" />\n    </application>'
  );
  console.log('Spotify-Anmeldung eingetragen');
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
if (!xml.includes('usesCleartextTraffic')) {
  xml = xml.replace('<application', '<application android:usesCleartextTraffic="true"');
  console.log('Radio: unverschluesselte Streams erlaubt');
}
fs.writeFileSync(manifest, xml);

// ---- Fester Signierschlüssel (sonst lehnt Android jedes Update ab) ----
{
  const ks = path.join('android', 'app', 'fox-debug.keystore');
  fs.writeFileSync(ks, Buffer.from(fs.readFileSync('fox-debug.keystore.b64', 'utf8').replace(/\s+/g, ''), 'base64'));
  const gradle = path.join('android', 'app', 'build.gradle');
  let g = fs.readFileSync(gradle, 'utf8');
  if (!g.includes('fox-debug.keystore')) {
    const block =
      'android {\n' +
      '    signingConfigs {\n' +
      '        debug {\n' +
      "            storeFile file('fox-debug.keystore')\n" +
      "            storePassword 'android'\n" +
      "            keyAlias 'androiddebugkey'\n" +
      "            keyPassword 'android'\n" +
      '        }\n' +
      '    }\n';
    const g2 = g.replace(/^android\s*\{\s*\n/m, block);
    if (g2 === g) { console.error('FEHLER: android { } in build.gradle nicht gefunden'); process.exit(1); }
    fs.writeFileSync(gradle, g2);
  }
  console.log('Signierschlüssel fest eingetragen');
}

// ---- QR-Code-Bibliothek fuer den Party-Modus ----
{
  const gradle = path.join('android', 'app', 'build.gradle');
  const g = fs.readFileSync(gradle, 'utf8');
  if (!g.includes('zxing')) {
    const g2 = g.replace(/^dependencies\s*\{\s*\n/m, (m) => m + "    implementation 'com.google.zxing:core:3.5.3'\n");
    if (g2 === g) { console.error('FEHLER: dependencies { } in build.gradle nicht gefunden'); process.exit(1); }
    fs.writeFileSync(gradle, g2);
  }
  console.log('QR-Code-Bibliothek eingetragen');
}

const res = path.join('android', 'app', 'src', 'main', 'res');
fs.mkdirSync(path.join(res, 'xml'), { recursive: true });
// Widget-, Android-Auto- und weitere Ressourcen aus dem Ordner android-res uebernehmen
(function copyRes(src, dst) {
  if (!fs.existsSync(src)) return;
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const a = path.join(src, e.name), b = path.join(dst, e.name);
    if (e.isDirectory()) { fs.mkdirSync(b, { recursive: true }); copyRes(a, b); }
    else fs.copyFileSync(a, b);
  }
})('android-res', res);
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
