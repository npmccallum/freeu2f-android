package org.fedorahosted.freeu2f;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelUuid;
import android.util.Log;

import org.fedorahosted.freeu2f.u2f.APDURequest;
import org.fedorahosted.freeu2f.u2f.ErrorCode;
import org.fedorahosted.freeu2f.u2f.Packet;
import org.fedorahosted.freeu2f.u2f.PacketParser;
import org.fedorahosted.freeu2f.u2f.Packetable;
import org.fedorahosted.freeu2f.u2f.PacketableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public abstract class U2FGattCallback extends BluetoothGattServerCallback {
    private static Map<APDURequest.Instruction, RequestHandler> requestHandlers = new HashMap<>();
    private static byte[] VERSION = new byte[] { 0b01000000 }; /* VERSION = 1.2, see U2F BT 6.1 */

    static {
        requestHandlers.put(APDURequest.Instruction.AUTHENTICATE, new AuthenticateRequestHandler());
        requestHandlers.put(APDURequest.Instruction.REGISTER, new RegisterRequestHandler());
        requestHandlers.put(APDURequest.Instruction.VERSION, new VersionRequestHandler());
    }

    private U2FGattService mU2FGattService = new U2FGattService();
    private BluetoothLeAdvertiser mBtLeAdvertiser = null;
    private PacketParser mParser = new PacketParser();
    private char mMTU = 20;

    private AdvertiseSettings cfg = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
            .build();

    private AdvertiseData data = new AdvertiseData.Builder()
            .addServiceUuid(new ParcelUuid(mU2FGattService.getUuid()))
            .setIncludeTxPowerLevel(true)
            .setIncludeDeviceName(true)
            .build();

    private AdvertiseCallback cb = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.d("AdvertiseCallback", "Advertising started...");
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.d("AdvertiseCallback", "Advertising failed!");
        }
    };

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device,
                                            int requestId,
                                            int offset,
                                            BluetoothGattCharacteristic chr) {
        int status = BluetoothGatt.GATT_FAILURE;
        byte[] bytes = null;

        if (offset != 0) {
            status = BluetoothGatt.GATT_INVALID_OFFSET;
        } else if (chr.equals(mU2FGattService.controlPointLength)) {
            status = BluetoothGatt.GATT_SUCCESS;
            ByteBuffer bb = ByteBuffer.allocate(2);
            bb.order(ByteOrder.BIG_ENDIAN);
            bb.putChar(mMTU);
            bytes = bb.array();
        } else if (chr.equals(mU2FGattService.serviceRevisionBitfield)) {
            bytes = VERSION;
        }

        getBluetoothGattServer().sendResponse(device, requestId, status, 0, bytes);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattCharacteristic chr,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
        int status = BluetoothGatt.GATT_FAILURE;

        if (offset != 0) {
            status = BluetoothGatt.GATT_INVALID_OFFSET;
        } else if (chr.equals(mU2FGattService.controlPoint)) {
            status = BluetoothGatt.GATT_SUCCESS;

            dump("Input", value);

            try {
                Packet pkt = mParser.update(value);
                if (pkt != null) {
                    Log.d("=================================", "Got packet!");
                    switch (pkt.getCommand()) {
                        case PING:
                            sendReply(device, pkt);
                            break;
                        case MSG:
                            APDURequest req = new APDURequest(pkt.getData());
                            sendReply(device, requestHandlers.get(req.ins).handle(req));
                            break;
                        default:
                            sendReply(device, ErrorCode.INVALID_CMD.toPacket());
                            break;
                    }
                }
            } catch (PacketableException e) {
                e.printStackTrace();
                sendReply(device, e);
            }
        } else if (chr.equals(mU2FGattService.serviceRevisionBitfield)) {
            status = BluetoothGatt.GATT_SUCCESS;
        }

        getBluetoothGattServer().sendResponse(device, requestId, status, 0, null);
    }

    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                         BluetoothGattDescriptor descriptor,
                                         boolean preparedWrite, boolean responseNeeded,
                                         int offset, byte[] value) {
        getBluetoothGattServer().sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
    }

    @Override
    public void onMtuChanged(BluetoothDevice device, int mtu) {
        mtu -= 3; // iOS needs this. I don't know why... Without it, the packet is truncated.
        mtu = mtu > 512 ? 512 : mtu; // Maximum MTU size
        mtu = mtu < 20 ? 20 : mtu; // Minimum MTU size
        mMTU = (char) mtu;
    }

    public void start(BluetoothAdapter bluetoothAdapter) {
        getBluetoothGattServer().addService(mU2FGattService);
        mBtLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        mBtLeAdvertiser.startAdvertising(cfg, data, cb);
    }

    public void stop() {
        mBtLeAdvertiser.stopAdvertising(cb);
        getBluetoothGattServer().removeService(mU2FGattService);
    }

    private void dump(String prfx, byte[] value, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < len; i++)
            sb.append(String.format("%02X", value[i]));
        Log.d("===========================", String.format("%s (%d): %s", prfx, value.length, sb.toString()));
    }

    private void dump(String prfx, byte[] value) {
        dump(prfx, value, 0, value.length);
    }

    public void sendReply(BluetoothDevice device, Packetable reply) {
        for (byte[] frame : reply.toPacket().toFrames(mMTU)) {
            dump("Output", frame);
            mU2FGattService.status.setValue(frame);
            getBluetoothGattServer().notifyCharacteristicChanged(device, mU2FGattService.status, true);
        }
    }

    public abstract void onRequest(BluetoothDevice device, Packet request);
    public abstract BluetoothGattServer getBluetoothGattServer();
}
