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

import android.content.res.ColorStateList;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.bluetooth.BluetoothManager;
import lu.fisch.canze.classes.Blacklist;
import lu.fisch.canze.interfaces.FieldListener;
import lu.fisch.canze.widgets.WidgetView;

/**
 * Created by robertfisch on 30.09.2015.
 */
public abstract class CanzeActivity extends AppCompatActivity implements FieldListener, Blacklist.ChangeListener {

    /** colour applied to the text of a value that is no longer being requested */
    private static final int SKIPPED_TEXT_COLOR = 0xFFCC0000;

    /** SID -> id of the TextView showing it, populated by addField(sid, interval, viewId) */
    private final HashMap<String, Integer> skipViewIds = new HashMap<>();
    /** original text colours, captured once so un-skipping restores them faithfully */
    private final HashMap<Integer, ColorStateList> originalTextColors = new HashMap<>();

    private TextView skipBanner = null;

    private boolean iLeftMyOwn = false;
    private boolean back = false;

    protected boolean widgetView = false;

    public void setWidgetClicked(boolean widgetClicked) {
        this.widgetClicked = widgetClicked;
    }

    protected boolean widgetClicked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(MainActivity.device!=null)
            if(!BluetoothManager.getInstance().isConnected()) {
                // restart Bluetooth
                MainActivity.debug("CanzeActivity: restarting BT");
                (new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            BluetoothManager.getInstance().connect();
                        } catch (InvalidParameterException e) {
                            MainActivity.toast(-100, "Can't connect. Bluetooth not configured yet?");
                        }
                    }
                })).start();
                //BluetoothManager.getInstance().connect();
            }
        MainActivity.debug("CanzeActivity: onCreate ("+this.getClass().getSimpleName()+")");
    }

    @Override
    protected void onPause() {
        super.onPause();
        MainActivity.debug("CanzeActivity: onPause");

        // done before the early return below, so we never leave a stale listener behind
        Blacklist.getInstance().clearChangeListener(this);

        // stop here if BT should stay on!
        if(MainActivity.bluetoothBackgroundMode)
        {
            return;
        }

        // if we are not coming back from somewhere, stop Bluetooth
        if(!back && !widgetClicked) {
            MainActivity.debug("CanzeActivity: onPause > stopBluetooth");
            MainActivity.getInstance().stopBluetooth(false);
        }
        if(!widgetClicked) {
            // remember we paused ourselves
            iLeftMyOwn=true;
        }
        removeFieldListeners();
        if(MainActivity.getInstance()!=null)
            MainActivity.getInstance().setDebugListener(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MainActivity.debug("CanzeActivity: onResume");
        // if we paused ourselvers
        if (iLeftMyOwn && !widgetClicked) {
            MainActivity.debug("CanzeActivity: onResume > reloadBluetooth");
            // restart Bluetooth
            MainActivity.getInstance().reloadBluetooth(false);
            iLeftMyOwn = false;
        }

        if(BluetoothManager.getInstance().isDummyMode())
            MainActivity.device.initConnection();

        if(!widgetClicked) {
            MainActivity.debug("CanzeActivity: onResume > initWidgets");
            // initialise the widgets (if any present)
            initWidgets();
        }
        widgetClicked=false;
        initListeners();

        installSkipBanner();
        Blacklist.getInstance().setChangeListener(this);
        applySkipColors();
    }

    @Override
    protected void onDestroy() {
        MainActivity.debug("CanzeActivity: onDestroy");
        if(!widgetView) {
            // free the widget listerners
            freeWidgetListeners();
            // free field listeners
            removeFieldListeners();
            if (isFinishing()) {
                MainActivity.debug("CanzeActivity: onDestroy (finishing)");
                // clear filters
                if(MainActivity.device!=null)
                    MainActivity.device.clearFields();
                //MainActivity.registerFields();
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if(MainActivity.isSafe()) {
            super.onBackPressed();
            back = true;
        }
    }

    /********************************************************/

    protected void initWidgets()
    {
        final ArrayList<WidgetView> widgets = getWidgetViewArrayList((ViewGroup) findViewById(android.R.id.content));
        if(!widgets.isEmpty())
            MainActivity.toast(R.string.toast_InitWidgets);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // connect the widgets to the respective fields
                // and add the filters to the reader
                for (int i = 0; i < widgets.size(); i++) {
                    final WidgetView wv = widgets.get(i);

                    // connect widgets to fields
                    if (wv == null) {
                        throw new ExceptionInInitializerError("CanzeActivity: initWidgets: Widget <" + i + "> is NULL!");
                    }

                    wv.setCanzeActivity(CanzeActivity.this);

                    MainActivity.debug("CanzeActivity: initWidgets: Widget: " + wv.getDrawable().getTitle() + " ("+wv.getFieldSID()+")");
                }
            }
        }).start();
    }

    protected void freeWidgetListeners()
    {
        // free up the listener again
        ArrayList<WidgetView> widgets = getWidgetViewArrayList((ViewGroup) findViewById(R.id.table));
        for(int i=0; i<widgets.size(); i++) {
            WidgetView wv = widgets.get(i);
            String sid = wv.getFieldSID();
            if(sid!=null) {
                String[] sids = sid.split(",");
                for (String sid1 : sids) {
                    Field field = MainActivity.fields.getBySID(sid1);
                    if (field != null) {
                        field.removeListener(wv.getDrawable());
                    }
                }
            }
        }
    }


    protected ArrayList<WidgetView> getWidgetViewArrayList(ViewGroup viewGroup)
    {
        ArrayList<WidgetView> result = new ArrayList<>();

        if(viewGroup!=null)
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View v = viewGroup.getChildAt(i);
            if (v instanceof ViewGroup) {
                result.addAll(getWidgetViewArrayList((ViewGroup) v));
            }
            else if (v instanceof WidgetView)
            {
                result.add((WidgetView)v);
            }
        }

        return result;
    }

    /*
    public static String compress(String string) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(string.length());
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(string.getBytes());
        gos.close();
        byte[] compressed = os.toByteArray();
        os.close();
        return Base64.encodeToString(compressed, Base64.NO_WRAP);
    }

    public static String decompress(String zipText) throws IOException {
        byte[] compressed = Base64.decode(zipText,Base64.NO_WRAP);
        final int BUFFER_SIZE = 32;
        ByteArrayInputStream is = new ByteArrayInputStream(compressed);
        GZIPInputStream gis = new GZIPInputStream(is, BUFFER_SIZE);
        StringBuilder string = new StringBuilder();
        byte[] data = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = gis.read(data)) != -1) {
            string.append(new String(data, 0, bytesRead));
        }
        gis.close();
        is.close();
        return string.toString();
    }
    */

    /******* activity field stuff ********************/

    protected ArrayList<Field> subscribedFields = new ArrayList<>();

    protected void addField(String sid) {
        addField(sid, 0);
    }

    /**
     * Register a field and remember which TextView displays it, so that the value can
     * be turned red if it ever gets blacklisted.
     *
     * @param sid        the field to register
     * @param intervalMs the polling interval
     * @param viewId     the id of the TextView showing this value, or 0 for none
     */
    protected void addField(String sid, int intervalMs, int viewId)
    {
        addField(sid, intervalMs);
        if (viewId != 0) {
            skipViewIds.put(sid, viewId);
        }
    }

    protected void addField(String sid, int intervalMs)
    {
        Field field = MainActivity.fields.getBySID(sid);
        if (field != null)
        {
            // add a listener to the field
            field.addListener(this);
            // register it in the queue
            MainActivity.device.addActivityField(field, intervalMs);
            // remember this field has been added (filter out doubles)
            if(!subscribedFields.contains(field))
                subscribedFields.add(field);
        } else {
            MainActivity.debug(this.getClass().getSimpleName()+" (CanzeActivity): SID " + sid + " does not exist in class Fields");
        }
    }

    private void removeFieldListeners()
    {
        // free up the listeners again
        for (Field field : subscribedFields)
        {
            field.removeListener(this);
        }
        subscribedFields.clear();
        // note: originalTextColors is deliberately NOT cleared. Re-capturing after we
        // already painted a view red would record red as the "original" colour.
        skipViewIds.clear();
    }

    /* ------------- skipped value feedback ------------- */

    @Override
    public void onBlacklistChanged() {
        applySkipColors();
    }

    /**
     * Adds a red strip at the top of the screen. Uses addContentView rather than
     * assuming anything about the root view, since the layouts in this app root at
     * LinearLayout, ScrollView and TableLayout depending on the screen.
     */
    private void installSkipBanner() {
        if (skipBanner != null) return;

        skipBanner = new TextView(this);
        skipBanner.setBackgroundColor(SKIPPED_TEXT_COLOR);
        skipBanner.setTextColor(0xFFFFFFFF);
        skipBanner.setTextSize(11);
        skipBanner.setPadding(8, 4, 8, 4);
        skipBanner.setVisibility(View.GONE);

        FrameLayout overlay = new FrameLayout(this);
        overlay.addView(skipBanner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        addContentView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void updateSkipBanner() {
        if (skipBanner == null) return;

        StringBuilder skipped = new StringBuilder();
        int count = 0;
        for (Field field : subscribedFields) {
            if (field == null || !field.isSkipped()) continue;
            if (count > 0) skipped.append(", ");
            skipped.append(field.getSID());
            count++;
        }

        if (count == 0) {
            skipBanner.setVisibility(View.GONE);
            return;
        }

        skipBanner.setText(MainActivity.getStringSingle(R.string.label_SkippedOnThisScreen) + " " + skipped);
        skipBanner.setVisibility(View.VISIBLE);
    }

    /**
     * Paint every mapped TextView red if its value is blacklisted, restore it otherwise,
     * and refresh the banner. Safe to call from any thread.
     */
    protected void applySkipColors() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<String, Integer> entry : skipViewIds.entrySet()) {
                    Integer viewId = entry.getValue();
                    if (viewId == null) continue;

                    View view = findViewById(viewId);
                    if (!(view instanceof TextView)) continue;
                    TextView tv = (TextView) view;

                    if (!originalTextColors.containsKey(viewId)) {
                        originalTextColors.put(viewId, tv.getTextColors());
                    }

                    Field field = MainActivity.fields.getBySID(entry.getKey());
                    if (field != null && field.isSkipped()) {
                        tv.setTextColor(SKIPPED_TEXT_COLOR);
                    } else {
                        ColorStateList original = originalTextColors.get(viewId);
                        if (original != null) tv.setTextColor(original);
                    }
                }
                updateSkipBanner();
            }
        });
    }

    public void dropDebugMessage (final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView tv = findViewById(R.id.textDebug);
                if (tv != null) tv.setText(msg);
            }
        });
    }

    public void appendDebugMessage (final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView tv = findViewById(R.id.textDebug);
                if (tv != null) tv.setText(tv.getText() + " " + msg);
            }
        });
    }

    @Override
    public void onFieldUpdateEvent(Field field) {
        // empty --> descents should override this
    }

    protected abstract void initListeners();
}

