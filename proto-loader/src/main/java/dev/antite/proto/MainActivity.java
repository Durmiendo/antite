package dev.antite.proto;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity {
    private static GeckoRuntime runtime;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(ProtoFactory.TAG, "GeckoRuntime loaded by " + GeckoRuntime.class.getClassLoader());
        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext());
        GeckoSession session = new GeckoSession();
        session.open(runtime);
        GeckoView view = new GeckoView(this);
        view.setSession(session);
        setContentView(view);
        Uri data = getIntent().getData();
        session.loadUri(data != null ? data.toString() : "https://example.org");
    }
}
