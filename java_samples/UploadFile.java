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
    static String key = "YOUR_PROJECT_KEY";

    // example 01: upload existing file
    static String existingFilename = "siren.wav";
    static boolean useResultAbbreviation = false;

    // example 02: upload file recorded by java sound api (macOS / windows)
    static boolean useRecorder = true;
    static String recordedFilename = "recorded.wav";

    public static void main(String[] args) {
        if (useRecorder) {
            new Recorder();
            return;
        }

        try {
            inference();
        } catch (ApiException e) {
            System.err.println(e.getResponseBody());
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    static void inference() throws ApiException, java.io.IOException {
        String contentType = "audio/wav";
        byte[] file = Files.readAllBytes(Paths.get(existingFilename));

        ApiClient cli = Configuration.getDefaultApiClient();
        ApiKeyAuth API_Key = (ApiKeyAuth) cli.getAuthentication("API_Key");
        API_Key.setApiKey(key);

        AudioSessionApi api = new AudioSessionApi(cli);

        CreateSession create = new CreateSession();
        create.setContentType(contentType);
        create.setType(AudioType.FILE);
        create.setTotalSize(file.length);
        SessionRefs session = api.createSession(create);

        //upload
        int chunkSize = 1024 * 1024;
        for (int sequence = 0; sequence * chunkSize < file.length; sequence++) {
            System.out.println("uploading..");
            byte[] slice = Arrays.copyOfRange(file, sequence * chunkSize, (sequence + 1) * chunkSize);

            AudioChunk chunk = new AudioChunk();
            chunk.setData(Base64.getEncoder().encodeToString(slice));
            api.uploadChunk(session.getSessionId(), sequence, chunk);
        }

        HashMap<String, Integer> intervalMargin = new HashMap<>();
        ResultAbbreviation resultAbbreviation = new ResultAbbreviation(true, 1, intervalMargin, 0.5f);

        //Get result
        String token = null;
        while (true) {
            SessionStatus result = api.readStatus(session.getSessionId(), null, null, token);
            token = Objects.requireNonNull(result.getInference().getPage()).getNextToken();
            if (token == null) {
                break;
            }

            for (SenseEvent event : Objects.requireNonNull(result.getInference().getResults())) {
                if (useResultAbbreviation) {
                    System.out.println(resultAbbreviation.abbreviation(event));
                } else {
                    System.out.println(event.toString());
                }
            }
        }
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
                System.err.println(e);
                System.exit(0);
            }
        }

        class ThreadRecording extends Thread {
            public void run() {
                try {
                    targetDataLine.open(audioFormat);
                    targetDataLine.start();

                    AudioSystem.write(new AudioInputStream(targetDataLine), audioFileFormatType, new File(recordedFilename));
                } catch (Exception e) {
                    System.out.println(e);
                }
            }
        }
    }

    static class PairOfDoubles {
        private double first;
        private double second;

        PairOfDoubles(double first, double second) {
            this.first = first;
            this.second = second;
        }

        public double getFirst() {
            return first;
        }

        public double getSecond() {
            return second;
        }

        public void setSecond(double second) {
            this.second = second;
        }
    }
}
