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

package lu.fisch.canze.devices;

import android.os.SystemClock;

import java.io.IOException;
import java.util.Locale;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Ecu;
import lu.fisch.canze.actors.Ecus;
import lu.fisch.canze.actors.Frame;
import lu.fisch.canze.actors.Message;
import lu.fisch.canze.bluetooth.BluetoothManager;

/**
 * ELM327 driver.
 *
 * Reading: all waiting bytes are pulled from the socket in one call into rxBuffer and consumed
 * from there, instead of one socket call per byte. Everything that reads goes through
 * readByte() and pendingBytes(), so bytes read ahead are never lost: the next reader gets them.
 *
 * ISO-TP header caching (MainActivity.elmHeaderCache, off by default): when on, the atsh / atcra
 * / atfcsh triple is only sent when the target ECU changes. It is invalidated by every free
 * frame filter, every atar and every (re)initialisation.
 *
 * Created by robertfisch on 07.09.2015.
 */
public class ELM327 extends Device {

    private static final int DEFAULT_TIMEOUT = 500;
    private static final int FREE_FRAME_MIN_TIMEOUT = 350;
    private static final int RX_BUFFER_SIZE = 1024;
    /** a flush gives up after this many characters: the ELM may be spewing ATMA data */
    private static final int FLUSH_MAX_CHARS = 100;

    private static final char EOM1 = '\r';
    private static final char EOM2 = '>';
    private static final char EOM3 = '?';

    private int generalTimeout = DEFAULT_TIMEOUT;
    private boolean deviceIsInitialized = false;

    /** CAN id the ISO-TP header, filter and flow control point at, 0 if unknown */
    private int lastId = 0;
    private boolean lastCommandWasFreeFrame = false;
    private String lastFreeFrameFilter = "";

    /**
     * Set when a read gave up before the '>' prompt: the stream may still be one message out of
     * phase, so the next command drains before trusting what it reads back.
     */
    private volatile boolean bufferDirty = false;

    private final byte[] rxBuffer = new byte[RX_BUFFER_SIZE];
    private int rxPos = 0;
    private int rxLen = 0;

    /* ----------------------------------------------------------------
     * Initialisation
     \ -------------------------------------------------------------- */

    protected boolean initDevice(int toughness, int retries) {
        if (initDevice(toughness)) return true;
        while (retries-- > 0) {
            if (isStopRequested()) {
                MainActivity.debug("ELM327: initDevice retries abandoned, a stop was requested");
                return false;
            }
            flushWithTimeout(500);
            MainActivity.debug("ELM327: initDevice(" + toughness + "), " + retries + " retries left");
            if (initDevice(toughness)) return true;
        }
        // nobody wants this connection any more (pause, back to the menu, ...): do not resurrect it
        if (isStopRequested()) {
            MainActivity.debug("ELM327: initDevice failed while stopping, not restarting Bluetooth");
            return false;
        }
        MainActivity.toast(MainActivity.TOAST_ELM, "Hard reset failed, restarting Bluetooth ...");
        MainActivity.debug("ELM327: Hard reset failed, restarting Bluetooth ...");

        // We are inside the poller thread, so it cannot be joined here. Stop it from making the
        // next request, and let the serialized Bluetooth executor restart the link, keeping the
        // registered fields. The executor joins this poller only after we returned.
        setPollerActive(false);
        BluetoothManager.getInstance().publishConnecting();
        MainActivity.restartBluetoothAsync();
        return false;
    }

    public boolean initDevice(int toughness) {
        MainActivity.debug("ELM327: initDevice (" + toughness + ")");
        lastInitProblem = "";
        // the header has to be set again
        lastId = 0;

        // extremely soft, just clear the global error condition
        if (toughness == TOUGHNESS_NONE) {
            deviceIsInitialized = true;
            return true;
        }

        deviceIsInitialized = false;
        // The dongle is reset below: nothing read before, and no filter set before, is valid any
        // more. The free frame filter used to survive this, so the next atma ran unfiltered.
        clearRx();
        lastFreeFrameFilter = "";
        lastCommandWasFreeFrame = false;

        killCurrentOperation();

        String command = (toughness == TOUGHNESS_HARD || toughness == TOUGHNESS_MEDIUM) ? "atws" : "atd";
        // the answer contains several <cr>, so read until the line is quiet
        String response = sendAndWaitForAnswer(command, 0, true, -1, true);
        MainActivity.debug("ELM327: version: [" + response + "]");
        response = response.trim();

        if (Thread.currentThread().isInterrupted()) {
            // we are being stopped, not a dongle problem worth a toast
            lastInitProblem = "initialisation interrupted";
            return false;
        }
        if (response.isEmpty()) {
            lastInitProblem = "ELM is not responding (toughness = " + toughness + ")";
            MainActivity.toast(MainActivity.TOAST_ELM, lastInitProblem);
            return false;
        }

        int elmVersion = 0;
        // only check the version at a full reset
        if (toughness <= TOUGHNESS_MEDIUM) {
            elmVersion = parseElmVersion(response);
            if (elmVersion < 0) {
                lastInitProblem = "Unrecognized ELM version response [" + response.replace("\r", "<cr>").replace(" ", "<sp>") + "]";
                MainActivity.toast(MainActivity.TOAST_ELM, lastInitProblem);
                return false;
            }
        }

        if (!sendInitCommands()) return false;
        if (toughness == TOUGHNESS_HARD) toastElmVersion(elmVersion);

        deviceIsInitialized = true;
        return true;
    }

    /**
     * ate0 (no echo), ats0 (no spaces), ath0 (no headers), atl0 (no linefeeds), atcaf0 (no
     * formatting), atfcsh77b / atfcsd300000 / atfcsm1 (flow control: any id, clear to send all
     * frames without delay, id and data supplied), atsp6 (CAN 500K 11 bit). ATSH and ATFCSH are
     * set per request. atal is not sent: it is an ISO9141 command and stalls several clones.
     */
    private boolean sendInitCommands() {
        String[] commands = {"ate0", "ats0", "ath0", "atl0", "atcaf0", "atfcsh77b", "atfcsd300000", "atfcsm1", "atsp6"};
        boolean first = true;
        for (String command : commands) {
            if (!initCommandExpectOk(command, first)) {
                // flow control modes are optional on several clones
                if (command.startsWith("atfc")) {
                    MainActivity.debug("ELM327: optional command " + command + " failed, continuing anyway");
                } else {
                    lastInitProblem = command + " command problem";
                    return false;
                }
            }
            first = false;
        }
        return true;
    }

    /** @return the version code, or -1 if the answer is not a known ELM */
    private static int parseElmVersion(String response) {
        String upper = response.toUpperCase(Locale.US);
        if (upper.contains("V1.3")) return 13;
        if (upper.contains("V1.4")) return 14;
        if (upper.contains("V1.5")) return 15;
        if (upper.contains("V2.")) return 20;
        if (upper.contains("INNOCAR")) return 8015;
        return -1;
    }

    private void toastElmVersion(int elmVersion) {
        switch (elmVersion) {
            case 13:
                MainActivity.toast(MainActivity.TOAST_ELM, "ELM ready, version 1.3, should work");
                break;
            case 14:
                MainActivity.toast(MainActivity.TOAST_ELM, "ELM ready, version 1.4, should work");
                break;
            case 15:
                MainActivity.toast(MainActivity.TOAST_ELM, "ELM is now ready");
                break;
            case 20:
                lastInitProblem = "ELM ready, version 2.x, will probably not work, please report if it does";
                MainActivity.toast(MainActivity.TOAST_ELM, lastInitProblem);
                break;
            case 8015:
                MainActivity.toast(MainActivity.TOAST_ELM, "ELM ready, version innocar, should work");
                break;
            default:
                lastInitProblem = "ELM ready, unknown version, will probably not work, please report if it does";
                MainActivity.toast(MainActivity.TOAST_ELM, lastInitProblem);
                break;
        }
    }

    /** true once stopAndJoin() or a Bluetooth restart has asked the poller to wind down */
    private boolean isStopRequested() {
        return Thread.currentThread().isInterrupted() || !isPollerActive();
    }

    /* ----------------------------------------------------------------
     * Buffered reading
     \ -------------------------------------------------------------- */

    /** @return bytes readable without blocking */
    private int pendingBytes() throws IOException {
        int buffered = rxLen - rxPos;
        if (buffered > 0) return buffered;
        return BluetoothManager.getInstance().available();
    }

    /** @return the next byte, or -1 if none is available right now. Never blocks. */
    private int readByte() throws IOException {
        if (rxPos < rxLen) return rxBuffer[rxPos++] & 0xFF;
        BluetoothManager bt = BluetoothManager.getInstance();
        if (bt.available() <= 0) return -1;
        // bytes are waiting, so this read returns at once with what is there
        int n = bt.read(rxBuffer);
        if (n <= 0) {
            clearRx();
            return -1;
        }
        rxPos = 0;
        rxLen = n;
        return rxBuffer[rxPos++] & 0xFF;
    }

    private void clearRx() {
        rxPos = 0;
        rxLen = 0;
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // preserve the stop request, the callers check the flag
            Thread.currentThread().interrupt();
        }
    }

    /* ----------------------------------------------------------------
     * Flushing and resynchronisation
     \ -------------------------------------------------------------- */

    private void killCurrentOperation() {
        // sending a return might restart the last command, so send a character
        sendNoWait("x");
        // discard everything that still comes in
        flushWithTimeoutCore(200, '\0');
        // If no command was running, the x above is still in the ELM's input. Sending x<cr>
        // makes it answer '?' either way (to x or xx), which is discarded.
        sendNoWait("x\r");
        if (!flushWithTimeoutCore(500, '\0')) {
            MainActivity.debug("ELM327: KillCurrentOperation unable to flush after x");
        }
    }

    private void flushWithTimeout(int timeout) {
        flushWithTimeout(timeout, '\0');
    }

    private void flushWithTimeout(int timeout, char eom) {
        if (flushWithTimeoutCore(timeout, eom)) return;
        killCurrentOperation();
    }

    /**
     * Empties the incoming stream until eom is seen or the line is quiet for timeout ms.
     *
     * @return false if more than FLUSH_MAX_CHARS came in (the ELM keeps sending) or the link is down
     */
    private boolean flushWithTimeoutCore(int timeout, char eom) {
        int count = FLUSH_MAX_CHARS;
        BluetoothManager bt = BluetoothManager.getInstance();
        try {
            if (timeout == 0) {
                while (bt.isConnected() && pendingBytes() > 0) {
                    readByte();
                    if (count-- == 0) return false;
                }
                return true;
            }
            long end = now() + timeout;
            while (now() < end) {
                if (!bt.isConnected()) return false;
                if (pendingBytes() > 0) {
                    while (pendingBytes() > 0) {
                        int c = readByte();
                        if (c == (int) eom) return true;
                        if (count-- == 0) return false;
                    }
                    // something arrived: restart the quiet timer
                    end = now() + timeout;
                } else {
                    Thread.sleep(2);
                }
            }
        } catch (IOException e) {
            MainActivity.debug("ELM327: flushWithTimeoutCore I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * Consumes whatever is still coming and returns once the line was silent for quietMs.
     * Costs nothing on a clean buffer, and is bounded by maxMs if the ELM keeps sending.
     */
    private void drainUntilQuiet(int quietMs, int maxMs) {
        if (quietMs < 0 || maxMs <= 0) return;
        long start = now();
        long deadline = start + maxMs;
        long lastByteAt = start;
        BluetoothManager bt = BluetoothManager.getInstance();
        try {
            while (now() < deadline) {
                if (!bt.isConnected()) return;
                if (readByte() >= 0) {
                    lastByteAt = now();
                } else {
                    if (now() - lastByteAt >= quietMs) return;
                    Thread.sleep(1);
                }
            }
        } catch (IOException e) {
            MainActivity.debug("ELM327: drainUntilQuiet I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Brings the ELM back in step after an unexpected answer: drains what is in flight, sends a
     * bare CR and consumes the fresh prompt, so the next command reads its own answer.
     */
    private void resyncPrompt() {
        MainActivity.debug("ELM327: resyncPrompt > stream out of phase, resynchronising");
        drainUntilQuiet(10, 300);
        BluetoothManager.getInstance().write("\r");
        flushWithTimeout(120, '>');
        bufferDirty = false;
    }

    /**
     * Stops ATMA monitoring and drains until the '>' prompt. Without the prompt within the
     * deadline the buffer is flagged dirty: leftover bytes would otherwise be read as the answer
     * to the next command.
     */
    private void stopAtmaAndDrainPrompt() {
        sendNoWait("x");
        long deadline = now() + 350;
        BluetoothManager bt = BluetoothManager.getInstance();
        try {
            while (now() < deadline) {
                if (!bt.isConnected()) return;
                int c = readByte();
                if (c == '>') {
                    bufferDirty = false;
                    return;
                }
                if (c < 0) Thread.sleep(1);
            }
        } catch (IOException e) {
            MainActivity.debug("ELM327: stopAtmaAndDrainPrompt I/O error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        bufferDirty = true;
        MainActivity.debug("ELM327: stopAtmaAndDrainPrompt > no prompt within deadline, buffer marked dirty");
    }

    /* ----------------------------------------------------------------
     * Commands
     \ -------------------------------------------------------------- */

    private boolean initCommandExpectOk(String command) {
        return initCommandExpectOk(command, false, true);
    }

    private boolean initCommandExpectOk(String command, boolean untilEmpty) {
        return initCommandExpectOk(command, untilEmpty, true);
    }

    private boolean initCommandExpectOk(String command, boolean untilEmpty, boolean addReturn) {
        String response = "";
        for (int i = 2; i > 0; i--) {
            if (Thread.currentThread().isInterrupted()) return false;
            if (untilEmpty) {
                response = sendAndWaitForAnswer(command, 40, true, -1, addReturn);
            } else {
                response = sendAndWaitForAnswer(command, 0, false, -1, addReturn);
            }
            if (response.toUpperCase(Locale.US).contains("OK")) return true;
            // ANY answer other than OK means the stream is out of phase
            resyncPrompt();
        }
        MainActivity.toast(MainActivity.TOAST_ELM, "Error [" + command + "] [" + response.replace("\r", "<cr>").replace(" ", "<sp>") + "]");
        MainActivity.debug("ELM327.initCommandExpectOk c:" + command + ", untilempty:" + untilEmpty + " res:" + response);
        return false;
    }

    private void sendNoWait(String command) {
        if (command == null || !BluetoothManager.getInstance().isConnected()) return;
        BluetoothManager.getInstance().write(command);
    }

    private String sendAndWaitForAnswer(String command, int waitMillis) {
        return sendAndWaitForAnswer(command, waitMillis, false, -1, true);
    }

    private String sendAndWaitForAnswer(String command, int waitMillis, int answerLinesCount) {
        return sendAndWaitForAnswer(command, waitMillis, false, answerLinesCount, true);
    }

    private String sendAndWaitForAnswer(String command, int waitMillis, boolean untilEmpty) {
        return sendAndWaitForAnswer(command, waitMillis, untilEmpty, -1, true);
    }

    /**
     * Sends a command (if not null) and collects the answer.
     *
     * @param untilEmpty       read until the line is quiet instead of counting lines
     * @param answerLinesCount lines to wait for when not untilEmpty
     * @return the answer, or "" on timeout or interrupt
     */
    private String sendAndWaitForAnswer(String command, int waitMillis, boolean untilEmpty, int answerLinesCount, boolean addReturn) {
        int maxUntilEmptyCounter = 10;
        int maxLengthCounter = 500; // char = nibble, so 2000 bits
        BluetoothManager bt = BluetoothManager.getInstance();

        if (!bt.isConnected()) return "";

        if (command != null) {
            // Only flush when there may be something to flush. After a drain gave up before the
            // prompt, bytes can still be in flight that available() does not report yet.
            try {
                if (bufferDirty || pendingBytes() > 0) {
                    drainUntilQuiet(10, 250);
                    bufferDirty = false;
                }
            } catch (IOException e) {
                MainActivity.debug("ELM327: pre-send check failed: " + e.getMessage());
            }
            bt.write(command + (addReturn ? "\r" : ""));
        }

        boolean stop = false;
        boolean timedOut = false;
        StringBuilder readBuffer = new StringBuilder();
        long end = now() + generalTimeout;

        while (!stop && !timedOut && !Thread.currentThread().isInterrupted()) {
            try {
                int data = readByte();
                if (data >= 0) {
                    char ch = (char) data;
                    if (ch == '\n') ch = '\r';
                    readBuffer.append(ch);
                    if (ch == EOM1 || ch == EOM2 || ch == EOM3) {
                        answerLinesCount--;
                        if (!untilEmpty) {
                            if (answerLinesCount <= 0) {
                                stop = true;
                            } else {
                                end = now() + generalTimeout; // more lines to come
                            }
                        } else {
                            stop = pendingBytes() == 0;
                            if (stop) {
                                // the next character may simply not be there yet
                                sleepQuietly(50);
                                stop = pendingBytes() == 0;
                            } else if (--maxUntilEmptyCounter <= 0) {
                                timedOut = true; // too many lines
                            }
                        }
                    } else if (--maxLengthCounter <= 0) {
                        timedOut = true; // line too long
                    }
                } else {
                    sleepQuietly(2);
                }
                // a complete answer arriving right at the deadline is kept
                if (!stop && now() > end) timedOut = true;
            } catch (IOException e) {
                MainActivity.debug("ELM327: read failed on [" + command + "]: " + e.getMessage());
                timedOut = true;
            }
        }

        // stopped while waiting: the answer may still arrive, so the next command drains first
        if (Thread.currentThread().isInterrupted()) {
            bufferDirty = true;
            MainActivity.debug("ELM327: sendAndWaitForAnswer > interrupted on [" + command + "]");
            return "";
        }

        if (timedOut) {
            // no toast for atma: late broadcast frames on the bus are normal
            String shown = readBuffer.toString().replace("\r", "<cr>").replace(" ", "<sp>");
            if (command != null && !command.startsWith("atma")) {
                MainActivity.toast(MainActivity.TOAST_ELM, "Timeout on [" + command + "] [" + shown + "]");
            }
            MainActivity.debug("ELM327: sendAndWaitForAnswer > timed out on [" + command + "] [" + shown + "]");
            return "";
        }
        return readBuffer.toString();
    }

    private int getToId(int fromId) {
        Ecu ecu = Ecus.getInstance().getByFromId(fromId);
        return ecu != null ? ecu.getToId() : 0;
    }

    private String getToIdHex(int fromId) {
        return Integer.toHexString(getToId(fromId));
    }

    /* ----------------------------------------------------------------
     * Free frames
     \ -------------------------------------------------------------- */

    @Override
    public Message requestFreeFrame(Frame frame) {
        if (!deviceIsInitialized) {
            return new Message(frame, "-E-Re-initialisation needed", true);
        }

        // the next ISO-TP request has to reset the receive filter
        lastCommandWasFreeFrame = true;

        String filter = frame.getHexId().toLowerCase(Locale.US);
        if (!filter.equals(lastFreeFrameFilter)) {
            // the ISO-TP receive filter is gone once a free frame filter is set
            lastId = 0;
            if (MainActivity.isVerbose()) MainActivity.debug("ELM327: requestFreeFrame: atcra" + filter);
            if (!initCommandExpectOk("atcra" + filter)) {
                lastFreeFrameFilter = "";
                return new Message(frame, "-E-Problem sending atcra command", true);
            }
            lastFreeFrameFilter = filter;
        }

        // generous: Bluetooth packetisation must not cause false timeouts on broadcast frames
        generalTimeout = Math.max(FREE_FRAME_MIN_TIMEOUT, (int) (frame.getInterval() * intervalMultiplicator + 200));
        String hexData;
        try {
            hexData = sendAndWaitForAnswer("atma", 0, false, 1, true);
        } finally {
            // stop atma and drain the trailing bytes up to the prompt
            stopAtmaAndDrainPrompt();
            generalTimeout = DEFAULT_TIMEOUT;
        }

        hexData = hexData.trim().replace(" ", "");
        if (hexData.isEmpty()) {
            return new Message(frame, "-E-data empty", true, Message.ERROR_TIMEOUT);
        }
        if (!isHexData(hexData)) {
            // STOPPED, BUFFER FULL, CAN ERROR, ...: used to be decoded as garbage values
            return new Message(frame, "-E-unexpected free frame data:" + hexData, true);
        }
        return new Message(frame, hexData, false);
    }

    private static boolean isHexData(String data) {
        for (int i = 0; i < data.length(); i++) {
            if (Character.digit(data.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /* ----------------------------------------------------------------
     * ISO-TP frames
     \ -------------------------------------------------------------- */

    @Override
    public Message requestIsoTpFrame(Frame frame) {
        if (!deviceIsInitialized) {
            return new Message(frame, "-E-Re-initialisation needed", true);
        }
        Message problem = ensureIsoTpAddressing(frame);
        if (problem != null) return problem;

        String response = sendIsoTpRequest(frame);
        if (response == null) return new Message(frame, "-E-ISOTP tx flow Error", true);
        return parseIsoTpResponse(frame, response);
    }

    /** Points header, receive filter and flow control at the frame's ECU. @return an error or null */
    private Message ensureIsoTpAddressing(Frame frame) {
        if (lastCommandWasFreeFrame) {
            // atar clears the free frame receive filter, so every ISO-TP setting must be redone
            lastFreeFrameFilter = "";
            lastId = 0;
            if (!initCommandExpectOk("atar")) {
                return new Message(frame, "-E-Problem sending atar command", true);
            }
            lastCommandWasFreeFrame = false;
        }

        if (!MainActivity.elmHeaderCache) lastId = 0;
        if (lastId == frame.getId()) return null;

        // only marked valid once all three commands succeeded
        lastId = 0;
        String toIdHex = getToIdHex(frame.getId());
        if (!initCommandExpectOk("atsh" + toIdHex))
            return new Message(frame, "-E-Problem sending atsh command", true);
        if (!initCommandExpectOk("atcra" + Integer.toHexString(frame.getId())))
            return new Message(frame, "-E-Problem sending atcra command", true);
        if (!initCommandExpectOk("atfcsh" + toIdHex))
            return new Message(frame, "-E-Problem sending atfcsh command", true);
        lastId = frame.getId();
        return null;
    }

    /**
     * Sends the request, as a single frame or as a first frame plus consecutive frames.
     *
     * @return the raw answer without CRs, or null on a flow control error
     */
    private String sendIsoTpRequest(Frame frame) {
        String requestId = frame.getRequestId();
        int outgoingLength = requestId.length();

        if (outgoingLength <= 12) {
            // e.g. 022104: single frame, length 2, payload 2104
            String command = "0" + (outgoingLength / 2) + requestId;
            return sendAndWaitForAnswer(command, 0, false).replace("\r", "");
        }

        String command = String.format(Locale.US, "1%03X", outgoingLength / 2) + requestId.substring(0, 12);
        String flowResponse = sendAndWaitForAnswer(command, 0, false).replace("\r", "");
        String response = "";
        int startIndex = 12;
        int endIndex = Math.min(startIndex + 14, outgoingLength);
        int next = 1;
        // bounded: startIndex grows by up to 14 characters per pass until the end of the request
        while (startIndex < outgoingLength) {
            command = String.format(Locale.US, "2%01X", next) + requestId.substring(startIndex, endIndex);
            // block size and separation time are ignored: all at once, or one by one
            if (flowResponse.startsWith("3000")) {
                // no further flow control: the answer to the last frame is the actual answer
                response = sendAndWaitForAnswer(command, 0, false).replace("\r", "");
            } else if (flowResponse.startsWith("30")) {
                // the answer is either the next flow control or the actual answer
                flowResponse = sendAndWaitForAnswer(command, 0, false).replace("\r", "");
                response = flowResponse;
            } else {
                return null;
            }
            startIndex = endIndex;
            endIndex = Math.min(startIndex + 14, outgoingLength);
            next = next == 15 ? 0 : next + 1;
        }
        return response;
    }

    private Message parseIsoTpResponse(Frame frame, String response) {
        String elmResponse = response.trim();
        if (elmResponse.startsWith(">")) elmResponse = elmResponse.substring(1);

        if (elmResponse.equals("CAN ERROR")) {
            return new Message(frame, "-E-Can Error", true);
        } else if (elmResponse.equals("?")) {
            return new Message(frame, "-E-Unknown command", true);
        } else if (elmResponse.isEmpty()) {
            // no answer at all: candidate for blacklisting
            return new Message(frame, "-E-Empty result", true, Message.ERROR_TIMEOUT);
        } else if (elmResponse.startsWith("NO DATA")) {
            // the ELM's own timeout: the ECU did not answer. Used to force a dongle re-init
            // every cycle instead of counting toward the blacklist.
            flushWithTimeout(400, '>');
            return new Message(frame, "-E-No data", true, Message.ERROR_TIMEOUT);
        }

        int len;
        String hexData;
        try {
            switch (elmResponse.charAt(0)) {
                case '0': // SINGLE frame: type + length nibbles, then the data
                    len = Integer.parseInt(elmResponse.substring(1, 2), 16);
                    hexData = elmResponse.substring(2);
                    break;
                case '1': // FIRST frame: type + 3 length nibbles, then the data
                    len = Integer.parseInt(elmResponse.substring(1, 4), 16);
                    String rest = readConsecutiveFrames(len);
                    if (rest == null) return new Message(frame, "-E-out of sequence ISO-TP frame", true);
                    hexData = elmResponse.substring(4) + rest;
                    break;
                default: // NEXT, FLOWCONTROL or any other text should not be received here
                    flushWithTimeout(400, '>');
                    return new Message(frame, "-E-unexpected ISO-TP 1st nibble of 1st frame:" + elmResponse, true);
            }
        } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
            return new Message(frame, "-E-unexpected ISO-TP length:" + elmResponse, true);
        }

        // The ELM may still be finishing the command ("OK>" or ">"): wait for the prompt, or the
        // next command fails and a following atma could run without a filter.
        flushWithTimeout(400, '>');

        len *= 2;
        // filler bytes beyond the length are cut away
        hexData = (hexData.length() <= len) ? hexData.trim().toLowerCase(Locale.US) : hexData.substring(0, len).trim().toLowerCase(Locale.US);

        if (hexData.isEmpty())
            return new Message(frame, "-E-data empty", true, Message.ERROR_TIMEOUT);
        return new Message(frame, hexData, false);
    }

    /**
     * Reads the consecutive frames after a first frame. 6 bytes came with the first frame, each
     * consecutive frame carries 7.
     *
     * @return their payload, or null if one arrives out of sequence
     */
    private String readConsecutiveFrames(int len) {
        int framesToReceive = len / 7;
        String lines = sendAndWaitForAnswer(null, 0, framesToReceive);
        StringBuilder payload = new StringBuilder();
        int next = 1;
        for (String line : lines.split("[\\r]+")) {
            String trimmed = line.trim();
            if (trimmed.length() <= 2) continue;
            if (!trimmed.startsWith(String.format(Locale.US, "2%01X", next))) return null;
            payload.append(trimmed.substring(2));
            next = next == 15 ? 0 : next + 1;
        }
        return payload.toString();
    }
}
