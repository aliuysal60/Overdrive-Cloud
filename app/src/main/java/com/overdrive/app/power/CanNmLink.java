package com.overdrive.app.power;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pure-Java replacement for the closed-source {@code libodpark.so} parked transport.
 *
 * <p>Static analysis of that library (2026-10-25) showed it speaks exactly this
 * protocol — its "obfuscated" constants are a byte-wise {@code xor(i*29+c)} blob
 * that decodes to the OTA update service endpoint:
 *
 * <ul>
 *   <li>action {@code android.Otasdk.OtaTsUpdateService}</li>
 *   <li>component {@code com.ts.ota.otasdkmgr / .OtaTsUpdateService}</li>
 *   <li>binder descriptor {@code com.ts.ota.otasdkmgr.IOtaSdkManager} (verified after bind)</li>
 *   <li>transaction 135 = {@code TRANSACTION_sendCanNM}, arguments {@code {0, mode}}</li>
 *   <li>mode 0 = HOLD, 1 = RELEASE (also hardcoded in the blob as two xor'd ints)</li>
 *   <li>async confirmation through a callback binder describing
 *       {@code com.ts.ota.otasdkmgr.IAbsMessageCallback}, event code 11</li>
 * </ul>
 *
 * <p>That is CAN Network Management hold — the same lever the OEM's own OTA
 * update flow uses to keep the vehicle network awake, and the same protocol
 * Otto Plus implements openly (cross-validated against its decompiled
 * Dl5CanNetworkClient). The native library additionally SHA-256-verifies the
 * APK signer against the upstream release key and returns -109 for any other
 * signature, which is why a self-built APK could never use it; reimplementing
 * the transport here removes that gate without changing the wire protocol.
 *
 * <p>Threading: connection state lives behind {@link #LOCK}; the bind latch is
 * awaited WITHOUT holding the lock (the service callback stores the binder
 * under the lock and counts the latch down outside it), so the first bind can
 * confirm well inside the timeout instead of deadlocking against it.
 */
final class CanNmLink {

    private CanNmLink() {}

    // ── Wire protocol (matches IOtaSdkManager's AIDL ordering) ────────────

    static final String SERVICE_PACKAGE = "com.ts.ota.otasdkmgr";
    static final String SERVICE_CLASS = "com.ts.ota.otasdkmgr.OtaTsUpdateService";
    static final String SERVICE_ACTION = "android.Otasdk.OtaTsUpdateService";
    static final String SERVICE_APK_PATH =
            "/system/app/OtaTsUpdateService/OtaTsUpdateService.apk";

    static final String MANAGER_DESCRIPTOR = "com.ts.ota.otasdkmgr.IOtaSdkManager";
    static final String MANAGER_STUB_CLASS = MANAGER_DESCRIPTOR + "$Stub";
    static final String CALLBACK_DESCRIPTOR = "com.ts.ota.otasdkmgr.IAbsMessageCallback";
    static final String CALLBACK_STUB_CLASS = CALLBACK_DESCRIPTOR + "$Stub";

    static final int CODE_SEND_CAN_NM = 135;
    static final int CODE_ON_SEND_CAN_NM = 11;
    static final int MODE_HOLD = 0;
    static final int MODE_RELEASE = 1;

    static final long BIND_TIMEOUT_MS = 3_000L;
    static final long CALLBACK_TIMEOUT_MS = 4_000L;

    // ── Connection state ──────────────────────────────────────────────────

    private static final Object LOCK = new Object();

    private static Context appContext;
    private static IBinder binder;
    private static boolean bound;
    private static CountDownLatch connectLatch = new CountDownLatch(1);
    private static volatile int sendCanNmCode = CODE_SEND_CAN_NM;
    private static volatile int onSendCanNmCode = CODE_ON_SEND_CAN_NM;
    private static volatile boolean codesResolved;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CountDownLatch toCount;
            synchronized (LOCK) {
                binder = service;
                if (service != null) {
                    try {
                        service.linkToDeath(DEATH_RECIPIENT, 0);
                    } catch (Throwable ignored) {
                    }
                }
                toCount = connectLatch;
            }
            // Outside the lock: a requester holding LOCK elsewhere never
            // blocks this countdown.
            toCount.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            onBinderGone();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onBinderGone();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            CountDownLatch toCount;
            synchronized (LOCK) {
                binder = null;
                toCount = connectLatch;
            }
            toCount.countDown();
        }
    };

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            onBinderGone();
        }
    };

    // ── Entry point (ParkedLinkContract operations) ───────────────────────

    /**
     * Executes one parked-transport operation in the app process.
     *
     * @param operation {@link ParkedLinkContract#OP_ASSERT} (CAN NM hold),
     *                  {@link ParkedLinkContract#OP_RELEASE} (CAN NM release) or
     *                  {@link ParkedLinkContract#OP_DETACH} (unbind only, no wire write)
     * @return a {@link ParkedLinkContract} result code
     */
    static int execute(Context context, int operation) {
        if (context == null) return ParkedLinkContract.RESULT_BAD_REQUEST;
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            appContext = app;
            resolveTransactionCodesLocked();
        }
        switch (operation) {
            case ParkedLinkContract.OP_ASSERT:
                return request(MODE_HOLD);
            case ParkedLinkContract.OP_RELEASE:
                return request(MODE_RELEASE);
            case ParkedLinkContract.OP_DETACH:
                synchronized (LOCK) {
                    unbindLocked();
                }
                return ParkedLinkContract.RESULT_CONFIRMED;
            default:
                return ParkedLinkContract.RESULT_BAD_REQUEST;
        }
    }

    // ── CAN NM request ────────────────────────────────────────────────────

    private static int request(int mode) {
        if (!isServiceAvailable()) {
            return ParkedLinkContract.RESULT_SERVICE_UNAVAILABLE;
        }
        IBinder target = ensureBound(BIND_TIMEOUT_MS);
        if (target == null) {
            return ParkedLinkContract.RESULT_BIND_FAILED;
        }
        String descriptor = null;
        try {
            descriptor = target.getInterfaceDescriptor();
        } catch (Throwable ignored) {
        }
        if (!MANAGER_DESCRIPTOR.equals(descriptor)) {
            // Not the endpoint we verified statically: refuse to transact.
            return ParkedLinkContract.RESULT_REJECTED;
        }
        CallbackBinder cb = new CallbackBinder();
        cb.setExpectedCode(onSendCanNmCode);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MANAGER_DESCRIPTOR);
            // IOtaSdkManager.sendCanNM(int session, int mode, IAbsMessageCallback cb)
            data.writeInt(0);
            data.writeInt(mode);
            data.writeStrongBinder(cb);
            long sequence = cb.sequence();
            boolean transacted;
            try {
                transacted = target.transact(sendCanNmCode, data, reply, 0);
            } catch (DeadObjectException dead) {
                onBinderGone();
                return ParkedLinkContract.RESULT_TRANSPORT_FAILED;
            } catch (Throwable transport) {
                return ParkedLinkContract.RESULT_TRANSPORT_FAILED;
            }
            if (!transacted) {
                // Unknown transaction code for this firmware: definitely not applied.
                return ParkedLinkContract.RESULT_NOT_DISPATCHED;
            }
            reply.readException();
            boolean accepted = reply.readInt() != 0;
            if (!accepted) {
                return ParkedLinkContract.RESULT_REJECTED;
            }
            Integer confirm = cb.await(sequence, CALLBACK_TIMEOUT_MS);
            return confirm != null
                    ? ParkedLinkContract.RESULT_CONFIRMED
                    : ParkedLinkContract.RESULT_DISPATCHED_UNCONFIRMED;
        } catch (Throwable failure) {
            return ParkedLinkContract.RESULT_INTERNAL_FAILED;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ── Service availability / binding ────────────────────────────────────

    private static boolean isServiceAvailable() {
        Context app = appContext;
        if (app == null) return false;
        try {
            ServiceInfo info = app.getPackageManager().getServiceInfo(
                    new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS), 0);
            return info != null && info.exported;
        } catch (Throwable t) {
            return false;
        }
    }

    private static IBinder ensureBound(long timeoutMs) {
        CountDownLatch latch;
        synchronized (LOCK) {
            if (binder != null && binder.isBinderAlive()) return binder;
            if (!bound) {
                connectLatch = new CountDownLatch(1);
                Intent intent = new Intent(SERVICE_ACTION)
                        .setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
                try {
                    bound = appContext.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
                } catch (Throwable t) {
                    bound = false;
                }
                if (!bound) {
                    try {
                        appContext.unbindService(CONNECTION);
                    } catch (Throwable ignored) {
                    }
                    return null;
                }
            }
            latch = connectLatch;
        }
        // Wait WITHOUT the lock so the service callback can store the binder.
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (LOCK) {
            return binder != null && binder.isBinderAlive() ? binder : null;
        }
    }

    private static void unbindLocked() {
        if (binder != null) {
            try {
                binder.unlinkToDeath(DEATH_RECIPIENT, 0);
            } catch (Throwable ignored) {
            }
        }
        binder = null;
        if (bound) {
            try {
                appContext.unbindService(CONNECTION);
            } catch (Throwable ignored) {
            }
        }
        bound = false;
        connectLatch = new CountDownLatch(1);
    }

    private static void onBinderGone() {
        synchronized (LOCK) {
            if (binder != null) {
                try {
                    binder.unlinkToDeath(DEATH_RECIPIENT, 0);
                } catch (Throwable ignored) {
                }
            }
            binder = null;
            connectLatch = new CountDownLatch(1);
        }
    }

    // ── Transaction code resolution (firmware-proof) ──────────────────────

    /**
     * Reads {@code TRANSACTION_sendCanNM} / {@code TRANSACTION_onSendCanNM}
     * from the service APK itself via a throwaway DexClassLoader, so a firmware
     * that reorders the AIDL methods cannot silently misroute the transact.
     * Falls back to the codes hardcoded in libodpark.so (135/11), which are
     * the values observed on this vehicle's firmware.
     */
    private static void resolveTransactionCodesLocked() {
        if (codesResolved) return;
        codesResolved = true;
        try {
            File apk = new File(SERVICE_APK_PATH);
            if (!apk.canRead()) return;
            File odex = appContext.getDir("dl5_ota_dex", 0);
            DexClassLoader loader = new DexClassLoader(
                    apk.getAbsolutePath(), odex.getAbsolutePath(), null,
                    appContext.getClassLoader());
            Integer send = readStaticInt(loader, MANAGER_STUB_CLASS, "TRANSACTION_sendCanNM");
            Integer onSend = readStaticInt(loader, CALLBACK_STUB_CLASS, "TRANSACTION_onSendCanNM");
            if (send != null && send >= 1 && send < 0x1000000) sendCanNmCode = send;
            if (onSend != null && onSend >= 1 && onSend < 0x1000000) onSendCanNmCode = onSend;
        } catch (Throwable ignored) {
            // Fallback codes stay in place.
        }
    }

    private static Integer readStaticInt(ClassLoader loader, String className, String field) {
        try {
            Field f = Class.forName(className, false, loader).getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Async confirmation callback ───────────────────────────────────────

    /**
     * Server-side callbacks land here as binder transactions carrying one int.
     * INTERFACE_TRANSACTION (1598968902) is answered with our descriptor, the
     * same handshake libodpark.so performs.
     */
    static final class CallbackBinder extends Binder {
        private static final int INTERFACE_TRANSACTION = 1598968902;

        private final Object monitor = new Object();
        private volatile int expectedCode = CODE_ON_SEND_CAN_NM;
        private long sequence;
        private int lastResult;

        CallbackBinder() {
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        void setExpectedCode(int code) {
            expectedCode = code;
        }

        long sequence() {
            synchronized (monitor) {
                return sequence;
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == expectedCode) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                int result = data.readInt();
                synchronized (monitor) {
                    lastResult = result;
                    sequence++;
                    monitor.notifyAll();
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        Integer await(long afterSequence, long timeoutMs) {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            synchronized (monitor) {
                while (sequence <= afterSequence) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) return null;
                    try {
                        monitor.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return lastResult;
            }
        }
    }
}
