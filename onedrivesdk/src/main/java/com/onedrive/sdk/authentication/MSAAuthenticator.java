// ------------------------------------------------------------------------------
// Copyright (c) 2015 Microsoft Corporation
// 
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
// 
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
// 
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
// ------------------------------------------------------------------------------

package com.onedrive.sdk.authentication;

import android.app.Activity;

import com.microsoft.services.msa.LiveAuthClient;
import com.microsoft.services.msa.LiveAuthException;
import com.microsoft.services.msa.LiveAuthListener;
import com.microsoft.services.msa.LiveConnectSession;
import com.microsoft.services.msa.LiveStatus;
import com.onedrive.sdk.concurrency.ICallback;
import com.onedrive.sdk.core.ClientException;
import com.onedrive.sdk.concurrency.SimpleWaiter;
import com.onedrive.sdk.concurrency.IExecutors;
import com.onedrive.sdk.core.OneDriveErrorCodes;
import com.onedrive.sdk.http.IHttpProvider;
import com.onedrive.sdk.logger.ILogger;

import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wrapper around the MSA authentication library.
 * https://github.com/MSOpenTech/msa-auth-for-android
 */
@SuppressWarnings("ThrowableResultOfMethodCallIgnored")
public abstract class MSAAuthenticator implements IAuthenticator {

    /**
     * The sign in cancellation message.
     */
    private static final String SIGN_IN_CANCELLED_MESSAGE = "The user cancelled the login operation.";

    /**
     * The executors.
     */
    private IExecutors mExecutors;

    /**
     * Indicates whether this authenticator has been initialized.
     */
    private boolean mInitialized;

    /**
     * The context UI interactions should happen with.
     */
    private Activity mActivity;

    /**
     * The logger.
     */
    private ILogger mLogger;

    /**
     * The client id for this authenticator.
     * https://dev.onedrive.com/auth/msa_oauth.htm#to-register-your-app
     * @return The client id.
     */
    public abstract String getClientId();

    /**
     * The scopes for this application.
     * https://dev.onedrive.com/auth/msa_oauth.htm#authentication-scopes
     * @return The scopes for this application.
     */
    public abstract String[] getScopes();

    /**
     * The live authentication client.
     */
    private LiveAuthClient mAuthClient;

    /**
     * Initializes the authenticator.
     * @param executors The executors to schedule foreground and background tasks.
     * @param httpProvider The http provider for sending requests.
     * @param activity The activity to create interactive UI on.
     * @param logger The logger for diagnostic information.
     */
    @Override
    public synchronized void init(final IExecutors executors,
                     final IHttpProvider httpProvider,
                     final Activity activity,
                     final ILogger logger) {
        if (mInitialized) {
            return;
        }

        mExecutors = executors;
        mActivity = activity;
        mLogger = logger;
        mInitialized = true;
        mAuthClient = new LiveAuthClient(activity, getClientId(), Arrays.asList(getScopes()));
    }

    /**
     * Starts an interactive login asynchronously.
     * @param emailAddressHint The hint for the email address during the interactive login.
     * @param loginCallback The callback to be called when the login is complete.
     */
    @Override
    public void login(final String emailAddressHint, final ICallback<IAccountInfo> loginCallback) {
        if (!mInitialized) {
            throw new IllegalStateException("init must be called");
        }

        if (loginCallback == null) {
            throw new InvalidParameterException("loginCallback");
        }

        mLogger.logDebug("Starting login async");

        mExecutors.performOnBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    mExecutors.performOnForeground(login(emailAddressHint), loginCallback);
                } catch (final ClientException e) {
                    mExecutors.performOnForeground(e, loginCallback);
                }
            }
        });
    }

    /**
     * Starts an interactive login.
     * @param emailAddressHint The hint for the email address during the interactive login.
     * @return The account info.
     * @throws ClientException An exception occurs if the login was unable to complete for any reason.
     */
    @Override
    public synchronized IAccountInfo login(final String emailAddressHint) throws ClientException {
        if (!mInitialized) {
            throw new IllegalStateException("init must be called");
        }

        mLogger.logDebug("Starting login");

        final AtomicReference<ClientException> error = new AtomicReference<>();
        final SimpleWaiter waiter = new SimpleWaiter();

        final LiveAuthListener listener = new LiveAuthListener() {
            @Override
            public void onAuthComplete(final LiveStatus liveStatus,
                                       final LiveConnectSession liveConnectSession,
                                       final Object o) {
                if (liveStatus != LiveStatus.CONNECTED) {
                    error.set(new ClientAuthenticatorException("Was unable to connect to the MSA login session",
                                                               OneDriveErrorCodes.AuthenticationFailure));
                    mLogger.logError(error.get().getMessage(), error.get());
                }

                mLogger.logDebug("Successful login");
                waiter.signal();
            }

            @Override
            public void onAuthError(final LiveAuthException e,
                                    final Object o) {
                OneDriveErrorCodes code = OneDriveErrorCodes.AuthenticationFailure;
                if (e.getError().equals(SIGN_IN_CANCELLED_MESSAGE)) {
                    code = OneDriveErrorCodes.AuthenticationCancelled;
                }

                error.set(new ClientAuthenticatorException("Unable to login with MSA", e, code));
                mLogger.logError(error.get().getMessage(), error.get());
                waiter.signal();
            }
        };

        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAuthClient.login(mActivity, /* scopes */null, /* user object */ null, emailAddressHint, listener);
            }
        });

        mLogger.logDebug("Waiting for MSA callback");
        waiter.waitForSignal();
        final ClientException exception = error.get();
        if (exception != null) {
            throw exception;
        }

        return getAccountInfo();
    }

    /**
     * Starts a silent login asynchronously.
     * @param loginCallback The callback to be called when the login is complete.
     */
    @Override
    public void loginSilent(final ICallback<IAccountInfo> loginCallback) {
        if (!mInitialized) {
            throw new IllegalStateException("init must be called");
        }

        if (loginCallback == null) {
            throw new InvalidParameterException("loginCallback");
        }

        mLogger.logDebug("Starting login silent async");

        mExecutors.performOnBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    mExecutors.performOnForeground(loginSilent(), loginCallback);
                } catch (final ClientException e) {
                    mExecutors.performOnForeground(e, loginCallback);
                }
            }
        });
    }

    /**
     * Starts a silent login.
     * @return The account info.
     * @throws ClientException An exception occurs if the login was unable to complete for any reason.
     */
    @Override
    public synchronized IAccountInfo loginSilent() throws ClientException {
        if (!mInitialized) {
            throw new IllegalStateException("init must be called");
        }

        mLogger.logDebug("Starting login silent");

        final SimpleWaiter loginSilentWaiter = new SimpleWaiter();
        final AtomicReference<ClientException> error = new AtomicReference<>();

        final boolean waitForCallback = mAuthClient.loginSilent(new LiveAuthListener() {
            @Override
            public void onAuthComplete(final LiveStatus liveStatus,
                                       final LiveConnectSession liveConnectSession,
                                       final Object o) {
                if (liveStatus != LiveStatus.CONNECTED) {
                    error.set(new ClientAuthenticatorException("Was unable to connect to the MSA login session",
                                                               OneDriveErrorCodes.AuthenticationFailure));
                    mLogger.logError(error.get().getMessage(), error.get());
                }

                mLogger.logDebug("Successful silent login");
                loginSilentWaiter.signal();
            }

            @Override
            public void onAuthError(final LiveAuthException e,
                                    final Object o) {
                OneDriveErrorCodes code = OneDriveErrorCodes.AuthenticationFailure;
                if (e.getError().equals(SIGN_IN_CANCELLED_MESSAGE)) {
                    code = OneDriveErrorCodes.AuthenticationCancelled;
                }

                error.set(new ClientAuthenticatorException("Login silent authentication error", e, code));
                mLogger.logError(error.get().getMessage(), error.get());
                loginSilentWaiter.signal();
            }
        });

        if (!waitForCallback) {
            mLogger.logDebug("MSA silent auth fast-failed");
            return null;
        }

        mLogger.logDebug("Waiting for MSA callback");
        loginSilentWaiter.waitForSignal();
        final ClientException exception = error.get();
        if (exception != null) {
            throw exception;
        }

        return getAccountInfo();
    }

    /**
     * Log the current user out.
     * @param logoutCallback The callback to be called when the logout is complete.
     */
    @Override
    public void logout(final ICallback<Void> logoutCallback) {
        if (!mInitialized) {
            throw new IllegalStateException("init must be called");
        }

        if (logoutCallback == null) {
            throw new InvalidParameterException("logoutCallback");
        }

        mLogger.logDebug("Starting logout async");

        mExecutors.performOnBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    logout();
                    mExecutors.performOnForeground((Void) null, logoutCallback);
                } catch (final ClientException e) {
                    mExecutors.performOnForeground(e, logoutCallback);
                }
            }
        });
    }

    /**
     * Log the current user out.
     * @throws ClientException An exception occurs if the logout was unable to complete for any reason.
     */
    @Override
    public synchronized void logout() throws ClientException {
        if (!mInitialized) {
            throw new IllegalStateException("init must be called");
        }

        mLogger.logDebug("Starting logout");

        final SimpleWaiter logoutWaiter = new SimpleWaiter();
        final AtomicReference<ClientException> error = new AtomicReference<>();
        mAuthClient.logout(new LiveAuthListener() {
            @Override
            public void onAuthComplete(final LiveStatus liveStatus,
                                       final LiveConnectSession liveConnectSession,
                                       final Object o) {
                mLogger.logDebug("Logout completed");
                logoutWaiter.signal();
            }

            @Override
            public void onAuthError(final LiveAuthException e, final Object o) {
                error.set(new ClientAuthenticatorException("MSA Logout failed",
                                                           e,
                                                           OneDriveErrorCodes.AuthenticationFailure));
                mLogger.logError(error.get().getMessage(), error.get());
                logoutWaiter.signal();
            }
        });

        mLogger.logDebug("Waiting for logout to complete");
        logoutWaiter.waitForSignal();
        final ClientException exception = error.get();
        if (exception != null) {
            throw exception;
        }
    }

    /**
     * Gets the current account info for this authenticator.
     * @return NULL if no account is available.
     */
    @Override
    public IAccountInfo getAccountInfo() {
        final LiveConnectSession session = mAuthClient.getSession();
        if (session == null) {
            return null;
        }

        return new MSAAccountInfo(this, session, mLogger);
    }
}
