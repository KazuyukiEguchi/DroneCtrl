package jp.eguchi.android.dronectrl;

// Parrot MiniDrones Controller
// Programed by Kazuyuki Eguchi 2016

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "DroneCtrl";

    private BluetoothAdapter mBleAdapter = null;
    private BluetoothLeScanner mBleScanner = null;
    private BluetoothDevice mDevice = null;
    private BluetoothGatt mGatt = null;

    private final static int REQUEST_ENABLE_BT = 128;

    private byte step_fa0b = 0;     // 送信用
    private byte step_fb0e = 100;   // 受信用

    private byte battery = 100;

    private int setup_mode = 0;
    private int setup_conf = 0;

    private byte driveStepsRemaining = 0;
    private byte roll = 0;
    private byte pitch = 0;
    private byte yaw = 0;
    private byte altitude = 0;

    private Timer pingTimer = null;

    private TextView text01 = null;
    private TextView text02 = null;

    private Handler mHandler = null;

    private int status = 0;

    private Vibrator mVib = null;

    // SDK 23向け
    private final static int PERMISSIONS_REQUEST_LOCATION_STATE = 1;

    private boolean checkPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_LOCATION_STATE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
    {
        if (PERMISSIONS_REQUEST_LOCATION_STATE == requestCode) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "許可されました", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "権限を拒否されました", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, final ScanResult result) {
            super.onScanResult(callbackType, result);

            if(result.getDevice().getName() != null) {
                Log.d(TAG, "Discover: " + result.getDevice().getName() + "("  +result.getDevice().getAddress() + ")");

                mDevice = result.getDevice();

                if(mDevice.getName().regionMatches(0,"RS_",0,3) == true)
                {
                    setText01("Discover: " + result.getDevice().getAddress());
                    mBleScanner.stopScan(mScanCallback);
                    gattconnect();
                }
                else if(mDevice.getName().regionMatches(0,"Maclan_",0,7) == true)
                {
                    setText01("Discover: " + result.getDevice().getAddress());
                    mBleScanner.stopScan(mScanCallback);
                    gattconnect();
                }
            } else {
                Log.d(TAG, "Discover: " + result.getDevice().getAddress());
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.d(TAG, "failed:" + errorCode);
        }
    };

    private void gattconnect()
    {
        mDevice.connectGatt(this, true, mGattCallback);
    }

    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            Log.d(TAG, "onConnectionStateChange");

            switch(newState)
            {
                case BluetoothGatt.STATE_CONNECTED:
                    Log.d(TAG,"STATE_CONNECTED");
                    setup_mode = 0;
                    mGatt = gatt;

                    if(!mGatt.discoverServices())
                    {
                        Log.d(TAG,"Error discoverServices()");
                    }
                    break;

                case BluetoothGatt.STATE_DISCONNECTED:
                    Log.d(TAG,"STATE_DISCONNECTED");
                    mGatt = null;
                    break;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Log.d(TAG, "onServicesDiscovered");
            setup();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.d(TAG, "onDescriptorWrite");
            setup_mode++;
            setup();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            // Log.d(TAG, "onCharacteristicWrite");
            setup_conf++;
            switch(setup_conf)
            {
                case 1:
                    init_time();
                    break;

                case 2:
                    wheelOff();
                    break;

                case 3:
                    flatTrim();
                    break;

                case 4:
                    ping();
                    setText01("Ready");
                    break;
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            if(characteristic.getUuid().compareTo(UUID.fromString("9a66fb0f-0800-9191-11e4-012d1540cb8e")) == 0)
            {
                byte[] value = characteristic.getValue();
                battery = value[value.length-1];
                Log.d(TAG,"Battery = "+ Integer.toString(battery,10) + "%");
                setText02("Batt = " + Integer.toString(battery, 10) + "%");
            }
            else if(characteristic.getUuid().compareTo(UUID.fromString("9a66fb1b-0800-9191-11e4-012d1540cb8e")) == 0)
            {
                // ACK 0x1 , seq , コマンド送信時のseq
                Log.d(TAG,"ARCommand ACK");
            }
            else if(characteristic.getUuid().compareTo(UUID.fromString("9a66fb0e-0800-9191-11e4-012d1540cb8e")) == 0)
            {
                byte[] value = characteristic.getValue();

                if(step_fb0e == value[1])
                {
                    return;
                }

                step_fb0e = value[1];

                // ARCOMMANDS_ID_PROJECT_COMMON
                if (value[2] == 0x00)
                {
                    if (value[3] == 0x05)
                    {
                        switch (value[4]) {
                            case 0x04:
                                Log.d(TAG, "ARCOMMANDS_ID_COMMON_COMMONSTATE_CMD_CURRENTDATECHANGED");
                                break;

                            case 0x05:
                                Log.d(TAG, "ARCOMMANDS_ID_COMMON_COMMONSTATE_CMD_CURRENTTIMECHANGED");
                                break;
                        }
                    }

                }
                // ARCOMMANDS_ID_PROJECT_MINIDRONE
                else if (value[2] == 0x02)
                {
                    if (value[3] == 0x03)
                    {
                        switch (value[4])
                        {
                            case 0x00:
                                Log.d(TAG, "ARCOMMANDS_ID_MINIDRONE_PILOTINGSTATE_CMD_FLATTRIMCHANGED");
                                break;

                            case 0x01:
                                // Log.d(TAG, "ARCOMMANDS_ID_MINIDRONE_PILOTINGSTATE_CMD_FLYINGSTATECHANGED");

                                String mes = "";

                                status = value[6];

                                switch (status) {
                                    case 0x00:
                                        Log.d(TAG, "landed");
                                        mes = "着陸中";
                                        break;

                                    case 0x01:
                                        Log.d(TAG, "takingoff");
                                        mes = "離陸";
                                        break;

                                    case 0x02:
                                        Log.d(TAG, "hovering");
                                        mes = "ホバーリング";
                                        break;

                                    case 0x03:
                                        Log.d(TAG, "flying");
                                        mes = "飛んでます";
                                        break;

                                    case 0x04:
                                        Log.d(TAG, "landing");
                                        mes = "着陸しています";
                                        break;

                                    case 0x05:
                                        Log.d(TAG, "emergency");
                                        mes = "緊急停止";
                                        break;

                                    case 0x06:
                                        Log.d(TAG, "rolling");
                                        mes = "旋回中";
                                        break;

                                    case 0x07:
                                        Log.d(TAG, "init");
                                        mes = "初期化";
                                        break;

                                    default:
                                        Log.d(TAG, "Error");
                                        break;
                                }

                                setText01(mes);
                                break;

                            case 0x02:
                                Log.d(TAG, "ARCOMMANDS_ID_MINIDRONE_PILOTINGSTATE_CMD_ALERTSTATECHANGED");
                                break;

                            case 0x03:
                                Log.d(TAG, "ARCOMMANDS_ID_MINIDRONE_PILOTINGSTATE_CMD_AUTOTAKEOFFMODECHANGED");
                                break;

                        }
                    }
                    else if(value[3] == 0x05)
                    {
                        switch(value[4])
                        {
                            case 0x00:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_SPEEDSETTINGSSTATE_CMD_MAXVERTICALSPEEDCHANGED");
                                break;

                            case 0x01:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_SPEEDSETTINGSSTATE_CMD_MAXROTATIONSPEEDCHANGED");
                                break;

                            case 0x02:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_SPEEDSETTINGSSTATE_CMD_WHEELSCHANGED");
                                break;

                            case 0x03:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_SPEEDSETTINGSSTATE_CMD_MAXHORIZONTALSPEEDCHANGED");
                                break;
                        }
                    }
                    else if(value[3] == 0x07)
                    {
                        if (value[4] == 0x00)
                        {
                            if(value[6] == 0x01)
                            {
                                Log.d(TAG,"Picture OK No="+Integer.toHexString(value[7]));
                                if(mVib != null) {
                                    mVib.vibrate(100);
                                }
                            }
                            else
                            {
                                Log.d(TAG,"Picture NG No="+Integer.toHexString(value[7]));
                                if(mVib != null) {
                                    mVib.vibrate(1000);
                                }
                            }

                        }
                    }
                    else if(value[3] == 0x09)
                    {
                        switch(value[4])
                        {
                            case 0x00:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_PILOTINGSETTINGSSTATE_CMD_MAXALTITUDECHANGED");
                                break;

                            case 0x01:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_PILOTINGSETTINGSSTATE_CMD_MAXTILTCHANGED");
                                break;
                        }
                    }
                    else if(value[3] == 0x0b)
                    {
                        switch(value[4])
                        {
                            case 0x00:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_SETTINGSSTATE_CMD_PRODUCTMOTORSVERSIONCHANGED");
                                break;

                            case 0x01:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_SETTINGSSTATE_CMD_PRODUCTINERTIALVERSIONCHANGED");
                                break;

                            case 0x02:
                                Log.d(TAG,"ARCOMMANDS_ID_MINIDRONE_SETTINGSSTATE_CMD_CUTOUTMODECHANGED");
                                break;
                        }
                    }
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate()");

        mVib = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        if(mVib == null) {
            Log.d(TAG, "mVib is Null");
        }

        mHandler = new Handler();

        text01 = (TextView)findViewById(R.id.text01);
        text02 = (TextView)findViewById(R.id.text02);

        setText02("Batt = " + battery + "%");

        Button btn01 = (Button)findViewById(R.id.btn01);

        btn01.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takeoff();
            }
        });

        Button btn02 = (Button)findViewById(R.id.btn02);

        btn02.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                land();
            }
        });

        Button btn03 = (Button)findViewById(R.id.btn03);

        btn03.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                down();
            }
        });

        Button btn04 = (Button)findViewById(R.id.btn04);

        btn04.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                front();
            }
        });

        Button btn05 = (Button)findViewById(R.id.btn05);

        btn05.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                up();
            }
        });

        Button btn06 = (Button)findViewById(R.id.btn06);

        btn06.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                left();
            }
        });

        Button btn07 = (Button)findViewById(R.id.btn07);

        btn07.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takepicture();
            }
        });

        Button btn08 = (Button)findViewById(R.id.btn08);

        btn08.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                right();
            }
        });

        Button btn09 = (Button)findViewById(R.id.btn09);

        btn09.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                turn_left();
            }
        });

        Button btn10 = (Button)findViewById(R.id.btn10);

        btn10.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                back();
            }
        });

        Button btn11 = (Button)findViewById(R.id.btn11);

        btn11.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                turn_right();
            }
        });

        Button btn12 = (Button)findViewById(R.id.btn12);

        btn12.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                leftFlip();
            }
        });

        Button btn13 = (Button)findViewById(R.id.btn13);

        btn13.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                frontFlip();
            }
        });

        Button btn14 = (Button)findViewById(R.id.btn14);

        btn14.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                backFlip();
            }
        });

        Button btn15 = (Button)findViewById(R.id.btn15);

        btn15.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                rightFlip();
            }
        });


        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.d(TAG, "Bluetooth LE Not Support");
            finish();
        }

        BluetoothManager mBleManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        mBleAdapter = mBleManager.getAdapter();

        if ((mBleAdapter == null)
                || (! mBleAdapter.isEnabled())) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        checkPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d(TAG, "onResume()");

        if(mBleAdapter != null)
        {
            mBleScanner = mBleAdapter.getBluetoothLeScanner();

            if(mBleScanner != null) {
                mBleScanner.startScan(mScanCallback);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        Log.d(TAG, "onPause()");

        if(mBleScanner != null)
        {
            mBleScanner.stopScan(mScanCallback);
        }

        if(pingTimer != null)
        {
            pingTimer.cancel();
            pingTimer = null;
        }

        if(mGatt != null)
        {
            mGatt.disconnect();
            mGatt = null;
        }
    }

    private void setup()
    {
        BluetoothGattCharacteristic character = null;
        switch(setup_mode)
        {
            case 0:
                // fb0f Battery
                character = mGatt.getService(UUID.fromString("9a66fb00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fb0f-0800-9191-11e4-012d1540cb8e"));
                break;

            case 1:
                // fb0e
                character = mGatt.getService(UUID.fromString("9a66fb00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fb0e-0800-9191-11e4-012d1540cb8e"));
                break;

            case 2:
                // fb1b
                character = mGatt.getService(UUID.fromString("9a66fb00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fb1b-0800-9191-11e4-012d1540cb8e"));
                break;

            case 3:
                // fb1c
                character = mGatt.getService(UUID.fromString("9a66fb00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fb1c-0800-9191-11e4-012d1540cb8e"));
                break;

            case 4:
                // fd22
                character = mGatt.getService(UUID.fromString("9a66fd21-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fd22-0800-9191-11e4-012d1540cb8e"));
                break;

            case 5:
                // fd23
                character = mGatt.getService(UUID.fromString("9a66fd21-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fd23-0800-9191-11e4-012d1540cb8e"));
                break;

            case 6:
                // fd24
                character = mGatt.getService(UUID.fromString("9a66fd21-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fd24-0800-9191-11e4-012d1540cb8e"));
                break;

            case 7:
                // fd52
                character = mGatt.getService(UUID.fromString("9a66fd51-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fd52-0800-9191-11e4-012d1540cb8e"));
                break;

            case 8:
                // fd53
                character = mGatt.getService(UUID.fromString("9a66fd51-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fd53-0800-9191-11e4-012d1540cb8e"));
                break;

            case 9:
                // fd54
                character = mGatt.getService(UUID.fromString("9a66fd51-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fd54-0800-9191-11e4-012d1540cb8e"));
                break;

            case 10:
                // 初期化コマンドを投入
                init_date();
                return;
        }

        mGatt.setCharacteristicNotification(character,true);

        BluetoothGattDescriptor descriptor = character.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

        mGatt.writeDescriptor(descriptor);
    }

    private void print_byte(byte[] a)
    {
        String res = "";

        for(int i = 0; i < a.length ; i++)
        {
            res = res + Integer.toHexString(a[i] & 0xff) + ",";
        }

        Log.d(TAG, res);
    }

    private void init_date()
    {
        // DRONEの日付を設定する
        Log.d(TAG, "init_date");

        setup_conf = 0;

        Calendar c = Calendar.getInstance();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        byte[] date = sdf.format(c.getTime()).getBytes();

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_COMMON , ARCOMMANDS_ID_COMMON_CLASS_COMMON , ARCOMMANDS_ID_COMMON_COMMON_CMD_CURRENTDATE , ISO-8601 format , NULL
        byte[] data = {0x04,(byte)(++step_fa0b & 0xff),0x00,0x04,0x01,0x00, date[0], date[1], date[2], date[3], date[4], date[5], date[6], date[7], date[8], date[9], 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void init_time()
    {
        Log.d(TAG, "init_time");

        setup_conf = 1;

        Calendar c = Calendar.getInstance();

        SimpleDateFormat sdf = new SimpleDateFormat("'T'HHmmssZZZ");
        byte[] time = sdf.format(c.getTime()).getBytes();

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_COMMON , ARCOMMANDS_ID_COMMON_CLASS_COMMON , ARCOMMANDS_ID_COMMON_COMMON_CMD_CURRENTTIME ,  ISO-8601 format , NULL
        byte[] data = {0x04,(byte)(++step_fa0b & 0xff),0x00,0x04,0x02,0x00, time[0], time[1], time[2], time[3], time[4], time[5], time[6], time[7] , time[8] , time[9], time[10],time[11] , 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void wheelOff()
    {
        Log.d(TAG,"wheelOff()");

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_SPEEDSETTINGS , ARCOMMANDS_ID_MINIDRONE_SPEEDSETTINGS_CMD_WHEELS , FALSE
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff), 0x02, 0x01, 0x02, 0x00, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG, "Error writeCharacteristic");
        }
    }

    private void flatTrim()
    {
        Log.d(TAG,"flatTrim()");

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_PILOTING , ARCOMMANDS_ID_MINIDRONE_PILOTING_CMD_FLATTRIM
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff), 0x02, 0x00, 0x00, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void takeoff()
    {
        Log.d(TAG,"takeoff()");

        if(status != 0)
        {
            Log.d(TAG,"Error Status");
            return;
        }

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_PILOTING , ARCOMMANDS_ID_MINIDRONE_PILOTING_CMD_TAKEOFF
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff),0x02, 0x00, 0x01, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void land()
    {
        Log.d(TAG,"land()");

        if(status != 2)
        {
            Log.d(TAG,"Error Status");
            return;
        }

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_PILOTING , ARCOMMANDS_ID_MINIDRONE_PILOTING_CMD_LANDING
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff), 0x02, 0x00, 0x03, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG, "Error writeCharacteristic");
        }
    }

    private void frontFlip()
    {
        Log.d(TAG,"frontFlip()");

        if(status != 2)
        {
            Log.d(TAG,"Error Status");
            return;
        }

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_ANIMATIONS , ARCOMMANDS_ID_MINIDRONE_ANIMATIONS_CMD_FLIP , 0x00000000
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff), 0x02, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void backFlip()
    {
        Log.d(TAG,"backFlip()");

        if(status != 2)
        {
            Log.d(TAG,"Error Status");
            return;
        }

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_ANIMATIONS , ARCOMMANDS_ID_MINIDRONE_ANIMATIONS_CMD_FLIP , 0x00000001
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff), 0x02, 0x04, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void rightFlip()
    {
        Log.d(TAG,"rightFlip()");

        if(status != 2)
        {
            Log.d(TAG,"Error Status");
            return;
        }

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_ANIMATIONS , ARCOMMANDS_ID_MINIDRONE_ANIMATIONS_CMD_FLIP , 0x00000002
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff), 0x02, 0x04, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void leftFlip()
    {
        Log.d(TAG,"leftFlip()");

        if(status != 2)
        {
            Log.d(TAG,"Error Status");
            return;
        }

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_ANIMATIONS , ARCOMMANDS_ID_MINIDRONE_ANIMATIONS_CMD_FLIP , 0x00000003
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff), 0x02, 0x04, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    // 写真を撮影する
    private void takepicture()
    {
        Log.d(TAG,"takepicture()");

        // fa0b
        BluetoothGattCharacteristic character = null;
        character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

        // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_MEDIARECORD , ARCOMMANDS_ID_MINIDRONE_MEDIARECORD_CMD_PICTURE , STORAGE_ID
        byte[] data = {0x04, (byte) (++step_fa0b & 0xff),0x02, 0x06, 0x00, 0x00, 0x00};

        character.setValue(data);

        if(!mGatt.writeCharacteristic(character))
        {
            Log.d(TAG,"Error writeCharacteristic");
        }
    }

    private void ping()
    {
        Log.d(TAG,"ping()");

        if(pingTimer != null)
        {
            pingTimer.cancel();
        }

        pingTimer = new Timer();

        pingTimer.schedule(new TimerTask() {
            @Override
            public void run() {

                if (mGatt == null) {
                    pingTimer.cancel();
                    pingTimer = null;
                    return;
                }

                // fa0b
                BluetoothGattCharacteristic character = null;
                character = mGatt.getService(UUID.fromString("9a66fa00-0800-9191-11e4-012d1540cb8e")).getCharacteristic(UUID.fromString("9a66fa0b-0800-9191-11e4-012d1540cb8e"));

                // ARCommand ARCOMMANDS_ID_PROJECT_MINIDRONE , ARCOMMANDS_ID_MINIDRONE_CLASS_PILOTING , ARCOMMANDS_ID_MINIDRONE_PILOTING_CMD_PCMD ,
                // Boolean flag to activate roll/pitch movement
                // Roll consign for the MiniDrone [-100;100]
                // Pitch consign for the MiniDrone [-100;100]
                // Yaw consign for the MiniDrone [-100;100]
                // Gaz consign for the MiniDrone [-100;100]
                // Timestamp in miliseconds. Not an absolute time. (Typically 0 = time of connexion). 32bit!?

                byte[] data = {0x02, (byte) (++step_fa0b & 0xff), 0x02, 0x00, 0x02, 0x00, (driveStepsRemaining != 0) ? (byte) 1 : 0, roll, pitch, yaw, altitude, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

                character.setValue(data);

                if (!mGatt.writeCharacteristic(character)) {
                    Log.d(TAG, "Error writeCharacteristic");
                    return;
                }

                if (driveStepsRemaining < 0) {
                } else if (driveStepsRemaining > 0) {
                    driveStepsRemaining--;
                } else {
                    driveStepsRemaining = 0;
                    roll = 0;
                    pitch = 0;
                    yaw = 0;
                    altitude = 0;
                }

            }
        }, 500, 50);
    }

    private void front()
    {
        pitch = 50;
        driveStepsRemaining = 5;
    }

    private void back()
    {
        pitch = -50;
        driveStepsRemaining = 5;
    }

    private void right()
    {
        roll = 50;
        driveStepsRemaining = 5;
    }

    private void left()
    {
        roll = -50;
        driveStepsRemaining = 5;
    }

    private void turn_right()
    {
        yaw = 90;
        driveStepsRemaining = 5;
    }

    private void turn_left()
    {
        yaw = -90;
        driveStepsRemaining = 5;
    }

    private void up()
    {
        altitude = 50;
        driveStepsRemaining = 5;
    }

    private void down()
    {
        altitude = -50;
        driveStepsRemaining = 5;
    }

    private void setText01(final String mes)
    {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                text01.setText(mes);
            }
        });
    }

    private void setText02(final String mes)
    {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                text02.setText(mes);
            }
        });
    }
}
