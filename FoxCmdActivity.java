package de.foxmusik.app;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;

// Unsichtbare Zwischenstation fuer Verknuepfungen am App-Symbol, Senderkacheln im Widget und Sprachbefehle
// ("Spiel Klassik Radio Healing mit Fox Musik"). Sie merkt sich den Befehl und holt die laufende App nach vorn,
// ohne sie neu zu starten (sonst wuerde die Musik abbrechen).
public class FoxCmdActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent in = getIntent();
            String cmd = in == null ? null : in.getStringExtra("fox_cmd");
            if (in != null && "android.media.action.MEDIA_PLAY_FROM_SEARCH".equals(in.getAction())) {
                String q = in.getStringExtra(SearchManager.QUERY);
                cmd = "search:" + (q == null ? "" : q);
            }
            if (cmd != null && cmd.length() > 0) {
                MainActivity.pendingCmd = cmd;
            }
            Intent go = new Intent(this, MainActivity.class);
            go.putExtra("fox_cmd_wake", true);
            go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(go);
        } catch (Exception ignore) {
            // ignore
        }
        finish();
    }
}
