package com.example.david.bt_rs232hmi;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
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

    String message_to_send = "SOUR:SENS:DATA?";

    String notify_temp;
    boolean notify_bool = false;
    byte delimiter;

    EditText tv_temp;
    Switch mswitch;
    Button mybutton, mybutton2;
    CheckBox mcheckbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tv_temp = (EditText) findViewById(R.id.editText);
        mswitch = (Switch) findViewById(R.id.switch1);
        mybutton2 = (Button) findViewById(R.id.button2);

        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

        notify_bool = pref.getBoolean("pref_notification", false);

        tv_temp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                periodically = false;
                try {
                    sendData(message_to_send);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        tv_temp.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                periodically = true;
                if(periodically)
                try {
                    sprechen();
                } catch (IOException e){
                    e.printStackTrace();
                }
                return true;
            }
        });

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

        mybutton2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
                String cmd_to_send = pref.getString("text_to_send","*IDN?");

                if(periodically)
                    toastMessage("Disenable Periodically");
                else
                    try {
                        sendData(cmd_to_send);
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
        mBluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
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

    void zuhoren() throws IOException {
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

                                check_for_eol_pref();

                                if(b == delimiter)       //delimiter
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

                                    if(notify_bool)
                                        check_for_notification(data);
                                }
                                else
                                {
                                    if (b == delimiter_lf || b == delimiter_cr){
                                        //  ¯\_(ツ)_/¯
                                        //toastMessage("wrong delimiter");
                                        readBufferPosition[0] = 0;
                                    }
                                    else
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
        //mInputStream.reset();
        workerThread.start();
    }

    void sprechen() throws  IOException
    {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        final String sleep_mils = pref.getString("period_length_mills","1000");
        final long millis = Long.parseLong(String.valueOf(sleep_mils));

        final Thread workerThread = new Thread(new Runnable(){
            public void run(){
                mBluetoothAdapter.cancelDiscovery();

                while(!Thread.currentThread().isInterrupted() && !stopWorker && periodically){

                    String data_to_send = message_to_send + "\n";
                    try {
                        sleep(millis);
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

    void sendData(String message_to_send) throws IOException
    {
        message_to_send= message_to_send + "\n";
        mOutputStream.write(message_to_send.getBytes());
        toastMessage("Data Sent");
    }

    public void notification(){
        NotificationCompat.Builder Notificationbuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.mipmap.di_icon)
                        .setContentTitle("Notify temperature reached")
                        .setContentText("seems we are near or in the temp. you wanted.")
                        .setOnlyAlertOnce(true)
                        .setAutoCancel(true)
                        .setVibrate(new long[] {1000,1000,1000})
                        .setLights(Color.RED, 300, 300)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));

        // Creates an explicit intent for an Activity in your app
        Intent result_notifIntent = new Intent(this,MainActivity.class);

        // The stack builder object will contain an artificial back stack for the
        // started Activity.
        // This ensures that navigating backward from the Activity leads out of
        // your application to the Home screen.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);

        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);

        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(result_notifIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent
                (0,PendingIntent.FLAG_UPDATE_CURRENT);
        Notificationbuilder.setContentIntent(resultPendingIntent);
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // mId allows you to update the notification later on.
        mNotificationManager.notify("notif tag",1,Notificationbuilder.build());
    }

    public void check_for_notification(String data){
        //PROBLEM NOTIFICATION CHECK BOX DOES NOT WORK PROPERLY, SEND TEXT WHIT AND WITHOUT CHECKING IT

        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        notify_temp = pref.getString("temp_to_notify", "25");

        final float fl_data = Float.parseFloat(data);
        final float fl_notif_temp = Float.parseFloat(notify_temp);

        if ((fl_data < (fl_notif_temp + 0.1)) && (fl_data > (fl_notif_temp - 0.1)))
            notification();

    }

    public void check_for_eol_pref(){
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        String str_delimiter = pref.getString("eol_pref","LF");

        Log.d("taaag", str_delimiter);



        if(str_delimiter.equals("LF"))
            delimiter = 10;
        else
            delimiter = 13;
    }

    public int getInt(String s){
        return Integer.parseInt(s.replaceAll("[\\D]", ""));
    }

    // To make simpler the toasting
    private void toastMessage(String message){
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
} //change
