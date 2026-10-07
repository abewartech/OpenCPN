package org.opencpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Modern sandboxed file/directory chooser used by the native layer through
 * {@code AndroidFileDialog::showDialogJNI}.
 *
 * <p>Replaces the legacy {@link FileChooser} ListActivity for paths inside
 * the app sandbox (e.g. {@code /storage/emulated/0/.../opencpn}). The result
 * is delivered through {@link Callback}: {@code "cancel"} when dismissed, or
 * {@code "file:<absolute path>"} on selection — the native side strips the
 * scheme with {@code AfterFirst(':')}.
 *
 * <p>Must be called on the UI thread (the native caller guarantees this via
 * runOnUiThread in the dialog path... if not, we post to it ourselves).
 */
public class OCPNFileDialog {

    private static final String TAG = "OCPN-FileDialog";

    /** Result callback; implemented by {@link FileDialogCallbackProxy}. */
    public interface Callback {
        void onFinished(String path);
    }

    public static void showFileDialog(final Activity activity,
                                      final String startDir,
                                      final Callback callback,
                                      final boolean dirMode,
                                      final boolean allowCreate) {
        Runnable show = new Runnable() {
            @Override
            public void run() {
                new Dialog(activity, startDir, callback, dirMode,
                        allowCreate).show();
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            show.run();
        } else {
            activity.runOnUiThread(show);
        }
    }

    private static class Dialog {
        private final Activity activity;
        private final Callback callback;
        private final boolean dirMode;
        private final boolean allowCreate;
        private File currentDir;
        private AlertDialog dialog;
        private ArrayAdapter<String> adapter;
        private final List<File> shown = new ArrayList<>();

        Dialog(Activity activity, String startDir, Callback callback,
               boolean dirMode, boolean allowCreate) {
            this.activity = activity;
            this.callback = callback;
            this.dirMode = dirMode;
            this.allowCreate = allowCreate;
            File start = new File(startDir);
            this.currentDir = (start.isDirectory() && start.canRead())
                    ? start : new File("/storage/emulated/0");
        }

        void show() {
            ListView list = new ListView(activity);
            adapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_list_item_1, new ArrayList<String>());
            list.setAdapter(adapter);
            list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(android.widget.AdapterView<?> parent,
                                        android.view.View view, int position, long id) {
                    File f = shown.get(position);
                    if (f.getName().equals("..")) {
                        File p = currentDir.getParentFile();
                        if (p != null) currentDir = p;
                    } else if (f.isDirectory()) {
                        currentDir = f;
                    } else if (!dirMode) {
                        finish("file:" + f.getAbsolutePath());
                        return;
                    }
                    refresh();
                }
            });

            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle(dirMode ? "Select folder" : "Select file");
            b.setView(list);
            b.setNegativeButton(android.R.string.cancel,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            finish("cancel");
                        }
                    });
            if (dirMode) {
                b.setPositiveButton("Select this folder",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                finish("file:" + currentDir.getAbsolutePath());
                            }
                        });
            }
            if (allowCreate) {
                b.setNeutralButton("New folder",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                promptNewFolder();
                            }
                        });
            }
            b.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface d) {
                    finish("cancel");
                }
            });
            dialog = b.create();
            refresh();
            dialog.show();
        }

        private void refresh() {
            shown.clear();
            List<String> names = new ArrayList<>();
            File parent = currentDir.getParentFile();
            if (parent != null) {
                shown.add(new File(".."));
                names.add("..");
            }
            File[] files = currentDir.listFiles();
            List<File> dirs = new ArrayList<>();
            List<File> plain = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) dirs.add(f);
                    else if (!dirMode) plain.add(f);
                }
            }
            Collections.sort(dirs);
            Collections.sort(plain);
            for (File f : dirs) {
                shown.add(f);
                names.add("[ " + f.getName() + " ]");
            }
            for (File f : plain) {
                shown.add(f);
                names.add(f.getName());
            }
            adapter.clear();
            adapter.addAll(names);
            adapter.notifyDataSetChanged();
            if (dialog != null) dialog.setTitle(currentDir.getAbsolutePath());
        }

        private void promptNewFolder() {
            final EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setHint("Folder name");
            new AlertDialog.Builder(activity)
                    .setTitle("New folder")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    String name = input.getText().toString().trim();
                                    if (!name.isEmpty()) {
                                        File nf = new File(currentDir, name);
                                        if (nf.mkdirs()) {
                                            currentDir = nf;
                                        } else {
                                            Log.w(TAG, "mkdirs failed: " + nf);
                                        }
                                    }
                                    show(); // reopen the browser
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private boolean done = false;

        private void finish(String result) {
            if (done) return;
            done = true;
            try {
                if (dialog != null) dialog.dismiss();
            } catch (Exception ignored) {
            }
            try {
                callback.onFinished(result);
            } catch (Exception e) {
                Log.w(TAG, "callback failed", e);
            }
        }
    }
}
