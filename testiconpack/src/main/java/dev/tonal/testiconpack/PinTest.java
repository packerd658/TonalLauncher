package dev.tonal.testiconpack;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;

/** Asks the home app to pin a shortcut, the same request Google Maps sends for a saved route. */
public class PinTest extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "test-route")
                .setShortLabel("Route to work")
                .setIcon(Icon.createWithResource(this, R.drawable.pack_star))
                .setIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps")))
                .build();
        manager.requestPinShortcut(info, null);
        finish();
    }
}
