package autostepper;

import ddf.minim.AudioSample;
import ddf.minim.Minim;
import ddf.minim.MultiChannelBuffer;
import ddf.minim.analysis.BeatDetect;
import ddf.minim.analysis.FFT;
import ddf.minim.spi.AudioRecordingStream;
import gnu.trove.list.array.TFloatArrayList;
import java.io.*;
import java.util.ArrayList;
import java.util.Scanner;

/**
 *
 * @author Phr00t
 */
public class AutoStepper {
    
    public static boolean DEBUG_STEPS = false;
    public static float MAX_BPM = 170f, MIN_BPM = 70f, BPM_SENSITIVITY = 0.05f, STARTSYNC = 0.0f;
    public static double TAPSYNC = -0.11;
    public static boolean USETAPPER = false, HARDMODE = false, UPDATESM = false;
    
    public static Minim minim;
    public static AutoStepper myAS = new AutoStepper();
    
    public static final int KICKS = 0, ENERGY = 1, SNARE = 2, HAT = 3;
    
    // collected song data
    private final TFloatArrayList[] manyTimes = new TFloatArrayList[4];
    private final TFloatArrayList[] fewTimes = new TFloatArrayList[4];
    
    // for minim
    public String sketchPath( String fileName ) {
        return fileName;
    }
    
    // for minim
    public InputStream createInput( String fileName ) {
        try {
            return new FileInputStream(new File(fileName));
        } catch(Exception e) {
            return null;
        }
    }
    
    // argument parser
    public static String getArg(String[] args, String argname, String def) {
        try {
            for(String s : args) {
                s = s.replace("\"", "");
                if( s.startsWith(argname) ) {
                    return s.substring(s.indexOf("=") + 1).toLowerCase();
                }
            }
        } catch(Exception e) { }
        return def;
    }
    
    // argument parser
    public static boolean hasArg(String[] args, String argname) {
        for(String s : args) {
            if( s.toLowerCase().equals(argname) ) return true;
        }
        return false;
    }
    
    public static void main(String[] args) {
        minim = new Minim(myAS);
        String outputDir, input;
        float duration;
        System.out.println("Starting AutoStepper by Phr00t's Software, v1.7 (See www.phr00t.com for more goodies!)");
        if( hasArg(args, "help") || hasArg(args, "h") || hasArg(args, "?") || hasArg(args, "-help") || hasArg(args, "-?") || hasArg(args, "-h") ) {
            System.out.println("Argument usage (all fields are optional):\n"
                    + "input=<file or dir> output=<songs dir> duration=<seconds to process, default: 90> tap=<true/false> tapsync=<tap time offset, default: -0.11> hard=<true/false> updatesm=<true/false>");
            return;
        }
        MAX_BPM = Float.parseFloat(getArg(args, "maxbpm", "170f"));
        outputDir = getArg(args, "output", ".");
        if( outputDir.endsWith("/") == false ) outputDir += "/";
        input = getArg(args, "input", ".");
        duration = Float.parseFloat(getArg(args, "duration", "90"));
        STARTSYNC = Float.parseFloat(getArg(args, "synctime", "0.0"));
        BPM_SENSITIVITY = Float.parseFloat(getArg(args, "bpmsensitivity", "0.05"));
        USETAPPER = getArg(args, "tap", "false").equals("true");
        TAPSYNC = Double.parseDouble(getArg(args, "tapsync", "-0.11"));
        HARDMODE = getArg(args, "hard", "false").equals("true");
        UPDATESM = getArg(args, "updatesm", "false").equals("true");
        File inputFile = new File(input);
        if( inputFile.isFile() ) {
            myAS.analyzeUsingAudioRecordingStream(inputFile, duration, outputDir);            
        } else if( inputFile.isDirectory() ) {
            System.out.println("Processing directory: " + inputFile.getAbsolutePath());
            File[] allfiles = inputFile.listFiles();
            for(File f : allfiles) {
                String extCheck = f.getName().toLowerCase();
                if( f.isFile() &&
                    (extCheck.endsWith(".mp3") || extCheck.endsWith(".wav")) ) {
                    myAS.analyzeUsingAudioRecordingStream(f, duration, outputDir);                    
                } else {
                    System.out.println("Skipping unsupported file: " + f.getName());
                }
            }
        } else {
            System.out.println("Couldn't find any input files.");
        }
    }

    TFloatArrayList calculateDifferences(TFloatArrayList arr, float timeThreshold) {
        TFloatArrayList diff = new TFloatArrayList();
        int currentlyAt = 0;
        while(currentlyAt < arr.size() - 1) {
            float mytime = arr.getQuick(currentlyAt);
            int oldcurrentlyat = currentlyAt;
            for(int i=currentlyAt+1;i<arr.size();i++) {
                float diffcheck = arr.getQuick(i) - mytime;
                if( diffcheck >= timeThreshold ) {
                    diff.add(diffcheck);
                    currentlyAt = i;
                    break;
                }
            }
            if( oldcurrentlyat == currentlyAt ) break;
        }
        return diff;
    }
    
    float getDifferenceAverage(TFloatArrayList arr) {
        float avg = 0f;
        for(int i=0;i<arr.size()-1;i++) {
            avg += Math.abs(arr.getQuick(i+1) - arr.getQuick(i));
        }
        if( arr.size() <= 1 ) return 0f;
        return avg / arr.size()-1;
    }
    
    float getMostCommon(TFloatArrayList arr, float threshold, boolean closestToInteger) {
        ArrayList<TFloatArrayList> values = new ArrayList<>();
        for(int i=0;i<arr.size();i++) {
            float val = arr.get(i);
            // check for this value in our current lists
            boolean notFound = true;
            for(int j=0;j<values.size();j++) {
                TFloatArrayList tal = values.get(j);
                for(int k=0;k<tal.size();k++) {
                    float listValue = tal.get(k);
                    if( Math.abs(listValue - val) < threshold ) {
                        notFound = false;
                        tal.add(val);
                        break;
                    }                    
                }
                if( notFound == false ) break;
            }
            // if it wasn't found, start a new list
            if( notFound ) {
                TFloatArrayList newList = new TFloatArrayList();
                newList.add(val);
                values.add(newList);
            }
        }
        // get the longest list
        int longest = 0;
        TFloatArrayList longestList = null;
        for(int i=0;i<values.size();i++) {
            TFloatArrayList check = values.get(i);
            if( check.size() > longest ||
                check.size() == longest && getDifferenceAverage(check) < getDifferenceAverage(longestList) ) {
                longest = check.size();
                longestList = check;
            }
        }        
        if( longestList == null ) return -1f;
        if( longestList.size() == 1 && values.size() > 1 ) {
            // one value only, no average needed.. but what to pick?
            // just pick the smallest one... or integer, if we want that instead
            if( closestToInteger ) {
                float closestIntDiff = 1f;
                float result = arr.getQuick(0);
                for(int i=0;i<arr.size();i++) {
                    float diff = Math.abs(Math.round(arr.getQuick(i)) - arr.getQuick(i));
                    if( diff < closestIntDiff ) {
                        closestIntDiff = diff;
                        result = arr.getQuick(i);
                    }
                }
                return result;
            } else {
                float smallest = 99999f;
                for(int i=0;i<arr.size();i++) {
                    if( arr.getQuick(i) < smallest ) smallest = arr.getQuick(i);
                }
                return smallest;
            }
        }
        // calculate average
        float avg = 0f;
        for(int i=0;i<longestList.size();i++) {
            avg += longestList.get(i);
        }
        return avg / longestList.size();
    }
    
    public float getBestOffset(float timePerBeat, TFloatArrayList times, float groupBy) {
        TFloatArrayList offsets = new TFloatArrayList();
        for(int i=0;i<times.size();i++) {
            offsets.add(times.getQuick(i) % timePerBeat);
        }
        return getMostCommon(offsets, groupBy, false);
    }
      
    public void AddCommonBPMs(TFloatArrayList common, TFloatArrayList times, float doubleSpeed, float timePerSample) {
        float commonBPM = 60f / getMostCommon(calculateDifferences(times, doubleSpeed), timePerSample, true);
        if( commonBPM > MAX_BPM ) {
            common.add(commonBPM * 0.5f);
        } else if( commonBPM < MIN_BPM / 2f ) {
            common.add(commonBPM * 4f);
        } else if( commonBPM < MIN_BPM ) {
            common.add(commonBPM * 2f);
        } else common.add(commonBPM);
    }
    
    public static float tappedOffset;
    public int getTappedBPM(String filename) {
        // now we load the whole song so we don't have to worry about streaming a variable mp3 with timing inaccuracies
        System.out.println("Loading whole song for tapping...");
        AudioSample fullSong = minim.loadSample(filename);
        System.out.println("\n********************************************************************\n\nPress [ENTER] to start song, then press [ENTER] to tap to the beat.\nIt will complete after 30 entries.\nDon't worry about hitting the first beat, just start anytime.\n\n********************************************************************");
        TFloatArrayList positions = new TFloatArrayList();
        Scanner in = new Scanner(System.in);
        try {
            in.nextLine();
        } catch(Exception e) { }        
        // get the most accurate start time as possible
        long nano = System.nanoTime();
        fullSong.trigger();
        nano = (System.nanoTime() + nano) / 2;
        try {
            for(int i=0;i<30;i++) {
                in.nextLine();
                // get two playtime values & average them together for accuracy
                long now = System.nanoTime();
                // calculate the time difference
                // we note a consistent 0.11 second delay in input to song here
                double time = (double)((now - nano) / 1000000000.0) + TAPSYNC;
                positions.add((float)time);                
                System.out.println("#" + positions.size() + "/30: " + time + "s");
            }
        } catch(Exception e) { }
        fullSong.stop();
        fullSong.close();
        float avg = ((positions.getQuick(positions.size()-1) - positions.getQuick(0)) / (positions.size() - 1));
        int BPM = (int)Math.floor(60f / avg);
        float timePerBeat = 60f / BPM;
        tappedOffset = -getBestOffset(timePerBeat, positions, 0.1f);
        return BPM;
    }
    
    void analyzeUsingAudioRecordingStream(File filename, float seconds, String outputDir) {
      int fftSize = 512;
      
      System.out.println("\n[--- Processing " + seconds + "s of "+ filename.getName() + " ---]");
      AudioRecordingStream stream = minim.loadFileStream(filename.getAbsolutePath(), fftSize, false);

      // tell it to "play" so we can read from it.
      stream.play();

      // create the fft/beatdetect objects we'll use for analysis
      BeatDetect manybd = new BeatDetect(BeatDetect.FREQ_ENERGY, fftSize, stream.getFormat().getSampleRate());
      BeatDetect fewbd = new BeatDetect(BeatDetect.FREQ_ENERGY, fftSize, stream.getFormat().getSampleRate());
      BeatDetect manybde = new BeatDetect(BeatDetect.SOUND_ENERGY, fftSize, stream.getFormat().getSampleRate());
      BeatDetect fewbde = new BeatDetect(BeatDetect.SOUND_ENERGY, fftSize, stream.getFormat().getSampleRate());
      manybd.setSensitivity(BPM_SENSITIVITY);
      manybde.setSensitivity(BPM_SENSITIVITY);
      fewbd.setSensitivity(60f/MAX_BPM);
      fewbde.setSensitivity(60f/MAX_BPM);
      
      FFT fft = new FFT( fftSize, stream.getFormat().getSampleRate() );

      // create the buffer we use for reading from the stream
      MultiChannelBuffer buffer = new MultiChannelBuffer(fftSize, stream.getFormat().getChannels());

      // figure out how many samples are in the stream so we can allocate the correct number of spectra
      float songTime = stream.getMillisecondLength() / 1000f;
      int totalSamples = (int)( songTime * stream.getFormat().getSampleRate() );
      float timePerSample = fftSize / stream.getFormat().getSampleRate();

      // now we'll analyze the samples in chunks
      int totalChunks = (totalSamples / fftSize) + 1;

      System.out.println("Performing Beat Detection...");
      for(int i=0;i<fewTimes.length;i++) {
          if( fewTimes[i] == null ) fewTimes[i] = new TFloatArrayList();
          if( manyTimes[i] == null ) manyTimes[i] = new TFloatArrayList();
          fewTimes[i].clear();
          manyTimes[i].clear();
      }
      TFloatArrayList MidFFTAmount = new TFloatArrayList(), MidFFTMaxes = new TFloatArrayList();
      float largestAvg = 0f, largestMax = 0f;
      int lowFreq = fft.freqToIndex(300f);
      int highFreq = fft.freqToIndex(3000f);
      for(int chunkIdx = 0; chunkIdx < totalChunks; ++chunkIdx) {
        stream.read( buffer );
        float[] data = buffer.getChannel(0);
        float time = chunkIdx * timePerSample;
        // now analyze the left channel
        manybd.detect(data, time);
        manybde.detect(data, time);
        fewbd.detect(data, time);
        fewbde.detect(data, time);
        fft.forward(data);
        // fft processing
        float avg = fft.calcAvg(300f, 3000f);
        float max = 0f;
        for(int b=lowFreq;b<=highFreq;b++) {
            float bandamp = fft.getBand(b);
            if( bandamp > max ) max = bandamp;
        }
        if( max > largestMax ) largestMax = max;
        if( avg > largestAvg ) largestAvg = avg;
        MidFFTAmount.add(avg);
        MidFFTMaxes.add(max);
        // store basic percussion times
        if(manybd.isKick()) manyTimes[KICKS].add(time);
        if(manybd.isHat()) manyTimes[HAT].add(time);
        if(manybd.isSnare()) manyTimes[SNARE].add(time);
        if(manybde.isOnset()) manyTimes[ENERGY].add(time);
        if(fewbd.isKick()) fewTimes[KICKS].add(time);
        if(fewbd.isHat()) fewTimes[HAT].add(time);
        if(fewbd.isSnare()) fewTimes[SNARE].add(time);
        if(fewbde.isOnset()) fewTimes[ENERGY].add(time);
      }
      System.out.println("Loudest midrange average to normalize to 1: " + largestAvg);
      System.out.println("Loudest midrange maximum to normalize to 1: " + largestMax);
      float scaleBy = 1f / largestAvg;
      float scaleMaxBy = 1f / largestMax;
      for(int i=0;i<MidFFTAmount.size();i++) {
          MidFFTAmount.replace(i, MidFFTAmount.get(i) * scaleBy);
          MidFFTMaxes.replace(i, MidFFTMaxes.get(i) * scaleMaxBy);
      }
      
      // calculate differences between percussive elements,
      // then find the most common differences among all
      // use this to calculate BPM
      TFloatArrayList common = new TFloatArrayList();
      float doubleSpeed = 60f / (MAX_BPM * 2f);
      for(int i=0;i<fewTimes.length;i++) {
          AddCommonBPMs(common, fewTimes[i], doubleSpeed, timePerSample * 1.5f);
          AddCommonBPMs(common, manyTimes[i], doubleSpeed, timePerSample * 1.5f);
      }
      float BPM = 0f, startTime = 0f, timePerBeat = 0f;
      if( USETAPPER ) {
          BPM = getTappedBPM(filename.getAbsolutePath());
          timePerBeat = 60f / BPM;
          startTime = tappedOffset;
      } else if( UPDATESM ) {
          File smfile = SMGenerator.getSMFile(filename, outputDir);
          if( smfile.exists() ) {
              try {
                BufferedReader br = new BufferedReader(new FileReader(smfile));
                while(br.ready() && (BPM == 0f || startTime == 0f)) {
                    String line = br.readLine();
                    if( line.contains("#OFFSET:") ) {
                        int off = line.indexOf("#OFFSET:") + 8;
                        int end = line.indexOf(";", off);
                        startTime = Float.parseFloat(line.substring(off, end));
                        System.out.println("StartTime from SM file: " + startTime);
                    }
                    if( line.contains("#BPMS:") ) {
                        int off = line.indexOf("#BPMS:");
                        off = line.indexOf("=", off) + 1;
                        int end = line.indexOf(";", off);
                        BPM = Float.parseFloat(line.substring(off, end));
                        System.out.println("BPM from SM file: " + BPM);
                    }
                }
                timePerBeat = 60f / BPM;
              } catch(Exception e) { }
          } else {
              System.out.println("Couldn't find SM to update: " + smfile.getAbsolutePath());
          }
      }
      if( BPM == 0f ) {
        if( common.isEmpty() ) {
            System.out.println("[--- FAILED: COULDN'T CALCULATE BPM ---]");
            return;
        }      
        BPM = Math.round(getMostCommon(common, 0.5f, true));
        timePerBeat = 60f / BPM;
        TFloatArrayList startTimes = new TFloatArrayList();
        for(int i=0;i<fewTimes.length;i++) {
            startTimes.add(getBestOffset(timePerBeat, fewTimes[i], 0.01f));
            startTimes.add(getBestOffset(timePerBeat, manyTimes[i], 0.01f));
        }
        // give extra weight to fewKicks
        float kickStartTime = getBestOffset(timePerBeat, fewTimes[KICKS], 0.01f);
        startTimes.add(kickStartTime);
        startTimes.add(kickStartTime);
        startTime = -getMostCommon(startTimes, 0.02f, false);            
      }
      System.out.println("Time per beat: " + timePerBeat + ", BPM: " + BPM);
      System.out.println("Start Time: " + startTime);
      
      // start making the SM
      BufferedWriter smfile = SMGenerator.GenerateSM(BPM, startTime, filename, outputDir);
      
      if( HARDMODE ) System.out.println("Hard mode enabled! Extra steps for you! :-O");
      
      SMGenerator.AddNotes(smfile, SMGenerator.Beginner, StepGenerator.GenerateNotes(1, HARDMODE ? 2 : 4, manyTimes, fewTimes, MidFFTAmount, MidFFTMaxes, timePerSample, timePerBeat, startTime, seconds, false));
      SMGenerator.AddNotes(smfile, SMGenerator.Easy, StepGenerator.GenerateNotes(1, HARDMODE ? 1 : 2, manyTimes, fewTimes, MidFFTAmount, MidFFTMaxes, timePerSample, timePerBeat, startTime, seconds, false));
      SMGenerator.AddNotes(smfile, SMGenerator.Medium, StepGenerator.GenerateNotes(2, HARDMODE ? 4 : 6, manyTimes, fewTimes, MidFFTAmount, MidFFTMaxes, timePerSample, timePerBeat, startTime, seconds, false));
      SMGenerator.AddNotes(smfile, SMGenerator.Hard, StepGenerator.GenerateNotes(2, HARDMODE ? 2 : 4, manyTimes, fewTimes, MidFFTAmount, MidFFTMaxes, timePerSample, timePerBeat, startTime, seconds, false));
      SMGenerator.AddNotes(smfile, SMGenerator.Challenge, StepGenerator.GenerateNotes(2, HARDMODE ? 1 : 2, manyTimes, fewTimes, MidFFTAmount, MidFFTMaxes, timePerSample, timePerBeat, startTime, seconds, true));
      SMGenerator.Complete(smfile);
      
      System.out.println("[--------- SUCCESS ----------]");
    }
}
