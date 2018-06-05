package com.eLongboard.HandyController;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.widget.TextView;


public class HCTalker {

    public static final int STATE_NONE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private int safeTimeDelay = 100;
    private long firstTouchTime = 0;
    private int brakePos = 126;

    private int mState;
    private Handler mHandler;
    private BluetoothAdapter mAdapter;

    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int power = 126;
    private TextView powerTextView = null;
    private int prePower = 126;

    private Handler timerHandle = new Handler();
    private int timerDaly = 100;
    private Runnable runnable = new Runnable() {
        public void run() {
            long loopStartTime = System.currentTimeMillis();
            if (prePower != power && loopStartTime - firstTouchTime > safeTimeDelay) {

                int step = 200;
                if (prePower > power) {
                    prePower = Math.max(prePower - step, power);
                } else {
                    prePower = Math.min(prePower + step, power);
                }
            }
            if (powerTextView != null){
                String displayText = "";
                if (prePower <= brakePos) {
                    int percent = (brakePos - prePower) * 100 / brakePos;
                    displayText = "Brake " + Integer.toString(percent) + "%";
                } else if (prePower <254) {
                    int percent = (prePower - brakePos) * 100 / (253 - brakePos);
                    displayText = "Power " + Integer.toString(percent) + "%";
                }
                if (firstTouchTime == 0) {
                    displayText = "No touch detected";
                }
                powerTextView.setText(displayText);
            }
            sendCmd();
            long loopUsedTime = System.currentTimeMillis() - loopStartTime;
            timerHandle.postDelayed(this, Math.max(timerDaly - loopUsedTime, 1));
        }
    };

    public HCTalker(Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mHandler = handler;
        setState(STATE_NONE);
        timerHandle.postDelayed(runnable, timerDaly);
    }

    public void setPowerTextView(TextView view) {
        powerTextView = view;
    }

    private synchronized void setState(int state) {
        mState = state;
        if (mHandler != null) {
            mHandler.obtainMessage(HCRemoteControl.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
        } else {
            // XXX
        }
    }

    public synchronized void connect(BluetoothDevice device) {

        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    public synchronized void connected(BluetoothSocket socket) {
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();

        setState(STATE_CONNECTED);
    }

    public synchronized void stop() {
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        setState(STATE_NONE);
    }

    private void connectionFailed() {
        setState(STATE_NONE);
    }

    public void setCtrlValue(int newValue) {
        long currentTime = System.currentTimeMillis();
        if (newValue <= 253 && firstTouchTime == 0) {
            firstTouchTime = currentTime;
        }
        if (newValue == 255) {
            firstTouchTime = 0;
            power = brakePos;
        } else {
            if (newValue < 254)
                power=newValue;
                sendCmd((byte) newValue);
        }
    }

    public void sendCmd() {
        byte[] data = { (byte) prePower};
        write(data);
    }

    public void sendCmd(byte cmd) {
        byte[] data = {cmd};
        write(data);
    }

    private void write(byte[] out) {
        ConnectedThread r;
        synchronized (this) {
            if (mState != STATE_CONNECTED) {
                return;
            }
            r = mConnectedThread;
        }
        r.write(out);
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;

            try {
                tmp = device.createRfcommSocketToServiceRecord(UUID
                        .fromString("00001101-0000-1000-8000-00805F9B34FB"));
            } catch (IOException e) {
                e.printStackTrace();
            }
            mmSocket = tmp;
        }

        public void run() {
            setName("ConnectThread");
            mAdapter.cancelDiscovery();

            try {
                mmSocket.connect();
            } catch (IOException e) {
                connectionFailed();
                try {
                    mmSocket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                return;
            }

            synchronized (HCTalker.this) {
                mConnectThread = null;
            }

            connected(mmSocket);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            OutputStream tmpOut = null;

            try {
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            mmOutStream = tmpOut;
        }

        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
                e.printStackTrace();
                // XXX?
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
