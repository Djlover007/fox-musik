package de.foxmusik.app;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;

// Eine gemeinsame Mediensitzung fuer Benachrichtigung, Sperrbildschirm, Widget und Android Auto.
public class FoxSession {

    private static MediaSession session;

    static synchronized MediaSession get(Context ctx) {
        if (session != null) {
            return session;
        }
        session = new MediaSession(ctx.getApplicationContext(), "FoxMusik");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play", null); }
            @Override public void onPause() { send("pause", null); }
            @Override public void onStop() { send("pause", null); }
            @Override public void onSkipToNext() { send("next", null); }
            @Override public void onSkipToPrevious() { send("prev", null); }
            @Override public void onSeekTo(long pos) { PlaybackPlugin.emit("seek", pos); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { send("playId", mediaId); }
            @Override public void onPlayFromSearch(String query, Bundle extras) { send("search", query == null ? "" : query); }
        });
        session.setActive(true);
        update();
        return session;
    }

    private static void send(String action, String data) {
        if (PlaybackPlugin.inst == null) {
            // App (und damit der Player) laeuft nicht: Hinweis fuer Android Auto anzeigen
            if (session != null) {
                session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_ERROR, 0, 0f)
                    .setErrorMessage("Bitte Fox Musik einmal auf dem Handy öffnen")
                    .build());
            }
            return;
        }
        PlaybackPlugin.emitData(action, data);
    }

    static void update() {
        if (session == null) {
            return;
        }
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, PlaybackService.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, PlaybackService.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, PlaybackService.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, PlaybackService.duration);
            if (PlaybackService.cover != null) {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, PlaybackService.cover);
            }
            session.setMetadata(mb.build());
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY_FROM_SEARCH;
            session.setPlaybackState(new PlaybackState.Builder()
                .setActions(acts)
                .setState(PlaybackService.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                    PlaybackService.position, PlaybackService.playing ? PlaybackService.speed : 0f, SystemClock.elapsedRealtime())
                .build());
        } catch (Exception ignore) {
            // ignore
        }
    }
}
