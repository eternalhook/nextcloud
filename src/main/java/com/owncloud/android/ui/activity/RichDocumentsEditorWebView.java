/*
 * Nextcloud Android client application
 *
 * @author Tobias Kaminsky
 * @author Chris Narkiewicz
 *
 * Copyright (C) 2018 Tobias Kaminsky
 * Copyright (C) 2018 Nextcloud GmbH.
 * Copyright (C) 2019 Chris Narkiewicz <hello@ezaquarii.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.owncloud.android.ui.activity;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.nextcloud.client.account.CurrentAccountProvider;
import com.nextcloud.client.account.User;
import com.nextcloud.client.network.ClientFactory;
import com.owncloud.android.R;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.Template;
import com.owncloud.android.lib.common.OwnCloudAccount;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.operations.RichDocumentsCreateAssetOperation;
import com.owncloud.android.ui.asynctasks.PrintAsyncTask;
import com.owncloud.android.ui.fragment.OCFileListFragment;
import com.owncloud.android.utils.DisplayUtils;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.glide.CustomGlideStreamLoader;

import org.json.JSONException;
import org.json.JSONObject;
import org.parceler.Parcels;

import java.io.File;
import java.lang.ref.WeakReference;

import javax.inject.Inject;

import androidx.annotation.RequiresApi;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * Opens document for editing via Richdocuments app in a web view
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class RichDocumentsEditorWebView extends EditorWebView {
    public static final int REQUEST_LOCAL_FILE = 101;
    private static final int REQUEST_REMOTE_FILE = 100;
    private static final String URL = "URL";
    private static final String TYPE = "Type";
    private static final String PRINT = "print";
    private static final String NEW_NAME = "NewName";

    private Unbinder unbinder;

    public ValueCallback<Uri[]> uploadMessage;

    @Inject
    protected CurrentAccountProvider currentAccountProvider;

    @Inject
    protected ClientFactory clientFactory;

    @SuppressLint("AddJavascriptInterface") // suppress warning as webview is only used >= Lollipop
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        unbinder = ButterKnife.bind(this);

        webview.addJavascriptInterface(new RichDocumentsMobileInterface(), "RichDocumentsMobileInterface");

        webview.setWebChromeClient(new WebChromeClient() {
            RichDocumentsEditorWebView activity = RichDocumentsEditorWebView.this;

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }

                activity.uploadMessage = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                intent.setType("image/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    activity.startActivityForResult(intent, REQUEST_LOCAL_FILE);
                } catch (ActivityNotFoundException e) {
                    uploadMessage = null;
                    Toast.makeText(getBaseContext(), "Cannot open file chooser", Toast.LENGTH_LONG).show();
                    return false;
                }

                return true;
            }
        });

        // load url in background
        loadUrl(getIntent().getStringExtra(EXTRA_URL), file);
    }

    @Override
    protected void initLoadingScreen() {
        if (file == null) {
            fileName.setText(R.string.create_file_from_template);

            Template template = Parcels.unwrap(getIntent().getParcelableExtra(EXTRA_TEMPLATE));

            int placeholder;

            switch (template.getType()) {
                case DOCUMENT:
                    placeholder = R.drawable.file_doc;
                    break;

                case SPREADSHEET:
                    placeholder = R.drawable.file_xls;
                    break;

                case PRESENTATION:
                    placeholder = R.drawable.file_ppt;
                    break;

                default:
                    placeholder = R.drawable.file;
                    break;
            }

            Glide.with(this).using(new CustomGlideStreamLoader(currentAccountProvider, clientFactory))
                .load(template.getThumbnailLink())
                .placeholder(placeholder)
                .error(placeholder)
                .into(thumbnail);
        } else {
            setThumbnail(file, thumbnail);
            fileName.setText(file.getFileName());
        }
    }



    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    private void openFileChooser() {
        Intent action = new Intent(this, FilePickerActivity.class);
        action.putExtra(OCFileListFragment.ARG_MIMETYPE, "image/");
        startActivityForResult(action, REQUEST_REMOTE_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (RESULT_OK != resultCode) {
            // TODO
            return;
        }

        switch (requestCode) {
            case REQUEST_LOCAL_FILE:
                handleLocalFile(data, resultCode);
                break;

            case REQUEST_REMOTE_FILE:
                handleRemoteFile(data);
                break;

            default:
                // unexpected, do nothing
                break;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleLocalFile(Intent data, int resultCode) {
        if (uploadMessage == null) {
            return;
        }

        uploadMessage.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
        uploadMessage = null;
    }

    private void handleRemoteFile(Intent data) {
        OCFile file = data.getParcelableExtra(FolderPickerActivity.EXTRA_FILES);

        new Thread(() -> {
            User user = currentAccountProvider.getUser();
            RichDocumentsCreateAssetOperation operation = new RichDocumentsCreateAssetOperation(file.getRemotePath());
            RemoteOperationResult result = operation.execute(user.toPlatformAccount(), this);

            if (result.isSuccess()) {
                String asset = (String) result.getSingleData();

                runOnUiThread(() -> webview.evaluateJavascript("OCA.RichDocuments.documentsMain.postAsset('" +
                                                                   file.getFileName() + "', '" + asset + "');", null));
            } else {
                runOnUiThread(() -> DisplayUtils.showSnackMessage(this, "Inserting image failed!"));
            }
        }).start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(EXTRA_URL, url);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        url = savedInstanceState.getString(EXTRA_URL);
        super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        unbinder.unbind();
        webview.destroy();

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        webview.evaluateJavascript("if (typeof OCA.RichDocuments.documentsMain.postGrabFocus !== 'undefined') " +
                                       "{ OCA.RichDocuments.documentsMain.postGrabFocus(); }", null);
    }

    private void printFile(Uri url) {
        OwnCloudAccount account = accountManager.getCurrentOwnCloudAccount();

        if (account == null) {
            DisplayUtils.showSnackMessage(webview, getString(R.string.failed_to_print));
            return;
        }

        File targetFile = new File(FileStorageUtils.getTemporalPath(account.getName()) + "/print.pdf");

        new PrintAsyncTask(targetFile, url.toString(), new WeakReference<>(this)).execute();
    }

    private void downloadFile(Uri url) {
        DownloadManager downloadmanager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        if (downloadmanager == null) {
            DisplayUtils.showSnackMessage(webview, getString(R.string.failed_to_download));
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(url);
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        downloadmanager.enqueue(request);
    }

    private class RichDocumentsMobileInterface extends MobileInterface {
        @JavascriptInterface
        public void insertGraphic() {
            openFileChooser();
        }

        @JavascriptInterface
        public void documentLoaded() {
            runOnUiThread(RichDocumentsEditorWebView.this::hideLoading);
        }

        @JavascriptInterface
        public void downloadAs(String json) {
            try {
                JSONObject downloadJson = new JSONObject(json);

                Uri url = Uri.parse(downloadJson.getString(URL));

                if (downloadJson.getString(TYPE).equalsIgnoreCase(PRINT)) {
                    printFile(url);
                } else {
                    downloadFile(url);
                }
            } catch (JSONException e) {
                Log_OC.e(this, "Failed to parse download json message: " + e);
                return;
            }
        }

        @JavascriptInterface
        public void fileRename(String renameString) {
            // when shared file is renamed in another instance, we will get notified about it
            // need to change filename for sharing
            try {
                JSONObject renameJson = new JSONObject(renameString);
                String newName = renameJson.getString(NEW_NAME);
                file.setFileName(newName);
            } catch (JSONException e) {
                Log_OC.e(this, "Failed to parse rename json message: " + e);
            }
        }

        @JavascriptInterface
        public void paste() {
            // Javascript cannot do this by itself, so help out.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                webview.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE));
                webview.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE));
            }
        }
    }
}
