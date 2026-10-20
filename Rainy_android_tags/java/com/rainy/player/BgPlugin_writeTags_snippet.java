package com.rainy.player;

// ВСТАВИТЬ В СУЩЕСТВУЮЩИЙ BgPlugin.java (методы и импорты ниже). Класс-обёртка нужен только чтобы файл компилировался отдельно.

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.app.RecoverableSecurityException;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BgPlugin_writeTags_snippet extends Plugin {
    private final ExecutorService tagIo = Executors.newSingleThreadExecutor();

    /** JS: Capacitor.Plugins.Bg.writeTags({uri, title, artist, cover}) ; cover = base64 jpeg/png без префикса data: или null (оставить). */
    @PluginMethod
    public void writeTags(PluginCall call) {
        Uri uri = resolveMediaUri(call.getString("uri"));
        if (uri == null) { call.reject("not found"); return; }
        tryWrite(call, uri, false);
    }

    private void tryWrite(PluginCall call, Uri uri, boolean afterGrant) {
        tagIo.execute(() -> {
            try {
                String cov = call.getString("cover");
                byte[] cover = cov == null ? null : (cov.isEmpty() ? new byte[0] : Base64.decode(cov, Base64.DEFAULT));
                TagWriter.writeToUri(getContext(), uri, call.getString("title"), call.getString("artist"), cover);
                call.resolve();
            } catch (RecoverableSecurityException e) {                     // Android 10
                if (afterGrant) { call.reject("denied"); return; }
                askUser(call, e.getUserAction().getActionIntent().getIntentSender());
            } catch (SecurityException e) {                                // Android 11+: нужен createWriteRequest
                if (afterGrant || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { call.reject("denied"); return; }
                askUser(call, MediaStore.createWriteRequest(getContext().getContentResolver(), Collections.singletonList(uri)).getIntentSender());
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        });
    }

    private void askUser(PluginCall call, android.content.IntentSender sender) {
        Intent i = new Intent(ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST)
                .putExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_INTENT_SENDER_REQUEST,
                        new IntentSenderRequest.Builder(sender).build());
        startActivityForResult(call, i, "writeTagsResult");
    }

    @ActivityCallback
    private void writeTagsResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK) { call.reject("denied"); return; }
        Uri uri = resolveMediaUri(call.getString("uri"));
        if (uri == null) { call.reject("not found"); return; }
        tryWrite(call, uri, true);
    }

    /** content://... принимаем как есть; путь к файлу ищем в MediaStore по колонке _DATA. */
    private Uri resolveMediaUri(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.startsWith("content://")) return Uri.parse(s);
        String path = s.startsWith("file://") ? Uri.parse(s).getPath() : s;
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        try (Cursor c = getContext().getContentResolver().query(base, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DATA + "=?", new String[]{path}, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(base, c.getLong(0));
        } catch (Exception ignored) { }
        return null;
    }
}
