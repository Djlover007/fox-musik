// Prüft den nativen Code auf bekannte Fallen, bevor die APK gebaut wird.
// Capacitors call.getLong/getInt/getDouble/getFloat liefern stillschweigend den Standardwert, wenn die Zahl
// intern einen anderen Typ hat. Das hat "Radio zurückspulen" (immer wieder von vorn) und den Wecker kaputt gemacht.
// Zahlen deshalb nur über num(call, ...) lesen.
const fs = require('fs');
const path = require('path');
const dir = path.resolve(__dirname, '..');
let bad = 0;
for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.java'))) {
  fs.readFileSync(path.join(dir, f), 'utf8').split('\n').forEach((line, i) => {
    if (/\bcall\.get(Long|Int|Double|Float)\s*\(/.test(line)) {
      console.error(f + ':' + (i + 1) + ': Zahl bitte mit num(call, ...) lesen: ' + line.trim());
      bad++;
    }
  });
}
if (bad) { console.error(bad + ' Stelle(n) gefunden.'); process.exit(1); }
console.log('Nativer Code: keine Zahlen-Fallen gefunden.');
