package ai.cochlear.example;

import ai.cochl.sense.model.SenseEvent;
import ai.cochl.sense.model.SenseEventTag;

import java.util.*;

class ResultAbbreviation {
    private final boolean enable;
    private final int defaultIntervalMargin;
    private final HashMap<String, Integer> intervalMargin;
    private final double hopSize;

    private final TreeMap<String, UploadFile.PairOfDoubles> buffer = new TreeMap<>();
    private final StringBuilder stringBuilder = new StringBuilder();

    public ResultAbbreviation(boolean enable, int defaultIntervalMargin, HashMap<String, Integer> intervalMargin, double hopSize) {
        this.enable = enable;
        this.defaultIntervalMargin = defaultIntervalMargin;
        this.intervalMargin = intervalMargin;
        this.hopSize = hopSize;

        if (enable) {
            System.out.println("\tDefault Interval Margin (IM): " + defaultIntervalMargin);

            if (!intervalMargin.isEmpty()) {
                System.out.println("\tIM:");
            }

            for (Map.Entry<String, Integer> entrySet : intervalMargin.entrySet()) {
                System.out.println("\t\t[" + entrySet.getKey() + "]: " + entrySet.getValue());
            }
        }
    }

    String abbreviation(SenseEvent frameResult) {
        if (!enable) {
            return "Result Abbreviation is currently disabled.";
        }

        double startTime = frameResult.getStartTime();
        for (int i = 0; i < frameResult.getTags().size(); ++i) {
            SenseEventTag tag = frameResult.getTags().get(i);
            String name = tag.getName();

            String _kTagNameOther = "Others";
            if (Objects.equals(name, _kTagNameOther)) {
                continue;
            }

            double holdTime = getIntervalMarginByTag(name);
            if (!buffer.containsKey(name)) {
                buffer.put(name, new UploadFile.PairOfDoubles(startTime, holdTime));
            } else {
                buffer.get(name).setSecond(holdTime);
            }
        }


        List<Map.Entry<String, UploadFile.PairOfDoubles>> bufferAsList = new ArrayList<>(buffer.entrySet());

        stringBuilder.setLength(0);
        for (int i = 0; i < bufferAsList.size(); ) {
            Map.Entry<String, UploadFile.PairOfDoubles> entrySet = bufferAsList.get(i);
            double holdTime = entrySet.getValue().getSecond();
            holdTime = holdTime - hopSize;

            if (holdTime < 0) {
                String newName = entrySet.getKey();
                double newImByTag = getIntervalMarginByTag(newName);

                double newStartTime = entrySet.getValue().getFirst();
                double newEndTime = frameResult.getEndTime() - newImByTag;

                String newLine = line(newName, newStartTime, newEndTime);
                stringBuilder.append("\n").append(newLine);
            } else {
                i++;
            }
        }
        String output = stringBuilder.toString();

        String _kEmpty = "...";
        return output.isEmpty() ? _kEmpty : output;
    }

    double getIntervalMarginByTag(String tag) {
        int tempIm = defaultIntervalMargin;
        if (intervalMargin.containsKey(tag)) {
            tempIm = intervalMargin.get(tag);
        }

        return tempIm + (tempIm > 0 ? .0f : hopSize);
    }

    String getFormattedTime(double time) {
        String formattedTime = Double.toString(time);
        return formattedTime.substring(0, formattedTime.indexOf('.') + 2);
    }

    String line(String name, double startTime, double endTime) {
        String from = getFormattedTime(startTime);
        String to = getFormattedTime(endTime);

        return "At " + from + "-" + to + "s, [" + name + "] was detected";
    }
}
