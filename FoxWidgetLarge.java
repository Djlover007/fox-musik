package de.foxmusik.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

// Grosses Widget mit grossem Cover
public class FoxWidgetLarge extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        FoxWidget.update(ctx);
    }
}
