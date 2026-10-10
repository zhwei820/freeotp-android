/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2018  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.main;
import android.Manifest;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.UserNotAuthenticatedException;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.fedorahosted.freeotp.Code;
import org.fedorahosted.freeotp.TokenIcon;
import org.fedorahosted.freeotp.ManualAdd;
import org.fedorahosted.freeotp.R;
import org.fedorahosted.freeotp.Token;
import org.fedorahosted.freeotp.TokenPersistence;
import org.fedorahosted.freeotp.main.share.ShareFragment;
import org.fedorahosted.freeotp.utils.GridLayoutItemDecoration;
import org.fedorahosted.freeotp.utils.SelectableAdapter;
import org.fedorahosted.freeotp.utils.UserNotifier;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;
import androidx.recyclerview.widget.RecyclerView;

public class Activity extends AppCompatActivity
    implements SelectableAdapter.EventListener, View.OnClickListener, View.OnLongClickListener {
    private static final String LOGTAG = "Activity";

    private List<WeakReference<ViewHolder>> mViewHolders = new LinkedList<>();
    private int mLongClickCount = 0;

    private FloatingActionButton mFloatingActionButton;
    private FloatingActionButton mFABScan;
    private FloatingActionButton mFABManual;
    private RecyclerView mRecyclerView;
    private Adapter mTokenAdapter;
    private TextView mEmpty;
    private Menu mMenu;
    private MenuItem mAutoClipboard;
    private MenuItem mSortByMru;
    ActivityResultLauncher<Intent> mManualAddLauncher;
    ActivityResultLauncher<Intent> mBackupSaveLauncher;
    ActivityResultLauncher<Intent> mRestoreSaveLauncher;
    ActivityResultLauncher<Intent> mRestoreReselectLauncher;
    private String mRestorePwd = "";
    private SharedPreferences mBackups;
    private TokenPersistence mTokenBackup;
    static final String BACKUP = "tokenBackup";
    static final String RESTORED = "restoreComplete";
    /* Generic settings preferences file */
    private SharedPreferences mSettings;
    static final String SETTINGS = "settings";
    static final String AUTO_COPY_CLIPBOARD = "copyClipboard";
    public static final String SORT_BY_MRU = "sortByMostRecentlyUsed";
    /* Remembered backup file for Quick Restore */
    static final String RESTORE_URI = "restoreUri";

    /* 候选密码搜索（还原对话框的「尝试候选」），每轮最多 CANDIDATES_PER_ROUND 个 */
    private static final int CANDIDATES_PER_ROUND = 100;
    private ExecutorService mCandidateExecutor;
    private Handler mHandler;
    private List<String> mCandidates;
    private int mNextCandidate;
    private boolean mCandidateSearchRunning;
    private volatile boolean mCandidateSearchCancelled;


    private final RecyclerView.AdapterDataObserver mAdapterDataObserver =
        new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mEmpty.setVisibility(mTokenAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                onChanged();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                onChanged();
            }
        };

    private void onActivate(ViewHolder vh) {
        try {
            Log.i(LOGTAG, "onActivate: adapter.getCode()");
            int position = vh.getAdapterPosition();
            Code code = mTokenAdapter.getCode(position);

            if (mSettings.getBoolean(AUTO_COPY_CLIPBOARD, false)) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("code", code.getCode());
                clipboard.setPrimaryClip(clip);
            }

            Log.i(LOGTAG, "onActivate: vh.displayCode()");
            vh.displayCode(code);

            // Defer sorting to next frame to avoid ViewHolder rebinding during click
            if (mSettings.getBoolean(SORT_BY_MRU, false)) {
                new Handler(Looper.getMainLooper()).post(() -> mTokenAdapter.sortByMostRecentlyUsed());
            }

        } catch (UserNotAuthenticatedException e) {
            Log.e(LOGTAG, "Exception", e);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            Intent i = km.createConfirmDeviceCredentialIntent(vh.getIssuer(), vh.getLabel());

            mViewHolders.add(new WeakReference<ViewHolder>(vh));
            startActivityForResult(i, mViewHolders.size() - 1);
        } catch (KeyPermanentlyInvalidatedException e) {
            Log.e(LOGTAG, "Exception", e);
            try {
                mTokenAdapter.delete(vh.getAdapterPosition());
            } catch (GeneralSecurityException | IOException f) {
                Log.e(LOGTAG, "Exception", e);
            }

            new AlertDialog.Builder(this)
                .setTitle(R.string.main_invalidated_title)
                .setMessage(R.string.main_invalidated_message)
                .setPositiveButton(R.string.ok, null)
                .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);

        while (mViewHolders.size() > 0) {
            ViewHolder holder = mViewHolders.remove(requestCode).get();

            if (resultCode == Activity.RESULT_OK && holder != null)
                onActivate(holder);
        }
    }

    interface LauncherCallback {
        void execute(Uri uri);
    }

    public ActivityResultLauncher<Intent> registerLauncher(LauncherCallback callback) {
        return registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent intent = result.getData();
                        Uri uri = intent.getData();
                        if (uri != null) {
                            callback.execute(uri);
                        }
                    }
                });
    }

    private void initLaunchers() {
        try {
            mTokenBackup = new TokenPersistence(getApplicationContext());
        } catch (Exception e) {
            Log.e(LOGTAG, "Exception", e);
        }

        mManualAddLauncher = registerLauncher(uri -> addToken(uri, true));

        mBackupSaveLauncher = registerLauncher(uri -> {
            /* Copy tokenBackup to picked file on external storage */
            mTokenBackup.copyBackupToExternal(uri);
            UserNotifier.show(this, R.string.backup_toast);
        });

        mRestoreSaveLauncher = registerLauncher(uri -> {
            /* Remember + stage the chosen file (externalBackup.xml) */
            stageRestoreBackup(uri);

            /* Prompt for restore */
            showRestoreAlert();
        });

        mRestoreReselectLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    Uri uri = result.getResultCode() == RESULT_OK && data != null ? data.getData() : null;
                    if (uri != null) {
                        if (stageRestoreBackup(uri)) {
                            UserNotifier.show(this,
                                    getString(R.string.main_restore_file_selected, backupFileName(uri)));
                        } else {
                            UserNotifier.show(this, R.string.quick_restore_unavailable);
                        }
                    }
                    /* uri 为 null 表示用户取消了选择，沿用之前已暂存的文件 */
                    showRestoreAlert();
                });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Don't let other apps screenshot token codes...
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        mBackups = getApplicationContext().getSharedPreferences(BACKUP, Context.MODE_PRIVATE);
        mSettings = getApplicationContext().getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
        mHandler = new Handler(Looper.getMainLooper());

        initLaunchers();

        mFABScan = findViewById(R.id.fab_scan);
        mFABManual = findViewById(R.id.fab_manual);
        mFloatingActionButton = findViewById(R.id.fab);
        mRecyclerView = findViewById(R.id.recycler);
        mEmpty = findViewById(android.R.id.empty);

        try {
            mTokenAdapter = new Adapter(getApplicationContext(), this) {
                @Override
                public void onActivated(ViewHolder holder) {
                    Activity.this.onActivate(holder);
                }

                @Override
                public void onShare(String code) {
                    Bundle b = new Bundle();
                    b.putString(ShareFragment.CODE_ID, code);

                    ShareFragment sf = new ShareFragment();
                    sf.setArguments(b);
                    sf.show(getSupportFragmentManager(), sf.getTag());
                }
            };
        } catch (GeneralSecurityException | IOException e) {
            Log.e(LOGTAG, "Exception", e);
        }

        mFloatingActionButton.setOnClickListener(this);
        mFloatingActionButton.setOnLongClickListener(this);
        if (!ScanDialogFragment.hasCamera(getApplicationContext()))
            mFloatingActionButton.hide();

        /* Scan QR code listener */
        mFABScan.setOnClickListener(view -> {
            mFABManual.hide();
            mFABScan.hide();
            ScanDialogFragment scan = new ScanDialogFragment();
            scan.show(getSupportFragmentManager(), scan.getTag());
        });

        /* Manual entry listener */
        mFABManual.setOnClickListener(view -> {
            mFABManual.hide();
            mFABScan.hide();
            Intent intent = new Intent(view.getContext(), ManualAdd.class);
            mManualAddLauncher.launch(intent);
        });


        int margin = getResources().getDimensionPixelSize(R.dimen.margin);
        mRecyclerView.setAdapter(mTokenAdapter);
        mRecyclerView.addItemDecoration(new GridLayoutItemDecoration(margin));
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                /* Hide FAB on scroll-down, revealing the bottom token. */
                if (mFloatingActionButton.getVisibility() == View.VISIBLE) {
                    if (dy > 0)
                        mFloatingActionButton.hide();
                } else if (dy < 0 && ScanDialogFragment.hasCamera(getApplicationContext())) {
                    mFloatingActionButton.show();
                }
            }
        });

        mTokenAdapter.registerAdapterDataObserver(mAdapterDataObserver);
        mAdapterDataObserver.onChanged();

        /* EditTokenDialogFragment listener */
        getSupportFragmentManager().setFragmentResultListener("requestKey", this, (requestKey, bundle) -> {
            String account = bundle.getString("account");
            String issuer = bundle.getString("issuer");

            int selected = mTokenAdapter.getSelected().first();

            try {
                mTokenAdapter.setLabel(selected, account, issuer);
                mTokenAdapter.notifyItemChanged(selected);
            } catch (IOException e) {
                Log.e(LOGTAG, "Exception", e);
            }
            // ensure viewholder in recyclerview is refreshed
        });

        if (mBackups.getBoolean(RESTORED, false)) {
            mBackups.edit().remove(RESTORED).apply();
            showRestoreAlert();
        }

        onNewIntent(getIntent());


    }

    @Override
    protected void onStart() {
        super.onStart();

        int p = ContextCompat.checkSelfPermission(this, Manifest.permission.USE_FINGERPRINT);
        if (p != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.USE_FINGERPRINT }, 0);

        mBackups = getApplicationContext().getSharedPreferences(BACKUP, Context.MODE_PRIVATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        discardCandidateSearch();
        if (mCandidateExecutor != null) {
            mCandidateExecutor.shutdown();
            mCandidateExecutor = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        mMenu = menu;
        mAutoClipboard = menu.findItem(R.id.action_clipboard);
        /* Set checked/unchecked checkbox in menu for auto copy to clipboard setting */
        if(mSettings.getBoolean(AUTO_COPY_CLIPBOARD, false)) {
            mAutoClipboard.setIcon(R.drawable.ic_check_box_checked);
        } else {
            /* Should not be needed since this is the default, here as fallback */
            mAutoClipboard.setIcon(R.drawable.ic_check_box_blank);
        }

        mSortByMru = menu.findItem(R.id.action_sort_mru);
        /* Set checked/unchecked checkbox in menu for sort by MRU setting */
        if(mSettings.getBoolean(SORT_BY_MRU, false)) {
            mSortByMru.setIcon(R.drawable.ic_check_box_checked);
        } else {
            mSortByMru.setIcon(R.drawable.ic_check_box_blank);
        }
        return true;
    }

    private void showRestoreCancelAlert() {
        RestoreCancelDialogFragment cancelDialog = RestoreCancelDialogFragment.newInstance();
        cancelDialog.setListener(new RestoreCancelDialogFragment.RestoreCancelDialogListener() {
            @Override
            public void onGoBack() {
                showRestoreAlert();
            }

            @Override
            public void onProceed() {
                // User chose to proceed without restoring
            }
        });
        cancelDialog.show(getSupportFragmentManager(), "restore_cancel");
    }

    private void launchRestorePicker(ActivityResultLauncher<Intent> launcher) {
        Intent restoreIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        restoreIntent.addCategory(Intent.CATEGORY_OPENABLE);
        restoreIntent.setType("*/*");
        restoreIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        launcher.launch(restoreIntent);
    }

    /* 记住并暂存所选备份文件；返回 false 表示文件读不出来（暂存区保持原样） */
    private boolean stageRestoreBackup(Uri uri) {
        rememberRestoreUri(uri);
        return mTokenBackup.restoreBackupFromExternal(uri);
    }

    /* 所选备份文件的显示名；查不到时退回 URI 末段 */
    private String backupFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Exception", e);
        }

        String lastSegment = uri.getLastPathSegment();
        return lastSegment != null ? lastSegment : uri.toString();
    }

    private void rememberRestoreUri(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // Some providers don't grant persistable permission; Quick Restore just
            // won't survive a reboot in that case, which is acceptable.
            Log.e(LOGTAG, "Exception", e);
        }
        mSettings.edit().putString(RESTORE_URI, uri.toString()).apply();
    }

    private void quickRestore() {
        String saved = mSettings.getString(RESTORE_URI, null);
        if (saved == null) {
            // Nothing remembered yet: fall back to picking a file (and remember it).
            launchRestorePicker(mRestoreSaveLauncher);
            return;
        }

        if (mTokenBackup.restoreBackupFromExternal(Uri.parse(saved))) {
            showRestoreAlert();
        } else {
            // Remembered file is gone or unreadable: forget it and let the user pick again.
            mSettings.edit().remove(RESTORE_URI).apply();
            UserNotifier.show(this, R.string.quick_restore_unavailable);
            launchRestorePicker(mRestoreSaveLauncher);
        }
    }

    private void showRestoreAlert() {
        RestoreDialogFragment restoreDialog = RestoreDialogFragment.newInstance();
        restoreDialog.setListener(new RestoreDialogFragment.RestoreDialogListener() {
            @Override
            public void onRestorePassword(String password) {
                restoreWithPassword(restoreDialog, password, false);
            }

            @Override
            public void onVerifyPassword(String password) {
                if (mTokenBackup.verifyPassword(password))
                    restoreDialog.showGoodPasswordResult();
                else
                    restoreDialog.showBadPasswordError();
            }

            @Override
            public void onRestoreCancel() {
                restoreDialog.dismiss();
                showRestoreCancelAlert();
            }

            @Override
            public void onTryCandidates(String password) {
                startOrResumeCandidateSearch(restoreDialog, password);
            }

            @Override
            public void onStopCandidates() {
                stopCandidateSearch();
            }

            @Override
            public void onChooseAnotherFile() {
                restoreDialog.dismiss();
                launchRestorePicker(mRestoreReselectLauncher);
            }

            @Override
            public void onPasswordEdited() {
                discardCandidateSearch();
            }

            @Override
            public void onDialogDismissed() {
                discardCandidateSearch();
            }
        });
        restoreDialog.show(getSupportFragmentManager(), "restore");
    }

    /* 与「确定」同一条还原路径；showRecoveredPassword 表示密码是试出来的，
       还原成功后展示出来供用户记录——该密码之后无法再次查看。 */
    private void restoreWithPassword(RestoreDialogFragment dialog, String password, boolean showRecoveredPassword) {
        mRestorePwd = password;

        try {
            mTokenAdapter.restoreTokens(mRestorePwd);
            dialog.dismiss();
            if (showRecoveredPassword) {
                showRecoveredPasswordAlert(password);
            }
        } catch (TokenPersistence.BadPasswordException e) {
            dialog.showBadPasswordError();
        }
    }

    private void showRecoveredPasswordAlert(String password) {
        View content = getLayoutInflater().inflate(R.layout.dialog_save_password, null);
        ((TextView) content.findViewById(R.id.save_warning))
                .setText(R.string.main_restore_try_recovered_warning);
        ((TextView) content.findViewById(R.id.saved_password)).setText(password);

        AlertDialog alert = new AlertDialog.Builder(this)
                .setTitle(R.string.main_restore_try_recovered_title)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .create();
        alert.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        alert.show();
    }

    /* 在后台逐个校验候选密码。默认规则集不超过 100 个，通常一轮跑完；
       候选超过 100 个时才会出现轮间暂停（每轮 CANDIDATES_PER_ROUND 个）。 */
    private void startOrResumeCandidateSearch(RestoreDialogFragment dialog, String basePassword) {
        if (mCandidateSearchRunning) {
            return;
        }
        if (mCandidates == null) {
            mCandidates = PasswordCandidates.generate(basePassword);
            mNextCandidate = 0;
        }

        final List<String> candidates = mCandidates;
        final int total = candidates.size();
        final int start = mNextCandidate;
        final int end = Math.min(start + CANDIDATES_PER_ROUND, total);
        final int round = start / CANDIDATES_PER_ROUND + 1;
        final int roundStart = (round - 1) * CANDIDATES_PER_ROUND;

        mCandidateSearchCancelled = false;
        mCandidateSearchRunning = true;
        dialog.showSearchRunning(start - roundStart, total, round);

        mCandidateExecutor = Executors.newSingleThreadExecutor();
        mCandidateExecutor.execute(() -> {
            for (int i = start; i < end; i++) {
                if (mCandidateSearchCancelled) {
                    finishCandidateSearch(dialog, i, total, round, null, true);
                    return;
                }
                if (mTokenBackup.verifyPassword(candidates.get(i))) {
                    finishCandidateSearch(dialog, i, total, round, candidates.get(i), false);
                    return;
                }
                final int done = i + 1 - roundStart;
                mHandler.post(() -> {
                    if (dialog.isAdded()) {
                        dialog.updateSearchProgress(done, total, round);
                    }
                });
            }
            finishCandidateSearch(dialog, end, total, round, null, false);
        });
    }

    /* 收尾都回到主线程：更新会话状态、停掉线程池、刷新对话框。
       nextIndex 为停下时未试候选的下标（命中时为命中候选的下标，提示里用 +1 展示）。 */
    private void finishCandidateSearch(RestoreDialogFragment dialog, int nextIndex, int total,
                                       int round, String found, boolean cancelled) {
        mHandler.post(() -> {
            mCandidateSearchRunning = false;
            if (mCandidateExecutor != null) {
                mCandidateExecutor.shutdown();
                mCandidateExecutor = null;
            }
            if (!dialog.isAdded()) {
                discardCandidateSearch();
                return;
            }
            if (found != null) {
                discardCandidateSearch();
                dialog.showCandidateFound(found, nextIndex + 1, total);

                /* 试出来的密码直接执行还原，不再要求用户点「确定」 */
                restoreWithPassword(dialog, found, true);
            } else if (nextIndex >= total) {
                discardCandidateSearch();
                dialog.showCandidatesExhausted(total);
            } else if (cancelled) {
                mNextCandidate = nextIndex;
                dialog.showSearchStopped(nextIndex, total);
            } else {
                mNextCandidate = nextIndex;
                dialog.showRoundFinished(round, nextIndex, total);
            }
        });
    }

    private void stopCandidateSearch() {
        mCandidateSearchCancelled = true;
    }

    /* 输入变化或对话框关闭时作废会话：候选列表基于旧输入，不能复用 */
    private void discardCandidateSearch() {
        mCandidateSearchCancelled = true;
        mCandidates = null;
        mNextCandidate = 0;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_about:
                try {
                    Resources r = getResources();
                    PackageManager pm = getPackageManager();
                    PackageInfo info = pm.getPackageInfo(getPackageName(), 0);
                    final SpannableString msg = new SpannableString(r.getString(R.string.main_about_message));
                    Linkify.addLinks(msg, Linkify.ALL);

                    final AlertDialog dialog = new AlertDialog.Builder(this)
                            .setTitle(r.getString(R.string.main_about_title,
                                    info.versionName, info.versionCode))
                            .setMessage(HtmlCompat.fromHtml(r.getString(R.string.main_about_message), HtmlCompat.FROM_HTML_MODE_LEGACY))
                            .setPositiveButton(R.string.close, null)
                            .create();

                    dialog.show();

                    ((TextView)dialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(LOGTAG, "Exception", e);
                    return false;
                }

                return true;

            case R.id.action_down:
                if (mTokenAdapter.isSelected(mTokenAdapter.getItemCount() - 1))
                    return true;

                for (Integer i : new TreeSet<>(mTokenAdapter.getSelected().descendingSet()))
                    mTokenAdapter.move(i, i + 1);

                mRecyclerView.scrollToPosition(mTokenAdapter.getSelected().first());
                return true;

            case R.id.action_edit:
                int selected = mTokenAdapter.getSelected().first();
                TokenIcon token_icon = mTokenAdapter.getTokenIcon(selected);
                Pair<String, String> label = mTokenAdapter.getLabel(selected);
                Pair<Integer, String> image = token_icon.mImage;
                int color = token_icon.mColor;

                EditTokenDialogFragment edit = EditTokenDialogFragment.newInstance(label.first,
                        label.second, image.first, image.second, color);
                edit.show(getSupportFragmentManager(), edit.getTag());

                return true;

            case R.id.action_up:
                if (mTokenAdapter.isSelected(0))
                    return true;

                for (Integer i : new TreeSet<>(mTokenAdapter.getSelected()))
                    mTokenAdapter.move(i, i - 1);

                mRecyclerView.scrollToPosition(mTokenAdapter.getSelected().first());
                return true;

            case R.id.action_delete:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.main_deletion_title)
                        .setMessage(R.string.main_deletion_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                for (Integer i : new TreeSet<>(mTokenAdapter.getSelected().descendingSet())) {
                                    try { mTokenAdapter.delete(i); }
                                    catch (GeneralSecurityException | IOException e) {
                                        Log.e(LOGTAG, "Exception", e);
                                    }
                                }

                                mFloatingActionButton.show();
                            }
                        }).show();

                return true;

            case R.id.action_clipboard:
                boolean copy_clipboard = mSettings.getBoolean(AUTO_COPY_CLIPBOARD, false);

                mSettings.edit().putBoolean(AUTO_COPY_CLIPBOARD, !copy_clipboard).apply();
                UserNotifier.show(this, R.string.auto_copy_clipboard_toast, !copy_clipboard ? R.string.state_enabled_toast : R.string.state_disabled_toast);

                /* Update checkbox icon in menu */
                if(mAutoClipboard == null) return true;
                mAutoClipboard.setIcon(!copy_clipboard ? R.drawable.ic_check_box_checked : R.drawable.ic_check_box_blank);

                return true;

            case R.id.action_sort_mru:
                boolean sortByMru = mSettings.getBoolean(SORT_BY_MRU, false);

                mSettings.edit().putBoolean(SORT_BY_MRU, !sortByMru).apply();
                UserNotifier.show(this, R.string.sort_by_recent_use_toast, !sortByMru ? R.string.state_enabled_toast : R.string.state_disabled_toast);

                /* Update checkbox icon in menu */
                if (mSortByMru != null) {
                    mSortByMru.setIcon(!sortByMru ? R.drawable.ic_check_box_checked : R.drawable.ic_check_box_blank);
                }

                /* If enabling, sort immediately */
                if (!sortByMru) {
                    mTokenAdapter.sortByMostRecentlyUsed();
                }

                return true;

            case R.id.action_backup:

                Intent backupIntent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                backupIntent.addCategory(Intent.CATEGORY_OPENABLE);
                backupIntent.setType("application/xml");
                backupIntent.putExtra(Intent.EXTRA_TITLE, "externalBackup");
                mBackupSaveLauncher.launch(backupIntent);

                return true;

            case R.id.action_restore:

                launchRestorePicker(mRestoreSaveLauncher);

                return true;

            case R.id.action_quick_restore:

                quickRestore();

                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onSelectEvent(NavigableSet<Integer> selected) {
        if (mMenu == null)
            return;

        for (int i = 0; i < mMenu.size(); i++) {
            MenuItem mi = mMenu.getItem(i);

            switch (mi.getItemId()) {
                case R.id.menu_todo:
                    mi.setVisible(selected.size() == 0);
                    break;
                case R.id.action_backup:
                case R.id.action_restore:
                case R.id.action_clipboard:

                case R.id.action_up:
                    mi.setVisible(selected.size() > 0);
                    mi.setEnabled(!mTokenAdapter.isSelected(0));
                    break;

                case R.id.action_down:
                    mi.setVisible(selected.size() > 0);
                    mi.setEnabled(!mTokenAdapter.isSelected(mTokenAdapter.getItemCount() - 1));
                    break;
                case R.id.action_edit:
                    mi.setVisible(selected.size() > 0);
                    break;

                case R.id.action_delete:
                    mi.setVisible(selected.size() > 0);
                    break;

                default:
                    break;
            }
        }
    }

    @Override
    public boolean onLongClick(View v) {
        switch (mLongClickCount++) {
            case 0:
                /* You have 15 seconds from the first click to enter random mode. */
                mFloatingActionButton.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mLongClickCount < 3)
                            mLongClickCount = 0;
                    }
                }, 15000);
                break;

            case 2:
                /* Random mode lasts for 15 seconds... */
                mFloatingActionButton.setImageResource(R.drawable.ic_add);
                mFloatingActionButton.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mFloatingActionButton.setImageResource(R.drawable.ic_scan);
                        mLongClickCount = 0;
                    }
                }, 15000);
                break;
        }

        return true;
    }

    @Override
    public void onClick(View v) {
        if (mLongClickCount >= 3) {
            Pair<SecretKey, Token> pair = Token.random();
            addToken(pair.second.toUri(pair.first), true);
        } else {
            if (mFABManual.isShown()) {
                mFABManual.hide();
                mFABScan.hide();
            } else {
                mFABManual.show();
                mFABScan.show();
            }
        }
    }

    void addToken(final Uri uri, boolean enableSecurity) {
        try {
            Pair<SecretKey, Token> pair = enableSecurity ? Token.parse(uri) : Token.parseUnsafe(uri);
            mRecyclerView.scrollToPosition(mTokenAdapter.add(pair.first, pair.second));
        } catch (Token.UnsafeUriException e) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.main_unsafe_title)
                .setMessage(R.string.main_unsafe_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add_anyway, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        addToken(uri, false);
                    }
                }).show();
        } catch (Token.InvalidUriException e) {
            new AlertDialog.Builder(this)
                .setTitle(R.string.main_invalid_title)
                .setMessage(R.string.main_invalid_message)
                .setPositiveButton(R.string.ok, null)
                .show();
        } catch (GeneralSecurityException | IOException e) {
            if (!e.getClass().equals(KeyStoreException.class)) {
                Log.e(LOGTAG, "Exception", e);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.main_error_title)
                        .setMessage(R.string.main_error_message)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }

            new AlertDialog.Builder(this)
                .setTitle(R.string.main_lock_title)
                .setMessage(R.string.main_lock_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.enable_lock_screen, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
                            startActivity(intent);
                        }
                    })
                .show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        Uri uri = intent.getData();
        if (uri != null)
            addToken(uri, true);
    }
}