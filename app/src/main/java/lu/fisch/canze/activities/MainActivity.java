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

package lu.fisch.canze.activities;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import android.text.Html;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import lu.fisch.canze.BuildConfig;
import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.actors.Frames;
import lu.fisch.canze.bluetooth.BluetoothManager;
import lu.fisch.canze.classes.DataLogger;
import lu.fisch.canze.classes.DebugLogger;
import lu.fisch.canze.database.CanzeDataSource;
import lu.fisch.canze.devices.BobDue;
import lu.fisch.canze.devices.Device;
import lu.fisch.canze.devices.ELM327;
import lu.fisch.canze.devices.ELM327OverHttp;
import lu.fisch.canze.interfaces.BluetoothEvent;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;
import lu.fisch.canze.ui.AppSectionsPagerAdapter;

public class MainActivity extends AppCompatActivity implements FieldListener /*, android.support.v7.app.ActionBar.TabListener */ {
    public static final String TAG = "CanZE";

    // SPP UUID service
    // private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public final static String PREFERENCES_FILE = "lu.fisch.canze.settings";
    public final static String DATA_FILE = "lu.fisch.canze.data";

    // MAC-address of Bluetooth module (you must edit this line)
    private static String bluetoothDeviceAddress = null;
    private static String bluetoothDeviceName = null;

    // url of gateway if in use
    private static String gatewayUrl = null;

    // public final static int RECEIVE_MESSAGE      = 1;
    public final static int REQUEST_ENABLE_BT = 3;
    public final static int SETTINGS_ACTIVITY = 7;
    public final static int LEAVE_BLUETOOTH_ON = 11;

    // note that the CAR constants are stored in the option property of the field object
    // this is a short

    // public static final short CAR_MASK            = 0xff;

    public static final short CAR_NONE = 0x000;
    //public static final int CAR_ANY               = 0x0ff;
    public static final short CAR_FLUENCE = 0x001;
    public static final short CAR_ZOE_Q210 = 0x002;
    public static final short CAR_KANGOO = 0x004;
    public static final short CAR_TWIZY = 0x008;     // you'll never know ;-)
    public static final short CAR_X10 = 0x010;     // not used
    public static final short CAR_ZOE_R240 = 0x020;
    public static final short CAR_ZOE_Q90 = 0x040;
    public static final short CAR_ZOE_R90 = 0x080;

    public static final short FIELD_TYPE_MASK = 0x700;
    //public static final short FIELD_TYPE_UNSIGNED = 0x000;
    public static final short FIELD_TYPE_SIGNED = 0x100;
    public static final short FIELD_TYPE_STRING = 0x200;      // not implemented yet

    public static final short TOAST_NONE = 0;
    public static final short TOAST_ELM = 1;
    public static final short TOAST_ELMCAR = 2;

    public static final double reduction = 9.32;     // update suggested by Loc Dao

    // private StringBuilder sb = new StringBuilder();
    // private String buffer = "";

    // private int count;
    // private long start;

    private boolean visible = true;
    public boolean leaveBluetoothOn = false;
    private boolean returnFromWidget = false;

    public static Fields fields = Fields.getInstance();

    public static volatile Device device = null;

    private static volatile MainActivity instance = null;

    /** ignore ACL disconnect broadcasts this soon after we closed the link ourselves */
    private static final long ACL_IGNORE_WINDOW_MS = 10000;

    /** runs every Bluetooth stop/reconnect off the UI thread, one at a time and in order */
    private static final ExecutorService BLUETOOTH_EXECUTOR = Executors.newSingleThreadExecutor();

    /** how often the history database is trimmed */
    private static final long CLEANUP_INTERVAL_MS = 60L * 60L * 1000L;

    /** speed poll for safe driving mode. 10 s made the lock-out react far too late */
    private static final int SAFE_MODE_SPEED_INTERVAL_MS = 2000;

    private final Handler cleanUpHandler = new Handler(Looper.getMainLooper());
    private final Runnable cleanUpTask = new Runnable() {
        @Override
        public void run() {
            // the delete itself runs on the database writer thread
            CanzeDataSource.getInstance().cleanUpAsync();
            cleanUpHandler.postDelayed(this, CLEANUP_INTERVAL_MS);
        }
    };

    public static boolean safeDrivingMode = true;
    public static boolean bluetoothBackgroundMode = false;
    public static boolean debugLogMode = false;
    public static boolean fieldLogMode = false;

    public static boolean dataExportMode = false;
    public static DataLogger dataLogger = null; // rather use singleton in onCreate

    public static int car = CAR_NONE;

    private static boolean isDriving = false;

    public static boolean milesMode = false;
    public static int toastLevel = 1;

    /** reuse the ELM327 ISO-TP header between requests to the same ECU (experimental, default off) */
    public static volatile boolean elmHeaderCache = false;

    private DebugListener debugListener = null;

    // private Fragment actualFragment;

    static private Resources res;

    // bluetooth stuff
    private MenuItem bluetoothMenutItem = null;
    public final static int BLUETOOTH_DISCONNECTED = 21;
    public final static int BLUETOOTH_SEARCH = 22;
    public final static int BLUETOOTH_CONNECTED = 23;


    //The BroadcastReceiver that listens for bluetooth broadcasts
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction()))
                return;

            // headphones, car stereos, ... are none of our business
            if (!isOurDongle(intent)) {
                debug("MainActivity: ignoring ACL disconnect of another Bluetooth device");
                return;
            }

            // Android reports the link going down a few seconds after we closed it ourselves
            // (pause, restart). By then we may already be on a fresh connection, which the
            // old code used to kill again.
            BluetoothManager manager = BluetoothManager.getInstance();
            if (manager.isStopped() || manager.wasStoppedWithin(ACL_IGNORE_WINDOW_MS)) {
                debug("MainActivity: ignoring ACL disconnect caused by our own disconnect");
                return;
            }

            // a real signal loss: inform the user and reconnect, all off the UI thread
            if (visible) {
                setTitle(TAG + " - disconnected");
                setBluetoothState(BLUETOOTH_DISCONNECTED);
            }
            toast(R.string.toast_BluetoothLost);
            manager.publishConnecting();
            restartBluetoothAsync();
        }
    };

    private static boolean isOurDongle(Intent intent) {
        String ours = bluetoothDeviceAddress;
        if (ours == null || ours.isEmpty()) return false;
        try {
            BluetoothDevice lost = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            return lost != null && ours.equalsIgnoreCase(lost.getAddress());
        } catch (RuntimeException e) {
            debug("MainActivity: could not read the disconnected device: " + e.getMessage());
            return false;
        }
    }

    /**
     * @return the live MainActivity, or null if Android killed the process and has only
     * recreated a sub screen so far. Never construct an Activity by hand: it has no Context
     * and crashes on the first runOnUiThread / getSharedPreferences.
     */
    public static MainActivity getInstance() {
        return instance;
    }

    public static void debug(String text) {
        Log.d(TAG, text);
        // time stamped and written on the logger's own thread
        if (debugLogMode) DebugLogger.getInstance().log(text);
    }

    /**
     * Whether per-request trace lines are worth building. Hot paths check this before
     * concatenating strings: always in debug builds, in release only with debug logging on.
     */
    public static boolean isVerbose() {
        return debugLogMode || BuildConfig.DEBUG;
    }

    /* TODO we should move to simply always provide the level in the toast() call instead of all those if's in the code */
    public static void toast(int level, final String message) {
        if (level > toastLevel) return;
        if (instance != null)
            instance.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(instance, message, Toast.LENGTH_SHORT).show();
                }
            });
    }

    public static void toast(final String message) {
        if (instance != null)
            instance.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(instance, message, Toast.LENGTH_SHORT).show();
                }
            });
    }

    public static void toast(String format, Object... arguments) {
        final String finalMessage = String.format(Locale.getDefault(), format, arguments);
        if (instance != null)
            instance.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(instance, finalMessage, Toast.LENGTH_SHORT).show();
                }
            });
    }

    public static void toast(final int resource) {
        if (instance != null)
            instance.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final String finalMessage = getStringSingle(resource);
                    Toast.makeText(instance, finalMessage, Toast.LENGTH_SHORT).show();
                }
            });
    }

    public void loadSettings() {
        debug("MainActivity: loadSettings");
        try {
            SharedPreferences settings = getSharedPreferences(PREFERENCES_FILE, 0);
            versionChangeCheck(settings);
            // restore the list of values that repeatedly failed to answer, before the
            // device (and therefore the poller) is created below
            lu.fisch.canze.classes.Blacklist.getInstance().load(settings);
            bluetoothDeviceName = settings.getString("deviceName", null);
            bluetoothDeviceAddress = settings.getString("deviceAddress", null);
            gatewayUrl = settings.getString("gatewayUrl", null);
            // String dataFormat = settings.getString("dataFormat", "crdt");
            String deviceType = settings.getString("device", "Arduino");
            safeDrivingMode = settings.getBoolean("optSafe", true);
            bluetoothBackgroundMode = settings.getBoolean("optBTBackground", false);
            milesMode = settings.getBoolean("optMiles", false);
            dataExportMode = settings.getBoolean("optDataExport", false);
            debugLogMode = settings.getBoolean("optDebugLog", false);
            fieldLogMode = settings.getBoolean("optFieldLog", false);
            toastLevel = settings.getInt("optToast", 1);
            elmHeaderCache = settings.getBoolean("optElmHeaderCache", false);

            if (bluetoothDeviceName != null && !bluetoothDeviceName.isEmpty() && bluetoothDeviceName.length() > 4)
                BluetoothManager.getInstance().setDummyMode(bluetoothDeviceName.substring(0, 4).compareTo("HTTP") == 0);

            String carStr = settings.getString("car", "None");
            switch (carStr) {
                case "None":
                    car = CAR_NONE;
                    break;
                case "Zoé":
                case "ZOE":
                case "ZOE Q210":
                    car = CAR_ZOE_Q210;
                    break;
                case "ZOE R240":
                    car = CAR_ZOE_R240;
                    break;
                case "ZOE Q90":
                    car = CAR_ZOE_Q90;
                    break;
                case "ZOE R90":
                case "ZOE R90/110":
                    car = CAR_ZOE_R90;
                    break;
                case "Fluence":
                    car = CAR_FLUENCE;
                    break;
                case "Kangoo":
                    car = CAR_KANGOO;
                    break;
                case "Twizy":
                    car = CAR_TWIZY;
                    break;
                case "X10":
                    car = CAR_X10;
                    break;
            }

            // as the settings may have changed, we need to reload different things

            // Publish the new device first, so every screen registering fields from now on
            // talks to it. It does not poll yet: the old poller may still own the dongle.
            final Device previousDevice = device;
            device = createDevice(deviceType);

            // since the car type may have changed, reload the frame timings and fields
            // (this also registers the application wide fields on the new device)
            Frames.getInstance().load();
            fields.load();

            // Hand the dongle over off the UI thread: stopping the old poller can block for
            // several seconds, which must never happen on the main thread.
            swapDeviceAsync(previousDevice, device);

            // after loading PREFERENCES we may have new values for "dataExportMode"
            dataExportMode = dataLogger.activate(dataExportMode);
        } catch (Exception e) {
            MainActivity.debug(e.getMessage());
            for (StackTraceElement traceElement : e.getStackTrace()) {
                MainActivity.debug(traceElement.toString());
            }
        }
    }

    private static Device createDevice(String deviceType) {
        if (deviceType == null) return null;
        switch (deviceType) {
            case "Bob Due":
                return new BobDue();
            case "ELM327":
                return new ELM327();
            case "ELM327Http":
                return new ELM327OverHttp();
            default:
                return null;
        }
    }

    /**
     * Stops the old poller (bounded join) and only then starts the new one, serialized with
     * every other Bluetooth task, so two pollers never talk to the dongle at the same time.
     */
    private static void swapDeviceAsync(final Device previous, final Device next) {
        submitBluetoothTask("device swap", new Runnable() {
            @Override
            public void run() {
                if (previous != null && previous != next) previous.stopAndJoin();
                // a later settings reload may already have replaced 'next'
                if (next != null && next == device) next.initConnection();
            }
        });
    }

    public void registerApplicationFields() {
        if (safeDrivingMode) {
            // speed
            Field field = fields.getBySID("5d7.0");
            if (field != null) {
                field.addListener(this); // callback is onFieldUpdateEvent
                if (device != null)
                    device.addApplicationField(field, SAFE_MODE_SPEED_INTERVAL_MS);
            }
        } else {
            Field field = fields.getBySID("5d7.0");
            if (field != null) {
                field.removeListener(MainActivity.getInstance());
                if (device != null)
                    device.removeApplicationField(field);
            }
        }
    }

    protected void updateActionBar() {
        switch (viewPager.getCurrentItem()) {
            case 0:
                actionBar.setIcon(R.mipmap.ic_launcher);
                break;
            case 1:
                actionBar.setIcon(R.mipmap.fragement_technical);
                break;
            case 2:
                actionBar.setIcon(R.mipmap.fragement_experimental);
                break;
            default:
                break;
        }
    }

    private ViewPager viewPager;
    private ActionBar actionBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // always create an instance

        // needed to get strings from resources in non-Activity classes
        res = getResources();

        // dataLogger = DataLogger.getInstance();
        dataLogger = new DataLogger();

        debug("MainActivity: onCreate");

        instance = this;

        getWindow().requestFeature(Window.FEATURE_ACTION_BAR);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        // navigation bar
        AppSectionsPagerAdapter appSectionsPagerAdapter = new AppSectionsPagerAdapter(getSupportFragmentManager());
        actionBar = getSupportActionBar();
        if (actionBar != null)
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_SHOW_TITLE);
        //actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
        viewPager = findViewById(R.id.main);
        viewPager.setAdapter(appSectionsPagerAdapter);
        // viewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                //actionBar.setSelectedNavigationItem(position);
                updateActionBar();
            }
        });
        updateActionBar();

        setTitle(TAG + " - not connected");
        setBluetoothState(BLUETOOTH_DISCONNECTED);

        // open the database and trim it right away, off the UI thread
        CanzeDataSource.getInstance(getApplicationContext()).open();
        CanzeDataSource.getInstance().cleanUpAsync();
        // then trim it again every hour for as long as the app runs
        cleanUpHandler.removeCallbacks(cleanUpTask);
        cleanUpHandler.postDelayed(cleanUpTask, CLEANUP_INTERVAL_MS);


        // register for bluetooth changes
        IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        this.registerReceiver(broadcastReceiver, intentFilter);

        // configure Bluetooth manager
        BluetoothManager.getInstance().setBluetoothEvent(new BluetoothEvent() {
            @Override
            public void onBeforeConnect() {
                setBluetoothState(BLUETOOTH_SEARCH);
            }

            @Override
            public void onAfterConnect(BluetoothSocket bluetoothSocket) {
                Device current = device;
                if (current != null) current.init(visible);

                // set title
                debug("MainActivity: onAfterConnect > set title");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setTitle(TAG + " - connected to <" + bluetoothDeviceName + "@" + bluetoothDeviceAddress + ">");
                        setBluetoothState(BLUETOOTH_CONNECTED);
                    }
                });
            }

            @Override
            public void onBeforeDisconnect(BluetoothSocket bluetoothSocket) {
            }

            @Override
            public void onAfterDisconnect() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setTitle(TAG + " - disconnected");
                    }
                });
            }
        });
        // detect hardware status
        int BT_STATE = BluetoothManager.getInstance().getHardwareState();
        if (BT_STATE == BluetoothManager.STATE_BLUETOOTH_NOT_AVAILABLE)
            toast("Sorry, but your device doesn't seem to have Bluetooth support!");
        else if (BT_STATE == BluetoothManager.STATE_BLUETOOTH_NOT_ACTIVE) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        }

        // load settings
        // - includes the reader
        // - includes the decoder
        //loadSettings(); --> done in onResume

        // load fields from static code
        debug("Loaded fields: " + fields.size());

        // load fields
        //final SharedPreferences settings = getSharedPreferences(PREFERENCES_FILE, 0);
        (new Thread(new Runnable() {
            @Override
            public void run() {
                debug("Loading fields last field values from database");
                for (int i = 0; i < fields.size(); i++) {
                    Field field = fields.get(i);
                    if (field != null)
                        field.setCalculatedValue(CanzeDataSource.getInstance().getLast(field.getSID()));
                    //debug("MainActivity: Setting "+field.getSID()+" = "+field.getValue());
                    //f.setValue(settings.getFloat(f.getUniqueID(), 0));
                }
                debug("Loading fields last field values from database (done)");
            }
        })).start();
    }


    @Override
    public void onResume() {
        debug("MainActivity: onResume");

        instance = this;

        visible = true;
        super.onResume();

        // if returning from a single widget activity, we have to leave here!
        if (returnFromWidget) {
            returnFromWidget = false;
            return;
        }

        if (!leaveBluetoothOn) {
            setBluetoothState(BLUETOOTH_DISCONNECTED);
            // settings are (re)loaded here on the UI thread, the connection is made in the background
            loadSettings();
            reloadBluetoothAsync(false);
        }

        final SharedPreferences settings = getSharedPreferences(PREFERENCES_FILE, 0);
        if (!settings.getBoolean("disclaimer", false)) {

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);

            // set title
            alertDialogBuilder.setTitle(R.string.prompt_Disclaimer);

            // set dialog message
            String yes = getStringSingle(R.string.prompt_Accept);
            String no = getStringSingle(R.string.prompt_Decline);

            Display display = getWindowManager().getDefaultDisplay();
            Point size = new Point();
            display.getSize(size);
            float width = size.x;
            float height = size.y;
            // int height = size.y;
            width = width / getResources().getDisplayMetrics().scaledDensity;
            height = height / getResources().getDisplayMetrics().scaledDensity;
            if (width <= 480 || height <= 480) {
                yes = getStringSingle(R.string.default_Yes);
                no = getStringSingle(R.string.default_No);
            }

            alertDialogBuilder
                    .setMessage(Html.fromHtml(getStringSingle(R.string.prompt_DisclaimerText)))
                    .setCancelable(true)
                    .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            // if this button is clicked, close
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putBoolean("disclaimer", true);
                            // editor.commit();
                            editor.apply();
                            // current activity
                            dialog.cancel();
                        }
                    })
                    .setNegativeButton(no,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    // if this button is clicked, just close
                                    // the dialog box and do nothing
                                    dialog.cancel();
                                    //MainActivity.this.finishAffinity(); requires API16
                                    MainActivity.this.finish();
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                    System.exit(0);
                                }
                            });

            // create alert dialog
            AlertDialog alertDialog = alertDialogBuilder.create();

            // show it
            alertDialog.show();
            //alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_SP, 25.0f);
            //alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextSize(TypedValue.COMPLEX_UNIT_SP, 25.0f);
        }
    }

    public void reloadBluetooth() {
        reloadBluetooth(true);
    }

    public void reloadBluetooth(boolean reloadSettings) {
        if (reloadSettings) {
            // settings must be reloaded on the UI thread; the connect follows in order
            reloadBluetoothAsync(true);
            return;
        }
        reloadBluetoothNow();
    }

    @Override
    public void onPause() {
        debug("MainActivity.onPause");
        debug("MainActivity.onPause > leaveBluetoothOn = " + leaveBluetoothOn);
        visible = false;

        // stop here if BT should stay on!
        if (bluetoothBackgroundMode) {
            super.onPause();
            return;
        }

        if (!leaveBluetoothOn) {
            if (device != null)
                device.clearFields();
            debug("MainActivity.onPause: stopping BT");
            stopBluetoothAsync(true);
        }

        super.onPause();
    }

    public void stopBluetooth() {
        stopBluetooth(true);
    }

    /** Blocking (bounded). UI code should use stopBluetoothAsync() instead. */
    public void stopBluetooth(boolean reset) {
        stopBluetoothNow(reset);
    }

    private static void stopBluetoothNow(boolean reset) {
        Device current = device;
        if (current != null) {
            // stop the device
            debug("MainActivity.stopBluetooth > stopAndJoin");
            current.stopAndJoin();
            // remove reference
            if (reset) {
                current.clearFields();
            }
        }
        // disconnect BT
        debug("MainActivity.stopBluetooth > BT disconnect");
        BluetoothManager.getInstance().disconnect();
    }

    private static void reloadBluetoothNow() {
        String address = bluetoothDeviceAddress;
        if (address == null || address.isEmpty()) {
            debug("MainActivity.reloadBluetooth > no dongle configured, not connecting");
            return;
        }
        // returns immediately, the connection is made on a background thread
        BluetoothManager.getInstance().connect(address, true, BluetoothManager.RETRIES_INFINITE);
    }

    /** Stop Bluetooth without blocking the caller. Runs after every earlier request. */
    public static void stopBluetoothAsync(final boolean reset) {
        submitBluetoothTask("stop", new Runnable() {
            @Override
            public void run() {
                stopBluetoothNow(reset);
            }
        });
    }

    /**
     * Reconnect without blocking the caller. Runs after every earlier request.
     * With reloadSettings the settings are first reloaded on the UI thread (immediately when
     * called from it), never on the Bluetooth executor.
     */
    public static void reloadBluetoothAsync(final boolean reloadSettings) {
        final MainActivity main = instance;
        if (reloadSettings && main != null) {
            main.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    main.loadSettings();
                    submitReload();
                }
            });
            return;
        }
        submitReload();
    }

    private static void submitReload() {
        submitBluetoothTask("reload", new Runnable() {
            @Override
            public void run() {
                reloadBluetoothNow();
            }
        });
    }

    /** Drop the link and reconnect, keeping the registered fields. Safe from any thread. */
    public static void restartBluetoothAsync() {
        submitBluetoothTask("restart", new Runnable() {
            @Override
            public void run() {
                stopBluetoothNow(false);
                reloadBluetoothNow();
            }
        });
    }

    private static void submitBluetoothTask(final String name, final Runnable task) {
        try {
            BLUETOOTH_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        debug("MainActivity: Bluetooth " + name + " task failed: " + e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            debug("MainActivity: Bluetooth " + name + " task rejected: " + e.getMessage());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        MainActivity.debug("MainActivity.onActivityResult");
        MainActivity.debug("MainActivity.onActivityResult > requestCode = " + requestCode);
        MainActivity.debug("MainActivity.onActivityResult > resultCode = " + resultCode);

        // this must be set in any case
        leaveBluetoothOn = false;

        if (requestCode == SETTINGS_ACTIVITY) {
            // nothing to do: leaveBluetoothOn is false now, so onResume (which follows right
            // after this) reloads the settings and reconnects. Loading here parsed them twice.
            MainActivity.debug("MainActivity.onActivityResult > settings closed, onResume reloads");
        } else if (requestCode == LEAVE_BLUETOOTH_ON) {
            MainActivity.debug("MainActivity.onActivityResult > " + LEAVE_BLUETOOTH_ON);
            returnFromWidget = true;
            // register fields this activity needs
            /*
            registerFields();
             */
        } else
            super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        debug("MainActivity: onDestroy");

        dataLogger.destroy(); // clean up
        cleanUpHandler.removeCallbacks(cleanUpTask);

        // stop the device nicely and disconnect the bluetooth, off the UI thread
        stopBluetoothAsync(true);

        // un-register for bluetooth changes
        this.unregisterReceiver(broadcastReceiver);

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);

        // get a reference to the bluetooth action button
        bluetoothMenutItem = menu.findItem(R.id.action_bluetooth);
        // and put the right view on it
        bluetoothMenutItem.setActionView(R.layout.animated_menu_item);
        // set the correct initial state
        setBluetoothState(BLUETOOTH_DISCONNECTED);
        // get access to the image view
        ImageView imageView = bluetoothMenutItem.getActionView().findViewById(R.id.animated_menu_item_action);
        // define an action
        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast(getStringSingle(R.string.toast_Reconnecting));
                stopBluetoothAsync(true);
                reloadBluetoothAsync(true);
            }
        });

        return true;
    }


    /** Safe to call from any thread: the icon is always updated on the UI thread. */
    private void setBluetoothState(final int btState) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyBluetoothState(btState);
            }
        });
    }

    private void applyBluetoothState(int btState) {
        MenuItem item = bluetoothMenutItem;
        if (item == null) return;
        View view = item.getActionView();
        if (view == null) return;
        ImageView imageView = view.findViewById(R.id.animated_menu_item_action);
        if (imageView == null) return;

        stopIconAnimation(imageView);
        switch (btState) {
            case BLUETOOTH_DISCONNECTED:
                imageView.setBackgroundResource(R.mipmap.bluetooth_none);
                break;
            case BLUETOOTH_CONNECTED:
                imageView.setBackgroundResource(R.mipmap.bluetooth_3);
                break;
            case BLUETOOTH_SEARCH:
                imageView.setBackgroundResource(R.drawable.animation_bluetooth);
                if (imageView.getBackground() instanceof AnimationDrawable) {
                    ((AnimationDrawable) imageView.getBackground()).start();
                }
                break;
            default:
                break;
        }
    }

    private static void stopIconAnimation(ImageView imageView) {
        if (!(imageView.getBackground() instanceof AnimationDrawable)) return;
        AnimationDrawable animation = (AnimationDrawable) imageView.getBackground();
        if (animation.isRunning()) animation.stop();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        // start the settings activity
        if (id == R.id.action_settings) {

            if (isSafe()) {
                // run a toast
                toast(R.string.toast_WaitingSettings);

                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // give the toast a moment to appear
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        if (device != null) {
                            // stop the BT device
                            device.stopAndJoin();
                            device.clearFields();
                            BluetoothManager.getInstance().disconnect();
                        }

                        // load the activity
                        Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                        startActivityForResult(intent, SETTINGS_ACTIVITY);
                    }
                })).start();
                return true;
            }

            // see AppSectionsPagerAdapter for the right sequence
        } else if (id == R.id.action_main) {
            viewPager.setCurrentItem(0, true);
            updateActionBar();

        } else if (id == R.id.action_technical) {
            viewPager.setCurrentItem(1, true);
            updateActionBar();

        } else if (id == R.id.action_experimental) {
            viewPager.setCurrentItem(2, true);
            updateActionBar();
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onFieldUpdateEvent(Field field) {
        if (field.getSID().equals("5d7.0")) {
            //debug("Speed "+field.getValue());
            isDriving = (field.getValue() > 10);
        }
    }

    public static boolean isSafe() {
        boolean safe = !isDriving || !safeDrivingMode;
        MainActivity main = instance;
        if (!safe && main != null) {
            Toast.makeText(main, R.string.toast_NotWhileDriving, Toast.LENGTH_LONG).show();
        }
        return safe;
    }

    public static boolean isZOE() {
        return (car == CAR_X10 || car == CAR_ZOE_Q90 || car == CAR_ZOE_Q210 || car == CAR_ZOE_R90 || car == CAR_ZOE_R240);
    }

    public static boolean isFluKan() {
        return (car == CAR_FLUENCE || car == CAR_KANGOO);
    }

    public static boolean isTwizy() {
        return (car == CAR_TWIZY);
    }


    public static String getBluetoothDeviceAddress() {
        if ("HTTP Gateway".equals(bluetoothDeviceName))
            return gatewayUrl;
        return bluetoothDeviceAddress;
    }

    void versionChangeCheck(SharedPreferences settings) {
        // get the current and the saved version of the app
        String previousVersion = settings.getString("appVersion", "");
        String currentVersion = "";
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            currentVersion = pInfo.versionName;
        } catch (Exception e) {
            // ignore this error, currentVersion = ""
        }

        if (currentVersion.equals(previousVersion)) return;

        // this case statement contains optional code to move a previous instance of the app to the
        // current state
        switch (previousVersion) {
            case "":
            default:
                // clear database
                CanzeDataSource.getInstance().clear();
                break;
        }

        // if we successfully got the current version of the app, we save it in the preferences
        if (!currentVersion.equals("")) {
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("appVersion", currentVersion);
            editor.apply();
            // no finish() here: it closed the app on the first launch after every update
        }
    }


    public static String getStringSingle(int resId) {
        if (res == null) return "";
        try {
            return res.getString(resId);
        } catch (Resources.NotFoundException e) {
            return "";
        }
    }

    public static String[] getStringList(int resId) {
        if (res == null) return null;
        try {
            return res.getStringArray(resId);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    public void setDebugListener(DebugListener debugListener) {
        this.debugListener = debugListener;
    }

    public void dropDebugMessage(String msg) {
        if (debugListener != null) debugListener.dropDebugMessage(msg);
    }

    public void appendDebugMessage(String msg) {
        if (debugListener != null) debugListener.appendDebugMessage(msg);
    }

    public int getScreenOrientation() {
        WindowManager wm = getWindowManager();
        if (wm == null) return Configuration.ORIENTATION_PORTRAIT;
        Display screenOrientation = wm.getDefaultDisplay();
        if (screenOrientation == null) return Configuration.ORIENTATION_PORTRAIT;
        if (screenOrientation.getWidth() == screenOrientation.getHeight()) {
            return Configuration.ORIENTATION_SQUARE;
        } else if (screenOrientation.getWidth() > screenOrientation.getHeight()) {
            return Configuration.ORIENTATION_LANDSCAPE;
        }
        return Configuration.ORIENTATION_PORTRAIT;
    }

    public boolean isLandscape() {
        return getScreenOrientation() == Configuration.ORIENTATION_LANDSCAPE;
    }

    public boolean isPortrait() {
        return getScreenOrientation() == Configuration.ORIENTATION_PORTRAIT;
    }

}
