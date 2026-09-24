/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/


/*
 * Helper class to manage the Bluetooth connection
 */
package lu.fisch.canze.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.InvalidParameterException;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.interfaces.BluetoothEvent;

/**
 * Created by robertfisch on 03.09.2015.
 *
 * Threading model: connect() never blocks its caller. Every connection attempt runs on its
 * own thread and at most one attempt runs at any time. Every connect() and disconnect()
 * starts a new "generation"; an attempt or retry belonging to an older generation gives up
 * instead of touching the socket, so a pause can never be undone by a late retry.
 */
public class BluetoothManager {

    /* --------------------------------
     * Sigleton stuff
     \ ------------------------------ */

    private static BluetoothManager bluetoothManager = null;

    public static synchronized BluetoothManager getInstance() {
        if (bluetoothManager == null)
            bluetoothManager = new BluetoothManager();
        return bluetoothManager;
    }

    /* --------------------------------
     * Attributes
     \ ------------------------------ */
    // SPP UUID service
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public static final int STATE_BLUETOOTH_NOT_AVAILABLE = -1;
    public static final int STATE_BLUETOOTH_ACTIVE = 1;
    public static final int STATE_BLUETOOTH_NOT_ACTIVE = 0;

    /** deliberately disconnected (pause, restart). Never a reason to show the reconnect UI */
    public static final int CONNECTION_STOPPED = 0;
    /** gave up (no tries left, or Bluetooth is switched off) */
    public static final int CONNECTION_DISCONNECTED = 1;
    /** an attempt is running or scheduled */
    public static final int CONNECTION_CONNECTING = 2;
    /** socket is up, dongle not initialised yet */
    public static final int CONNECTION_CONNECTED = 3;
    /** dongle answered its init, data flows */
    public static final int CONNECTION_READY = 4;

    public static final int RETRIES_NONE = 0;
    public static final int RETRIES_INFINITE = -1;

    private static final long RETRY_DELAY_MS = 2000;

    public interface ConnectionStateListener {
        /** always called on the main thread */
        void onConnectionStateChanged(int state, int attempt);
    }

    private final BluetoothAdapter bluetoothAdapter;
    private final Object stateLock = new Object();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final CopyOnWriteArrayList<ConnectionStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile BluetoothSocket bluetoothSocket = null;
    private volatile InputStream inputStream = null;
    private volatile OutputStream outputStream = null;

    private volatile boolean dummyMode = false;
    private volatile BluetoothEvent bluetoothEvent;

    private volatile String connectBluetoothAddress = null;
    private volatile boolean connectSecure = true;
    private volatile int connectRetries = RETRIES_NONE;

    // all guarded by stateLock
    private boolean retry = true;
    private boolean connecting = false;
    private int connectingGeneration = -1;
    private boolean pendingConnect = false;
    private Thread retryThread = null;

    private volatile int connectionState = CONNECTION_STOPPED;
    private volatile int connectionAttempt = 0;
    private volatile long lastStopAt = 0;

    /**
     * true once the dongle reached CONNECTION_READY during this app session. Kept in memory
     * only on purpose: every launch starts without it, so the reconnect UI never blocks the
     * demo modes before a real connection has happened. Cleared when the target dongle changes.
     */
    private volatile boolean everReady = false;

    public boolean isDummyMode() {
        return dummyMode;
    }

    private void debug(String text) {
        MainActivity.debug(this.getClass().getSimpleName() + ": " + text);
    }

    /**
     * Create a new manager
     */
    private BluetoothManager() {
        // get Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Determine the state of the Bluetooth hardware
     *
     * @return the state of the Bluetooth hardware
     */
    public int getHardwareState() {
        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null

        if (dummyMode) return STATE_BLUETOOTH_ACTIVE;

        if (bluetoothAdapter == null) {
            return STATE_BLUETOOTH_NOT_AVAILABLE;
        }
        return bluetoothAdapter.isEnabled() ? STATE_BLUETOOTH_ACTIVE : STATE_BLUETOOTH_NOT_ACTIVE;
    }

    /**
     * Creates a new Bluetooth socket from a given device
     */
    private BluetoothSocket createBluetoothSocket(BluetoothDevice device, boolean secure) throws IOException {
        try {
            final String methodName = secure ? "createRfcommSocketToServiceRecord" : "createInsecureRfcommSocketToServiceRecord";
            final Method m = device.getClass().getMethod(methodName, new Class[]{UUID.class});
            return (BluetoothSocket) m.invoke(device, MY_UUID);
        } catch (Exception e) {
            debug("Could not create RFComm Connection: " + e.getMessage());
        }
        return device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    /* --------------------------------
     * connect / disconnect
     \ ------------------------------ */

    public void connect() {
        if (dummyMode) return;

        if (connectBluetoothAddress == null)
            throw new InvalidParameterException("connect() has to be called at least once with parameters!");
        connect(connectBluetoothAddress, connectSecure, connectRetries);
    }

    /**
     * Start connecting. Returns immediately; the result is reported through the
     * BluetoothEvent and the connection state listeners.
     */
    public void connect(final String bluetoothAddress, final boolean secure, final int retries) {
        if (dummyMode) return;
        if (bluetoothAddress == null || bluetoothAddress.isEmpty()) {
            debug("BT: connect ignored, no device address given");
            return;
        }

        final int gen;
        final Thread staleRetry;
        synchronized (stateLock) {
            final String previousAddress = connectBluetoothAddress;
            if (previousAddress != null && !previousAddress.equalsIgnoreCase(bluetoothAddress)) {
                // a different dongle has not proven itself during this session yet
                everReady = false;
            }
            connectBluetoothAddress = bluetoothAddress;
            connectSecure = secure;
            connectRetries = retries;
            retry = true;

            if (connecting) {
                if (connectingGeneration == generation.get()) {
                    debug("BT: connect already in progress");
                } else {
                    // an attempt cancelled by disconnect() is still winding down
                    debug("BT: previous attempt still winding down, connecting right after it");
                    pendingConnect = true;
                }
                return;
            }
            if (isConnected()) {
                debug("BT: already connected, nothing to do");
                return;
            }

            gen = generation.incrementAndGet();
            staleRetry = retryThread;
            retryThread = null;
            connecting = true;
            connectingGeneration = gen;
        }

        if (staleRetry != null) staleRetry.interrupt();
        startAttemptThread(gen, 1);
    }

    private void startAttemptThread(final int gen, final int attempt) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runAttempt(gen, attempt);
            }
        }, "CanZE-bt-connect");
        thread.start();
    }

    /** caller must own the "connecting" flag */
    private void runAttempt(int gen, int attempt) {
        boolean connected = false;
        boolean restart = false;
        try {
            connected = openSocket(gen, attempt);
        } catch (RuntimeException e) {
            debug("BT: unexpected error while connecting: " + e);
        } finally {
            synchronized (stateLock) {
                connecting = false;
                restart = pendingConnect;
                pendingConnect = false;
            }
        }

        if (restart) {
            connect();
            return;
        }
        if (!connected) scheduleRetry(gen, attempt);
    }

    private boolean openSocket(int gen, int attempt) {
        final String address = connectBluetoothAddress;

        if (getHardwareState() != STATE_BLUETOOTH_ACTIVE) {
            debug("Bluetooth not active");
            if (isCurrent(gen)) publishState(CONNECTION_DISCONNECTED, attempt);
            return false;
        }
        if (!isCurrent(gen)) return false;

        publishState(CONNECTION_CONNECTING, attempt);

        final BluetoothEvent event = bluetoothEvent;
        if (event != null) event.onBeforeConnect();

        final BluetoothSocket socket = createSocket(address, connectSecure);
        if (socket == null) return false;

        synchronized (stateLock) {
            if (!isCurrentLocked(gen)) {
                closeQuietly(socket);
                return false;
            }
            // make sure there is no more active connection
            closeQuietly(bluetoothSocket);
            bluetoothSocket = socket;
            inputStream = null;
            outputStream = null;
        }

        cancelDiscovery();

        try {
            debug("Connect the socket (attempt " + attempt + ")");
            // blocking; disconnect() aborts it by closing the socket
            socket.connect();

            debug("Connect the streams");
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            synchronized (stateLock) {
                if (!isCurrentLocked(gen) || bluetoothSocket != socket) {
                    closeQuietly(socket);
                    return false;
                }
                inputStream = in;
                outputStream = out;
            }
        } catch (IOException e) {
            debug("Connect failed: " + e.getMessage());
            releaseSocket(socket);
            return false;
        }

        debug("Connected");
        publishState(CONNECTION_CONNECTED, attempt);
        if (event != null) event.onAfterConnect(socket);
        return true;
    }

    private BluetoothSocket createSocket(String address, boolean secure) {
        try {
            debug("Get remote device: " + address);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            debug("Create new socket");
            return createBluetoothSocket(device, secure);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            debug("Could not create socket: " + e.getMessage());
            return null;
        }
    }

    private void cancelDiscovery() {
        try {
            // discovery is resource intensive so make sure it is stopped
            bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) {
            debug("Cancel discovery not permitted: " + e.getMessage());
        }
    }

    private void scheduleRetry(final int gen, final int attempt) {
        final int retries = connectRetries;
        if (retries != RETRIES_INFINITE && attempt > retries) {
            debug("BT: no tries left");
            if (isCurrent(gen)) publishState(CONNECTION_DISCONNECTED, attempt);
            return;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    debug("BT: retry cancelled");
                    return;
                }
                if (tryAcquire(gen)) runAttempt(gen, attempt + 1);
            }
        }, "CanZE-bt-retry");

        synchronized (stateLock) {
            if (!isCurrentLocked(gen)) return;
            retryThread = thread;
        }
        debug("Starting new try in " + RETRY_DELAY_MS + " ms");
        thread.start();
    }

    private boolean tryAcquire(int gen) {
        synchronized (stateLock) {
            if (connecting || !isCurrentLocked(gen)) return false;
            if (retryThread == Thread.currentThread()) retryThread = null;
            connecting = true;
            connectingGeneration = gen;
            return true;
        }
    }

    private boolean isCurrent(int gen) {
        synchronized (stateLock) {
            return isCurrentLocked(gen);
        }
    }

    private boolean isCurrentLocked(int gen) {
        return retry && gen == generation.get();
    }

    private void releaseSocket(BluetoothSocket socket) {
        synchronized (stateLock) {
            if (bluetoothSocket == socket) {
                bluetoothSocket = null;
                inputStream = null;
                outputStream = null;
            }
        }
        closeQuietly(socket);
    }

    private void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException e) {
            debug("Closing socket failed: " + e.getMessage());
        }
    }

    /**
     * Close the link and cancel every pending attempt or retry. Never blocks on other threads.
     */
    public void disconnect() {

        if (dummyMode) return;

        final BluetoothSocket socket;
        final Thread pendingRetry;
        synchronized (stateLock) {
            generation.incrementAndGet();
            retry = false;
            pendingConnect = false;
            pendingRetry = retryThread;
            retryThread = null;
            socket = bluetoothSocket;
            bluetoothSocket = null;
            inputStream = null;
            outputStream = null;
            lastStopAt = SystemClock.elapsedRealtime();
        }

        // execute attached event
        final BluetoothEvent event = bluetoothEvent;
        if (event != null) event.onBeforeDisconnect(socket);

        if (pendingRetry != null) pendingRetry.interrupt();

        debug("Closing socket");
        closeQuietly(socket);
        publishState(CONNECTION_STOPPED, 0);

        // execute attached event
        if (event != null) event.onAfterDisconnect();

        debug("Closed");
    }

    /* --------------------------------
     * input / output
     \ ------------------------------ */

    // write a message to the output stream
    public void write(String message) {

        if (dummyMode || message == null) return;

        final OutputStream out = outputStream;
        if (out == null || !isConnected()) {
            MainActivity.debug("Write failed! Socket is closed ... M = " + message);
            return;
        }
        try {
            out.write(message.getBytes());
        } catch (IOException e) {
            Log.d(MainActivity.TAG, "BT: Error sending > " + e.getMessage());
        }
    }

    public int read(byte[] buffer) throws IOException {

        if (dummyMode || buffer == null) return 0;

        final InputStream in = inputStream;
        if (in == null || !isConnected()) return 0;
        return in.read(buffer);
    }

    public int read() throws IOException {

        if (dummyMode) return -1;

        final InputStream in = inputStream;
        if (in == null || !isConnected()) return -1;
        return in.read();
    }

    public int available() throws IOException {

        if (dummyMode) return 0;

        final InputStream in = inputStream;
        if (in == null || !isConnected()) return 0;
        return in.available();
    }

    public boolean isConnected() {

        if (dummyMode) return true;

        final BluetoothSocket socket = bluetoothSocket;
        return socket != null && socket.isConnected() && inputStream != null;
    }

    /* --------------------------------
     * Connection state
     \ ------------------------------ */

    public int getConnectionState() {
        return connectionState;
    }

    public int getConnectionAttempt() {
        return connectionAttempt;
    }

    /** true if the last thing that happened was a deliberate disconnect() */
    public boolean isStopped() {
        return connectionState == CONNECTION_STOPPED;
    }

    /** true if disconnect() was called less than windowMs ago */
    public boolean wasStoppedWithin(long windowMs) {
        final long at = lastStopAt;
        return at > 0 && SystemClock.elapsedRealtime() - at < windowMs;
    }

    /**
     * true if the current dongle reached CONNECTION_READY at least once during this app
     * session. Before that, connection trouble is not a "lost" connection and must not
     * trigger the reconnect UI.
     */
    public boolean hasEverBeenReady() {
        return everReady;
    }

    /** called by the device once the dongle answered its initialisation */
    public void publishReady() {
        publishState(CONNECTION_READY, 0);
    }

    /** called when the device gives up on the link and a restart is about to happen */
    public void publishConnecting() {
        publishState(CONNECTION_CONNECTING, Math.max(1, connectionAttempt));
    }

    public void addStateListener(ConnectionStateListener listener) {
        if (listener != null) stateListeners.addIfAbsent(listener);
    }

    public void removeStateListener(ConnectionStateListener listener) {
        if (listener != null) stateListeners.remove(listener);
    }

    private void publishState(final int state, final int attempt) {
        // set before posting, so listeners on the main thread always see the updated flag
        if (state == CONNECTION_READY) everReady = true;
        connectionState = state;
        connectionAttempt = attempt;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (ConnectionStateListener listener : stateListeners) {
                    try {
                        listener.onConnectionStateChanged(state, attempt);
                    } catch (RuntimeException e) {
                        debug("Connection state listener failed: " + e);
                    }
                }
            }
        });
    }

    /* --------------------------------
     * Events
     \ ------------------------------ */

    public void setBluetoothEvent(BluetoothEvent bluetoothEvent) {

        if (dummyMode) return;

        this.bluetoothEvent = bluetoothEvent;
    }

    public void setDummyMode(boolean dummyMode) {
        this.dummyMode = dummyMode;
    }

}
