// MainActivity.java: Upload recorded chunks from microphone in android

// Important: You need to add these two permissions below to AndroidManifest.xml

// android.permission.INTERNET
// android.permission.RECORD_AUDIO

// AndroidManifest.xml
//
// <?xml version="1.0" encoding="utf-8"?>
// <manifest xmlns:android="http://schemas.android.com/apk/res/android"
//        xmlns:tools="http://schemas.android.com/tools">
//
// <uses-permission android:name="android.permission.INTERNET" />
// <uses-permission android:name="android.permission.RECORD_AUDIO" />
//
// <application..

package ai.cochlear.example;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import ai.cochl.client.ApiException;
import ai.cochl.sense.api.AudioSessionApi;
import ai.cochl.sense.model.*;
import ai.cochl.client.ApiClient;
import ai.cochl.client.Configuration;
import ai.cochl.client.auth.ApiKeyAuth;

public class MainActivity extends AppCompatActivity {
    static String key = "YOUR_API_PROJECT_KEY";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView textview1 = findViewById(R.id.textview1);
        textview1.setText(R.string.hello_world);

        Button button1 = findViewById(R.id.button1);
        button1.setText(R.string.start_recording);

        button1.setOnClickListener(v -> {
            textview1.setText(R.string.recording_for_10_seconds);
            button1.setEnabled(false);

            Thread thread1 = new Thread(() -> {
                Inference inference = new Inference();
                Thread inferenceThread = new Thread(inference);
                inferenceThread.start();

                try {
                    inferenceThread.join();

                    runOnUiThread(() -> {
                        button1.setEnabled(true);
                        textview1.setText(R.string.stopped);
                    });
                } catch (InterruptedException e) {
                    System.out.println(e);
                }
            });

            thread1.start();
        });

        askRecordAudioPermission();
    }

    private void askRecordAudioPermission() {
        int checkSelfPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        if (checkSelfPerm == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(MainActivity.this, "Permission (already) Granted!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.RECORD_AUDIO)) {
            int REQUEST_PERMISSION_RECORD_AUDIO = 1;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSION_RECORD_AUDIO);
        }
    }

    static class Inference implements Runnable {
        @Override
        public void run() {
            try {
                inference();
            } catch (ApiException e) {
                System.out.println(e.getResponseBody());
            }
        }

        public void inference() throws ApiException {
            ApiClient cli = Configuration.getDefaultApiClient();
            ApiKeyAuth API_Key = (ApiKeyAuth) cli.getAuthentication("API_Key");
            API_Key.setApiKey(key);

            AudioSessionApi api = new AudioSessionApi(cli);

            CreateSession create = new CreateSession();
            create.setContentType("audio/x-raw; rate=22050; format=s32");
            create.setType(AudioType.STREAM);
            SessionRefs session = api.createSession(create);

            // upload data from microphone in other thread
            Uploader uploader = new Uploader(api, session.getSessionId());
            Thread uploaderThread = new Thread(uploader);
            uploaderThread.start();

            // Get result
            String token = null;

            int i = 25;
            while (--i > 0) {
                SessionStatus result = api.readStatus(session.getSessionId(), null, null, token);

                token = Objects.requireNonNull(result.getInference().getPage()).getNextToken();
                if (token == null) {
                    break;
                }

                for (SenseEvent event : Objects.requireNonNull(result.getInference().getResults())) {
                    System.out.println(event.toString());
                }
            }
        }
    }

    static class Uploader implements Runnable {
        final private AudioSessionApi api;
        final private String sessionId;

        Uploader(AudioSessionApi api, String id) {
            this.api = api;
            this.sessionId = id;
        }

        @Override
        public void run() {
            int channel = AudioFormat.CHANNEL_IN_MONO;
            int format = AudioFormat.ENCODING_PCM_FLOAT;
            int bitsSample = 4;
            int rate = 22050;

            @SuppressLint("MissingPermission")
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                    rate,
                    channel,
                    format,
                    AudioRecord.getMinBufferSize(rate, channel, format)
            );
            recorder.startRecording();

            // record for 10 seconds
            float[] samples = new float[rate / 2];
            byte[] bytes = new byte[rate * bitsSample / 2];
            int sequence = 0;
            int totalRecorded = 0;
            while (totalRecorded < 10 * rate * bitsSample) {
                recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);

                AudioChunk chunk = new AudioChunk();
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(samples);
                chunk.setData(Base64.encodeToString(bytes, Base64.DEFAULT));

                totalRecorded += bytes.length;
                try {
                    api.uploadChunk(sessionId, sequence, chunk);
                } catch (ApiException e) {
                    System.out.println(e.getResponseBody());
                    break;
                }
                sequence++;
            }

            try {
                api.deleteSession(sessionId);
            } catch (ApiException e) {
                System.out.println(e.getResponseBody());
            }
        }
    }
}