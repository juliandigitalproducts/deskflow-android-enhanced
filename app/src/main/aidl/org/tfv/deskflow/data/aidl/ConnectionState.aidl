package org.tfv.deskflow.data.aidl;
import org.tfv.deskflow.data.aidl.ScreenState;

parcelable ConnectionState {
	boolean isEnabled;
	boolean isConnected;
	boolean ackReceived;
    long connectionTimestamp;
    ScreenState screen;

    boolean isCaptureKeyModeEnabled;
    // Set while a TLS server presents a certificate the user has not confirmed yet.
    @nullable String pendingFingerprint;
    @nullable String pendingServer;
    // Why the last attempts failed (ConnectionFailure.code, 0 = none) and how often in a row.
    int failureKind;
    int failedAttempts;

}
