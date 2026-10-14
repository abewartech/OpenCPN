
package org.opencpn;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import org.opencpn.opencpn.R;

public class WebViewActivity extends Activity {

        private WebView webView;

        public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                setContentView(R.layout.webview);

                webView = (WebView) findViewById(R.id.webView1);
                webView.getSettings().setJavaScriptEnabled(true);

                // OCPNActivity.launchWebView passes the URL to show; without
                // it (help view) fall back to the bundled help page.
                String url = getIntent().getStringExtra("WEBVIEW_URL");
                if (url == null || url.isEmpty()) {
                    //webView.loadUrl("file://localhost/storage/sdcard0/doc/help_en_US.html");  // this worked, after manual copy
                    url = "file:///android_asset/doc/doc/help_en_US.html";
                }
                webView.loadUrl(url);
        }

}
