package ai.cochlear.example;

import ai.cochl.sense.model.SenseEvent;
import ai.cochl.sense.model.WindowHop;

import java.util.*;

class ResultAbbreviation {
    static class BufferValue {
        private final double intervalMargin;
        private final double startTime;
        private final double endTime;

        BufferValue(double im, double startTime, double endTime) {
            this.intervalMargin = im;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public double getIntervalMargin() {
            return intervalMargin;
        }

        public double getStartTime() {
            return startTime;
        }

        public double getEndTime() {
            return endTime;
        }
    }

    static String _kTagNameOther = "Others";

    private final boolean enabled;
    private final float hopSize;
    private final int defaultIntervalMargin;
    private final LinkedHashMap<String, Integer> tagsIM;

    private final LinkedHashMap<String, BufferValue> buffer;
    private double minimumAcceptableMargin;

    private final StringBuilder stringBuilder1 = new StringBuilder();
    private final StringBuilder stringBuilder2 = new StringBuilder();

    public ResultAbbreviation(boolean enabled, int defaultIntervalMargin, WindowHop windowHop, LinkedHashMap<String, Integer> tagsIM) {
        this.enabled = enabled;
        this.defaultIntervalMargin = defaultIntervalMargin;

        if (windowHop == WindowHop._0_5S) {
            this.hopSize = 0.5f;
        } else if (windowHop == WindowHop._1S) {
            this.hopSize = 1.0f;
        } else {
            throw new IllegalArgumentException("Hop size can only be 0.5 or 1");
        }

        this.tagsIM = tagsIM;
        this.buffer = new LinkedHashMap<>();

        this.minimumAcceptableMargin = 0;
        if (this.defaultIntervalMargin == 0 && this.hopSize == 0.5d) {
            this.minimumAcceptableMargin = -0.5d;
        }
    }

    String minimizeDetails(List<SenseEvent> results, boolean endOfFile) {
        if (!enabled) {
            return "Result Abbreviation is currently disabled";
        }

        stringBuilder1.setLength(0);

        for (SenseEvent result : results) {
            String line = minimizeDetailsFrame(result);
            if (line.isEmpty()) {
                continue;
            }

            stringBuilder1.append(line);
        }

        if (endOfFile) {
            for (Map.Entry<String, BufferValue> entry : buffer.entrySet()) {
                String tagName = entry.getKey();
                double fromTime = entry.getValue().getStartTime();
                double toTime = entry.getValue().getEndTime();

                stringBuilder1.append(getLine(fromTime, toTime, tagName));
            }
        }

        return stringBuilder1.toString();
    }

    String minimizeDetailsFrame(SenseEvent senseEvent) {
        if (!enabled) {
            return "Result Abbreviation is currently disabled";
        }

        stringBuilder2.setLength(0);

        double startTime = senseEvent.getStartTime();
        double endTime = senseEvent.getEndTime();
        ArrayList<String> treatedTags = new ArrayList<>();

        for (int i = 0; i < senseEvent.getTags().size(); i++) {
            String tagName = senseEvent.getTags().get(i).getName();

            if (Objects.equals(tagName, _kTagNameOther)) {
                continue;
            }

            double fromTime;
            if (buffer.containsKey(tagName)) {
                fromTime = buffer.get(tagName).getStartTime();
                buffer.put(tagName, new BufferValue(getIntervalMarginByTag(tagName), fromTime, endTime));
            } else {
                buffer.put(tagName, new BufferValue(getIntervalMarginByTag(tagName), startTime, endTime));
            }

            treatedTags.add(tagName);
        }

        ArrayList<String> tagsToRemove = new ArrayList<>();
        for (Map.Entry<String, BufferValue> entry : buffer.entrySet()) {
            String tagName = entry.getKey();
            if (treatedTags.contains(tagName)) {
                continue;
            }

            double fromTime = entry.getValue().getStartTime();
            double toTime = entry.getValue().getEndTime();

            double im = entry.getValue().getIntervalMargin();
            im -= hopSize;

            if (im < minimumAcceptableMargin) {
                stringBuilder2.append(getLine(fromTime, toTime, tagName));
                tagsToRemove.add(tagName);
            } else {
                buffer.put(tagName, new BufferValue(im, fromTime, toTime));
            }
        }

        for (String s : tagsToRemove) {
            buffer.remove(s);
        }

        return stringBuilder2.toString();
    }

    int getIntervalMarginByTag(String tagName) {
        if (tagsIM.containsKey(tagName)) {
            return tagsIM.get(tagName);
        }

        return defaultIntervalMargin;
    }

    String getFormattedTime(double time) {
        String formattedTime = Double.toString(time);
        return formattedTime.substring(0, formattedTime.indexOf('.') + 2);
    }

    String getLine(double fromTime, double toTime, String tagName) {
        String from = getFormattedTime(fromTime);
        String to = getFormattedTime(toTime);

        return "\nAt " + from + "-" + to + "s, [" + tagName + "] was detected";
    }
}
