
/*
Copyright (C) 2024 Brian Apps

This file is part of picoFaceLoader.

picoFaceLoader is free software: you can redistribute it and/or modify it under the terms
of the GNU General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.

picoFace is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with picoFace. If 
not, see <https://www.gnu.org/licenses/>.
*/

package net.brianapps.picofaceloader;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SectionIndexer;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public class MainActivity extends AppCompatActivity {

    private static final byte COMMAND_START = 0x04;
    private static final byte COMMAND_END = 0x05;
    private static final byte COMMAND_RESPONSE_BEGIN = 0x06;
    private static final byte COMMAND_RECEIVE_DATA = 0x07;
    private static final byte COMMAND_SEND_DATA = 0x08;
    private static final byte COMMAND_SUCCESS = 0x09;
    private static final byte COMMAND_FAILURE = 0x10;
    private static final byte COMMAND_RESPONSE_END = 0x11;
    private static final byte START_OF_DATA = 0x14;
    private static final byte START_OF_PACKET = 0x15;
    private static final byte END_OF_DATA = 0x16;
    private static final byte ACK = 0x17;
    private static final byte NAK = 0x18;

    private static final String ACTION_USB_PERMISSION =
            "net.brianapps..USB_PERMISSION";
    private static final String TAG = "pF Loader";

    private String pendingSnapshotfile = null;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            // call method to set up device communication
                            attemptToLoadSnapshot(false, pendingSnapshotfile);
                        }
                    }
                    else {
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };
    private SearchView searchView;
    private ListView listView;
    private File snapsZip;


    private void transferData(UsbSerialPort port, byte[] data) throws IOException {

        byte[] header = new byte[5];
        int totalSize = data.length;

        header[0] = START_OF_DATA;
        header[1] = (byte) (0xFF & (totalSize >> 24));
        header[2] = (byte) (0xFF & (totalSize >> 16));
        header[3] = (byte) (0xFF & (totalSize >> 8));
        header[4] = (byte) (0xFF & (totalSize));

        port.write(header, 2000);

        byte[] input = new byte[1];

        if (port.read(input, 2000) != 1 || input[0] != ACK) {
            Log.d(TAG, "Expecting ACK but got " + input[0] + " instead");
            return;
        }

        int sentSoFar = 0;
        while (sentSoFar < totalSize) {
            int packetSize = Math.min(totalSize - sentSoFar, 8192);

            header[0] = START_OF_PACKET;
            header[1] = (byte) (0xFF & (packetSize >> 8));
            header[2] = (byte) (0xFF & (packetSize));

            port.write(header, 3, 2000);
            byte[] packetData = Arrays.copyOfRange(data, sentSoFar, sentSoFar + packetSize);
            port.write(packetData, 2000);

            Log.d(TAG, "Sending packet of data. Length=" + packetSize);


            if (port.read(input, 2000) != 1 || input[0] != ACK) {
                Log.d(TAG, "Expecting ACK but got " + input[0] + " instead");
                return;
            }

            sentSoFar += packetSize;
        }

        header[0] = END_OF_DATA;
        port.write(header, 1, 2000);
        if (port.read(input, 2000) != 1 || input[0] != ACK) {
            Log.d(TAG, "Expecting ACK but got " + input[0] + " instead");
            return;
        }
    }


    private byte[] getSnapshotData(String snapshotFilename) throws IOException {

        try (ZipFile zf = new ZipFile(snapsZip, ZipFile.OPEN_READ)) {
            ZipEntry entry = zf.getEntry(snapshotFilename);
            try (InputStream is = zf.getInputStream(entry)) {
                byte[] data = new byte [(int) entry.getSize()];
                is.read(data);
                return data;
            }
        }

    }


    private void sendSnapshot(UsbSerialPort port, String snapshotFile) throws IOException {
        try {
            byte[] data = getSnapshotData(snapshotFile);
            ByteArrayOutputStream bs = new ByteArrayOutputStream();
            bs.write(COMMAND_START);
            bs.write("snapupload".getBytes(StandardCharsets.US_ASCII));
            bs.write(COMMAND_END);
            port.write(bs.toByteArray(), 2000);

            byte[] inputBuffer = new byte [1024];
            while (true) {
                if (port.read(inputBuffer, 1, 2000) != 1) {
                    Log.d(TAG, "timeout");
                    return;
                }

                if (inputBuffer[0] == COMMAND_RESPONSE_BEGIN)
                    break;
            }

            while (true) {
                if (port.read(inputBuffer, 1, 2000) != 1) {
                    Log.d(TAG, "timeout");
                    return;
                }
                boolean succeeded = false;
                byte status = inputBuffer[0];

                if (status == COMMAND_RECEIVE_DATA) {
                    transferData(port, data);
                }
                else if (status == COMMAND_SEND_DATA) {
                    inputBuffer[0] = NAK;
                    port.write(inputBuffer, 1, 2000);
                    Log.d(TAG, "Unexpected COMMAND_SEND_DATA");
                    return;
                }
                else if (status == COMMAND_SUCCESS) {
                    succeeded = true;
                    break;
                }
                else if (status == COMMAND_FAILURE) {
                    succeeded = false;
                    break;
                }
            }

            bs.reset();

            while (true) {
                if (port.read(inputBuffer, 1, 2000) != 1) {
                    Log.d(TAG, "timeout");
                    return;
                }
                if (inputBuffer[0] == COMMAND_RESPONSE_END) {
                    if (bs.size() > 0) {
                    }
                    return;
                }
                bs.write(inputBuffer[0]);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }



    private void attemptToLoadSnapshot(boolean requestAccess, String name) {
        Log.d(TAG, "Attempt to load snapshot " + name);

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

        if (!availableDrivers.isEmpty()) {
            UsbSerialDriver driver = availableDrivers.get(0);
            UsbDeviceConnection usbDeviceConnection = manager.openDevice(driver.getDevice());

            if (usbDeviceConnection == null) {
                if (requestAccess) {
                    Log.d(TAG, "Request permission");
                    pendingSnapshotfile = name;
                    PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                    manager.requestPermission(driver.getDevice(), permissionIntent);
                }
                return;
            }


            List<UsbSerialPort> ports = driver.getPorts();
            UsbSerialPort port = driver.getPorts().get(0); // Most devices have just one port (port 0)


            try {
                port.open(usbDeviceConnection);
                port.setDTR(true);
                port.setRTS(true);
                sendSnapshot(port, name);
                port.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setContentView(R.layout.activity_main);
        searchView = findViewById(R.id.searchView);
        listView = findViewById(R.id.list);


        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                String itemClicked = (String) adapterView.getItemAtPosition(i);
                attemptToLoadSnapshot(true, itemClicked);
            }
        });


        for (File f : getExternalFilesDirs("snaps")) {
            File zipfile = new File(f, "snaps.zip");
            if (zipfile.exists()) {
                snapsZip = zipfile;
            }
        }


        ArrayList<String> snapShots = getSnapshotFileNames(snapsZip);


        String[] arrayOfNames = snapShots.toArray(new String[snapShots.size()]);

        Arrays.sort(arrayOfNames);
        ArrayAdapter<String> listAdaptor;
        listAdaptor = new StoreListAdaptor(this, arrayOfNames);
        listView.setAdapter(listAdaptor);

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                listAdaptor.getFilter().filter(query);
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                listAdaptor.getFilter().filter(newText);
                return false;
            }
        });

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
  }

    private static @NonNull ArrayList<String> getSnapshotFileNames(File snapsZip) {
        ArrayList<String> snapShots = new ArrayList<>();


        try {
            if (snapsZip != null) {
                ZipFile zf = new ZipFile(snapsZip, ZipFile.OPEN_READ);

                Enumeration<? extends ZipEntry> entries = zf.entries();

                while (entries.hasMoreElements()) {
                    ZipEntry zipEntry = entries.nextElement();
                    if (zipEntry.getName().endsWith(".z80")) {
                        snapShots.add(zipEntry.getName());
                    }
                }

                zf.close();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return snapShots;
    }


    static class StoreListAdaptor extends ArrayAdapter<String> implements SectionIndexer {

        HashMap<String, Integer> alphaIndexer;
        String[] sections;

        public StoreListAdaptor(Context context, String[] items) {
            super(context, androidx.appcompat.R.layout.support_simple_spinner_dropdown_item, items);

            alphaIndexer = new HashMap<String, Integer>();
            int size = items.length;

            for (int x = 0; x < size; x++) {
                String s = items[x];

                // get the first letter of the store
                String ch =  s.substring(0, 1);
                // convert to uppercase otherwise lowercase a -z will be sorted after upper A-Z
                ch = ch.toUpperCase();

                if (!alphaIndexer.containsKey(ch)) {
                    // HashMap will prevent duplicates
                    alphaIndexer.put(ch, x);
                }
            }

            Set<String> sectionLetters = alphaIndexer.keySet();

            // create a list from the set to sort
            ArrayList<String> sectionList = new ArrayList<String>(sectionLetters);

            Collections.sort(sectionList);

            sections = new String[sectionList.size()];

            sectionList.toArray(sections);
        }

        public int getPositionForSection(int section) {
            return alphaIndexer.get(sections[section]);
        }

        public int getSectionForPosition(int position) {
            return 1;
        }

        public Object[] getSections() {
            return sections;
        }
    }

}