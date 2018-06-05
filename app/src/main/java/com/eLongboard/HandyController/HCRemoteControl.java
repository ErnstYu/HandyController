package com.eLongboard.HandyController;

import java.io.FileOutputStream;

import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Vibrator;

public class HCRemoteControl extends Activity implements OnSharedPreferenceChangeListener {

    private boolean NO_BT = false;

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_CONNECT_DEVICE = 2;

    public static final int MESSAGE_TOAST = 1;
    public static final int MESSAGE_STATE_CHANGE = 2;

    public static final String TOAST = "toast";

    private static final int MODE_BUTTONS = 1;

    private BluetoothAdapter mBluetoothAdapter;
    private HCTalker mHCTalker;
    private boolean hornOpen = false;

    private int mState = HCTalker.STATE_NONE;
    private int mSavedState = HCTalker.STATE_NONE;
    private boolean mNewLaunch = true;
    private String mDeviceAddress = null;
    private TextView mStateDisplay;
    private MenuItem mBluetoothMenu;
    private TextView power_value;
    private Menu mMenu;

    private int mPower = 0;
    private boolean inLowPowerArea = true;
    private int mControlsMode = MODE_BUTTONS;
    private FileOutputStream fos;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        readPreferences(prefs, null);
        prefs.registerOnSharedPreferenceChangeListener(this);

        if (savedInstanceState != null) {
            mNewLaunch = false;
            mDeviceAddress = savedInstanceState.getString("device_address");
            if (mDeviceAddress != null) {
                mSavedState = HCTalker.STATE_CONNECTED;
            }

            if (savedInstanceState.containsKey("power")) {
                mPower = savedInstanceState.getInt("power");
            }
            if (savedInstanceState.containsKey("controls_mode")) {
                mControlsMode = savedInstanceState.getInt("controls_mode");
            }
        }

        if (!NO_BT) {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            if (mBluetoothAdapter == null) {
                Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        setupUI();
        mHCTalker = new HCTalker(mHandler);
        mHCTalker.setPowerTextView(power_value);
    }

    private void updateMenu(int disabled) {
        if (mMenu != null)
            mMenu.findItem(R.id.menuitem_buttons).setEnabled(disabled != R.id.menuitem_buttons).setVisible(disabled != R.id.menuitem_buttons);
    }

    @Override
    public boolean onTouchEvent (MotionEvent event) {
        switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_MOVE:
            int x  = (int)event.getRawX();
            int y = (int)event.getRawY();
            int viewHeight = getWindowManager().getDefaultDisplay().getHeight();

            int offset = viewHeight / 6;
            int power = 253 - (y - offset) * 253 / (viewHeight - 2 * offset);
            power = Math.min(253, Math.max(0, power));
            mHCTalker.setCtrlValue(power);
            if ((inLowPowerArea && power > 133) || (!inLowPowerArea && power < 119)) {
                Vibrator mVibrator = (Vibrator)getApplication().getSystemService(Service.VIBRATOR_SERVICE);
                mVibrator.vibrate(200);
                inLowPowerArea = (power < 126);
            }
            break;
        case MotionEvent.ACTION_UP:
            mHCTalker.setCtrlValue(255);
            break;
        default:
            break;
        }
        return false;
    }


    private void setupUI() {
        if (mControlsMode == MODE_BUTTONS) {
            setContentView(R.layout.main);
            updateMenu(R.id.menuitem_buttons);
            power_value = (TextView) findViewById(R.id.power_value);
        }
        mStateDisplay = (TextView) findViewById(R.id.state_display);
        displayState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!NO_BT) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            } else {
                if (mSavedState == HCTalker.STATE_CONNECTED) {
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mDeviceAddress);
                    mHCTalker.connect(device);
                } else {
                    if (mNewLaunch) {
                        mNewLaunch = false;
                        findBrick();
                    }
                }
            }
        }
    }

    private void findBrick() {
        Intent intent = new Intent(this, ChooseDeviceActivity.class);
        startActivityForResult(intent, REQUEST_CONNECT_DEVICE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case REQUEST_ENABLE_BT:
            if (resultCode == Activity.RESULT_OK) {
                findBrick();
            } else {
                Toast.makeText(this, "Bluetooth not enabled, exiting.", Toast.LENGTH_LONG).show();
                finish();
            }
            break;
        case REQUEST_CONNECT_DEVICE:
            if (resultCode == Activity.RESULT_OK) {
                String address = data.getExtras().getString(ChooseDeviceActivity.EXTRA_DEVICE_ADDRESS);
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                mDeviceAddress = address;
                mHCTalker.connect(device);
            }
            break;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mState == HCTalker.STATE_CONNECTED) {
            outState.putString("device_address", mDeviceAddress);
        }
        outState.putInt("power", mPower);
        outState.putInt("controls_mode", mControlsMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setupUI();
    }

    private void displayState() {
        String stateText = null;
        int color = 0;

        switch (mState){
        case HCTalker.STATE_NONE:
            stateText = "Not connected";
            color = 0xffff0000;
            setProgressBarIndeterminateVisibility(false);
            break;
        case HCTalker.STATE_CONNECTING:
            stateText = "Connecting...";
            color = 0xffffff00;
            setProgressBarIndeterminateVisibility(true);
            break;
        case HCTalker.STATE_CONNECTED:
            stateText = "Connected";
            color = 0xff00ff00;
            setProgressBarIndeterminateVisibility(false);
            break;
        }
        mStateDisplay.setText(stateText);
        mStateDisplay.setTextColor(color);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_TOAST:
                Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST), Toast.LENGTH_SHORT).show();
                break;
            case MESSAGE_STATE_CHANGE:
                mState = msg.arg1;
                displayState();
                break;
            }
        }
    };

    @Override
    protected void onStop() {
        super.onStop();
        mSavedState = mState;
        mHCTalker.stop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.options_menu, menu);
        mMenu = menu;
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        mBluetoothMenu = menu.findItem(R.id.menuitem_bluetooth);

        switch (mState){
            case HCTalker.STATE_NONE:
                mBluetoothMenu.setTitle("Connect");
                break;
            case HCTalker.STATE_CONNECTING:
                mBluetoothMenu.setTitle("Waiting");
                break;
            case HCTalker.STATE_CONNECTED:
                mBluetoothMenu.setTitle("Disconnect");
                break;
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menuitem_buttons:
            mControlsMode = MODE_BUTTONS;
            setupUI();
            break;
        case R.id.menuitem_light:
            if (mState == HCTalker.STATE_CONNECTED) {
                mHCTalker.setCtrlValue(254);
            } else {
                findBrick();
            }
            break;
        case R.id.menuitem_bluetooth:
            if (item.getTitle().equals("Connect")) {
                if (!NO_BT) {
                    findBrick();
                } else {
                    mState = HCTalker.STATE_CONNECTED;
                    displayState();
                }
            } else if (item.getTitle().equals("Disconnect")) {
                mHCTalker.stop();
            }
            break;
        default:
            return false;
        }
        return true;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        readPreferences(sharedPreferences, key);
    }

    private void readPreferences(SharedPreferences prefs, String key) {
    }
}
