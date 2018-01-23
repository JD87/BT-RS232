package com.example.david.bt_rs232hmi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

import static java.lang.Thread.sleep;

public class MainActivity extends AppCompatActivity {

    BluetoothSocket mBluetoothSocket;
    BluetoothDevice mBluetoothDevice;
    BluetoothAdapter mBluetoothAdapter;
    OutputStream mOutputStream;
    InputStream mInputStream;

    boolean stopWorker = false;
    boolean periodically = false;

    TextView tv_temp;
    Switch mswitch;
    Button mybutton;
    CheckBox mcheckbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tv_temp = (TextView) findViewById(R.id.editText);
        mswitch = (Switch) findViewById(R.id.switch1);
        mybutton = (Button) findViewById(R.id.button);
        mcheckbox = (CheckBox) findViewById(R.id.checkBox);

        find_BTDevice();

        mswitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked)
                    find_BTDevice();
                else
                    try {
                        closeBT();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

            }
        });

        mcheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked)
                    periodically = true;
                else
                    periodically = false;
            }
        });

        mybutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (periodically)
                    try {
                        sprechen();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                else
                    try {
                        sendData();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu,menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i_settings = new Intent(this,PreferencesActivity.class);
        startActivity(i_settings);
        return true;
    }
    void find_BTDevice(){

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBluetoothAdapter == null) {

            // Device doesn't support Bluetooth
            toastMessage("Device doesn't support Bluetooth :(");
            finish();
        }

        if (mBluetoothAdapter.isEnabled()){
            // Bluetooth on
        }
        else {
            Intent turnBTon = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(turnBTon,1);
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if(pairedDevices.size() > 0)
        {
            for(BluetoothDevice device : pairedDevices)
            {
                if(device.getName().equals("HC-05"))
                {
                    mBluetoothDevice = device;
                    toastMessage(device.getName() + " " + device.getAddress() + " found");

                    // 12 bonded, 11bonding, 10 not bonded
                    while(mBluetoothDevice.getBondState() != 12){
                        // wait
                    }
                    if(mBluetoothDevice.getBondState() == 12)
                        toastMessage(device.getName() + " bonded (" + mBluetoothDevice.getBondState() + ")" );
                    try {
                        openBT(device);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
        }

    }

    void openBT(BluetoothDevice device) throws IOException
    {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
        mBluetoothSocket = mBluetoothDevice.createRfcommSocketToServiceRecord(uuid);
        mBluetoothSocket.connect();
        mOutputStream = mBluetoothSocket.getOutputStream();
        mInputStream = mBluetoothSocket.getInputStream();

        zuhoren();

        toastMessage("Bluetooth Opened");
    }

    void closeBT() throws IOException
    {
        stopWorker = true;
        mOutputStream.close();
        mInputStream.close();
        mBluetoothSocket.close();
        toastMessage("Bluetooth Closed");
    }

    void zuhoren(){
        final byte delimiter_lf = 10;      // LF character according to ASCII code, sort of like CR or \n... I think
        final byte delimiter_cr = 13;      // CR character according to ASCII code, for raspi
        final Handler mhandler = new Handler();

        stopWorker = false;
        final int[] readBufferPosition = {0};
        final byte [] readBuffer = new byte[1024];

        Thread workerThread = new Thread(new Runnable()
        {
            public void run()
            {
                mBluetoothAdapter.cancelDiscovery();

                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {
                        int bytesAvailable = mInputStream.available();
                        if(bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];
                            mInputStream.read(packetBytes);
                            for(int i=0;i<bytesAvailable;i++)
                            {
                                byte b = packetBytes[i];
                                if(b == delimiter_lf)       //delimiter
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition[0]];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition[0] = 0;

                                    mhandler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            tv_temp.setText(data);
                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition[0]++] = b;
                                }
                            }
                        }
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    void sprechen() throws  IOException
    {

        final Thread workerThread = new Thread(new Runnable(){
            public void run(){
                mBluetoothAdapter.cancelDiscovery();
                SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
                String message_to_send = pref.getString("text_to_send","*IDN?");

                while(!Thread.currentThread().isInterrupted() && !stopWorker && periodically){

                    String data_to_send = message_to_send + "\n";
                    try {
                        sleep(1000);
                        mOutputStream.write(data_to_send.getBytes());
                    } catch (IOException e) {
                        stopWorker = true;
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
            }
        });
        if(periodically)
            workerThread.start();
        else {
            workerThread.stop();
        }
    }

    void sendData() throws IOException
    {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        String message_to_send = pref.getString("text_to_send","*IDN?");
        message_to_send= message_to_send + "\n";
        mOutputStream.write(message_to_send.getBytes());
        toastMessage("Data Sent");
    }


    // To make simpler the toasting
    private void toastMessage(String message){
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
} //change
