package org.fedorahosted.freeu2f;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;

import org.fedorahosted.freeu2f.u2f.Packet;

public class MainActivity extends AppCompatActivity {
    private BluetoothManager mBluetoothManager = null;
    private BluetoothGattServer mGattServer = null;
    private ImageView mImageView = null;
    private TextView mTextView = null;

    private U2FGattCallback mGattCallback = new U2FGattCallback() {
        @Override
        public BluetoothGattServer getBluetoothGattServer() {
            return mGattServer;
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);

            switch (newState) {
                case BluetoothProfile.STATE_CONNECTING:
                    mImageView.setImageResource(R.drawable.ic_bluetooth_searching);
                    mTextView.setText(R.string.connecting);
                    break;

                case BluetoothProfile.STATE_CONNECTED:
                    String addr = device.getAddress();
                    String name = device.getName();
                    String text = "[unknown]";

                    if (addr != null && name != null)
                        text = String.format("%s\n%s", name, addr);
                    else if (addr != null)
                        text = addr;
                    else if (name != null)
                        text = name;

                    mImageView.setImageResource(R.drawable.ic_bluetooth_connected);
                    mTextView.setText(text);
                    break;

                case BluetoothProfile.STATE_DISCONNECTED:
                case BluetoothProfile.STATE_DISCONNECTING:
                    mImageView.setImageResource(R.drawable.ic_bluetooth_disabled);
                    mTextView.setText(R.string.disconnected);
                    break;
            }
        }

        @Override
        public void onRequest(BluetoothDevice device, Packet request) {

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        mImageView = (ImageView) findViewById(R.id.imageView);
        mTextView = (TextView) findViewById(R.id.textView);

        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mGattServer = mBluetoothManager.openGattServer(this, mGattCallback);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.about:
                Log.d(getClass().getCanonicalName(), "about button clicked");
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGattCallback.start(mBluetoothManager.getAdapter());
    }

    @Override
    protected void onStop() {
        super.onStop();
        mGattCallback.stop();
    }
}
