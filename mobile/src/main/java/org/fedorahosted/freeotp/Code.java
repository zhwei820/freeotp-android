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

package org.fedorahosted.freeotp;

import androidx.annotation.Nullable;
import org.fedorahosted.freeotp.utils.Time;

public class Code {
    static class Factory {
        // See: RFC 4226, Section 4, R4
        private static final double RFC_MIN = Math.pow(10, 6);
        private static final double RFC_MAX = Integer.MAX_VALUE;

        static Factory fromIssuer(String issuer) {
            if (issuer == null)
                return new Factory("0123456789");

            if (issuer.equals("Steam"))
                return new Factory("23456789BCDFGHJKMNPQRTVWXY");

            return new Factory("0123456789");
        }

        private final char[] mAlphabet;
        private final int mDigits;

        private Factory(String alphabet) {
            mAlphabet = alphabet.toCharArray();
            mDigits = getDigitsMin();
        }

        int getDigitsMin() {
            return (int) Math.ceil(Math.log(RFC_MIN) / Math.log(mAlphabet.length));
        }

        int getDigitsMax() {
            return (int) Math.floor(Math.log(RFC_MAX) / Math.log(mAlphabet.length));
        }

        Code makeHotpCode(int code, @Nullable Integer digits, int period, boolean reverse) {
            return new Code(format(code, digits, reverse), period);
        }

        Code makeTotpCode(int code, int nextCode, @Nullable Integer digits, int period,
                          boolean reverse, long now) {
            long periodMs = period * 1000L;
            long windowStart = now / periodMs * periodMs;
            Code current = new Code(format(code, digits, reverse), period, windowStart);
            current.mNext = new Code(format(nextCode, digits, reverse), period, windowStart + periodMs);
            return current;
        }

        private String format(int code, @Nullable Integer digits, boolean reverse) {
            if (digits == null)
                digits = mDigits;

            char[] buffer = new char[digits];

            for (int i = 0; i < digits; i++) {
                if (reverse)
                    buffer[i] = mAlphabet[code % mAlphabet.length];
                else
                    buffer[digits - i - 1] = mAlphabet[code % mAlphabet.length];
                code /= mAlphabet.length;
            }

            return new String(buffer);
        }
    }

    private final String mCode;
    private final long mPeriod;
    private final long mStart;
    private final long mWindowStart; // For TOTP: the epoch window this code is valid for
    private Code mNext;

    public Code(String code, long period) {
        // HOTP: valid for one period from when the code was generated
        mPeriod = period * 1000;
        mStart = Time.INSTANCE.current();
        mWindowStart = 0; // Not used for HOTP
        mCode = code;
    }

    private Code(String code, long period, long windowStart) {
        mPeriod = period * 1000;
        mStart = 0; // Flag to indicate TOTP mode
        mWindowStart = windowStart;
        mCode = code;
    }

    /**
     * The TOTP code for the following window, computed together with this one because a
     * locked token's key only stays authorized for one period. Null for HOTP: generating
     * one advances the counter.
     */
    @Nullable
    public Code getNext() {
        return mNext;
    }

    public String getCode() {
        return mCode;
    }

    public long timeValid() {
        return mPeriod;
    }

    public long timeLeft() {
        long now = Time.INSTANCE.current();
        if (mStart == 0) {
            // TOTP mode: calculate time until the end of this code's window
            long left = mWindowStart + mPeriod - now;
            return Math.max(0, Math.min(left, mPeriod));
        } else {
            // HOTP mode: calculate time since code was generated
            long left = mStart + mPeriod - now;
            return left < 0 ? 0 : left;
        }
    }

    public int getProgress(int max) {
        return (int) (timeLeft() * max / timeValid());
    }

    public boolean isValid() {
        if (mStart == 0) {
            // TOTP mode: check if we're still in the same window
            long now = Time.INSTANCE.current();
            long currentWindowStart = (now / mPeriod) * mPeriod;
            return currentWindowStart == mWindowStart;
        } else {
            // HOTP mode: check if time hasn't expired
            return timeLeft() > 0;
        }
    }
}
