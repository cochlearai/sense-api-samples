package ai.cochlear.example;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import ai.cochl.client.ApiException;
import ai.cochl.client.ApiClient;
import ai.cochl.client.Configuration;
import ai.cochl.client.auth.ApiKeyAuth;
import ai.cochl.sense.api.AudioSessionApi;
import ai.cochl.sense.model.*;

import javax.sound.sampled.*;
import javax.swing.*;

public class UploadFile {
    static String API_KEY = "YOUR_API_PROJECT_KEY";
    static WindowHop HOPE_SIZE = WindowHop._0_5S;
    static boolean USE_RESULT_ABBREVIATION = true;

    static int DEFAULT_SENSITIVITY = 0;
    static LinkedHashMap<String, Integer> TAGS_SENSITIVITY;

    static int DEFAULT_IM = 1;
    static LinkedHashMap<String, Integer> TAGS_IM;

    // example 01: upload existing file
    static String CONTENT_TYPE = "audio/wav";
    static String EXISTING_FILE_PATH = "siren.wav";

    // example 02: upload file recorded by java sound api (macOS / windows)
    static boolean USE_RECORDER = false;
    static String RECORDED_FILE_PATH = "recorded.wav";

    private static void initTags() {
        TAGS_SENSITIVITY = new LinkedHashMap<>();
        TAGS_SENSITIVITY.put("Crowd", 2);
        TAGS_SENSITIVITY.put("Sing", 1);

        TAGS_IM = new LinkedHashMap<>();
    }

    public static void main(String[] args) {
        initTags();

        if (USE_RECORDER) {
            new Recorder();
            return;
        }

        try {
            inference();
        } catch (ApiException e) {
            System.out.println(e.getResponseBody());
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    static void inference() throws ApiException, java.io.IOException {
        byte[] fileBytes = Files.readAllBytes(Paths.get(EXISTING_FILE_PATH));

        ApiClient apiClient = Configuration.getDefaultApiClient();
        apiClient.setBasePath("https://api.beta.cochl.ai/sense/api/v1");
        ApiKeyAuth apiKeyAuth = (ApiKeyAuth) apiClient.getAuthentication("API_Key");
        apiKeyAuth.setApiKey(API_KEY);

        AudioSessionApi audioSessionApi = new AudioSessionApi(apiClient);

        CreateSession createSession = new CreateSession();
        createSession.setDefaultSensitivity(DEFAULT_SENSITIVITY);
        createSession.setTagsSensitivity(TAGS_SENSITIVITY);
        createSession.setWindowHop(HOPE_SIZE);
        createSession.setContentType(CONTENT_TYPE);
        createSession.setType(AudioType.FILE);
        createSession.setTotalSize(fileBytes.length);

        SessionRefs sessionRefs = audioSessionApi.createSession(createSession);
        String sessionId = sessionRefs.getSessionId();

        //upload
        int chunkSize = 1024 * 1024;
        for (int sequence = 0; sequence * chunkSize < fileBytes.length; sequence++) {
            System.out.println("uploading..");
            byte[] slice = Arrays.copyOfRange(fileBytes, sequence * chunkSize, (sequence + 1) * chunkSize);

            AudioChunk audioChunk = new AudioChunk();
            audioChunk.setData(Base64.getEncoder().encodeToString(slice));
            audioSessionApi.uploadChunk(sessionId, sequence, audioChunk);
        }

        ResultAbbreviation resultAbbreviation = new ResultAbbreviation(USE_RESULT_ABBREVIATION, DEFAULT_IM, HOPE_SIZE, TAGS_IM);

        if (USE_RESULT_ABBREVIATION) {
            System.out.println("<Result Summary>");
        }

        // get results
        String nextToken = null;

        boolean endOfFile = false;
        while (!endOfFile) {
            SessionStatus sessionStatus = audioSessionApi.readStatus(sessionRefs.getSessionId(), null, null, nextToken);
            nextToken = Objects.requireNonNull(sessionStatus.getInference().getPage()).getNextToken();
            if (nextToken == null) {
                endOfFile = true;
            }

            if (USE_RESULT_ABBREVIATION) {
                System.out.println(resultAbbreviation.minimizeDetails(sessionStatus.getInference().getResults(), endOfFile));
            } else {
                for (SenseEvent senseEvent : Objects.requireNonNull(sessionStatus.getInference().getResults())) {
                    System.out.println(senseEvent.toString());
                }
            }
        }

        audioSessionApi.deleteSession(sessionId);
    }

    static class Recorder extends JFrame {
        AudioFormat audioFormat;
        TargetDataLine targetDataLine;

        final JButton buttonRecord = new JButton("Record");
        final JButton buttonStop = new JButton("Stop");

        final private AudioFileFormat.Type audioFileFormatType = AudioFileFormat.Type.WAVE;

        final private ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        public Recorder() {
            buttonRecord.setEnabled(true);
            buttonStop.setEnabled(false);

            buttonRecord.addActionListener(
                    e -> {
                        buttonRecord.setEnabled(false);
                        buttonStop.setEnabled(true);

                        byteArrayOutputStream.reset();
                        startRecording();
                    }
            );

            buttonStop.addActionListener(
                    e -> {
                        buttonRecord.setEnabled(true);
                        buttonStop.setEnabled(false);

                        targetDataLine.stop();
                        targetDataLine.close();
                    }
            );

            getContentPane().add(buttonRecord);
            getContentPane().add(buttonStop);

            getContentPane().setLayout(new FlowLayout());
            setTitle("Cochl.Sense API: Java Example");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(300, 100);
            setVisible(true);
        }

        private void startRecording() {
            try {
                int sampleRate = 22050;
                //8000, 11025, 16000, 22050, 44100

                int sampleSizeInBits = 16;
                //8, 16

                int channels = 1; // mono = 1, stereo = 2
                //1, 2

                boolean signed = true;
                //true, false

                boolean bigEndian = false;
                //true, false

                audioFormat = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
                DataLine.Info dataLineInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
                targetDataLine = (TargetDataLine) AudioSystem.getLine(dataLineInfo);

                new ThreadRecording().start();
            } catch (Exception e) {
                System.out.println(e);
                System.exit(0);
            }
        }

        class ThreadRecording extends Thread {
            public void run() {
                try {
                    targetDataLine.open(audioFormat);
                    targetDataLine.start();

                    AudioSystem.write(new AudioInputStream(targetDataLine), audioFileFormatType, new File(RECORDED_FILE_PATH));
                } catch (Exception e) {
                    System.out.println(e);
                }
            }
        }
    }
}
