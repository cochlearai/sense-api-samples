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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import ai.cochl.client.ApiClient;
import ai.cochl.client.ApiException;
import ai.cochl.client.Configuration;
import ai.cochl.client.auth.ApiKeyAuth;
import ai.cochl.sense.api.AudioSessionApi;
import ai.cochl.sense.model.AudioChunk;
import ai.cochl.sense.model.AudioType;
import ai.cochl.sense.model.CreateSession;
import ai.cochl.sense.model.SenseEvent;
import ai.cochl.sense.model.SessionRefs;
import ai.cochl.sense.model.SessionStatus;

public class MainActivity extends AppCompatActivity {
    static String API_KEY = "YOUR_API_PROJECT_KEY";
    static boolean running = true;

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
            running = true;

            Thread thread = new Thread(() -> {
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
                    e.printStackTrace();
                }
            });
            thread.start();
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
            ApiClient apiClient = Configuration.getDefaultApiClient();
            apiClient.setBasePath("https://api.beta.cochl.ai/sense/api/v1");
            ApiKeyAuth apiKeyAuth = (ApiKeyAuth) apiClient.getAuthentication("API_Key");
            apiKeyAuth.setApiKey(API_KEY);

            AudioSessionApi audioSessionApi = new AudioSessionApi(apiClient);

            CreateSession createSession = new CreateSession();
            createSession.setContentType("audio/x-raw; rate=22050; format=f32");
            createSession.setType(AudioType.STREAM);

            SessionRefs sessionRefs = audioSessionApi.createSession(createSession);

            // upload data from microphone in other thread
            Uploader uploader = new Uploader(audioSessionApi, sessionRefs.getSessionId());
            Thread uploaderThread = new Thread(uploader);
            uploaderThread.start();

            // Get result
            String token = null;
            int nFrame = 19;  // for 10 seconds;
            while (running) {
                SessionStatus sessionStatus = audioSessionApi.readStatus(sessionRefs.getSessionId(), null, null, token);

                token = Objects.requireNonNull(sessionStatus.getInference().getPage()).getNextToken();
                if (token == null) {
                    break;
                }

                for (SenseEvent senseEvent : Objects.requireNonNull(sessionStatus.getInference().getResults())) {
                    System.out.println(senseEvent.toString());

                    if (--nFrame == 0) {
                        running = false;
                        break;
                    }
                }
            }

            try {
                uploaderThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    static class Uploader implements Runnable {
        final private AudioSessionApi audioSessionApi;
        final private String sessionId;

        Uploader(AudioSessionApi api, String id) {
            this.audioSessionApi = api;
            this.sessionId = id;
        }

        @Override
        public void run() {
            int channel = AudioFormat.CHANNEL_IN_MONO;
            int format = AudioFormat.ENCODING_PCM_FLOAT;
            int bitsSample = 4;
            int rate = 22050;

            @SuppressLint("MissingPermission")
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,
                    rate,
                    channel,
                    format,
                    AudioRecord.getMinBufferSize(rate, channel, format)
            );

            float[] samples = new float[rate / 2];
            float[] previousSamples = null;

            recorder.startRecording();
            for (int i = 0; running; ++i) {
                recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);

                if (previousSamples == null) {
                    // first read
                    previousSamples = new float[samples.length];
                    System.arraycopy(samples, 0, previousSamples, 0, samples.length);
                    continue;
                }

                byte[] bytes = new byte[(previousSamples.length * bitsSample) + (samples.length * bitsSample)];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(previousSamples).put(samples);

                AudioChunk audioChunk = new AudioChunk();
                audioChunk.setData(Base64.encodeToString(bytes, Base64.DEFAULT));

                try {
                    audioSessionApi.uploadChunk(sessionId, i - 1, audioChunk);
                } catch (ApiException e) {
                    System.out.println(e.getResponseBody());
                    break;
                }

                System.arraycopy(samples, 0, previousSamples, 0, samples.length);
            }

            recorder.stop();
            recorder.release();

            try {
                audioSessionApi.deleteSession(sessionId);
            } catch (ApiException e) {
                System.out.println(e.getResponseBody());
            }
        }
    }
}
