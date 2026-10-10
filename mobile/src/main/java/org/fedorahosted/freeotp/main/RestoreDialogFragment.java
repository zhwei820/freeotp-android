/*
 * FreeOTP
 *
 * Authors: Brad Williams <brawilli@redhat.com>
 *
 * Copyright (C) 2026  Brad Williams, Red Hat
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

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.fedorahosted.freeotp.R;

public class RestoreDialogFragment extends DialogFragment implements View.OnClickListener {
    private TextInputEditText mPasswordInput;
    private TextInputLayout mPasswordLayout;
    private Button mOkButton;
    private Button mVerifyButton;
    private Button mCancelButton;
    private Button mTryButton;
    private Button mReselectButton;
    private TextView mTryStatus;
    private TryState mTryState = TryState.IDLE;
    private RestoreDialogListener mListener;

    private enum TryState { IDLE, RUNNING, PAUSED }

    public interface RestoreDialogListener {
        void onRestorePassword(String password);
        void onVerifyPassword(String password);
        void onRestoreCancel();
        void onTryCandidates(String password);
        void onStopCandidates();
        void onChooseAnotherFile();
        void onPasswordEdited();
        void onDialogDismissed();
    }

    static RestoreDialogFragment newInstance() {
        return new RestoreDialogFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (mListener != null) {
            mListener.onDialogDismissed();
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        /* 窗口宽度默认随内容自适应，会被按钮行拖窄到放不下按钮；显式撑满 */
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_restore, container, false);

        mPasswordInput = v.findViewById(R.id.restore_password_input);
        mPasswordLayout = v.findViewById(R.id.restore_password_layout);
        mOkButton = v.findViewById(R.id.restore_ok_button);
        mVerifyButton = v.findViewById(R.id.restore_verify_button);
        mCancelButton = v.findViewById(R.id.restore_cancel_button);
        mTryButton = v.findViewById(R.id.restore_try_button);
        mReselectButton = v.findViewById(R.id.restore_reselect_button);
        mTryStatus = v.findViewById(R.id.restore_try_status);

        mOkButton.setOnClickListener(this);
        mVerifyButton.setOnClickListener(this);
        mCancelButton.setOnClickListener(this);
        mTryButton.setOnClickListener(this);
        mReselectButton.setOnClickListener(this);

        // Clear error when user starts typing
        mPasswordInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mPasswordLayout.getError() != null) {
                    mPasswordLayout.setError(null);
                }
                mPasswordLayout.setHelperText(null);

                /* 输入变了，基于旧输入的候选会话作废 */
                resetCandidateSearchState();
                if (mListener != null) {
                    mListener.onPasswordEdited();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Handle Enter/Go key press
        mPasswordInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    mOkButton.performClick();
                    return true;
                }
                return false;
            }
        });

        return v;
    }

    public void setListener(RestoreDialogListener listener) {
        mListener = listener;
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();

        /* 运行中只允许「停止」；其余按钮已置灰，但输入法的 Go 键经 performClick 不受置灰限制 */
        if (mTryState == TryState.RUNNING && id != R.id.restore_try_button) {
            return;
        }

        if (id == R.id.restore_try_button) {
            if (mTryState == TryState.RUNNING) {
                if (mListener != null) {
                    mListener.onStopCandidates();
                }
            } else if (mPasswordInput != null && mListener != null) {
                String password = mPasswordInput.getText().toString();
                if (password.isEmpty()) {
                    mPasswordLayout.setError(getString(R.string.main_restore_password_required));
                    return;
                }
                mListener.onTryCandidates(password);
            }
        } else if (id == R.id.restore_reselect_button) {
            if (mListener != null) {
                mListener.onChooseAnotherFile();
            }
        } else if (id == R.id.restore_ok_button || id == R.id.restore_verify_button) {
            if (mPasswordInput != null && mListener != null) {
                String password = mPasswordInput.getText().toString();

                // Validate password is not empty
                if (password.isEmpty()) {
                    mPasswordLayout.setError(getString(R.string.main_restore_password_required));
                    return;
                }

                if (id == R.id.restore_verify_button) {
                    mListener.onVerifyPassword(password);
                } else {
                    mListener.onRestorePassword(password);
                }
            }
        } else if (id == R.id.restore_cancel_button) {
            if (mListener != null) {
                mListener.onRestoreCancel();
            }
        }
    }

    public void clearPassword() {
        if (mPasswordInput != null) {
            mPasswordInput.setText("");
        }
        if (mPasswordLayout != null) {
            mPasswordLayout.setError(null);
        }
    }

    public void showBadPasswordError() {
        if (mPasswordLayout != null) {
            mPasswordLayout.setHelperText(null);
            mPasswordLayout.setError(getString(R.string.main_restore_bad_password));
            mPasswordInput.requestFocus();
        }
    }

    public void showGoodPasswordResult() {
        if (mPasswordLayout != null) {
            mPasswordLayout.setError(null);
            mPasswordLayout.setHelperText(getString(R.string.main_restore_good_password));
        }
    }

    public void showSearchRunning(int done, int total, int round) {
        renderTryState(TryState.RUNNING);
        showTryStatus(getString(R.string.main_restore_try_progress, done, total, round));
    }

    public void updateSearchProgress(int done, int total, int round) {
        showTryStatus(getString(R.string.main_restore_try_progress, done, total, round));
    }

    public void showSearchStopped(int done, int total) {
        renderTryState(TryState.PAUSED);
        showTryStatus(getString(R.string.main_restore_try_stopped, done, total));
    }

    public void showRoundFinished(int round, int done, int total) {
        renderTryState(TryState.PAUSED);
        showTryStatus(getString(R.string.main_restore_try_round_finished, round, done, total));
    }

    public void showCandidateFound(String password, int index, int total) {
        renderTryState(TryState.IDLE);
        /* setText 会触发的 onTextChanged 先清掉旧状态，命中提示随后覆盖它 */
        mPasswordInput.setText(password);
        mPasswordInput.setSelection(password.length());
        mPasswordLayout.setError(null);
        showTryStatus(getString(R.string.main_restore_try_found, index, total));
    }

    public void showCandidatesExhausted(int total) {
        renderTryState(TryState.IDLE);
        showTryStatus(getString(R.string.main_restore_try_none, total));
    }

    private void renderTryState(TryState state) {
        mTryState = state;
        boolean running = state == TryState.RUNNING;
        mOkButton.setEnabled(!running);
        mVerifyButton.setEnabled(!running);
        mCancelButton.setEnabled(!running);
        mReselectButton.setEnabled(!running);
        mPasswordInput.setEnabled(!running);
        mTryButton.setText(state == TryState.RUNNING ? R.string.main_restore_try_stop
                : state == TryState.PAUSED ? R.string.main_restore_try_continue
                : R.string.main_restore_try);
    }

    private void resetCandidateSearchState() {
        mTryStatus.setVisibility(View.GONE);
        renderTryState(TryState.IDLE);
    }

    private void showTryStatus(CharSequence text) {
        mTryStatus.setText(text);
        mTryStatus.setVisibility(View.VISIBLE);
    }
}
