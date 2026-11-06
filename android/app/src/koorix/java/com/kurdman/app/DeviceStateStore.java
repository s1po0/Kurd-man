package com.kurdman.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device session/allowance storage. No plaintext fallback if the keystore fails. */
final class DeviceStateStore {
    interface Keys { SecretKey get() throws Exception; }
    private static final Object LOCK = new Object();
    private static final String ALIAS = "koorix.device-state.v2";
    private final SharedPreferences prefs;
    private final SharedPreferences legacy;
    private final Keys keys;
    DeviceStateStore(Context context) { this(context, DeviceStateStore::androidKey); }
    DeviceStateStore(Context context, Keys keys) {
        this.prefs=context.getApplicationContext().getSharedPreferences("koorix_device_v2",0);
        this.legacy=context.getApplicationContext().getSharedPreferences("koorix_account",0);
        this.keys=keys;
    }
    JSONObject read() throws IOException {
        synchronized(LOCK) {
            try {
                String value=prefs.getString("sealed","");
                if(!value.isEmpty()) {
                    String[] parts=value.split("\\.",-1);
                    if(parts.length!=2) throw new IOException("Invalid protected device state");
                    byte[] iv=Base64.decode(parts[0],Base64.NO_WRAP);
                    if(iv.length!=12) throw new IOException("Invalid protected device state");
                    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,keys.get(),new GCMParameterSpec(128,iv));
                    cipher.updateAAD("koorix-device-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
                }
                JSONObject state=new JSONObject();
                // Keep an existing subscriber's real session, not their email or pending login.
                for(String key:new String[]{"access_token","refresh_token","expires_at_ms","premium_until_cache"}) {
                    Object old=legacy.getAll().get(key);if(old!=null)state.put(key,old);
                }
                if(state.length()>0) { write(state); legacy.edit().clear().commit(); }
                return state;
            } catch(Exception error) { throw new IOException("Protected device state unavailable",error); }
        }
    }
    void write(JSONObject state) throws IOException {
        synchronized(LOCK) {
            try {
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get());
                cipher.updateAAD("koorix-device-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] encrypted=cipher.doFinal(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String sealed=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+"."+Base64.encodeToString(encrypted,Base64.NO_WRAP);
                if(!prefs.edit().putString("sealed",sealed).commit()) throw new IOException("Could not save protected device state");
            } catch(Exception error) { throw new IOException("Protected device state unavailable",error); }
        }
    }
    private static synchronized SecretKey androidKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
}
