package org.opencpn;

/**
 * JNI callback proxy for the native file dialog.
 *
 * <p>The native side ({@code AndroidFileDialog::showDialogJNI}) instantiates
 * this class via JNI and passes it to
 * {@link OCPNFileDialog#showFileDialog}. When the user finishes, the dialog
 * calls {@link #onFinished(String)}, which forwards to the native
 * {@code Java_org_opencpn_FileDialogCallbackProxy_nativeFileDialogFinished}.
 */
public class FileDialogCallbackProxy implements OCPNFileDialog.Callback {

    public FileDialogCallbackProxy() {
    }

    private static native void nativeFileDialogFinished(String path);

    @Override
    public void onFinished(String path) {
        try {
            nativeFileDialogFinished(path != null ? path : "cancel");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.w("OCPN-FileDialog",
                    "native lib not loaded for dialog callback", e);
        }
    }
}
